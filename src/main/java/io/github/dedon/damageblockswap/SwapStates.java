package io.github.dedon.damageblockswap;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Which block state a swapped block is placed as.
 *
 * <p>Swap rules are about block <i>types</i>; the placed state keeps every property the old and the
 * new block share ({@link Block#withPropertiesOf}): a log keeps its axis, stairs and slabs their
 * shape and facing, crops their age, a waterlogged block its water. Before, every swapped block was
 * placed in its default state.
 *
 * <p>Leaves are always placed {@code persistent=true}: default leaves are {@code persistent=false,
 * distance=7}, i.e. "far from any log", and decay on the next random tick, so stone turned into leaves
 * crumbled into a carpet of saplings.
 *
 * <p>The result only depends on the old state and the new block, so it is cached. Server thread only
 * (as all chunk work is).
 */
final class SwapStates {
	// target block -> (source state -> state to place). States are registry singletons, so identity maps.
	private static final Map<Block, Reference2ReferenceOpenHashMap<BlockState, BlockState>> CACHE = new IdentityHashMap<>();

	private SwapStates() {
	}

	/** The state of {@code target} that replaces {@code source}. */
	static BlockState convert(BlockState source, Block target) {
		Reference2ReferenceOpenHashMap<BlockState, BlockState> byTarget =
				CACHE.computeIfAbsent(target, t -> new Reference2ReferenceOpenHashMap<>());
		BlockState result = byTarget.get(source);
		if (result == null) {
			result = compute(source, target);
			byTarget.put(source, result);
		}
		return result;
	}

	/** Uncached {@link #convert}. Package-private for tests. */
	static BlockState compute(BlockState source, Block target) {
		BlockState result = target.withPropertiesOf(source);
		if (target instanceof LeavesBlock && result.hasProperty(BlockStateProperties.PERSISTENT)) {
			result = result.setValue(BlockStateProperties.PERSISTENT, true);
		}
		return result;
	}

	/**
	 * Whether a block is one half of a two-block structure (doors, tall flowers and grass, tall
	 * seagrass, small dripleaf, pitcher plants, beds). Rules never pick such blocks: the swap places
	 * a single block, so the other half would be missing and the placed half would break or float.
	 * Checked by state property, so it also covers modded doors and plants built on the vanilla classes.
	 */
	static boolean isMultiBlock(BlockState state) {
		return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				|| state.hasProperty(BlockStateProperties.BED_PART);
	}
}
