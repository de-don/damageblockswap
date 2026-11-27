package io.github.dedon.damageblockswap;

import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Composition of the swap rules (transpositions), with placeholders, against a brute-force model. */
class SwapJournalTest {
	private static final List<String> UNIVERSE = List.of("a", "b", "c", "d", "e", "f", "g", "h");

	private static SwapJournal<String> journal() {
		return new SwapJournal<>(UUID.randomUUID(), 0);
	}

	private static void add(SwapJournal<String> j, String a, String b) {
		j.append(JournalFile.RawRule.of(a, b), a, b);
	}

	private static void addPlaceholder(SwapJournal<String> j, String a, String b) {
		j.append(JournalFile.RawRule.of(a, b), null, null);
	}

	/** Brute force: push one element through rules [from, to) one by one. */
	private static String applySequentially(SwapJournal<String> j, String x, int from, int to) {
		for (int i = from; i < to; i++) {
			SwapJournal.Entry<String> e = j.entries().get(i);
			if (e.isPlaceholder()) {
				continue;
			}
			if (x.equals(e.a())) {
				x = e.b();
			} else if (x.equals(e.b())) {
				x = e.a();
			}
		}
		return x;
	}

	private static SwapJournal<String> randomJournal(long seed, int rules) {
		Random random = new Random(seed);
		SwapJournal<String> j = journal();
		for (int i = 0; i < rules; i++) {
			String a = UNIVERSE.get(random.nextInt(UNIVERSE.size()));
			String b = UNIVERSE.get(random.nextInt(UNIVERSE.size()));
			if (random.nextInt(10) == 0) {
				addPlaceholder(j, a, "othermod:" + b);
			} else {
				add(j, a, b); // a == b is allowed and must be a no-op
			}
		}
		return j;
	}

	@Test
	void composeMatchesSequentialApplicationForEveryRange() {
		for (long seed = 0; seed < 20; seed++) {
			SwapJournal<String> j = randomJournal(seed, 60);
			for (int from = 0; from <= j.version(); from += 3) {
				for (int to = from; to <= j.version(); to += 5) {
					Map<String, String> m = j.mapping(from, to);
					for (String x : UNIVERSE) {
						assertEquals(applySequentially(j, x, from, to), m.getOrDefault(x, x),
								"seed " + seed + " range [" + from + "," + to + ") element " + x);
					}
					m.forEach((k, v) -> assertNotEquals(k, v, "identity entries must be dropped"));
				}
			}
		}
	}

	@Test
	void splitRangesComposeToTheWholeRange() {
		SwapJournal<String> j = randomJournal(42, 50);
		Map<String, String> whole = j.mapping(0, j.version());
		for (int k = 0; k <= j.version(); k++) {
			Map<String, String> first = j.mapping(0, k);
			Map<String, String> second = j.mapping(k, j.version());
			for (String x : UNIVERSE) {
				String mid = first.getOrDefault(x, x);
				assertEquals(whole.getOrDefault(x, x), second.getOrDefault(mid, mid), "split at " + k + " element " + x);
			}
		}
	}

	@Test
	void undoByAppendingInverseRulesGivesIdentity() {
		SwapJournal<String> j = randomJournal(7, 30);
		int original = j.version();
		// Undo: append the same transpositions in reverse order (each is its own inverse).
		List<SwapJournal.Entry<String>> toUndo = new ArrayList<>(j.entries());
		for (int i = toUndo.size() - 1; i >= 0; i--) {
			SwapJournal.Entry<String> e = toUndo.get(i);
			j.append(e.raw(), e.a(), e.b());
		}
		assertEquals(2 * original, j.version());
		assertTrue(j.mapping(0, j.version()).isEmpty());
		assertTrue(j.changes().isEmpty());
		for (String x : UNIVERSE) {
			assertEquals(x, j.currentAppearanceOf(x));
			assertEquals(x, j.originalOf(x));
		}
	}

	@Test
	void repeatingTheLastRuleRestoresThePreviousState() {
		SwapJournal<String> j = randomJournal(11, 25);
		SwapJournal.Entry<String> last = j.entries().get(j.version() - 1);
		j.append(last.raw(), last.a(), last.b());
		assertEquals(j.mapping(0, j.version() - 2), j.mapping(0, j.version()));
	}

	@Test
	void lookupMapsAgreeWithFullComposition() {
		for (long seed = 100; seed < 110; seed++) {
			SwapJournal<String> j = randomJournal(seed, 40);
			Map<String, String> full = j.mapping(0, j.version());
			assertEquals(full, j.changes());
			for (String x : UNIVERSE) {
				String now = full.getOrDefault(x, x);
				assertEquals(now, j.currentAppearanceOf(x));
				assertEquals(x, j.originalOf(now));
			}
		}
	}

	@Test
	void placeholdersAreNoOpsButKeepTheirIndex() {
		SwapJournal<String> j = journal();
		add(j, "a", "b");                 // version 0 -> 1
		addPlaceholder(j, "c", "gone:x"); // 1 -> 2: unknown block, no-op
		add(j, "c", "d");                 // 2 -> 3

		assertEquals(3, j.version());
		assertEquals(1, j.placeholderCount());
		assertTrue(j.mapping(1, 2).isEmpty());
		assertEquals(Map.of("c", "d", "d", "c"), j.mapping(2, 3));
		assertEquals(Map.of("a", "b", "b", "a", "c", "d", "d", "c"), j.mapping(0, 3));
		// The original text survives for writing back.
		assertEquals(JournalFile.RawRule.of("c", "gone:x"), j.rawRules().get(1));
	}

