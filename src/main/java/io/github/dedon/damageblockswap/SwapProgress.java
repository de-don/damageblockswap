package io.github.dedon.damageblockswap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * A chunk that is half-way through a swap pass (persistent attachment, saved with the chunk).
 *
 * <p>A chunk is brought from its stamp version {@code fromVersion} to {@code toVersion} block by
 * block, over as many ticks as it takes. Blocks before {@code position} (a cell index, see
 * {@link ChunkJob}) are already at {@code toVersion}; the rest are still at {@code fromVersion}.
 * The stamp itself is only moved to {@code toVersion} once the whole chunk is done. So whenever the
 * chunk is saved mid-way (unload, autosave, server stop, crash), it is saved with this record and the
 * pass is resumed from {@code position} instead of being applied a second time to the blocks before it.
 *
 * @param journalId   journal id {@code toVersion} counts in (the id on disk when the record was written)
 * @param fromVersion the chunk's stamp version when the pass started; must still equal it
 * @param toVersion   the version the pass brings the chunk to
 * @param position    index of the first cell that is not done yet
 */
record SwapProgress(UUID journalId, int fromVersion, int toVersion, int position) {
	static final Codec<SwapProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("journal_id").forGetter(SwapProgress::journalId),
			Codec.INT.fieldOf("from").forGetter(SwapProgress::fromVersion),
			Codec.INT.fieldOf("to").forGetter(SwapProgress::toVersion),
			Codec.INT.fieldOf("position").forGetter(SwapProgress::position)
	).apply(instance, SwapProgress::new));
}
