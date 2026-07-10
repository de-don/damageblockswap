package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
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

import java.util.List;

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

	// The two small "services" that do the real work. See their own files.
	private final RandomSwapService randomSwapService = new RandomSwapService();
	private final WorldSwapService worldSwapService = new WorldSwapService();

	// Used only for the optional cooldown between swaps.
	private long lastSwapMillis = 0L;

	@Override
	public void onInitialize() {
		Config.load();
		worldSwapService.registerEvents(); // catches chunks up to the rule book, a little each tick

		// Fires AFTER a living entity has taken damage. Parameters:
		//   entity           - who got hit
		//   source           - what caused it (mob, fall, lava, cactus, ...)
		//   baseDamageTaken  - damage before shields/effects
		//   damageTaken      - damage that was actually applied
		//   blocked          - true if a shield fully blocked it
		ServerLivingEntityEvents.AFTER_DAMAGE.register(this::onEntityDamaged);

		// The swap map is saved inside the world folder, so load/save it with the world.
		ServerLifecycleEvents.SERVER_STARTED.register(SwapTracker::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			SwapTracker.save(server);
			worldSwapService.reset(); // don't let unfinished work leak into the next world
		});

		// /damageblockswap (alias /dbs) — inspect what turned into what.
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
				SwapCommand.register(dispatcher));

		LOGGER.info("Damage Block Swap initialized.");
	}

	private void onEntityDamaged(LivingEntity entity, DamageSource source,
	                             float baseDamageTaken, float damageTaken, boolean blocked) {
		// Only react to players who genuinely took damage.
		if (!(entity instanceof Player player)) return;
		if (blocked || damageTaken <= 0.0F) return;
		// World logic only runs on the server side.
		if (!(entity.level() instanceof ServerLevel serverLevel)) return;

		// Optional cooldown so rapid damage (fire, drowning) doesn't spam swaps.
		if (Config.cooldownTicks > 0) {
			long now = System.currentTimeMillis();
			if (now - lastSwapMillis < (long) Config.cooldownTicks * 50L) return;
			lastSwapMillis = now;
		}

		MinecraftServer server = serverLevel.getServer();
		BlockPos playerPos = player.blockPosition();

		// Optionally choose the swapped blocks from ones that actually exist in the affected area,
		// so the hit visibly changes the surroundings (built once and reused for all picks).
		List<Block> nearbyPool = null;
		if (Config.pickFromNearbyChunks) {
			nearbyPool = randomSwapService.collectSwappableNearby(serverLevel, playerPos, Config.swapRadius);
		}

		// One hit can add several rules (configurable).
		int added = 0;
		for (int i = 0; i < Config.swapsPerHit; i++) {
			RandomSwapService.BlockPair pair = nearbyPool != null
					? randomSwapService.getRandomPairFrom(nearbyPool)
					: randomSwapService.getRandomPair();
			if (pair == null) break; // not enough swappable blocks (should never happen)

			SwapTracker.recordSwap(pair.a(), pair.b()); // new rule -> version goes up
			announce(player, pair);
			added++;
		}
		if (added == 0) return;

		// Refresh the loaded area around the player now; as the player moves, more chunks catch up,
		// and brand-new chunks catch up when they load. Everything is throttled per tick.
		worldSwapService.onHit(serverLevel, playerPos.getX() >> 4, playerPos.getZ() >> 4);

		if (Config.logging) {
			LOGGER.info("Player {} took damage. Added {} rule(s), version now {}, {} chunks queued.",
					player.getName().getString(), added, SwapTracker.version(), worldSwapService.queueSize());
		}

		// Persist the rule book right away so it survives crashes.
		SwapTracker.save(server);
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