	@Test
	void fromContentsResolvesUnknownIdsToPlaceholdersInPlace() {
		JournalFile.Contents contents = new JournalFile.Contents(UUID.randomUUID(), 0, Map.of(), List.of(
				JournalFile.RawRule.of("a", "b"),
				JournalFile.RawRule.of("a", "removedmod:ore"),
				new JournalFile.RawRule(null, null, new JsonPrimitive(5)),
				JournalFile.RawRule.of("b", "c")), false, null);
		SwapJournal<String> j = SwapJournal.fromContents(contents, id -> id.contains(":") ? null : id);

		assertEquals(4, j.version());
		assertEquals(2, j.placeholderCount());
		assertEquals(contents.rules(), j.rawRules());
		// Rule 3 (b <-> c) is still rule 3, so a chunk stamped at version 3 gets exactly it.
		assertEquals(Map.of("b", "c", "c", "b"), j.mapping(3, 4));
	}

	@Test
	void newIdKeepsTheOldOneTrustedUpToTheCurrentRuleCountOnly() {
		UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");
		UUID third = UUID.fromString("33333333-3333-3333-3333-333333333333");
		SwapJournal<String> j = new SwapJournal<>(first, 0);
		j.startNewId(second); // no rules yet: nothing to remember
		assertTrue(j.previousIds().isEmpty());
		add(j, "a", "b");
		add(j, "b", "c");
		j.startNewId(third);
		assertEquals(third, j.id());
		assertEquals(Map.of(second, 2), j.previousIds());
		add(j, "c", "d");
		assertEquals(2, j.evaluateStamp(second, 2, 3).startVersion());
		assertTrue(j.evaluateStamp(second, 3, 3).isQuarantined());
		assertEquals(3, j.evaluateStamp(third, 3, 3).startVersion());
		assertThrows(IllegalArgumentException.class, () -> j.startNewId(third));
	}

	@Test
	void prefixCacheFollowsAppendsAndRangesAreClamped() {
		SwapJournal<String> j = journal();
		add(j, "a", "b");
		assertEquals(Map.of("a", "b", "b", "a"), j.mapping(0, 1));
		add(j, "a", "b");
		assertTrue(j.mapping(0, 2).isEmpty());
		assertEquals(Map.of("a", "b", "b", "a"), j.mapping(0, 1)); // older prefix still right
		assertTrue(j.mapping(-5, 100).isEmpty());
		assertTrue(j.mapping(2, 1).isEmpty());
	}

	// --- /dbs undo: append the rule once more --------------------------------------------------------

	/** What /dbs undo does: appends the target rule again. Returns the undone index. */
	private static int undo(SwapJournal<String> j) {
		int target = j.undoTarget();
		assertTrue(target >= 0, "nothing to undo");
		SwapJournal.Entry<String> e = j.entries().get(target);
		add(j, e.a(), e.b());
		return target;
	}

	@Test
	void nothingToUndoInAnEmptyOrCancelledJournal() {
		SwapJournal<String> j = journal();
		assertEquals(-1, j.undoTarget());
		add(j, "a", "b");
		add(j, "b", "a"); // the same transposition, other order
		assertEquals(-1, j.undoTarget());
		addPlaceholder(j, "x", "y");
		assertEquals(-1, j.undoTarget(), "a placeholder is a no-op and can't be undone");
	}

	@Test
	void repeatedUndosWalkBackRuleByRule() {
		SwapJournal<String> j = journal();
		add(j, "a", "b");
		add(j, "c", "d");
		add(j, "a", "c");
		Map<String, String> afterTwo = Map.copyOf(j.mapping(0, 2));
		Map<String, String> afterOne = Map.copyOf(j.mapping(0, 1));

		assertEquals(2, undo(j));
		assertEquals(afterTwo, j.mapping(0, j.version()));
		assertEquals(1, undo(j), "the second undo must not undo the first undo");
		assertEquals(afterOne, j.mapping(0, j.version()));
		assertEquals(0, undo(j));
		assertTrue(j.mapping(0, j.version()).isEmpty());
		assertEquals(-1, j.undoTarget());
		assertEquals(6, j.version(), "undo erased rules instead of appending");
	}

	@Test
	void undoSkipsPlaceholdersAndCountsNewRulesAfterAnUndo() {
		SwapJournal<String> j = journal();
		add(j, "a", "b");
		addPlaceholder(j, "gone", "b");
		add(j, "c", "d");
		assertEquals(2, undo(j));
		assertEquals(0, j.undoTarget(), "the placeholder in between was not skipped");
		add(j, "e", "f");
		assertEquals(4, j.undoTarget(), "a rule added after an undo is the next to undo");
	}

	@Test
	void undoRestoresTheWorldForRandomJournals() {
		Random random = new Random(42);
		for (int round = 0; round < 200; round++) {
			SwapJournal<String> j = journal();
			int rules = 1 + random.nextInt(12);
			for (int i = 0; i < rules; i++) {
				String a = UNIVERSE.get(random.nextInt(UNIVERSE.size()));
				String b = UNIVERSE.get(random.nextInt(UNIVERSE.size()));
				if (a.equals(b)) {
					continue;
				}
				add(j, a, b);
			}
			// Undo until nothing is left: each step must give back a state the world had before.
			int guard = 0;
			while (j.undoTarget() >= 0) {
				int target = j.undoTarget();
				Map<String, String> expected = Map.copyOf(j.mapping(0, target));
				undo(j);
				// The state after the undo equals the state right before the undone rule was added,
				// as long as every rule after it was cancelled already (true by construction).
				assertEquals(expected, j.mapping(0, j.version()), "round " + round);
				assertTrue(++guard <= 2 * rules + 1, "undo does not terminate");
			}
			assertTrue(j.mapping(0, j.version()).isEmpty(), "undoing everything must restore the original world");
		}
	}
}
