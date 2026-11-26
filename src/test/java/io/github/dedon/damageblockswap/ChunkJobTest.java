package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static io.github.dedon.damageblockswap.ChunkStamp.Problem.AHEAD_OF_JOURNAL;
import static io.github.dedon.damageblockswap.ChunkStamp.Problem.OTHER_JOURNAL;
import static io.github.dedon.damageblockswap.ChunkStamp.Problem.PROGRESS_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunk pass protocol: a chunk saved half-way (unload, autosave, stop, crash) and loaded again
 * gets each rule exactly once, never twice.
 */
class ChunkJobTest {
	private static final String[] BLOCKS = {"a", "b", "c", "d", "e", "f"};
	private static final int SECTIONS = 3;

	/** A chunk of strings, with the swap data that is saved with it. */
	private static final class FakeChunk implements ChunkJob.ChunkData<String> {
		final String[] cells;
		UUID stampId;
		int stampVersion;
		SwapProgress progress;
		int progressWrites;
		// Makes set() throw at this cell (after changing it if throwAfterChange), once.
		int throwAt = -1;
		boolean throwAfterChange = true;

		FakeChunk(String[] cells) {
			this.cells = cells;
		}

		/** What a save writes and a load reads back. */
		FakeChunk copy() {
			FakeChunk c = new FakeChunk(cells.clone());
			c.stampId = stampId;
			c.stampVersion = stampVersion;
			c.progress = progress;
			return c;
		}

		@Override
		public int sectionCount() {
			return cells.length / ChunkJob.SECTION_SIZE;
		}

		@Override
		public boolean mayContainAny(int section, Map<String, String> mapping) {
			for (int i = 0; i < ChunkJob.SECTION_SIZE; i++) {
				if (mapping.containsKey(cells[section * ChunkJob.SECTION_SIZE + i])) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String get(int section, int index) {
			return cells[section * ChunkJob.SECTION_SIZE + index];
		}

		@Override
		public boolean set(int section, int index, String value) {
			int cell = section * ChunkJob.SECTION_SIZE + index;
			if (cell == throwAt) {
				throwAt = -1;
				if (throwAfterChange) {
					cells[cell] = value;
				}
				throw new IllegalStateException("neighbour update blew up");
			}
			cells[cell] = value;
			return true;
		}

		@Override
		public void writeStamp(UUID journalId, int version) {
			stampId = journalId;
			stampVersion = version;
			progress = null;
		}

		@Override
		public void writeProgress(SwapProgress p) {
			progress = p;
			progressWrites++;
		}
	}

	/** At most {@code changes} block changes, and the clock "runs out" after {@code checks} checks. */
	private static final class CountingBudget implements ChunkJob.Budget {
		int changes;
		int checks;

		CountingBudget(int changes, int checks) {
			this.changes = changes;
			this.checks = checks;
		}

		static CountingBudget changes(int changes) {
			return new CountingBudget(changes, Integer.MAX_VALUE);
		}

		@Override
		public boolean hasRoom() {
			return changes > 0 && checks-- > 0;
		}

		@Override
		public void changed() {
			changes--;
		}
	}

	private static JournalStore<String> newStore() {
		return new JournalStore<>(new SwapJournal<>(UUID.randomUUID(), 0), JournalFile.Status.NEW,
				Path.of("unused.json"), UUID::randomUUID, (target, text) -> {
		});
	}

	/** A server restart: the same rules, loaded from the file; the next rule moves to a new id. */
	private static JournalStore<String> restart(JournalStore<String> store) {
		return new JournalStore<>(store.journal(), JournalFile.Status.LOADED, Path.of("unused.json"),
				UUID::randomUUID, (target, text) -> {
		});
	}

	private static void addSavedRule(JournalStore<String> store, String a, String b) throws Exception {
		assertTrue(store.record(JournalFile.RawRule.of(a, b), a, b));
		store.flush();
	}

	private static String[] cells(Random random) {
		String[] cells = new String[SECTIONS * ChunkJob.SECTION_SIZE];
		for (int i = 0; i < cells.length; i++) {
			cells[i] = BLOCKS[random.nextInt(BLOCKS.length)];
		}
		// One section that the rules never touch, to exercise skipping.
		Arrays.fill(cells, ChunkJob.SECTION_SIZE, 2 * ChunkJob.SECTION_SIZE, "untouched");
		return cells;
	}

	/** Every cell mapped once from version 0 to {@code version}. */
	private static String[] expected(String[] original, JournalStore<String> store, int version) {
		Map<String, String> mapping = store.journal().mapping(0, version);
		String[] out = original.clone();
		for (int i = 0; i < out.length; i++) {
			out[i] = mapping.getOrDefault(out[i], out[i]);
		}
		return out;
	}

	private static ChunkJob.Plan plan(JournalStore<String> store, FakeChunk chunk) {
		return ChunkJob.plan(store, chunk.stampId, chunk.stampVersion, chunk.progress);
	}

	/** Runs passes until the chunk is up to date with what is on disk. */
	private static void catchUp(JournalStore<String> store, FakeChunk chunk) {
		ChunkJob.Plan plan;
		while ((plan = plan(store, chunk)).hasWork()) {
			ChunkJob<String> job = ChunkJob.start(store, chunk, plan);
			while (!job.run(CountingBudget.changes(Integer.MAX_VALUE))) {
				// keep going
			}
		}
		assertFalse(plan.isQuarantined());
	}

	// --- The bug from the review: a chunk saved half-way got the first half swapped twice -------

	@Test
	void chunkSavedHalfWayIsResumedNotSwappedTwice() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		String[] original = cells(new Random(1));
		FakeChunk live = new FakeChunk(original.clone());

		ChunkJob<String> job = ChunkJob.start(store, live, plan(store, live));
		assertFalse(job.run(CountingBudget.changes(1000)), "only part of the chunk fits the budget");
		assertNotNull(live.progress, "progress is saved with the chunk when a slice ends");
		assertEquals(0, live.stampVersion, "the stamp only moves once the chunk is done");

		// The chunk is unloaded (saved) here, then loaded again: the in-memory job is gone.
		FakeChunk reloaded = live.copy();
		ChunkJob.Plan resume = plan(store, reloaded);
		assertEquals(live.progress.position(), resume.position());
		assertEquals(0, resume.from());
		assertEquals(1, resume.to());
		catchUp(store, reloaded);

		assertArrayEquals(expected(original, store, 1), reloaded.cells);
		assertEquals(1, reloaded.stampVersion);
		assertNull(reloaded.progress, "the record is dropped once the chunk is done");
	}

