package io.github.dedon.damageblockswap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Owns the list of blocks that are allowed to be swapped, and picks a random pair.
 *
 * <p>The list is built lazily the first time it is needed. Doing it lazily (instead of
 * during mod init) guarantees the block registry is already fully populated.
 */
public final class RandomSwapService {
	private final Random random = new Random();
	private List<Block> allowedBlocks = null;
	private Set<Block> allowedSet = null; // same contents as allowedBlocks, for fast membership tests
	private int cachedGeneration = -1;

	/** A pair of two different block types to swap. */
	public record BlockPair(Block a, Block b) {
	}

	/** (Re)builds the swappable block list if it's missing or the config has changed since. */
	private void ensureInitialized() {
		if (allowedBlocks == null || cachedGeneration != Config.generation) {
			initialize();
		}
	}

	/** Scans the whole block registry once and keeps only the blocks we may swap. */
	public void initialize() {
		List<Block> list = new ArrayList<>();
		Set<Block> set = new HashSet<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (isAllowed(block)) {
				list.add(block);
				set.add(block);
			}
		}
		allowedBlocks = list;
		allowedSet = set;
		cachedGeneration = Config.generation;
		DamageBlockSwap.LOGGER.info("Built swappable block list: {} blocks", list.size());
	}

	private boolean isAllowed(Block block) {
		BlockState state = block.defaultBlockState();

		// Never swap the various flavors of air.
		if (state.isAir()) return false;

		// Any block that stores data in a BlockEntity (chests, furnaces, signs, banners,
		// shulker boxes, spawners, beehives, decorated pots, vaults, ...) implements
		// EntityBlock. Skipping them protects inventories and NBT data.
		if (block instanceof EntityBlock) return false;

		if (!Config.enableLiquids && block instanceof LiquidBlock) return false;
		if (!Config.enableGravityBlocks && block instanceof FallingBlock) return false;

		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		if (Config.blacklist.contains(id.toString())) return false;

		return true;
	}

	/**
	 * Returns two different random blocks, or {@code null} if fewer than two blocks
	 * are available (which should never realistically happen).
	 */
	public BlockPair getRandomPair() {
		ensureInitialized();
		if (allowedBlocks.size() < 2) {
			return null;
		}

		return pickTwo(allowedBlocks);
	}

	/**
	 * Picks a pair from the given pool (e.g. blocks found near the player). Falls back to a fully
	 * random global pair if the pool has fewer than two distinct blocks.
	 */
	public BlockPair getRandomPairFrom(List<Block> pool) {
		if (pool == null || pool.size() < 2) {
			return getRandomPair();
		}
		return pickTwo(pool);
	}

	private BlockPair pickTwo(List<Block> pool) {
		Block a = pool.get(random.nextInt(pool.size()));
		Block b;
		do {
			b = pool.get(random.nextInt(pool.size()));
		} while (b == a); // retry until the two picks differ
		return new BlockPair(a, b);
	}

	/**
	 * Collects the distinct swappable block types that actually exist in the chunks around
	 * {@code center} (a {@code chunkRadius} of 1 means the 3x3 = 9 chunks). Only already-loaded
	 * chunks are inspected. Used so swaps always hit blocks the player can see.
	 */
	public List<Block> collectSwappableNearby(ServerLevel level, BlockPos center, int chunkRadius) {
		ensureInitialized();

		Set<Block> found = new HashSet<>();
		int centerChunkX = center.getX() >> 4;
		int centerChunkZ = center.getZ() >> 4;

		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
				if (chunk == null) {
					continue; // not loaded — skip
				}

				for (LevelChunkSection section : chunk.getSections()) {
					if (section == null || section.hasOnlyAir()) {
						continue;
					}
					// Ask the section's palette for the block types it holds (usually a few dozen)
					// instead of reading all 16x16x16 = 4096 positions. Hugely cheaper.
					section.getStates().count((state, count) -> {
						Block block = state.getBlock();
						if (allowedSet.contains(block)) {
							found.add(block);
						}
					});
				}
			}
		}
		return new ArrayList<>(found);
	}
}
