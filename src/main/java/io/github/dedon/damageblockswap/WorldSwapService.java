package io.github.dedon.damageblockswap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/**
 * Brings chunks up to date with the swap rule book ({@link SwapTracker}), a little each tick.
 *
 * <p>Each chunk remembers which rule <b>version</b> it has been transformed to (a persistent
 * attachment). When a chunk is behind — because a new swap happened, or because it just loaded
 * for the first time / after exploring — it is added to a queue. Every tick we apply a bounded
 * number of block changes from that queue, so the world stays consistent everywhere without ever
 * freezing.
 */
public final class WorldSwapService {
	// Per-tick work limits. Changing a block (especially with physics) is the costly part.
	private static final int CHANGES_PER_TICK_PHYSICS = 6_000;
	private static final int CHANGES_PER_TICK_NO_PHYSICS = 40_000;
	// Hard wall-clock cap: never spend more than this on swaps in one tick, whatever the cost.
	private static final long MAX_NANOS_PER_TICK = 8_000_000L; // 8 ms

	/** One chunk that still needs updating. */
	private record ChunkRef(ServerLevel level, ChunkPos pos) {
	}

	// Ordered, de-duplicated set of chunks waiting to be updated.
	private final LinkedHashSet<ChunkRef> queue = new LinkedHashSet<>();

	/**
	 * A chunk in a specific dimension. Chunk coordinates alone are ambiguous: after a portal or
	 * {@code /execute in} teleport a player can land on the same (x, z) in another dimension.
	 */
	record DimensionChunk(ResourceKey<Level> dimension, ChunkPos pos) {
	}

	// Last chunk (and dimension) each player was in, so we notice when they cross into a new one.
	private final Map<UUID, DimensionChunk> lastPlayerChunk = new HashMap<>();

	private final BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();

	// State for the chunk currently being processed (may span several ticks).
	private ServerLevel curLevel;
	private LevelChunk curChunk;
	private LevelChunkSection[] curSections;
	private Map<Block, Block> curMapping;
	private int curTargetVersion;
	private int curHighest;
	private int curMinX;
	private int curMinZ;
	private int curMinY;
	private int sectionIndex;
	private int posInSection;

	private int budgetChanges;
	private long deadlineNanos;

	// Diagnostics: how much a still-draining morph has done, so we can log when it finishes.
	private long changedSinceIdle;
	private int ticksSinceIdle;

