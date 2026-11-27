package io.github.dedon.damageblockswap;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How swapped blocks are placed: shared properties kept, leaves persistent, two-block blocks spotted. */
class SwapStatesTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	// The cache belongs to the first thread that uses it; each test starts with a free one.
	@BeforeEach
	@AfterEach
	void freeCache() {
		SwapStates.clear();
	}

	private static Throwable convertOnAnotherThread() throws InterruptedException {
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread other = new Thread(() -> {
			try {
				SwapStates.convert(Blocks.STONE.defaultBlockState(), Blocks.DIRT);
			} catch (Throwable t) {
				error.set(t);
			}
		});
		other.start();
		other.join();
		return error.get();
	}

	@Test
	void cacheRefusesASecondThread() throws InterruptedException {
		SwapStates.convert(Blocks.STONE.defaultBlockState(), Blocks.DIRT);

		assertInstanceOf(IllegalStateException.class, convertOnAnotherThread());
	}

	@Test
	void clearLetsANewThreadOwnTheCache() throws InterruptedException {
		SwapStates.convert(Blocks.STONE.defaultBlockState(), Blocks.DIRT);
		SwapStates.clear(); // what a server stop / the next server's start does

		assertEquals(null, convertOnAnotherThread());
	}

	@Test
	void logKeepsItsAxis() {
		BlockState source = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);

		BlockState result = SwapStates.convert(source, Blocks.BIRCH_LOG);

		assertSame(Blocks.BIRCH_LOG, result.getBlock());
		assertEquals(Direction.Axis.X, result.getValue(BlockStateProperties.AXIS));
	}

	@Test
	void stairsKeepFacingHalfAndWater() {
		BlockState source = Blocks.STONE_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.HALF, Half.TOP)
				.setValue(BlockStateProperties.WATERLOGGED, true);

		BlockState result = SwapStates.convert(source, Blocks.OAK_STAIRS);

		assertEquals(Direction.EAST, result.getValue(BlockStateProperties.HORIZONTAL_FACING));
		assertEquals(Half.TOP, result.getValue(BlockStateProperties.HALF));
		assertTrue(result.getValue(BlockStateProperties.WATERLOGGED));
	}

	@Test
	void unrelatedBlocksGetTheDefaultState() {
		BlockState source = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z);

		assertSame(Blocks.STONE.defaultBlockState(), SwapStates.convert(source, Blocks.STONE));
		assertSame(Blocks.OAK_STAIRS.defaultBlockState(), SwapStates.convert(Blocks.STONE.defaultBlockState(), Blocks.OAK_STAIRS));
	}

	@Test
	void leavesMadeFromStoneArePersistent() {
		BlockState result = SwapStates.convert(Blocks.STONE.defaultBlockState(), Blocks.OAK_LEAVES);

		assertSame(Blocks.OAK_LEAVES, result.getBlock());
		// The default (persistent=false, distance=7) decays on the next random tick.
		assertTrue(result.getValue(BlockStateProperties.PERSISTENT));
		assertFalse(Blocks.OAK_LEAVES.defaultBlockState().getValue(BlockStateProperties.PERSISTENT));
	}

	@Test
	void naturalLeavesSwappedIntoOtherLeavesArePersistentToo() {
		BlockState source = Blocks.BIRCH_LEAVES.defaultBlockState()
				.setValue(BlockStateProperties.PERSISTENT, false)
				.setValue(BlockStateProperties.DISTANCE, 3)
				.setValue(BlockStateProperties.WATERLOGGED, true);

		BlockState result = SwapStates.convert(source, Blocks.OAK_LEAVES);

		assertTrue(result.getValue(BlockStateProperties.PERSISTENT));
		assertEquals(3, result.getValue(BlockStateProperties.DISTANCE));
		assertTrue(result.getValue(BlockStateProperties.WATERLOGGED));
	}

	@Test
	void everyLeavesBlockIsPlacedPersistent() {
		int leaves = 0;
		for (Block block : BuiltInRegistries.BLOCK) {
			if (block instanceof LeavesBlock) {
				leaves++;
				BlockState result = SwapStates.compute(Blocks.STONE.defaultBlockState(), block);
				assertTrue(result.getValue(BlockStateProperties.PERSISTENT), block.toString());
			}
		}
		assertTrue(leaves > 5);
	}

	@Test
	void liquidLevelIsKept() {
		BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);

		BlockState result = SwapStates.convert(flowing, Blocks.LAVA);

		assertEquals(3, result.getValue(LiquidBlock.LEVEL));
	}

	@Test
	void resultsAreCached() {
		BlockState source = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y);

		assertSame(SwapStates.convert(source, Blocks.SPRUCE_LOG), SwapStates.convert(source, Blocks.SPRUCE_LOG));
		assertSame(SwapStates.compute(source, Blocks.SPRUCE_LOG), SwapStates.convert(source, Blocks.SPRUCE_LOG));
	}

	@Test
	void twoBlockBlocksAreRecognized() {
		for (Block block : List.of(Blocks.OAK_DOOR, Blocks.IRON_DOOR, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
				Blocks.SUNFLOWER, Blocks.PEONY, Blocks.TALL_SEAGRASS, Blocks.SMALL_DRIPLEAF,
				Blocks.PITCHER_PLANT, Blocks.PITCHER_CROP, Blocks.RED_BED)) {
			assertTrue(SwapStates.isMultiBlock(block.defaultBlockState()), block.toString());
		}
		for (Block block : List.of(Blocks.STONE, Blocks.OAK_LEAVES, Blocks.OAK_TRAPDOOR, Blocks.SHORT_GRASS,
				Blocks.OAK_SLAB, Blocks.POPPY)) {
			assertFalse(SwapStates.isMultiBlock(block.defaultBlockState()), block.toString());
		}
	}

	@Test
	void everyVanillaDoorBedAndDoublePlantIsRecognized() {
		List<Block> missed = new ArrayList<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			boolean twoBlock = block instanceof DoorBlock || block instanceof DoublePlantBlock || block instanceof BedBlock;
			if (twoBlock && !SwapStates.isMultiBlock(block.defaultBlockState())) {
				missed.add(block);
			}
		}
		assertTrue(missed.isEmpty(), missed.toString());
	}
}
