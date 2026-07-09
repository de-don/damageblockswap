package com.example;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Owns the list of blocks that are allowed to be swapped, and picks a random pair.
 *
 * <p>The list is built lazily the first time it is needed. Doing it lazily (instead of
 * during mod init) guarantees the block registry is already fully populated.
 */
public final class RandomSwapService {
	private final Random random = new Random();
	private List<Block> allowedBlocks = null;

	/** A pair of two different block types to swap. */
	public record BlockPair(Block a, Block b) {
	}

	/** Scans the whole block registry once and keeps only the blocks we may swap. */
	public void initialize() {
		List<Block> list = new ArrayList<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (isAllowed(block)) {
				list.add(block);
			}
		}
		allowedBlocks = list;
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
		if (allowedBlocks == null) {
			initialize();
		}
		if (allowedBlocks.size() < 2) {
			return null;
		}

		Block a = allowedBlocks.get(random.nextInt(allowedBlocks.size()));
		Block b;
		do {
			b = allowedBlocks.get(random.nextInt(allowedBlocks.size()));
		} while (b == a); // retry until the two picks differ

		return new BlockPair(a, b);
	}
}
