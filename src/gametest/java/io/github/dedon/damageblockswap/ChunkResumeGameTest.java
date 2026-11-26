package io.github.dedon.damageblockswap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The review's bug "a chunk saved half-way through a swap is swapped twice after it loads again", in
 * a real world: a chunk is swapped part-way, unloaded (saved to disk), loaded again, and the mod must
 * finish it from where it stopped. Every block has to end up swapped exactly once.
 */
public class ChunkResumeGameTest {
	// Far away from the test structures, which are placed around the world origin.
	private static final int CX = 200;
	private static final int CZ = 200;
	// Three whole sections of the test chunk, in the air above the flat test world's terrain.
	private static final int MIN_Y = 192;
	private static final int HEIGHT = 48;
	// Changes done before the chunk is unloaded: well under the 16 * 16 * 48 = 12288 blocks.
	private static final int PARTIAL_CHANGES = 5_000;

	private static volatile boolean centerUnloaded;
	private static volatile boolean centerReloaded;
	private static volatile SwapProgress progressAtReload;

	static {
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (isCenter(level, chunk)) {
				centerUnloaded = true;
			}
		});
		// Registered after the mod's own listener (this mod depends on it), which only queues the
		// chunk; the chunk is worked on at the end of the tick, so this still sees what was loaded.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
			if (isCenter(level, chunk) && centerUnloaded && !centerReloaded) {
				progressAtReload = chunk.getAttached(DamageBlockSwap.SWAP_PROGRESS);
				centerReloaded = true;
			}
		});
	}

	private static boolean isCenter(ServerLevel level, LevelChunk chunk) {
		return level.dimension() == Level.OVERWORLD && chunk.getPos().x() == CX && chunk.getPos().z() == CZ;
	}

	/** Alternating sponge / wet sponge: swapped once, every block flips; swapped twice, it flips back. */
	private static Block original(BlockPos pos) {
		return ((pos.getX() + pos.getY() + pos.getZ()) & 1) == 0 ? Blocks.SPONGE : Blocks.WET_SPONGE;
	}

	private static Block swappedOnce(BlockPos pos) {
		return original(pos) == Blocks.SPONGE ? Blocks.WET_SPONGE : Blocks.SPONGE;
	}

	/** Forces (or releases) the test chunk and its 8 neighbours. */
	private static void force(ServerLevel level, boolean forced) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setChunkForced(CX + dx, CZ + dz, forced);
			}
		}
	}

	/** Whether every chunk within {@code radius} of the test chunk is fully loaded. */
	private static boolean loadedAround(ServerLevel level, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (level.getChunkSource().getChunkNow(CX + dx, CZ + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static Iterable<BlockPos> testBlocks() {
		return BlockPos.betweenClosed(CX * 16, MIN_Y, CZ * 16, CX * 16 + 15, MIN_Y + HEIGHT - 1, CZ * 16 + 15);
	}

	@GameTest(maxTicks = 1200)
	public void halfSwappedChunkResumesAfterReloadInsteadOfSwappingTwice(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int[] partialPosition = new int[1];
		int[] target = new int[1];

		helper.startSequence()
				.thenExecute(() -> force(level, true))
				// Wait for the 5x5 that the forced 3x3 keeps fully loaded, so no late chunk load makes
				// the mod pick up the test chunk on its own before it has been unloaded.
				.thenWaitUntil(() -> helper.assertTrue(loadedAround(level, 2), "chunks around the test chunk still loading"))
				.thenExecute(() -> {
					LevelChunk chunk = level.getChunkSource().getChunkNow(CX, CZ);
					for (BlockPos pos : testBlocks()) {
						level.setBlock(pos, original(pos).defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					}
					helper.assertTrue(SwapTracker.recordSwap(Blocks.SPONGE, Blocks.WET_SPONGE), "rule not recorded");
					helper.assertTrue(SwapTracker.flush(), "journal not saved");
					target[0] = SwapTracker.persistedVersion();

					// The first part of the pass, with the same code the mod uses every tick.
					ChunkJob.Plan plan = SwapTracker.planChunk(chunk);
					helper.assertTrue(plan.hasWork() && plan.position() == 0 && plan.to() == target[0],
							"unexpected plan " + plan);
					ChunkJob<Block> job = SwapTracker.startJob(
							new LoadedChunkData(level, chunk, () -> WorldSwapService.NO_PHYSICS_FLAGS), plan);
					int[] left = {PARTIAL_CHANGES};
					boolean done = job.run(new ChunkJob.Budget() {
						@Override
						public boolean hasRoom() {
							return left[0] > 0;
						}

						@Override
						public void changed() {
							left[0]--;
						}
					});
					helper.assertTrue(!done, "the whole chunk was done in one go");
					SwapProgress progress = chunk.getAttached(DamageBlockSwap.SWAP_PROGRESS);
					helper.assertTrue(progress != null && progress.position() == job.position(),
							"progress not saved with the chunk: " + progress);
					partialPosition[0] = job.position();

					force(level, false); // let it unload (and be saved) half done
				})
				.thenWaitUntil(() -> helper.assertTrue(centerUnloaded, "test chunk not unloaded yet"))
				.thenExecute(() -> force(level, true))
				.thenWaitUntil(() -> {
					helper.assertTrue(centerReloaded, "test chunk not loaded again yet");
					LevelChunk chunk = level.getChunkSource().getChunkNow(CX, CZ);
					helper.assertTrue(chunk != null
									&& chunk.getAttachedOrElse(DamageBlockSwap.APPLIED_VERSION, 0) == target[0],
							"the mod hasn't finished the test chunk yet");
				})
				.thenExecute(() -> {
					helper.assertTrue(progressAtReload != null && progressAtReload.position() == partialPosition[0],
							"progress was not loaded back from disk: " + progressAtReload);
					LevelChunk chunk = level.getChunkSource().getChunkNow(CX, CZ);
					helper.assertTrue(chunk.getAttached(DamageBlockSwap.SWAP_PROGRESS) == null,
							"progress record left on a finished chunk");
					int wrong = 0;
					BlockPos firstWrong = null;
					for (BlockPos pos : testBlocks()) {
						if (level.getBlockState(pos).getBlock() != swappedOnce(pos)) {
							if (firstWrong == null) {
								firstWrong = pos.immutable();
							}
							wrong++;
						}
					}
					helper.assertTrue(wrong == 0, wrong + " blocks are not swapped exactly once, first at " + firstWrong);
					force(level, false);
				})
				.thenSucceed();
	}
}
