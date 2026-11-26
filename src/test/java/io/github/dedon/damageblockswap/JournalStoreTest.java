package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.dedon.damageblockswap.ChunkStamp.Problem.AHEAD_OF_JOURNAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What is on disk vs. in memory, id rotation per session, failed writes. */
class JournalStoreTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T10:15:30Z"), ZoneOffset.UTC);

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("damageblockswap_swaps.json");
	}

	/** Opens the journal file like a server start does. */
	private JournalStore<String> open(JournalStore.Writer writer) {
		JournalFile.LoadResult r = JournalFile.load(file(), UUID::randomUUID, CLOCK);
		return new JournalStore<>(SwapJournal.fromContents(r.contents(), id -> id), r.status(), file(),
				UUID::randomUUID, writer);
	}

	private JournalStore<String> open() {
		return open(JournalFile::writeAtomically);
	}

	private static void add(JournalStore<String> store, String a, String b) {
		assertTrue(store.record(JournalFile.RawRule.of(a, b), a, b));
	}

	private static void addRules(JournalStore<String> store, int count, String prefix) {
		for (int i = 0; i < count; i++) {
			add(store, prefix + ":a" + i, prefix + ":b" + i);
		}
	}

	@Test
	void newJournalIsOnlyStampableOnceSaved() throws IOException {
		JournalStore<String> store = open();
		UUID id = store.journal().id();
		assertEquals(0, store.persistedVersion());
		assertFalse(store.dirty());

		add(store, "a:x", "a:y");
		assertEquals(id, store.journal().id(), "a brand-new id needs no rotation");
		assertTrue(store.dirty());
		assertEquals(0, store.persistedVersion(), "not on disk yet, so no chunk may get the rule");

		store.flush();
		assertFalse(store.dirty());
		assertEquals(1, store.persistedVersion());
		assertEquals(id, store.stampId());
		assertTrue(store.journal().previousIds().isEmpty());
	}

	@Test
	void firstRuleOfASessionMovesToANewIdAndTrustsTheOldOneUpToWhatItHad() throws IOException {
		JournalStore<String> first = open();
		addRules(first, 3, "s1");
		first.flush();
		UUID loadedId = first.stampId();

		JournalStore<String> second = open();
		assertEquals(loadedId, second.journal().id());
		assertFalse(second.dirty());

		add(second, "s2:a", "s2:b");
		UUID newId = second.journal().id();
		assertNotEquals(loadedId, newId);
		assertEquals(Map.of(loadedId, 3), second.journal().previousIds());
		// Until the new id is on disk, chunks keep being stamped with the old one (at most 3).
		assertEquals(loadedId, second.stampId());
		assertEquals(3, second.persistedVersion());
		assertEquals(3, second.evaluateStamp(loadedId, 3).startVersion());

		second.flush();
		assertEquals(newId, second.stampId());
		assertEquals(4, second.persistedVersion());

		// Rotation happens once per session, not per rule.
		add(second, "s2:c", "s2:d");
		assertEquals(newId, second.journal().id());
		second.flush();

		// The chain survives a reload.
		JournalStore<String> third = open();
		assertEquals(newId, third.journal().id());
		assertEquals(Map.of(loadedId, 3), third.journal().previousIds());
		assertEquals(2, third.evaluateStamp(loadedId, 2).startVersion());
		assertEquals(5, third.evaluateStamp(newId, 5).startVersion());
	}

	/**
	 * Review 2, MAJOR 1: a backup of a running server holds the journal file at 10 rules, but region
	 * files copied a bit later, with chunks stamped at 12 by the same journal. After restoring it, those
	 * chunks must stay quarantined, even once the journal has grown past 12 with other rules.
	 */
	@Test
	void chunkAheadOfARestoredJournalStaysQuarantinedForGood() throws IOException {
		JournalStore<String> running = open();
		addRules(running, 10, "old");
		running.flush();
		byte[] backupAt10 = Files.readAllBytes(file());
		addRules(running, 2, "lost");
		running.flush();
		UUID id = running.stampId(); // the chunk in the backup is stamped (id, 12)
		assertEquals(12, running.evaluateStamp(id, 12).startVersion());

		// Server stopped, journal file restored from the backup.
		Files.write(file(), backupAt10);
		JournalStore<String> restored = open();
		assertEquals(id, restored.journal().id());
		assertEquals(AHEAD_OF_JOURNAL, restored.evaluateStamp(id, 12).problem());

		// Two new, different rules: the count is back at 12, the stamp must still not match.
		addRules(restored, 2, "new");
		restored.flush();
		assertEquals(12, restored.persistedVersion());
		assertEquals(AHEAD_OF_JOURNAL, restored.evaluateStamp(id, 12).problem());
		assertEquals(AHEAD_OF_JOURNAL, restored.evaluateStamp(id, 11).problem());
		// Chunks stamped within the restored rules are fine.
		assertEquals(10, restored.evaluateStamp(id, 10).startVersion());

		// And after a restart, too.
		JournalStore<String> reopened = open();
		assertEquals(AHEAD_OF_JOURNAL, reopened.evaluateStamp(id, 12).problem());
		assertEquals(10, reopened.evaluateStamp(id, 10).startVersion());
		assertEquals(12, reopened.evaluateStamp(reopened.stampId(), 12).startVersion());
	}

	@Test
	void failedWriteChangesNothingOnDiskOrInTheStampAndIsRetried() throws IOException {
		JournalStore<String> first = open();
		addRules(first, 2, "s1");
		first.flush();
		byte[] onDisk = Files.readAllBytes(file());
		UUID id = first.stampId();

		AtomicBoolean failing = new AtomicBoolean(true);
		JournalStore<String> store = open((target, text) -> {
			if (failing.get()) {
				throw new IOException("disk full");
			}
			JournalFile.writeAtomically(target, text);
		});
		add(store, "s2:a", "s2:b");

		assertThrows(IOException.class, store::flush);
		assertTrue(store.dirty());
		assertEquals(2, store.persistedVersion(), "chunks must not get the unsaved rule");
		assertEquals(id, store.stampId(), "chunks must keep getting the id that is on disk");
		assertTrue(store.evaluateStamp(id, 3).isQuarantined(), "stamp 3 isn't backed by the file");
		assertEquals(2, store.evaluateStamp(id, 2).startVersion());
		assertArrayEquals(onDisk, Files.readAllBytes(file()), "the old file is untouched");

		failing.set(false);
		store.flush();
		assertFalse(store.dirty());
		assertEquals(3, store.persistedVersion());
		assertEquals(store.journal().id(), store.stampId());
		// A chunk stamped with the old id while the write was failing is still accepted.
		assertEquals(2, store.evaluateStamp(id, 2).startVersion());
	}

	@Test
	void unreadableJournalRecordsNothingAndWritesNothing() throws IOException {
		Files.createDirectory(file()); // reading fails with an I/O error
		AtomicBoolean wrote = new AtomicBoolean();
		JournalStore<String> store = open((target, text) -> wrote.set(true));
		assertFalse(store.writable());
		assertFalse(store.record(JournalFile.RawRule.of("a:x", "a:y"), "a:x", "a:y"));
		store.flush();
		assertFalse(wrote.get());
		assertTrue(store.evaluateStamp(UUID.randomUUID(), 3).isQuarantined());
		assertEquals(0, store.evaluateStamp(null, 0).startVersion());
	}

	@Test
	void migratedJournalRotatesAwayFromTheDerivedId() throws IOException {
		Files.writeString(file(), "[[\"a:x\",\"a:y\"],[\"a:z\",\"a:w\"]]");
		JournalStore<String> store = open();
		UUID derived = store.journal().id();
		assertEquals(2, store.evaluateStamp(null, 2).startVersion(), "1.0.x stamps within the old file");

		add(store, "b:x", "b:y");
		store.flush();
		assertEquals(Map.of(derived, 2), store.journal().previousIds());
		assertEquals(2, store.evaluateStamp(null, 2).startVersion());
		assertEquals(2, store.evaluateStamp(derived, 2).startVersion());
		assertTrue(store.evaluateStamp(null, 3).isQuarantined());
	}

	@Test
	void noWorldStoreIsInert() throws IOException {
		JournalStore<String> store = JournalStore.none(new UUID(0, 0));
		assertFalse(store.writable());
		assertFalse(store.record(JournalFile.RawRule.of("a:x", "a:y"), "a:x", "a:y"));
		store.flush();
		assertEquals(0, store.persistedVersion());
	}
}
