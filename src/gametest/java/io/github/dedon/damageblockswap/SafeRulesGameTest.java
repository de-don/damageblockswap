package io.github.dedon.damageblockswap;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Safe rules in a real world: the shipped tags are loaded from the mod's resources (a broken entry
 * would drop a whole tag without failing anything else), and blocks are placed keeping their
 * properties, with leaves persistent. The blacklist and the tag only decide which blocks NEW rules
 * pick: a rule already in the journal keeps being applied even to a block blacklisted since.
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

	@GameTest
	public void damageTypeFilterHandlesIdsEmptyListAndUnregisteredTypes(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		DamageSource fall = level.damageSources().fall();
		DamageSource drown = level.damageSources().drown();
		// A damage type that isn't in the registry (some mods build them on the fly): no key, no tags.
		DamageSource unregistered = new DamageSource(Holder.direct(new DamageType("damageblockswap_test", 0.0F)));
		List<String> saved = Config.ignoredDamageTypes;
		try {
			DamageTypeFilter filter = new DamageTypeFilter();

			Config.ignoredDamageTypes = IdFilter.normalize(List.of("minecraft:fall"));
			Config.generation++;
			helper.assertTrue(filter.ignores(fall, level.registryAccess()), "a plain id in the list is not ignored");
			helper.assertFalse(filter.ignores(drown, level.registryAccess()), "drowning ignored with only fall listed");
			helper.assertFalse(filter.ignores(unregistered, level.registryAccess()), "an unregistered type matched an id");

			Config.ignoredDamageTypes = List.of();
			Config.generation++;
			helper.assertFalse(filter.ignores(fall, level.registryAccess()), "[] still ignores fall");
			helper.assertFalse(filter.ignores(drown, level.registryAccess()), "[] still ignores drowning");

			Config.ignoredDamageTypes = new ArrayList<>(Config.DEFAULT_IGNORED_DAMAGE_TYPES);
			Config.generation++;
			helper.assertFalse(filter.ignores(unregistered, level.registryAccess()), "an unregistered type matched a tag");
		} finally {
			Config.ignoredDamageTypes = saved;
			Config.generation++;
		}
		helper.succeed();
	}

	@GameTest
	public void newRulesNeverPickExcludedBlocks(GameTestHelper helper) {
		List<String> saved = Config.blacklist;
		try {
			Config.blacklist = IdFilter.normalize(List.of("#minecraft:logs", "dirt"));
			Config.generation++;
			RandomSwapService service = new RandomSwapService();
			List<Block> allowed = service.allowedBlocks();
			Set<Block> allowedSet = new HashSet<>(allowed);

			for (Block block : allowed) {
				BlockState state = block.defaultBlockState();
				helper.assertFalse(state.is(RandomSwapService.NEVER_SWAP), block + " is in never_swap but may be picked");
				helper.assertFalse(state.is(BlockTags.LOGS), block + " is blacklisted by #minecraft:logs but may be picked");
				helper.assertFalse(state.is(BlockTags.BEDS), block + " is a bed but may be picked");
				helper.assertFalse(SwapStates.isMultiBlock(state), block + " is a two-block block but may be picked");
			}
			for (Block block : List.of(Blocks.BEDROCK, Blocks.NETHER_PORTAL, Blocks.BARRIER, Blocks.DIRT, Blocks.OAK_LOG,
					Blocks.BIRCH_LOG, Blocks.OAK_DOOR, Blocks.TALL_GRASS, Blocks.SUNFLOWER)) {
				helper.assertFalse(allowedSet.contains(block), block + " may be picked");
			}
			helper.assertTrue(allowedSet.contains(Blocks.STONE) && allowedSet.contains(Blocks.GRANITE),
					"ordinary blocks are missing from the list");

			for (int i = 0; i < 2000; i++) {
				RandomSwapService.BlockPair pair = service.getRandomPair();
				helper.assertTrue(pair != null && pair.a() != pair.b(), "bad pair " + pair);
				helper.assertTrue(allowedSet.contains(pair.a()) && allowedSet.contains(pair.b()),
						"picked a block outside the list: " + pair);
			}
		} finally {
			Config.blacklist = saved;
			Config.generation++;
		}
		helper.succeed();
	}

	// A chunk far from the test structures and from ChunkResumeGameTest's.
	private static final int RULE_CX = -200;
	private static final int RULE_CZ = -200;
	private static final int RULE_Y = 200;

	@GameTest(maxTicks = 600)
	public void blacklistingABlockDoesNotChangeRulesAlreadyMade(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<BlockPos> cells = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			cells.add(new BlockPos(RULE_CX * 16 + i, RULE_Y, RULE_CZ * 16 + (i * 7) % 16));
		}

		helper.startSequence()
				.thenExecute(() -> level.setChunkForced(RULE_CX, RULE_CZ, true))
				.thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(RULE_CX, RULE_CZ) != null,
						"test chunk still loading"))
				.thenExecute(() -> {
					List<String> saved = Config.blacklist;
					try {
						LevelChunk chunk = level.getChunkSource().getChunkNow(RULE_CX, RULE_CZ);
						// Bring the chunk up to date first, so the passes below only carry the new rule.
						runAllPasses(helper, level, chunk);
						for (BlockPos pos : cells) {
							level.setBlock(pos, Blocks.TARGET.defaultBlockState(), Block.UPDATE_CLIENTS);
						}

						// The rule is made while target is allowed...
						helper.assertTrue(SwapTracker.recordSwap(Blocks.TARGET, Blocks.DRIED_KELP_BLOCK), "rule not recorded");
						helper.assertTrue(SwapTracker.flush(), "journal not saved");
						int target = SwapTracker.persistedVersion();

						// ...then target is blacklisted, by id and through a tag, before the chunk gets the rule.
						Config.blacklist = IdFilter.normalize(List.of("minecraft:target", "#minecraft:logs"));
						Config.generation++;
						helper.assertFalse(new RandomSwapService().allowedBlocks().contains(Blocks.TARGET),
								"target was not blacklisted");

						runAllPasses(helper, level, chunk);
						helper.assertTrue(chunk.getAttachedOrElse(DamageBlockSwap.APPLIED_VERSION, 0) == target,
								"chunk not brought up to the new rule");
						for (BlockPos pos : cells) {
							BlockState state = level.getBlockState(pos);
							helper.assertTrue(state.is(Blocks.DRIED_KELP_BLOCK),
									"a rule made before the blacklist change was not applied at " + pos + ": " + state);
						}
					} finally {
						Config.blacklist = saved;
						Config.generation++;
						for (BlockPos pos : cells) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						}
						level.setChunkForced(RULE_CX, RULE_CZ, false);
					}
				})
				.thenSucceed();
	}

	/** Runs the chunk passes the mod would, with no budget limit, until the chunk is up to date. */
	private static void runAllPasses(GameTestHelper helper, ServerLevel level, LevelChunk chunk) {
		for (int i = 0; i < 10; i++) {
			ChunkJob.Plan plan = SwapTracker.planChunk(chunk);
			if (!plan.hasWork()) {
				return;
			}
			helper.assertFalse(plan.isQuarantined(), "test chunk quarantined: " + plan);
			ChunkJob<Block> job = SwapTracker.startJob(
					new LoadedChunkData(level, chunk, () -> WorldSwapService.NO_PHYSICS_FLAGS), plan);
			job.run(new ChunkJob.Budget() {
				@Override
				public boolean hasRoom() {
					return true;
				}

				@Override
				public void changed() {
				}
			});
		}
		helper.fail("test chunk never caught up with the journal");
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
