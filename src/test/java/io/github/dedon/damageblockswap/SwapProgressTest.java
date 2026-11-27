package io.github.dedon.damageblockswap;

import com.mojang.serialization.DataResult;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unfinished-pass record survives the chunk's NBT round trip; an incomplete one doesn't decode. */
class SwapProgressTest {
	@Test
	void roundTripsThroughNbt() {
		SwapProgress progress = new SwapProgress(UUID.fromString("11111111-2222-3333-4444-555555555555"), 3, 7, 12_345);
		Tag tag = SwapProgress.CODEC.encodeStart(NbtOps.INSTANCE, progress).getOrThrow();
		CompoundTag compound = (CompoundTag) tag;
		assertEquals(3, compound.getIntOr("from", -1));
		assertEquals(7, compound.getIntOr("to", -1));
		assertEquals(12_345, compound.getIntOr("position", -1));
		assertEquals(progress, SwapProgress.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());
	}

	@Test
	void recordWithMissingFieldsDoesNotDecode() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("from", 1);
		tag.putInt("to", 2);
		DataResult<SwapProgress> result = SwapProgress.CODEC.parse(NbtOps.INSTANCE, tag);
		assertTrue(result.isError());
	}
}
