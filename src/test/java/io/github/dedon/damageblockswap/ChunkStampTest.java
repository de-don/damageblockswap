package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.github.dedon.damageblockswap.ChunkStamp.Problem.AHEAD_OF_JOURNAL;
import static io.github.dedon.damageblockswap.ChunkStamp.Problem.LEGACY_NOT_ADOPTED;
import static io.github.dedon.damageblockswap.ChunkStamp.Problem.OTHER_JOURNAL;
import static io.github.dedon.damageblockswap.ChunkStamp.QUARANTINED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a chunk's (journal id, version) stamp is interpreted against the current journal. */
class ChunkStampTest {
	private static final UUID CURRENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID PREVIOUS = UUID.fromString("44444444-4444-4444-4444-444444444444");

	private static ChunkStamp.Result eval(UUID stampId, int stampVersion, int persisted, int legacy) {
		return ChunkStamp.evaluate(stampId, stampVersion, CURRENT, persisted, legacy, Map.of());
	}

	private static int start(UUID stampId, int stampVersion, int persisted, int legacy) {
		return eval(stampId, stampVersion, persisted, legacy).startVersion();
	}

	private static ChunkStamp.Problem problem(UUID stampId, int stampVersion, int persisted, int legacy) {
		return eval(stampId, stampVersion, persisted, legacy).problem();
	}

	@Test
	void untouchedChunkStartsAtZeroWhateverTheJournal() {
		assertEquals(0, start(null, 0, 10, 0));
		assertEquals(0, start(OTHER, 0, 10, 0));
		assertEquals(0, start(null, 0, 0, 0));
		assertFalse(eval(OTHER, 0, 10, 0).isQuarantined());
	}

	@Test
	void sameJournalContinuesFromItsStamp() {
		assertEquals(4, start(CURRENT, 4, 10, 0));
		assertEquals(10, start(CURRENT, 10, 10, 0));
		// Rules inherited from format 1 don't matter for stamps that carry an id.
		assertEquals(8, start(CURRENT, 8, 10, 3));
	}

	@Test
	void otherJournalIsQuarantined() {
		ChunkStamp.Result r = eval(OTHER, 4, 10, 10);
		assertTrue(r.isQuarantined());
		assertEquals(QUARANTINED, r.startVersion());
		assertEquals(OTHER_JOURNAL, r.problem());
	}

	@Test
	void stampAheadOfSavedRulesIsQuarantined() {
		assertEquals(QUARANTINED, start(CURRENT, 11, 10, 0));
		assertEquals(AHEAD_OF_JOURNAL, problem(CURRENT, 11, 10, 0));
		// Legacy stamp within the inherited range but beyond what is saved (inconsistent file).
		assertEquals(AHEAD_OF_JOURNAL, problem(null, 11, 10, 12));
	}

	@Test
	void legacyStampIsAdoptedWithinTheInheritedRules() {
		assertEquals(7, start(null, 7, 10, 10));
		assertEquals(10, start(null, 10, 10, 10));
		assertNull(problem(null, 7, 10, 7));
	}

	@Test
	void legacyStampIsRejectedByAJournalThatWasNotMigrated() {
		// Fresh journal, or recreated after corruption: it can't know what an id-less stamp counted in.
		assertEquals(QUARANTINED, start(null, 7, 10, 0));
		assertEquals(LEGACY_NOT_ADOPTED, problem(null, 7, 10, 0));
	}

	@Test
	void legacyStampBeyondInheritedRulesStaysQuarantinedAfterTheJournalGrows() {
		// Mod 1.0.x saved 5 rules, then a chunk stamped 7 was saved before a crash (rules 6-7 lost).
		assertEquals(LEGACY_NOT_ADOPTED, problem(null, 7, 5, 5));
		// Two new, different rules later the journal is at 7 again: the stamp must still not match.
		assertEquals(LEGACY_NOT_ADOPTED, problem(null, 7, 7, 5));
		assertEquals(QUARANTINED, start(null, 7, 20, 5));
	}

	@Test
	void previousIdIsTrustedUpToItsRuleCount() {
		Map<UUID, Integer> previous = Map.of(PREVIOUS, 10);
		assertEquals(7, ChunkStamp.evaluate(PREVIOUS, 7, CURRENT, 15, 0, previous).startVersion());
		assertEquals(10, ChunkStamp.evaluate(PREVIOUS, 10, CURRENT, 15, 0, previous).startVersion());
		// Beyond what the previous id had (rules lost: older journal file restored). Permanently,
		// even though the current journal has more rules by now.
		assertEquals(AHEAD_OF_JOURNAL, ChunkStamp.evaluate(PREVIOUS, 12, CURRENT, 15, 0, previous).problem());
		// Beyond what is on disk now: quarantined too.
		assertEquals(AHEAD_OF_JOURNAL, ChunkStamp.evaluate(PREVIOUS, 9, CURRENT, 8, 0, Map.of(PREVIOUS, 9)).problem());
		// Unknown ids stay "other journal".
		assertEquals(OTHER_JOURNAL, ChunkStamp.evaluate(OTHER, 3, CURRENT, 15, 0, previous).problem());
	}
}
