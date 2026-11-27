package io.github.dedon.damageblockswap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point of the mod.
 *
 * <p>The idea: every time a player takes damage we add a new swap "rule" (block A &lt;-&gt; block B)
 * to a rule book, and gradually bring the world in line with it — the area around the player right
 * away, and any other chunk as it loads. So the world stays consistent everywhere you go, without
 * ever rewriting millions of blocks at once.
 */
public class DamageBlockSwap implements ModInitializer {
	public static final String MOD_ID = "damageblockswap";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Persistent per-chunk stamp: which swap rule version this chunk has already been brought up to.
	 * Stored with the chunk so it survives save/reload and never gets swapped twice.
	 */
	public static final AttachmentType<Integer> APPLIED_VERSION = AttachmentRegistry.createPersistent(
			Identifier.fromNamespaceAndPath(MOD_ID, "applied_version"), Codec.INT);

	/**
	 * Second half of the chunk stamp: the id of the journal that {@link #APPLIED_VERSION} counts in.
	 * A separate attachment (not a record replacing the int) so chunks stay readable by mod 1.0.x;
	 * chunks stamped by 1.0.x simply don't have it. See {@link ChunkStamp}.
	 */
	public static final AttachmentType<UUID> JOURNAL_ID = AttachmentRegistry.createPersistent(
			Identifier.fromNamespaceAndPath(MOD_ID, "journal_id"), UUIDUtil.CODEC);

	/**
	 * How far an unfinished swap pass over a chunk got (absent when the chunk isn't mid-pass), so a
	 * chunk saved half-way (unload, autosave, stop, crash) resumes instead of being swapped twice.
	 * A separate attachment again: older mod versions ignore it. See {@link SwapProgress}.
	 */
	public static final AttachmentType<SwapProgress> SWAP_PROGRESS = AttachmentRegistry.createPersistent(
			Identifier.fromNamespaceAndPath(MOD_ID, "swap_progress"), SwapProgress.CODEC);

	// The two small "services" that do the real work. See their own files.
	private final RandomSwapService randomSwapService = new RandomSwapService();
	private final WorldSwapService worldSwapService = new WorldSwapService();
	// The same service, for the gametests (src/gametest, same package), which drive the live pump.
	private static WorldSwapService activeWorldSwapService;

	// Which damage types don't cause swaps (config, by default the #damageblockswap:ignored tag).
	private final DamageTypeFilter damageTypeFilter = new DamageTypeFilter();
	// Bumped whenever the server (re)loads its tags (world start, /reload): the never_swap tag and tags
	// named in the config may have changed, so the lists built from them are rebuilt.
	private static volatile int tagsGeneration = 0;

	// Per-player server-tick of the last swap, for the cooldown.
	private final Map<UUID, Integer> lastSwapTick = new HashMap<>();

	/**
	 * A hit (or a /dbs add or undo) that added rules; announced to everyone (and applied around every
	 * player) once the rules are on disk. Keeps the name, not the player: a hit whose write failed may
	 * only be announced after a retry, by when the player may have left.
	 */
	private record PendingHit(Component playerName, List<RandomSwapService.BlockPair> pairs,
	                          SwapAnnouncement.Cause cause) {
	}

	// Hits of the current tick. Their rules are written once, together, at the end of the tick.
	private final List<PendingHit> pendingHits = new ArrayList<>();
	// At most this many rule lines are announced per write; the rest get one "...and N more" line.
	// Matters after a long write failure, when every hit since is announced at once.
	static final int MAX_ANNOUNCED_RULES = 20;
	// Hits whose rules are not written yet; announced once a write (or a retry) succeeds. Bounded.
	private final AnnouncementBacklog<PendingHit> unsavedHits = new AnnouncementBacklog<>(MAX_ANNOUNCED_RULES);

	// /dbs pause: damage adds no rules. Not saved anywhere: every server start (and every world in
	// single player) begins unpaused, see docs/decisions.md. Server thread only.
	private boolean paused = false;

