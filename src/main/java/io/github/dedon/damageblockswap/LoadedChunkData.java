package io.github.dedon.damageblockswap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * A loaded chunk as {@link ChunkJob} sees it: its block types, placing blocks into the level, and its
 * stamp / unfinished pass in the chunk's attachments ({@link SwapTracker}).
 *
 * <p>Only use it while the chunk and its 8 neighbours are fully loaded (see
 * {@link WorldSwapService}): placing a block reads and may update blocks across the chunk edge.
 */
final class LoadedChunkData implements ChunkJob.ChunkData<Block> {
	private final ServerLevel level;
	private final LevelChunk chunk;
	private final LevelChunkSection[] sections;
	private final IntSupplier placeFlags;
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	private final int minX;
	private final int minY;
	private final int minZ;

	/** @param placeFlags {@code setBlock} flags to place swapped blocks with (read on every placement) */
	LoadedChunkData(ServerLevel level, LevelChunk chunk, IntSupplier placeFlags) {
		this.level = level;
		this.chunk = chunk;
		this.sections = chunk.getSections();
		this.placeFlags = placeFlags;
		this.minX = chunk.getPos().getMinBlockX();
		this.minY = level.getMinY();
		this.minZ = chunk.getPos().getMinBlockZ();
	}

	@Override
	public int sectionCount() {
		return sections.length;
	}

	@Override
	public boolean mayContainAny(int section, Map<Block, Block> mapping) {
		LevelChunkSection s = sections[section];
		// Air-only sections, and — via the palette — sections holding none of the blocks this range
		// touches. Both checks are cheap and skip the vast majority of sections.
		return s != null && !s.hasOnlyAir() && s.maybeHas(state -> mapping.containsKey(state.getBlock()));
	}

	// Cell index layout (as PalettedContainer stores blocks): x = bits 0-3, z = bits 4-7, y = bits 8-11.

	@Override
	public Block get(int section, int index) {
		return sections[section].getBlockState(index & 15, (index >> 8) & 15, (index >> 4) & 15).getBlock();
	}

	@Override
	public boolean set(int section, int index, Block value) {
		cursor.set(minX + (index & 15), minY + (section << 4) + ((index >> 8) & 15), minZ + ((index >> 4) & 15));
		BlockState target = value.defaultBlockState();
		// Only place the swapped block where it can actually stay. This stops fragile blocks (rails,
		// torches, flowers, redstone, ...) from being placed without support and then popping off as
		// dropped items.
		if (!target.canSurvive(level, cursor)) {
			return false;
		}
		level.setBlock(cursor, target, placeFlags.getAsInt());
		return true;
	}

	@Override
	public void writeStamp(UUID journalId, int version) {
		SwapTracker.writeStamp(chunk, journalId, version);
	}

	@Override
	public void writeProgress(SwapProgress progress) {
		SwapTracker.writeProgress(chunk, progress);
	}
}