	@Test
	void unfinishedPassKeepsItsTargetEvenIfNewRulesArrive() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		String[] original = cells(new Random(2));
		FakeChunk chunk = new FakeChunk(original.clone());
		ChunkJob.start(store, chunk, plan(store, chunk)).run(CountingBudget.changes(500));

		addSavedRule(store, "b", "c");
		ChunkJob.Plan plan = plan(store, chunk);
		assertEquals(0, plan.from());
		assertEquals(1, plan.to(), "the half-done pass is finished first, with the rules it started with");
		catchUp(store, chunk);
		assertEquals(2, chunk.stampVersion);
		assertArrayEquals(expected(original, store, 2), chunk.cells);
	}

	/**
	 * Random sessions: rules are added, slices of random size run, the chunk is saved at random moments,
	 * the server stops or crashes (back to the last save, or a crash save from the middle of a slice)
	 * and restarts (new journal id). Whatever happens, every cell ends up mapped exactly once.
	 */
	@RepeatedTest(200)
	void anyMixOfSlicesSavesCrashesAndRestartsAppliesEachRuleOnce(RepetitionInfo info) throws Exception {
		Random random = new Random(info.getCurrentRepetition());
		JournalStore<String> store = newStore();
		String[] original = cells(random);
		FakeChunk live = new FakeChunk(original.clone());
		FakeChunk saved = live.copy();
		ChunkJob<String> job = null;

		for (int step = 0; step < 60; step++) {
			switch (random.nextInt(6)) {
				case 0 -> {
					String a = BLOCKS[random.nextInt(BLOCKS.length)];
					String b = BLOCKS[random.nextInt(BLOCKS.length)];
					if (!a.equals(b)) {
						addSavedRule(store, a, b);
					}
				}
				case 1, 2 -> {
					if (job == null) {
						ChunkJob.Plan plan = plan(store, live);
						assertFalse(plan.isQuarantined(), "never quarantined by its own pass: " + plan);
						job = plan.hasWork() ? ChunkJob.start(store, live, plan) : null;
					}
					if (job != null && job.run(new CountingBudget(1 + random.nextInt(3000), 1 + random.nextInt(40)))) {
						job = null;
					}
				}
				case 3 -> saved = live.copy(); // autosave or unload
				case 4 -> { // stop or crash: back to what was saved
					live = saved.copy();
					job = null;
					if (random.nextBoolean()) {
						store = restart(store);
					}
				}
				case 5 -> { // a mod's block update throws in the middle of a slice; the crash saves all
					if (job == null) {
						break;
					}
					live.throwAt = random.nextInt(live.cells.length);
					live.throwAfterChange = true;
					try {
						job.run(CountingBudget.changes(Integer.MAX_VALUE));
					} catch (IllegalStateException expectedCrash) {
						// the server crashes and saves every chunk
					}
					live.throwAt = -1;
					saved = live.copy();
					live = saved.copy();
					job = null;
					store = restart(store);
				}
				default -> throw new AssertionError();
			}
		}
		catchUp(store, live);
		assertArrayEquals(expected(original, store, store.persistedVersion()), live.cells);
		assertEquals(store.persistedVersion(), live.stampVersion);
	}

