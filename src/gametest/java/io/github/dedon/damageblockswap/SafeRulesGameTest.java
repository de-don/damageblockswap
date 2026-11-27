package io.github.dedon.damageblockswap;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Safe rules in a real world: the shipped tags are loaded from the mod's resources (a broken entry
 * would drop a whole tag without failing anything else), and blocks are placed keeping their
 * properties, with leaves persistent.
 */
public class SafeRulesGameTest {
	@GameTest
	public void shippedTagsAreLoaded(GameTestHelper helper) {
		for (Block block : List.of(Blocks.BEDROCK, Blocks.END_PORTAL_FRAME, Blocks.NETHER_PORTAL, Blocks.BARRIER,
				Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.OAK_DOOR, Blocks.TALL_GRASS, Blocks.REINFORCED_DEEPSLATE)) {
			helper.assertTrue(block.defaultBlockState().is(RandomSwapService.NEVER_SWAP), block + " is not in never_swap");
		}
		helper.assertFalse(Blocks.STONE.defaultBlockState().is(RandomSwapService.NEVER_SWAP), "stone is in never_swap");

		ServerLevel level = helper.getLevel();
		List<String> saved = Config.ignoredDamageTypes;
		try {
			Config.ignoredDamageTypes = new ArrayList<>(Config.DEFAULT_IGNORED_DAMAGE_TYPES);
			Config.generation++;
			DamageTypeFilter filter = new DamageTypeFilter();
			helper.assertTrue(filter.ignores(level.damageSources().drown(), level.registryAccess()), "drowning swaps");
			helper.assertTrue(filter.ignores(level.damageSources().inWall(), level.registryAccess()), "suffocation swaps");
			helper.assertFalse(filter.ignores(level.damageSources().fall(), level.registryAccess()), "falling doesn't swap");
		} finally {
			Config.ignoredDamageTypes = saved;
			Config.generation++;
		}
		helper.succeed();
	}

	@GameTest
	public void swappedBlocksKeepTheirPropertiesAndLeavesDoNotDecay(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos stonePos = helper.absolutePos(new BlockPos(1, 1, 1));
		BlockPos logPos = helper.absolutePos(new BlockPos(1, 2, 1));
		level.setBlock(stonePos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
		level.setBlock(logPos, Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
				Block.UPDATE_CLIENTS);

		place(level, stonePos, Blocks.OAK_LEAVES);
		place(level, logPos, Blocks.BIRCH_LOG);

		BlockState leaves = level.getBlockState(stonePos);
		helper.assertTrue(leaves.is(Blocks.OAK_LEAVES), "stone was not swapped: " + leaves);
		helper.assertTrue(leaves.getValue(BlockStateProperties.PERSISTENT), "swapped-in leaves would decay: " + leaves);
		BlockState log = level.getBlockState(logPos);
		helper.assertTrue(log.is(Blocks.BIRCH_LOG), "log was not swapped: " + log);
		helper.assertTrue(log.getValue(BlockStateProperties.AXIS) == Direction.Axis.X, "log lost its axis: " + log);
		helper.succeed();
	}

	/** Swaps one block with the code the mod uses for every block of a chunk. */
	private static void place(ServerLevel level, BlockPos pos, Block target) {
		LevelChunk chunk = level.getChunkAt(pos);
		LoadedChunkData data = new LoadedChunkData(level, chunk, () -> WorldSwapService.NO_PHYSICS_FLAGS);
		int section = (pos.getY() - level.getMinY()) >> 4;
		int index = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
		data.set(section, index, target);
	}
}
