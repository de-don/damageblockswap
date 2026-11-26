package io.github.dedon.damageblockswap;

import java.util.UUID;

/**
 * Decides what a chunk's stamp (journal id + applied version) means against the current journal.
 * Pure logic, unit-tested.
 *
 * <p>A chunk stamped by one journal must never be advanced by another: it would get rules applied
 * on top of a history it doesn't have. Such chunks are <b>quarantined</b>: left untouched, with
 * their stamp unchanged, so restoring the right journal file heals them.
 */
final class ChunkStamp {
	/** Returned by {@link #startVersion} for a chunk that must not be touched. */
	static final int QUARANTINED = -1;

	/** Why a chunk was quarantined (for the log). */
	enum Problem {
		/** Stamped by a different journal (journal file replaced, recreated after corruption, ...). */
		OTHER_JOURNAL,
		/** Stamped by this journal at a version it never persisted. */
		AHEAD_OF_JOURNAL,
		/**
		 * Stamped by mod 1.0.x (no id) at a version beyond the rules this journal inherited from
		 * that version's file (none, if the journal wasn't migrated, e.g. recreated after corruption).
		 */
		LEGACY_NOT_ADOPTED
	}

	private ChunkStamp() {
	}

	/**
	 * The version the chunk is at relative to the current journal, or {@link #QUARANTINED}.
	 *
	 * @param stampId           journal id stored in the chunk; null if none (untouched, or mod 1.0.x)
	 * @param stampVersion      applied version stored in the chunk (0 if none)
	 * @param journalId         the current journal's id
	 * @param persistedVersion  how many of its rules are safely on disk
	 * @param legacyRuleCount   rules the journal inherited from a format-1 file (0 if not migrated)
	 */
	static int startVersion(UUID stampId, int stampVersion, UUID journalId, int persistedVersion, int legacyRuleCount) {
		return problem(stampId, stampVersion, journalId, persistedVersion, legacyRuleCount) == null
				? Math.max(0, stampVersion)
				: QUARANTINED;
	}

	/** Null if the stamp is fine to continue from, otherwise why not. */
	static Problem problem(UUID stampId, int stampVersion, UUID journalId, int persistedVersion, int legacyRuleCount) {
		if (stampVersion <= 0) {
			return null; // version 0 = no rule applied yet, whichever journal stamped it
		}
		if (stampId == null) {
			// Mod 1.0.x could stamp chunks with versions its file never got (it saved the file only
			// every 10 s). Those rules are lost; newer rules at the same index are different ones.
			if (stampVersion > legacyRuleCount) {
				return Problem.LEGACY_NOT_ADOPTED;
			}
		} else if (!stampId.equals(journalId)) {
			return Problem.OTHER_JOURNAL;
		}
		return stampVersion > persistedVersion ? Problem.AHEAD_OF_JOURNAL : null;
	}
}
