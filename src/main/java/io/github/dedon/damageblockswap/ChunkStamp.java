package io.github.dedon.damageblockswap;

import java.util.Map;
import java.util.UUID;

/**
 * Decides what a chunk's stamp (journal id + applied version) means against the current journal.
 * Pure logic, unit-tested.
 *
 * <p>A chunk stamped by one journal must never be advanced by another: it would get rules applied
 * on top of a history it doesn't have. Such chunks are <b>quarantined</b>: left untouched, with
 * their stamp unchanged.
 *
 * <p>A journal can have <b>previous ids</b> (see {@link SwapJournal#startNewId}): each session that
 * adds rules continues under a fresh id, and the id it took over is trusted only up to the number of
 * rules it had then. That makes every quarantine permanent: a stamp that is beyond what its journal
 * had (journal file restored from an older copy) can never be "caught up with" by later, different
 * rules that happen to bring the count back up to it.
 */
final class ChunkStamp {
	/** {@link Result#startVersion} of a chunk that must not be touched. */
	static final int QUARANTINED = -1;

	/** Why a chunk was quarantined (for the log). */
	enum Problem {
		/** Stamped by a different journal (journal file replaced, recreated after corruption, ...). */
		OTHER_JOURNAL,
		/**
		 * Stamped by this journal (or one it continues) at a version it never had on disk, e.g. the
		 * journal file was restored from an older copy than the region files.
		 */
		AHEAD_OF_JOURNAL,
		/**
		 * Stamped by mod 1.0.x (no id) at a version beyond the rules this journal inherited from
		 * that version's file (none, if the journal wasn't migrated, e.g. recreated after corruption).
		 */
		LEGACY_NOT_ADOPTED,
		/**
		 * The chunk's unfinished swap pass ({@link SwapProgress}) doesn't start at the chunk's stamp
		 * (chunk data edited by hand or by another tool): which of its blocks are at which version
		 * is unknown.
		 */
		PROGRESS_MISMATCH
	}

	/** Verdict for one stamp: where the chunk continues from, or why it is quarantined. */
	record Result(int startVersion, Problem problem) {
		static final Result UNTOUCHED = new Result(0, null);

		static Result quarantined(Problem problem) {
			return new Result(QUARANTINED, problem);
		}

		boolean isQuarantined() {
			return problem != null;
		}
	}

	private ChunkStamp() {
	}

	/**
	 * @param stampId          journal id stored in the chunk; null if none (untouched, or mod 1.0.x)
	 * @param stampVersion     applied version stored in the chunk (0 if none)
	 * @param journalId        the current journal's id
	 * @param persistedVersion how many of its rules are safely on disk
	 * @param legacyRuleCount  rules the journal inherited from a format-1 file (0 if not migrated)
	 * @param previousIds      ids the journal continues, each with the number of rules it is trusted up to
	 */
	static Result evaluate(UUID stampId, int stampVersion, UUID journalId, int persistedVersion,
	                       int legacyRuleCount, Map<UUID, Integer> previousIds) {
		if (stampVersion <= 0) {
			return Result.UNTOUCHED; // version 0 = no rule applied yet, whichever journal stamped it
		}
		if (stampId == null) {
			// Mod 1.0.x could stamp chunks with versions its file never got (it saved the file only
			// every 10 s). Those rules are lost; newer rules at the same index are different ones.
			if (stampVersion > legacyRuleCount) {
				return Result.quarantined(Problem.LEGACY_NOT_ADOPTED);
			}
		} else if (!stampId.equals(journalId)) {
			Integer trustedUpTo = previousIds.get(stampId);
			if (trustedUpTo == null) {
				return Result.quarantined(Problem.OTHER_JOURNAL);
			}
			if (stampVersion > trustedUpTo) {
				return Result.quarantined(Problem.AHEAD_OF_JOURNAL);
			}
		}
		return stampVersion > persistedVersion
				? Result.quarantined(Problem.AHEAD_OF_JOURNAL)
				: new Result(stampVersion, null);
	}
}