	@Test
	void failureBeforeTheBlockChangedLeavesThatOneBlockOldButNeverSwapsTwice() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		String[] original = new String[SECTIONS * ChunkJob.SECTION_SIZE];
		Arrays.fill(original, "a");
		FakeChunk chunk = new FakeChunk(original.clone());
		chunk.throwAt = 10;
		chunk.throwAfterChange = false;

		ChunkJob<String> job = ChunkJob.start(store, chunk, plan(store, chunk));
		assertThrows(IllegalStateException.class, () -> job.run(CountingBudget.changes(Integer.MAX_VALUE)));
		assertEquals(11, chunk.progress.position(), "the failed cell counts as done");

		FakeChunk reloaded = chunk.copy();
		catchUp(store, reloaded);
		String[] want = expected(original, store, 1);
		want[10] = "a"; // the one block whose change failed
		assertArrayEquals(want, reloaded.cells);
	}

	// --- Plans ----------------------------------------------------------------------------------

	@Test
	void planWithoutProgressGoesUpToTheSavedRules() throws Exception {
		JournalStore<String> store = newStore();
		FakeChunk chunk = new FakeChunk(cells(new Random(3)));
		assertSame(ChunkJob.Plan.UP_TO_DATE, plan(store, chunk));

		addSavedRule(store, "a", "b");
		addSavedRule(store, "c", "d");
		assertTrue(store.record(JournalFile.RawRule.of("e", "f"), "e", "f")); // not on disk yet
		ChunkJob.Plan plan = plan(store, chunk);
		assertEquals(0, plan.from());
		assertEquals(2, plan.to(), "unsaved rules are never applied");
		assertEquals(0, plan.position());

		chunk.stampId = store.stampId();
		chunk.stampVersion = 2;
		assertFalse(plan(store, chunk).hasWork());
		assertFalse(plan(store, chunk).isQuarantined());
	}

	@Test
	void progressThatDoesNotStartAtTheStampIsQuarantined() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		addSavedRule(store, "b", "c");
		UUID id = store.stampId();
		ChunkJob.Plan plan = ChunkJob.plan(store, id, 1, new SwapProgress(id, 0, 2, 100));
		assertEquals(PROGRESS_MISMATCH, plan.problem());
		assertFalse(plan.hasWork());
		assertFalse(plan.staleProgress());

		assertEquals(PROGRESS_MISMATCH, ChunkJob.plan(store, id, 0, new SwapProgress(id, 0, 2, -5)).problem());
		assertEquals(PROGRESS_MISMATCH, ChunkJob.plan(store, id, 1, new SwapProgress(id, 1, 0, 0)).problem());
		// A record whose target is behind the stamp isn't "the pass the stamp finished" either.
		assertEquals(PROGRESS_MISMATCH, ChunkJob.plan(store, id, 2, new SwapProgress(id, 0, 1, 0)).problem());
	}

	@Test
	void progressWhoseTargetIsTheStampIsAFinishedPassAndIsDropped() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		UUID id = store.stampId();

		// from == to == stamp (review_3 minor 9), and a full record of the pass 0 -> 1 next to stamp 1:
		// both say the chunk is at the stamp, so it is up to date and the record goes.
		for (SwapProgress leftOver : new SwapProgress[]{new SwapProgress(id, 1, 1, 0), new SwapProgress(id, 0, 1, 77)}) {
			ChunkJob.Plan plan = ChunkJob.plan(store, id, 1, leftOver);
			assertFalse(plan.isQuarantined(), leftOver.toString());
			assertFalse(plan.hasWork(), leftOver.toString());
			assertTrue(plan.staleProgress(), leftOver.toString());
		}

		// Rules saved since: a new pass from the stamp, from cell 0, and the record still goes.
		addSavedRule(store, "c", "d");
		ChunkJob.Plan plan = ChunkJob.plan(store, id, 1, new SwapProgress(id, 0, 1, 77));
		assertTrue(plan.hasWork());
		assertEquals(1, plan.from());
		assertEquals(2, plan.to());
		assertEquals(0, plan.position());
		assertTrue(plan.staleProgress());

		// A real unfinished pass is not stale.
		assertFalse(ChunkJob.plan(store, id, 1, new SwapProgress(id, 1, 2, 5)).staleProgress());
		assertFalse(ChunkJob.plan(store, id, 1, null).staleProgress());
	}

	@Test
	void finishedPassRecordFromAnotherJournalIsStillQuarantined() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		UUID id = store.stampId();
		// The record's target is checked like a stamp before anything else.
		ChunkJob.Plan plan = ChunkJob.plan(store, id, 1, new SwapProgress(UUID.randomUUID(), 1, 1, 0));
		assertEquals(OTHER_JOURNAL, plan.problem());
		assertFalse(plan.staleProgress());
	}

	@Test
	void progressBeyondTheJournalOrFromAnotherJournalIsQuarantined() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		UUID id = store.stampId();
		// The journal file was restored from a copy older than the chunk's pass.
		assertEquals(AHEAD_OF_JOURNAL, ChunkJob.plan(store, id, 0, new SwapProgress(id, 0, 3, 7)).problem());
		assertEquals(OTHER_JOURNAL,
				ChunkJob.plan(store, id, 0, new SwapProgress(UUID.randomUUID(), 0, 1, 7)).problem());
		// The stamp itself is checked first.
		ChunkJob.Plan plan = ChunkJob.plan(store, UUID.randomUUID(), 1, new SwapProgress(id, 1, 1, 0));
		assertEquals(OTHER_JOURNAL, plan.problem());
	}

	@Test
	void progressUnderAnEarlierIdOfTheSameJournalIsResumed() throws Exception {
		JournalStore<String> first = newStore();
		addSavedRule(first, "a", "b");
		UUID oldId = first.stampId();
		JournalStore<String> second = restart(first);
		addSavedRule(second, "b", "c"); // moves to a new id
		assertFalse(oldId.equals(second.stampId()));

		ChunkJob.Plan plan = ChunkJob.plan(second, null, 0, new SwapProgress(oldId, 0, 1, 42));
		assertTrue(plan.hasWork());
		assertEquals(1, plan.to());
		assertEquals(42, plan.position());
	}

	// --- Budget ---------------------------------------------------------------------------------

	@Test
	void clockIsCheckedEvenWhileNothingChanges() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		String[] cells = new String[SECTIONS * ChunkJob.SECTION_SIZE];
		Arrays.fill(cells, "z");
		cells[cells.length - 1] = "a"; // the only match is in the very last cell
		FakeChunk chunk = new FakeChunk(cells);

		ChunkJob<String> job = ChunkJob.start(store, chunk, plan(store, chunk));
		assertFalse(job.run(new CountingBudget(Integer.MAX_VALUE, 3)), "stops when the clock runs out");
		assertTrue(job.position() < cells.length - 1);
		assertEquals(job.position(), chunk.progress.position());
		assertTrue(job.run(CountingBudget.changes(Integer.MAX_VALUE)));
		assertEquals("b", chunk.cells[cells.length - 1]);
	}

	@Test
	void progressIsOnlyWrittenWhenItMoved() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		FakeChunk chunk = new FakeChunk(cells(new Random(4)));
		ChunkJob<String> job = ChunkJob.start(store, chunk, plan(store, chunk));
		assertFalse(job.run(new CountingBudget(Integer.MAX_VALUE, 0)));
		assertEquals(0, chunk.progressWrites, "nothing done, nothing to write");
		assertNull(chunk.progress);
		assertFalse(job.run(CountingBudget.changes(10)));
		assertEquals(1, chunk.progressWrites);
	}

	@Test
	void emptyMappingStampsAtOnce() throws Exception {
		JournalStore<String> store = newStore();
		addSavedRule(store, "a", "b");
		addSavedRule(store, "a", "b"); // undoes the first: nothing changes from 0 to 2
		FakeChunk chunk = new FakeChunk(cells(new Random(5)));
		String[] before = chunk.cells.clone();
		ChunkJob<String> job = ChunkJob.start(store, chunk, plan(store, chunk));
		assertTrue(job.run(new CountingBudget(1, 1)));
		assertEquals(2, chunk.stampVersion);
		assertArrayEquals(before, chunk.cells);
	}

	@Test
	void startingWithoutWorkIsRejected() {
		JournalStore<String> store = newStore();
		FakeChunk chunk = new FakeChunk(cells(new Random(6)));
		assertThrows(IllegalArgumentException.class, () -> ChunkJob.start(store, chunk, ChunkJob.Plan.UP_TO_DATE));
	}
}
