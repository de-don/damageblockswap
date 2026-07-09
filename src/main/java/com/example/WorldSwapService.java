package com.example;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Performs the actual block swap across every loaded chunk of every loaded world.
 *
 * <p>We visit each position exactly once, so an in-place swap is correct: reading the
 * <em>current</em> block and turning A→B or B→A can never accidentally double-convert a
 * block, because we never come back to the same position.
 */
public final class WorldSwapService {

	/** Swaps block types {@code a} and {@code b} everywhere. Returns how many blocks changed. */
	public long swapBlocks(MinecraftServer server, Block a, Block b) {
		long changed = 0;
		for (ServerLevel level : server.getAllLevels()) {
			changed += swapInLevel(level, a, b);
		}
		return changed;
	}

	private long swapInLevel(ServerLevel level, Block a, Block b) {
		long changed = 0;

		BlockState stateA = a.defaultBlockState();
		BlockState stateB = b.defaultBlockState();

		int minY = level.getMinY();
		int maxY = level.getMaxY();

		// Copy the set first: setBlock() fires neighbor updates that may load/unload
		// chunks, which would otherwise mutate the tracked set while we iterate it.
		List<LevelChunk> chunks = new ArrayList<>(ChunkTracker.getLoadedChunks(level));

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (LevelChunk chunk : chunks) {
			ChunkPos cp = chunk.getPos();
			int minX = cp.getMinBlockX();
			int minZ = cp.getMinBlockZ();

			for (int dx = 0; dx < 16; dx++) {
				for (int dz = 0; dz < 16; dz++) {
					int x = minX + dx;
					int z = minZ + dz;

					for (int y = minY; y <= maxY; y++) {
						pos.set(x, y, z);
						Block current = level.getBlockState(pos).getBlock();

						if (current == a) {
							// Block.UPDATE_ALL lets vanilla run its physics: sand falls,
							// water/lava flow, plants re-check support, redstone updates.
							level.setBlock(pos, stateB, Block.UPDATE_ALL);
							changed++;
						} else if (current == b) {
							level.setBlock(pos, stateA, Block.UPDATE_ALL);
							changed++;
						}
					}
				}
			}
		}

		return changed;
	}
}
