package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the mod.
 *
 * <p>The whole idea: every time a player actually takes damage, we pick two random
 * block types and swap every occurrence of them across all loaded chunks. The world
 * slowly turns into chaos while vanilla physics keep working normally.
 */
public class DamageBlockSwap implements ModInitializer {
	public static final String MOD_ID = "damageblockswap";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// The two small "services" that do the real work. See their own files.
	private final RandomSwapService randomSwapService = new RandomSwapService();
	private final WorldSwapService worldSwapService = new WorldSwapService();

	// Used only for the optional cooldown between swaps.
	private long lastSwapMillis = 0L;

	@Override
	public void onInitialize() {
		Config.load();
		ChunkTracker.register();

		// Fires AFTER a living entity has taken damage. Parameters:
		//   entity           - who got hit
		//   source           - what caused it (mob, fall, lava, cactus, ...)
		//   baseDamageTaken  - damage before shields/effects
		//   damageTaken      - damage that was actually applied
		//   blocked          - true if a shield fully blocked it
		ServerLivingEntityEvents.AFTER_DAMAGE.register(this::onEntityDamaged);

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

		RandomSwapService.BlockPair pair = randomSwapService.getRandomPair();
		if (pair == null) return; // not enough swappable blocks (should never happen)

		long changed = worldSwapService.swapBlocks(server, pair.a(), pair.b());

		announce(player, pair, changed);
	}

	private void announce(Player player, RandomSwapService.BlockPair pair, long changed) {
		String idA = BuiltInRegistries.BLOCK.getKey(pair.a()).toString();
		String idB = BuiltInRegistries.BLOCK.getKey(pair.b()).toString();

		if (Config.logging) {
			LOGGER.info("Player {} took damage. Swapped {} <-> {} ({} blocks changed)",
					player.getName().getString(), idA, idB, changed);
		}

		if (Config.chatMessages) {
			// Uses the localized block names, e.g. "Stone ↔ Sand".
			Component message = pair.a().getName()
					.append(Component.literal(" ↔ "))
					.append(pair.b().getName());
			player.sendSystemMessage(message);
		}
	}
}