	public void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			trackPlayerMovement(server); // as players cross chunks, catch up the area around them
			pump();
		});
		// New terrain (or a chunk reloaded after exploring) catches up here.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> enqueueIfBehind(level, chunk));
		// Forget a player's tracked position when they leave.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> lastPlayerChunk.remove(handler.player.getUUID()));
	}

	/** Refreshes the area around the player right after a hit, so the swap shows immediately. */
	public void onHit(ServerLevel level, int centerChunkX, int centerChunkZ) {
		refreshAround(level, centerChunkX, centerChunkZ, Config.swapRadius);
	}

	/**
	 * When a player moves into a new chunk, re-check the chunks around them (within the configured
	 * radius) and update any that are behind. This spreads the work naturally over movement, and the
	 * affected "bubble" follows the player.
	 */
	private void trackPlayerMovement(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player.level() instanceof ServerLevel level)) {
				continue;
			}
			BlockPos pos = player.blockPosition();
			ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
			DimensionChunk current = new DimensionChunk(level.dimension(), chunkPos);
			DimensionChunk previous = lastPlayerChunk.get(player.getUUID());
			if (!current.equals(previous)) {
				lastPlayerChunk.put(player.getUUID(), current);
				refreshAround(level, chunkPos.x(), chunkPos.z(), Config.swapRadius);
			}
		}
	}

	private void refreshAround(ServerLevel level, int centerChunkX, int centerChunkZ, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
				if (chunk != null) {
					enqueueIfBehind(level, chunk);
				}
			}
		}
	}

	/** Drops all pending work — called when the world closes so nothing leaks into the next one. */
	public void reset() {
		queue.clear();
		lastPlayerChunk.clear();
		// Drop every reference into the old world (level, chunk, its sections and the mapping),
		// so a closed world isn't kept alive in memory until the next one starts processing.
		curLevel = null;
		curChunk = null;
		curSections = null;
		curMapping = null;
		curTargetVersion = 0;
		curHighest = 0;
		sectionIndex = 0;
		posInSection = 0;
		changedSinceIdle = 0;
		ticksSinceIdle = 0;
	}

	/** How many chunks are waiting to be updated (for diagnostics). */
	public int queueSize() {
		return queue.size();
	}

	private void enqueueIfBehind(ServerLevel level, LevelChunk chunk) {
		if (chunk.getAttachedOrElse(DamageBlockSwap.APPLIED_VERSION, 0) < SwapTracker.version()) {
			queue.add(new ChunkRef(level, chunk.getPos()));
		}
	}

	private void pump() {
		if (queue.isEmpty() && curChunk == null) {
			return; // idle
		}

		budgetChanges = Config.updateNeighbors ? CHANGES_PER_TICK_PHYSICS : CHANGES_PER_TICK_NO_PHYSICS;
		deadlineNanos = System.nanoTime() + MAX_NANOS_PER_TICK;
		int flags = Config.updateNeighbors ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS;
		ticksSinceIdle++;

		while (budgetChanges > 0 && System.nanoTime() < deadlineNanos) {
			if (curChunk == null && !startNextChunk()) {
				break; // nothing left to do
			}
			if (processCurrentChunk(flags)) {
				curChunk.setAttached(DamageBlockSwap.APPLIED_VERSION, curTargetVersion);
				curChunk = null;
			}
		}

		// Log once when a morph fully drains, so we can see how long/heavy it was.
		if (queue.isEmpty() && curChunk == null) {
			if (Config.logging && changedSinceIdle > 0) {
				DamageBlockSwap.LOGGER.info("Swap morph done: {} blocks over {} ticks.",
						changedSinceIdle, ticksSinceIdle);
			}
			changedSinceIdle = 0;
			ticksSinceIdle = 0;
		}
	}

	/** Picks the next chunk that actually needs work; returns false if the queue is exhausted. */
	private boolean startNextChunk() {
		ChunkRef ref;
		while ((ref = poll()) != null) {
			LevelChunk chunk = ref.level().getChunkSource().getChunkNow(ref.pos().x(), ref.pos().z());
			if (chunk == null) {
				continue; // unloaded since it was queued
			}
			int applied = chunk.getAttachedOrElse(DamageBlockSwap.APPLIED_VERSION, 0);
			int current = SwapTracker.version();
			if (applied >= current) {
				continue; // already up to date
			}

			Map<Block, Block> mapping = SwapTracker.mappingBetween(applied, current);
			if (mapping.isEmpty()) {
				chunk.setAttached(DamageBlockSwap.APPLIED_VERSION, current);
				continue; // nothing visibly changes for this range
			}

			curLevel = ref.level();
			curChunk = chunk;
			curSections = chunk.getSections();
			curHighest = chunk.getHighestFilledSectionIndex();
			curMinX = ref.pos().getMinBlockX();
			curMinZ = ref.pos().getMinBlockZ();
			curMinY = ref.level().getMinY();
			curMapping = mapping;
			curTargetVersion = current;
			sectionIndex = 0;
			posInSection = 0;
			return true;
		}
		return false;
	}

	/** Applies as much of the current chunk as the budget allows. Returns true when the chunk is done. */
	private boolean processCurrentChunk(int flags) {
		while (sectionIndex <= curHighest && sectionIndex < curSections.length) {
			LevelChunkSection section = curSections[sectionIndex];
			// Skip sections that are all air, or — via the palette — hold none of the blocks this
			// range touches. Both checks are cheap and skip the vast majority of sections.
			if (section == null || section.hasOnlyAir()
					|| (posInSection == 0 && !section.maybeHas(state -> curMapping.containsKey(state.getBlock())))) {
				sectionIndex++;
				posInSection = 0;
				continue;
			}

			int baseY = curMinY + (sectionIndex << 4);
			while (posInSection < 4096) {
				// Check the change budget and the wall-clock deadline (a few times per section) and,
				// if spent, pause here — the cursor is saved, so we resume from this spot next tick.
				if (budgetChanges <= 0) {
					return false;
				}
				if ((posInSection & 1023) == 0 && System.nanoTime() > deadlineNanos) {
					return false;
				}
				int lx = posInSection & 15;
				int lz = (posInSection >> 4) & 15;
				int ly = (posInSection >> 8) & 15;

				Block target = curMapping.get(section.getBlockState(lx, ly, lz).getBlock());
				posInSection++;
				if (target != null) {
					cursorPos.set(curMinX + lx, baseY + ly, curMinZ + lz);
					BlockState targetState = target.defaultBlockState();
					// Only place the swapped block where it can actually stay. This stops fragile
					// blocks (rails, torches, flowers, redstone, ...) from being placed without
					// support and then popping off as dropped items.
					if (targetState.canSurvive(curLevel, cursorPos)) {
						curLevel.setBlock(cursorPos, targetState, flags);
						budgetChanges--;
						changedSinceIdle++;
					}
				}
			}
			sectionIndex++;
			posInSection = 0;
		}
		return true;
	}

	private ChunkRef poll() {
		Iterator<ChunkRef> it = queue.iterator();
		if (!it.hasNext()) {
			return null;
		}
		ChunkRef ref = it.next();
		it.remove();
		return ref;
	}
}
