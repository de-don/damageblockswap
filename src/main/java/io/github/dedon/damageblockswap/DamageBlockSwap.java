package io.github.dedon.damageblockswap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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

	// Per-player server-tick of the last swap, for the optional cooldown.
	private final Map<UUID, Integer> lastSwapTick = new HashMap<>();

	/** A hit that added rules this tick; shown (chat + area refresh) once the rules are on disk. */
	private record PendingHit(Player player, ServerLevel level, int chunkX, int chunkZ,
	                          List<RandomSwapService.BlockPair> pairs) {
	}

	// Hits of the current tick. Their rules are written once, together, at the end of the tick.
	private final List<PendingHit> pendingHits = new ArrayList<>();

	// The rule book is written at the end of every tick in which hits added rules. If that write
	// failed, it is retried this often (in ticks); until then the new rules are not applied anywhere.
	private static final int SAVE_RETRY_INTERVAL_TICKS = 200; // ~10 seconds

	@Override
	public void onInitialize() {
		Config.load();
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
				SwapCommand.register(dispatcher, registry));

		LOGGER.info("Damage Block Swap initialized.");
	}

	/** Drops all per-world runtime state (pending chunk work, player tracking, cooldowns). */
	private void resetSessionState() {
		worldSwapService.reset();
		lastSwapTick.clear();
		pendingHits.clear();
	}

	/**
	 * End of tick: puts the rules added by this tick's hits on disk in one write (one write per tick
	 * however many players got hurt), and only then announces them and refreshes the area around the
	 * hit players. Chunks are only ever advanced to the saved version, so if the write fails nothing in
	 * the world changes, nothing is announced, and a crash can't leave chunks ahead of the journal
	 * file. A failed write is retried every {@link #SAVE_RETRY_INTERVAL_TICKS}.
	 */
	private void saveNewRules(MinecraftServer server) {
		if (pendingHits.isEmpty()) {
			if (server.getTickCount() % SAVE_RETRY_INTERVAL_TICKS == 0 && SwapTracker.saveIfDirty()) {
				// Rules that were stuck in memory are on disk now, so chunks may catch up to them.
				worldSwapService.refreshAroundAllPlayers();
			}
			return;
		}

		List<PendingHit> hits = List.copyOf(pendingHits);
		pendingHits.clear();
		int savedBefore = SwapTracker.persistedVersion();
		int addedThisTick = 0;
		for (PendingHit hit : hits) {
			addedThisTick += hit.pairs().size();
		}
		if (!SwapTracker.flush()) {
			return; // logged; the rules stay in memory, unapplied and unannounced, until a retry works
		}
		if (SwapTracker.persistedVersion() - savedBefore > addedThisTick) {
			worldSwapService.refreshAroundAllPlayers(); // older rules that had been stuck got saved too
		}
		for (PendingHit hit : hits) {
			for (RandomSwapService.BlockPair pair : hit.pairs()) {
				announce(hit.player(), pair);
			}
			// Refresh the loaded area around the player now; as the player moves, more chunks catch up,
			// and brand-new chunks catch up when they load. Everything is throttled per tick.
			worldSwapService.onHit(hit.level(), hit.chunkX(), hit.chunkZ());
			if (Config.logging) {
				LOGGER.info("Player {} took damage. Added {} rule(s), version now {}, {} chunks queued.",
						hit.player().getName().getString(), hit.pairs().size(), SwapTracker.version(),
						worldSwapService.queueSize());
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

		MinecraftServer server = serverLevel.getServer();

		// Optional per-player cooldown so rapid damage (fire, drowning) doesn't spam swaps.
		if (Config.cooldownTicks > 0) {
			int now = server.getTickCount();
			Integer last = lastSwapTick.get(player.getUUID());
			if (last != null && now - last < Config.cooldownTicks) return;
			lastSwapTick.put(player.getUUID(), now);
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

		// Written, announced and applied around the player at the end of this tick (saveNewRules).
		pendingHits.add(new PendingHit(player, serverLevel, playerPos.getX() >> 4, playerPos.getZ() >> 4, added));
	}

	private void announce(Player player, RandomSwapService.BlockPair pair) {
		if (Config.chatMessages) {
			// Uses the localized block names, e.g. "Stone ↔ Sand".
			Component message = pair.a().getName()
					.append(Component.literal(" ↔ "))
					.append(pair.b().getName());
			player.sendSystemMessage(message);
		}
	}
}
