package io.github.dedon.damageblockswap;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The per-player "last chunk" key must tell dimensions apart (portal to the same x/z). */
class DimensionChunkTest {
	private static ResourceKey<Level> OVERWORLD;
	private static ResourceKey<Level> NETHER;

	@BeforeAll
	static void bootstrap() {
		// ChunkPos's static init touches the chunk-status registry, so vanilla registries must exist.
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		OVERWORLD = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld"));
		NETHER = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_nether"));
	}

	@Test
	void sameDimensionAndChunkAreEqual() {
		var a = new WorldSwapService.DimensionChunk(OVERWORLD, new ChunkPos(3, -7));
		var b = new WorldSwapService.DimensionChunk(OVERWORLD, new ChunkPos(3, -7));
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	void sameChunkInAnotherDimensionDiffers() {
		var overworld = new WorldSwapService.DimensionChunk(OVERWORLD, new ChunkPos(3, -7));
		var nether = new WorldSwapService.DimensionChunk(NETHER, new ChunkPos(3, -7));
		assertNotEquals(overworld, nether);
	}

	@Test
	void anotherChunkInSameDimensionDiffers() {
		var a = new WorldSwapService.DimensionChunk(OVERWORLD, new ChunkPos(3, -7));
		var b = new WorldSwapService.DimensionChunk(OVERWORLD, new ChunkPos(4, -7));
		assertNotEquals(a, b);
	}
}