	// The rule book is written at the end of every tick in which hits added rules. If that write
	// failed, it is retried this often (in ticks); until then the new rules are not applied anywhere.
	private static final int SAVE_RETRY_INTERVAL_TICKS = 200; // ~10 seconds

	@Override
	public void onInitialize() {
		Config.load();
		activeWorldSwapService = worldSwapService;
		// Saves this tick's new rules. Registered BEFORE worldSwapService's own end-of-tick listener:
		// listeners of one event run in registration order (Fabric appends them to an array), so the
		// rules are on disk before the chunk pump of the same tick may apply them.
		ServerTickEvents.END_SERVER_TICK.register(this::saveNewRules);
		worldSwapService.registerEvents(); // catches chunks up to the rule book, a little each tick

		// Fires AFTER a living entity has taken damage. Parameters:
		//   entity           - who got hit
		//   source           - what caused it (mob, fall, lava, cactus, ...)
		//   baseDamageTaken  - damage before shields/effects
		//   damageTaken      - damage that was actually applied
		//   blocked          - true if a shield fully blocked it
		ServerLivingEntityEvents.AFTER_DAMAGE.register(this::onEntityDamaged);

		// The rule book is saved inside the world folder, so load/save it with the world.
		// Load on SERVER_STARTING (before the levels and their spawn chunks are loaded), so the
		// CHUNK_LOAD checks of those first chunks already compare against this world's rule book
		// and not the previous world's. New rules are written at the end of the tick they were added
		// in (saveNewRules), which also retries a write that failed.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			resetSessionState(); // in case a previous server didn't shut down cleanly
			SwapTracker.load(server);
		});
		// Once the world's tags are bound, build the swappable list and check the config lists, so typos
		// in the blacklist / ignored damage types are reported at startup and not on the first hit.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			randomSwapService.initialize();
			damageTypeFilter.refresh(server.registryAccess());
		});
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
			if (!client) {
				tagsGeneration++; // rebuilt lazily, on the server thread, the next time they are used
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			SwapTracker.flush();
			resetSessionState(); // don't let unfinished work leak into the next world
		});
		// Once the world is fully closed, forget its rule book too, so the swap map screen / Mod
		// Menu tab don't keep showing it (e.g. after leaving to the title screen or joining another
		// server). Done on STOPPED rather than STOPPING so nothing still running during shutdown
		// sees an empty rule book. Session state is reset once more here: chunks loaded between
		// STOPPING and STOPPED could have queued references to the closing world.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			resetSessionState();
			SwapTracker.clear();
		});

		// Forget a player's cooldown timer when they leave.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> lastSwapTick.remove(handler.player.getUUID()));

		// /damageblockswap (alias /dbs) — inspect what turned into what.
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
				SwapCommand.register(dispatcher, registry, this));

		LOGGER.info("Damage Block Swap initialized.");
	}

	/** Changes every time the server (re)loads tags; caches built from tags compare against it. */
	static int tagsGeneration() {
		return tagsGeneration;
	}

	/** The running chunk pump. Package-private, for the gametests only. */
	static WorldSwapService worldSwapService() {
		return activeWorldSwapService;
	}

	/** Drops all per-world runtime state (pending chunk work, player tracking, cooldowns). */
	private void resetSessionState() {
		worldSwapService.reset();
		SwapStates.clear(); // a new server runs on a new thread
		lastSwapTick.clear();
		pendingHits.clear();
		unsavedHits.clear();
		paused = false;
	}

	// --- Used by the admin commands (SwapCommand), on the server thread ----------------------------

	boolean isPaused() {
		return paused;
	}

	/** Pauses or resumes new rules from damage. Returns false if it already was in that state. */
	boolean setPaused(boolean pause) {
		if (paused == pause) {
			return false;
		}
		paused = pause;
		LOGGER.info("New swap rules from damage {}.", pause ? "paused" : "resumed");
		return true;
	}

	/**
	 * Records the rule a &lt;-&gt; b for {@code /dbs add} / {@code /dbs undo} exactly like a hit does:
	 * written, applied around every player and announced at the end of this tick. Returns the rule's
	 * number, or -1 if the journal is read-only. The caller checks the blocks.
	 */
	int addRule(Component who, Block a, Block b, SwapAnnouncement.Cause cause) {
		if (!SwapTracker.recordSwap(a, b)) {
			return -1;
		}
		pendingHits.add(new PendingHit(who, List.of(new RandomSwapService.BlockPair(a, b)), cause));
		return SwapTracker.version();
	}

	/**
	 * {@code /dbs reload}: re-reads the config and rebuilds what is built from it right away (so
	 * problems in the lists are logged now). Returns null, or what was wrong with the file (then the
	 * current settings stay).
	 */
	String reloadConfig(MinecraftServer server) {
		String error = Config.reload();
		if (error != null) {
			return error;
		}
		randomSwapService.initialize();
		damageTypeFilter.refresh(server.registryAccess());
		return null;
	}

	/** How many blocks new random rules may pick from now. */
	int swappableBlockCount() {
		return randomSwapService.allowedBlocks().size();
	}

	/** Chunks waiting in the pump's queue. */
	int chunkQueueSize() {
		return worldSwapService.queueSize();
	}

	/**
	 * End of tick: puts the rules added by this tick's hits on disk in one write (one write per tick
	 * however many players got hurt), and only then announces them to every player and refreshes the
	 * area around every player: a rule is global, so a player who wasn't hit must see it too, and not
	 * only once they cross a chunk border. Chunks are only ever advanced to the saved version, so if
	 * the write fails nothing in the world changes, nothing is announced, and a crash can't leave
	 * chunks ahead of the journal file. A failed write is retried every
	 * {@link #SAVE_RETRY_INTERVAL_TICKS}; its hits are announced once it succeeds.
	 */
	private void saveNewRules(MinecraftServer server) {
		if (pendingHits.isEmpty()) {
			if (server.getTickCount() % SAVE_RETRY_INTERVAL_TICKS == 0 && SwapTracker.saveIfDirty()) {
				// Rules that were stuck in memory are on disk now, so chunks may catch up to them.
				worldSwapService.refreshAroundAllPlayers();
				announceAll(server, unsavedHits.drain());
			}
			return;
		}

		for (PendingHit hit : pendingHits) {
			unsavedHits.add(hit, hit.pairs().size());
		}
		pendingHits.clear();
		if (!SwapTracker.flush()) {
			return; // logged; the rules stay in memory, unapplied and unannounced, until a retry works
		}
		// One refresh for all of this tick's hits, however many. WorldSwapService's end-of-tick listener
		// runs after this one (see onInitialize), so the areas are re-checked in this same tick; the
		// chunks only join the de-duplicated queue and are worked off within the usual per-tick budget,
		// a chunk already mid-pass keeps its saved progress.
		worldSwapService.refreshAroundAllPlayers();
		announceAll(server, unsavedHits.drain());
	}

	/**
	 * Announces a batch of written rules: one line per rule up to {@link #MAX_ANNOUNCED_RULES}, then a
	 * single "...and N more" line, so a long write failure doesn't end in a flood of messages.
	 */
	private void announceAll(MinecraftServer server, AnnouncementBacklog.Batch<PendingHit> batch) {
		int printed = 0;
		int cut = batch.omittedRules();
		for (PendingHit hit : batch.hits()) {
			for (RandomSwapService.BlockPair pair : hit.pairs()) {
				if (printed >= MAX_ANNOUNCED_RULES) {
					cut++;
					continue;
				}
				printed++;
				if (Config.chatMessages) {
					// To every player and the server console (broadcastSystemMessage logs it too).
					server.getPlayerList().broadcastSystemMessage(
							SwapAnnouncement.of(hit.cause(), hit.playerName(), pair.a(), pair.b()), false);
				}
			}
			if (Config.logging) {
				// No queue size here: the areas are only queued later in this tick (refreshAroundAllPlayers).
				switch (hit.cause()) {
					case HURT -> LOGGER.info("Player {} took damage. Added {} rule(s), version now {}.",
							hit.playerName().getString(), hit.pairs().size(), SwapTracker.version());
					case ADDED -> LOGGER.info("{} added a rule with /dbs add, version now {}.",
							hit.playerName().getString(), SwapTracker.version());
					case UNDONE -> LOGGER.info("{} undid a rule with /dbs undo, version now {}.",
							hit.playerName().getString(), SwapTracker.version());
				}
			}
		}
		if (cut > 0) {
			if (Config.chatMessages) {
				server.getPlayerList().broadcastSystemMessage(SwapAnnouncement.more(cut), false);
			}
			if (Config.logging && batch.omittedHits() > 0) {
				LOGGER.info("{} more hit(s) added {} rule(s) while the journal could not be saved; not listed one by one.",
						batch.omittedHits(), batch.omittedRules());
			}
		}
	}

	private void onEntityDamaged(LivingEntity entity, DamageSource source,
	                             float baseDamageTaken, float damageTaken, boolean blocked) {
		// Only react to players who genuinely took damage.
		if (!(entity instanceof Player player)) return;
		// Fabric fake players (block breakers/placers and other mod machines) are not real players
		// taking damage; without this check an automated farm would add rules nonstop. Carpet bots
		// are NOT caught here (they extend ServerPlayer directly) and are deliberately treated as
		// players, see docs/decisions.md.
		if (player instanceof FakePlayer) return;
		if (blocked || damageTaken <= 0.0F) return;
		// World logic only runs on the server side.
		if (!(entity.level() instanceof ServerLevel serverLevel)) return;
		// The journal file is unreadable (logged loudly at load): the mod is off for this session.
		if (!SwapTracker.canRecord()) return;
		// /dbs pause: checked before the cooldown, so pausing doesn't use it up.
		if (paused) return;

		MinecraftServer server = serverLevel.getServer();

		// Damage that repeats for as long as a situation lasts (drowning, suffocating, ...) doesn't
		// count; checked before the cooldown so it doesn't use it up.
		if (damageTypeFilter.ignores(source, serverLevel.registryAccess())) return;

		// Per-player cooldown so rapid damage (fire, cactus, a mob's hits) doesn't spam swaps. It is
		// only used up below, once the hit really added a rule.
		int now = server.getTickCount();
		if (Config.cooldownTicks > 0) {
			Integer last = lastSwapTick.get(player.getUUID());
			if (last != null && now - last < Config.cooldownTicks) return;
		}

		BlockPos playerPos = player.blockPosition();

		// Optionally choose the swapped blocks from ones that actually exist in the affected area,
		// so the hit visibly changes the surroundings (built once and reused for all picks).
		List<Block> nearbyPool = null;
		if (Config.pickFromNearbyChunks) {
			nearbyPool = randomSwapService.collectSwappableNearby(serverLevel, playerPos, Config.swapRadius);
		}

		// One hit can add several rules (configurable).
		List<RandomSwapService.BlockPair> added = new ArrayList<>();
		for (int i = 0; i < Config.swapsPerHit; i++) {
			RandomSwapService.BlockPair pair = nearbyPool != null
					? randomSwapService.getRandomPairFrom(nearbyPool)
					: randomSwapService.getRandomPair();
			if (pair == null) break; // not enough swappable blocks (should never happen)

			if (!SwapTracker.recordSwap(pair.a(), pair.b())) break; // new rule -> version goes up
			added.add(pair);
		}
		if (added.isEmpty()) return;
		if (Config.cooldownTicks > 0) {
			lastSwapTick.put(player.getUUID(), now);
		}

		// Written, then announced to everyone and applied around every player, at the end of this tick
		// (saveNewRules). Several hits in one tick share one write and one refresh.
		pendingHits.add(new PendingHit(player.getDisplayName(), added, SwapAnnouncement.Cause.HURT));
	}
}
