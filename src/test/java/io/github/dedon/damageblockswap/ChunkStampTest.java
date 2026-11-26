package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.dedon.damageblockswap.ChunkStamp.Problem.AHEAD_OF_JOURNAL;
import static io.github.dedon.damageblockswap.ChunkStamp.Problem.LEGACY_NOT_ADOPTED;
import static io.github.dedon.damageblockswap.ChunkStamp.Problem.OTHER_JOURNAL;
import static io.github.dedon.damageblockswap.ChunkStamp.QUARANTINED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** How a chunk's (journal id, version) stamp is interpreted against the current journal. */
class ChunkStampTest {
	private static final UUID CURRENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Test
	void untouchedChunkStartsAtZeroWhateverTheJournal() {
		assertEquals(0, ChunkStamp.startVersion(null, 0, CURRENT, 10, 0));
		assertEquals(0, ChunkStamp.startVersion(OTHER, 0, CURRENT, 10, 0));
		assertEquals(0, ChunkStamp.startVersion(null, 0, CURRENT, 0, 0));
	}

	@Test
	void sameJournalContinuesFromItsStamp() {
		assertEquals(4, ChunkStamp.startVersion(CURRENT, 4, CURRENT, 10, 0));
		assertEquals(10, ChunkStamp.startVersion(CURRENT, 10, CURRENT, 10, 0));
		// Rules inherited from format 1 don't matter for stamps that carry an id.
		assertEquals(8, ChunkStamp.startVersion(CURRENT, 8, CURRENT, 10, 3));
	}

	@Test
	void otherJournalIsQuarantined() {
		assertEquals(QUARANTINED, ChunkStamp.startVersion(OTHER, 4, CURRENT, 10, 10));
		assertEquals(OTHER_JOURNAL, ChunkStamp.problem(OTHER, 4, CURRENT, 10, 10));
	}

	@Test
	void stampAheadOfSavedRulesIsQuarantined() {
		assertEquals(QUARANTINED, ChunkStamp.startVersion(CURRENT, 11, CURRENT, 10, 0));
		assertEquals(AHEAD_OF_JOURNAL, ChunkStamp.problem(CURRENT, 11, CURRENT, 10, 0));
		// Legacy stamp within the inherited range but beyond what is saved (inconsistent file).
		assertEquals(AHEAD_OF_JOURNAL, ChunkStamp.problem(null, 11, CURRENT, 10, 12));
	}

	@Test
	void legacyStampIsAdoptedWithinTheInheritedRules() {
		assertEquals(7, ChunkStamp.startVersion(null, 7, CURRENT, 10, 10));
		assertEquals(10, ChunkStamp.startVersion(null, 10, CURRENT, 10, 10));
		assertNull(ChunkStamp.problem(null, 7, CURRENT, 10, 7));
	}

	@Test
	void legacyStampIsRejectedByAJournalThatWasNotMigrated() {
		// Fresh journal, or recreated after corruption: it can't know what an id-less stamp counted in.
		assertEquals(QUARANTINED, ChunkStamp.startVersion(null, 7, CURRENT, 10, 0));
		assertEquals(LEGACY_NOT_ADOPTED, ChunkStamp.problem(null, 7, CURRENT, 10, 0));
	}

	@Test
	void legacyStampBeyondInheritedRulesStaysQuarantinedAfterTheJournalGrows() {
		// Mod 1.0.x saved 5 rules, then a chunk stamped 7 was saved before a crash (rules 6-7 lost).
		assertEquals(LEGACY_NOT_ADOPTED, ChunkStamp.problem(null, 7, CURRENT, 5, 5));
		// Two new, different rules later the journal is at 7 again: the stamp must still not match.
		assertEquals(LEGACY_NOT_ADOPTED, ChunkStamp.problem(null, 7, CURRENT, 7, 5));
		assertEquals(QUARANTINED, ChunkStamp.startVersion(null, 7, CURRENT, 20, 5));
	}
}
