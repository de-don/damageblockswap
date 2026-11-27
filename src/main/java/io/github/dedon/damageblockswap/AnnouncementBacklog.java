package io.github.dedon.damageblockswap;

import java.util.ArrayList;
import java.util.List;

/**
 * Hits (or admin actions) whose rules wait to be announced, with a cap. Normally it holds one tick's
 * hits; but while the journal can't be written, every hit piles up here until a retry works, and
 * announcing all of them then would flood the chat. So it keeps whole hits only until
 * {@code maxRules} rules are kept and just counts the rest; the announcement shows at most
 * {@code maxRules} lines and closes with one "...and N more" summary line.
 *
 * <p>No Minecraft classes; generic over the hit type so it is unit-tested with plain values.
 */
final class AnnouncementBacklog<H> {
	/** What to announce: whole hits, then how many rules (from how many hits) were left out. */
	record Batch<H>(List<H> hits, int omittedRules, int omittedHits) {
	}

	private final int maxRules;
	private final List<H> kept = new ArrayList<>();
	private int keptRules;
	private int omittedRules;
	private int omittedHits;

	AnnouncementBacklog(int maxRules) {
		if (maxRules < 1) {
			throw new IllegalArgumentException("maxRules must be at least 1");
		}
		this.maxRules = maxRules;
	}

	/** Adds a hit that added {@code rules} rules. Memory stays bounded however many are added. */
	void add(H hit, int rules) {
		if (keptRules < maxRules) {
			kept.add(hit);
			keptRules += rules;
		} else {
			omittedRules += rules;
			omittedHits++;
		}
	}

	boolean isEmpty() {
		return kept.isEmpty() && omittedHits == 0;
	}

	/** At most this many rule lines are printed from the kept hits. */
	int maxLines() {
		return maxRules;
	}

	/**
	 * Takes everything out. {@link Batch#omittedRules()} counts only the hits that were not kept; a
	 * kept hit may still have more rules than fit in {@link #maxLines()}, the caller adds those.
	 */
	Batch<H> drain() {
		Batch<H> batch = new Batch<>(List.copyOf(kept), omittedRules, omittedHits);
		clear();
		return batch;
	}

	void clear() {
		kept.clear();
		keptRules = 0;
		omittedRules = 0;
		omittedHits = 0;
	}
}
