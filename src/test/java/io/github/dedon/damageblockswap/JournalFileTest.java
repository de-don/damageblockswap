package io.github.dedon.damageblockswap;

import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Journal file format, migration from format 1, corruption recovery and atomic writes. */
class JournalFileTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T10:15:30Z"), ZoneOffset.UTC);
	private static final UUID FRESH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final Supplier<UUID> NEW_ID = () -> FRESH_ID;

	private static final String LEGACY = """
			[
			  ["minecraft:stone", "minecraft:dirt"],
			  ["removedmod:ruby_ore", "minecraft:sand"],
			  ["minecraft:dirt", "minecraft:gravel"]
			]""";

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("damageblockswap_swaps.json");
	}

	private static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	// --- parse / serialize --------------------------------------------------------------------

	@Test
	void serializeThenParseRoundTrips() throws Exception {
		UUID id = UUID.randomUUID();
		List<JournalFile.RawRule> rules = List.of(
				JournalFile.RawRule.of("minecraft:stone", "minecraft:dirt"),
				JournalFile.RawRule.of("removedmod:ruby_ore", "minecraft:sand"),
				new JournalFile.RawRule(null, null, JsonParser.parseString("[\"only-one\"]")),
				new JournalFile.RawRule(null, null, JsonNull.INSTANCE));

		JournalFile.Contents parsed = JournalFile.parse(utf8(JournalFile.serialize(id, 2, rules)));

		assertEquals(id, parsed.journalId());
		assertEquals(2, parsed.legacyRuleCount());
		assertFalse(parsed.legacyFormat());
		assertEquals(rules, parsed.rules());
	}

	@Test
	void legacyFormatIsReadWithAllEntriesInPlace() throws Exception {
		JournalFile.Contents c = JournalFile.parse(utf8(LEGACY));
		assertTrue(c.legacyFormat());
		assertEquals(3, c.legacyRuleCount());
		assertEquals(List.of(
				JournalFile.RawRule.of("minecraft:stone", "minecraft:dirt"),
				JournalFile.RawRule.of("removedmod:ruby_ore", "minecraft:sand"),
				JournalFile.RawRule.of("minecraft:dirt", "minecraft:gravel")), c.rules());
	}

	@Test
	void legacyIdIsDeterministicPerContent() throws Exception {
		UUID first = JournalFile.parse(utf8(LEGACY)).journalId();
		assertEquals(first, JournalFile.parse(utf8(LEGACY)).journalId());
		assertNotEquals(first, JournalFile.parse(utf8("[[\"minecraft:stone\",\"minecraft:dirt\"]]")).journalId());
	}

	@Test
	void emptyLegacyArrayIsValid() throws Exception {
		JournalFile.Contents c = JournalFile.parse(utf8("[]"));
		assertTrue(c.legacyFormat());
		assertTrue(c.rules().isEmpty());
		assertEquals(0, c.legacyRuleCount());
		// Nothing to stay stable for, so worlds that never swapped don't all share one id.
		assertNotEquals(c.journalId(), JournalFile.parse(utf8("[]")).journalId());
	}

	@Test
	void malformedEntriesKeepTheirSlotAndText() throws Exception {
		String rules = "[[\"a:x\",\"a:y\"], null, [\"a:x\"], 5, [\"a:x\", 3], [\"a:z\",\"a:w\"]]";
		String text = "{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":" + rules + "}";
		JournalFile.Contents c = JournalFile.parse(utf8(text));
		assertEquals(6, c.rules().size());
		assertEquals(JournalFile.RawRule.of("a:z", "a:w"), c.rules().get(5));
		for (int i = 1; i <= 4; i++) {
			assertFalse(c.rules().get(i).wellFormed(), "entry " + i);
		}
		// Written back verbatim.
		String written = JournalFile.serialize(c.journalId(), c.legacyRuleCount(), c.rules());
		assertEquals(c.rules(), JournalFile.parse(utf8(written)).rules());
		assertEquals(JsonParser.parseString(rules), JsonParser.parseString(written).getAsJsonObject().get("rules"));
	}

	@Test
	void legacyEntriesThatMod10xSkippedAreDroppedSoIndicesMatchItsStamps() throws Exception {
		// 1.0.x skipped null and not-2-long entries without counting them: its rule 2 was ["a:z","a:w"].
		String text = "[[\"a:x\",\"a:y\"], null, [\"a:x\"], [\"a:z\",\"a:w\"], [\"a:x\", 3]]";
		JournalFile.Contents c = JournalFile.parse(utf8(text));
		assertEquals(3, c.rules().size());
		assertEquals(3, c.legacyRuleCount());
		assertEquals(JournalFile.RawRule.of("a:z", "a:w"), c.rules().get(1));
		// Anything else keeps its slot as a placeholder.
		assertFalse(c.rules().get(2).wellFormed());
		assertNotNull(c.note());
	}

	@Test
	void previousIdsRoundTripInOrder() throws Exception {
		Map<UUID, Integer> previous = new LinkedHashMap<>();
		previous.put(UUID.fromString("44444444-4444-4444-4444-444444444444"), 3);
		previous.put(UUID.fromString("22222222-2222-2222-2222-222222222222"), 7);
		UUID id = UUID.randomUUID();
		String text = JournalFile.serialize(id, 1, previous, List.of(JournalFile.RawRule.of("a:x", "a:y")));
		JournalFile.Contents c = JournalFile.parse(utf8(text));
		assertEquals(previous, c.previousIds());
		assertEquals(List.copyOf(previous.keySet()), List.copyOf(c.previousIds().keySet()));
		// Files written before previous ids existed simply have none.
		JournalFile.Contents old = JournalFile.parse(utf8(
				"{\"format\":2,\"journalId\":\"" + id + "\",\"legacyRuleCount\":0,\"rules\":[]}"));
		assertTrue(old.previousIds().isEmpty());
	}

	// --- format 1 as mod 1.0.x read it --------------------------------------------------------

	private static final String HAND_EDITED_LEGACY = """
			// edited by hand
			[
			  ['minecraft:stone', 'minecraft:dirt'],   # single quotes and comments
			  ["minecraft:dirt", "minecraft:gravel"]
			]
			""";

	@Test
	void handEditedLegacyFileThatMod10xAcceptedIsReadLikeIt() throws Exception {
		// What mod 1.0.x did: Gson.fromJson with a default Gson, which parses leniently.
		List<List<String>> asRead10x = new Gson().fromJson(HAND_EDITED_LEGACY, new TypeToken<List<List<String>>>() {
		}.getType());
		assertEquals(List.of(List.of("minecraft:stone", "minecraft:dirt"), List.of("minecraft:dirt", "minecraft:gravel")),
				asRead10x);

		JournalFile.Contents c = JournalFile.parse(utf8(HAND_EDITED_LEGACY));
		assertTrue(c.legacyFormat());
		assertEquals(2, c.legacyRuleCount());
		assertEquals(List.of(JournalFile.RawRule.of("minecraft:stone", "minecraft:dirt"),
				JournalFile.RawRule.of("minecraft:dirt", "minecraft:gravel")), c.rules());
		assertNotNull(c.note());
		assertEquals(c.journalId(), JournalFile.parse(utf8(HAND_EDITED_LEGACY)).journalId());
	}

	@Test
	void lenientEmptySlotIsAnEntryThatMod10xSkipped() throws Exception {
		String text = "[[\"a:x\",\"a:y\"],,[\"a:z\",\"a:w\"],]";
		List<List<String>> asRead10x = new Gson().fromJson(text, new TypeToken<List<List<String>>>() {
		}.getType());
		assertTrue(asRead10x.contains(null), "Gson reads the empty slot as null; 1.0.x skipped it uncounted");
		JournalFile.Contents c = JournalFile.parse(utf8(text));
		assertEquals(List.of(JournalFile.RawRule.of("a:x", "a:y"), JournalFile.RawRule.of("a:z", "a:w")), c.rules());
	}

	@Test
	void truncatedLegacyFileKeepsTheCompleteRulesBeforeTheCut() throws Exception {
		String full = "[\n  [\"a:1\", \"a:2\"],\n  [\"a:3\", \"a:4\"],\n  [\"a:5\", \"a:6\"]\n]";
		for (int cut = full.indexOf("[\"a:5\""); cut < full.indexOf("\"a:6\"]") + 5; cut++) {
			String truncated = full.substring(0, cut);
			JournalFile.Contents c = JournalFile.parse(utf8(truncated));
			assertTrue(c.legacyFormat(), truncated);
			assertEquals(2, c.legacyRuleCount(), truncated);
			assertEquals(List.of(JournalFile.RawRule.of("a:1", "a:2"), JournalFile.RawRule.of("a:3", "a:4")),
					c.rules(), truncated);
			assertNotNull(c.note());
		}
		// Only the closing bracket is missing: everything is kept.
		assertEquals(3, JournalFile.parse(utf8(full.substring(0, full.length() - 1))).rules().size());
	}

	@Test
	void truncatedLegacyFileIsMigratedWithAByteExactBackup() throws Exception {
		String truncated = "[[\"minecraft:stone\",\"minecraft:dirt\"],[\"minecraft:sa";
		Files.writeString(file(), truncated);
		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);
		assertEquals(JournalFile.Status.MIGRATED, r.status());
		assertEquals(1, r.contents().legacyRuleCount());
		assertEquals(truncated, Files.readString(r.backup()));
		assertEquals(1, JournalFile.parse(Files.readAllBytes(file())).rules().size());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"format\":3,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[]}",
			"{\"format\":17,\"whatever\":true}",
	})
	void journalOfANewerFormatIsUnsupportedNotCorrupt(String text) {
		assertThrows(JournalFile.UnsupportedFormatException.class, () -> JournalFile.parse(utf8(text)));
	}

	@Test
	void journalOfANewerFormatIsLeftUntouchedAndDisablesTheMod() throws IOException {
		String future = "{\"format\":3,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[]}";
		Files.writeString(file(), future);
		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);
		assertEquals(JournalFile.Status.UNREADABLE, r.status());
		assertTrue(r.detail().contains("format 3"), r.detail());
		assertEquals(future, Files.readString(file()));
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(List.of(file()), files.toList(), "no .corrupt-* copy, nothing else written");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"   \n",
			"[[\"minecraft:stone\",\"minecraft:di",         // truncated in the first rule: nothing to keep
			"{\"format\":2,\"journalId\":\"",               // truncated mid-write (format 2: never salvaged)
			"null",
			"\"text\"",
			"42",
			"[] []",
			"{}",
			"{\"format\":2,\"rules\":[]}",
			"{\"format\":2,\"journalId\":\"not-a-uuid\",\"rules\":[]}",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\"}",
			"{\"format\":2.5,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[]}",
			"{\"format\":1,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[]}",
			"{\"format\":0,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[]}",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[],\"previousJournalIds\":[]}",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[],\"previousJournalIds\":{\"nope\":1}}",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[],"
					+ "\"previousJournalIds\":{\"22222222-2222-2222-2222-222222222222\":-1}}",
			"{'format':2,'journalId':'11111111-1111-1111-1111-111111111111','rules':[]}", // format 2 is never lenient
			"[[\"a:x\",\"a:y\"]] trailing",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[],\"legacyRuleCount\":\"3\"}",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[],\"legacyRuleCount\":-1}",
			"[[\"a:x\",\"a:y\"], {broken]]",                 // garbled, not cut off: not salvaged
	})
	void corruptInputIsRejected(String text) {
		assertThrows(JournalFile.CorruptJournalException.class, () -> JournalFile.parse(utf8(text)));
	}

	// --- load ---------------------------------------------------------------------------------

	@Test
	void missingFileStartsANewJournalWithoutWriting() {
		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);
		assertEquals(JournalFile.Status.NEW, r.status());
		assertEquals(FRESH_ID, r.contents().journalId());
		assertEquals(0, r.contents().legacyRuleCount());
		assertTrue(r.contents().rules().isEmpty());
		assertFalse(Files.exists(file()));
	}

	@Test
	void currentFormatLoads() throws IOException {
		UUID id = UUID.randomUUID();
		Files.writeString(file(), JournalFile.serialize(id, 0, List.of(JournalFile.RawRule.of("a:b", "c:d"))));
		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);
		assertEquals(JournalFile.Status.LOADED, r.status());
		assertEquals(id, r.contents().journalId());
		assertEquals(1, r.contents().rules().size());
	}

	@Test
	void legacyFileIsMigratedWithBackupAndKeepsEveryRule() throws Exception {
		Files.writeString(file(), LEGACY);
		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);

		assertEquals(JournalFile.Status.MIGRATED, r.status());
		assertNull(r.detail());
		// Byte-for-byte copy of the old file.
		assertEquals(dir.resolve("damageblockswap_swaps.json.v1-backup"), r.backup());
		assertArrayEquals(utf8(LEGACY), Files.readAllBytes(r.backup()));
		// Rewritten in the new format, same id, same rules (including the unknown block) in order.
		JournalFile.Contents onDisk = JournalFile.parse(Files.readAllBytes(file()));
		assertFalse(onDisk.legacyFormat());
		assertEquals(3, onDisk.legacyRuleCount());
		assertEquals(r.contents().journalId(), onDisk.journalId());
		assertEquals(r.contents().rules(), onDisk.rules());
		assertEquals(3, onDisk.rules().size());

		// The next start just loads it, with the same id.
		JournalFile.LoadResult again = JournalFile.load(file(), NEW_ID, CLOCK);
		assertEquals(JournalFile.Status.LOADED, again.status());
		assertEquals(r.contents().journalId(), again.contents().journalId());
	}

	@Test
	void legacyBackupIsWrittenAtomically() throws Exception {
		// A stale temp file from a crash in the middle of an earlier backup attempt.
		Files.writeString(dir.resolve("damageblockswap_swaps.json.v1-backup.tmp"), "half a bac");
		Files.writeString(file(), LEGACY);
		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);
		assertArrayEquals(utf8(LEGACY), Files.readAllBytes(r.backup()));
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(List.of("damageblockswap_swaps.json", "damageblockswap_swaps.json.v1-backup"),
					files.map(p -> p.getFileName().toString()).sorted().toList());
		}
	}

	@Test
	void migrationThatCannotBeWrittenLeavesTheOldFileAndKeepsTheSameId() throws Exception {
		Files.writeString(file(), LEGACY);
		Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
		Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			Assumptions.assumeFalse(Files.isWritable(dir), "running as root: permissions are not enforced");
			JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);
			assertEquals(JournalFile.Status.MIGRATED, r.status());
			assertNotNull(r.detail());
			assertNull(r.backup(), "the backup could not be made");
			assertEquals(LEGACY, Files.readString(file()));
			assertEquals(3, r.contents().rules().size());

			JournalFile.LoadResult again = JournalFile.load(file(), NEW_ID, CLOCK);
			assertEquals(r.contents().journalId(), again.contents().journalId(),
					"chunks stamped in the first session must still match");
		} finally {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	void existingLegacyBackupIsNeverOverwritten() throws Exception {
		Path backup = dir.resolve("damageblockswap_swaps.json.v1-backup");
		Files.writeString(backup, "older backup");
		Files.writeString(file(), LEGACY);
		JournalFile.load(file(), NEW_ID, CLOCK);
		assertEquals("older backup", Files.readString(backup));
	}

	@Test
	void corruptFileIsMovedAsideAndAFreshJournalStarts() throws Exception {
		String garbage = "[[\"minecraft:stone\",\"minecraft:di";
		Files.writeString(file(), garbage);

		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);

		assertEquals(JournalFile.Status.RECOVERED_FROM_CORRUPT, r.status());
		assertNotNull(r.detail());
		assertEquals(dir.resolve("damageblockswap_swaps.json.corrupt-20261126-101530"), r.backup());
		assertEquals(garbage, Files.readString(r.backup()));
		assertFalse(Files.exists(file()), "the corrupt file must not stay where it would be overwritten");
		assertEquals(FRESH_ID, r.contents().journalId());
		assertEquals(0, r.contents().legacyRuleCount());
		assertTrue(r.contents().rules().isEmpty());

		// A second corrupt file in the same second gets its own name.
		Files.writeString(file(), "{");
		JournalFile.LoadResult second = JournalFile.load(file(), NEW_ID, CLOCK);
		assertEquals(dir.resolve("damageblockswap_swaps.json.corrupt-20261126-101530-1"), second.backup());
		assertEquals(garbage, Files.readString(r.backup()));
	}

	@Test
	void unreadablePathIsLeftAloneAndReported() throws IOException {
		Files.createDirectory(file()); // reading a directory fails with an IOException
		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);
		assertEquals(JournalFile.Status.UNREADABLE, r.status());
		assertNotNull(r.detail());
		assertTrue(Files.isDirectory(file()));
	}

	// --- atomic write -------------------------------------------------------------------------

	@Test
	void atomicWriteReplacesContentAndLeavesNoTempFile() throws IOException {
		Files.writeString(file(), "old content that is longer than the new one");
		Files.writeString(dir.resolve("damageblockswap_swaps.json.tmp"), "stale temp from a crash");

		JournalFile.writeAtomically(file(), "new");

		assertEquals("new", Files.readString(file()));
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(List.of(file()), files.toList());
		}
	}

	@Test
	void failedAtomicWriteLeavesTheTempFileWhichTheNextWriteReplaces() throws IOException {
		Files.createDirectories(file().resolve("blocker")); // a non-empty directory can't be renamed over
		assertThrows(IOException.class, () -> JournalFile.writeAtomically(file(), "attempt 1"));
		Path temp = dir.resolve("damageblockswap_swaps.json.tmp");
		assertEquals("attempt 1", Files.readString(temp));

		Files.delete(file().resolve("blocker"));
		Files.delete(file());
		JournalFile.writeAtomically(file(), "attempt 2");
		assertEquals("attempt 2", Files.readString(file()));
		assertFalse(Files.exists(temp));
	}

	@Test
	void atomicWriteCreatesTheFile() throws IOException {
		JournalFile.writeAtomically(file(), "{}");
		assertEquals("{}", Files.readString(file()));
	}

	@Test
	void placeholdersSurviveALoadSaveCycle() throws Exception {
		// Old-format world whose middle rule refers to a block from a mod that is gone now.
		Files.writeString(file(), LEGACY);
		JournalFile.LoadResult r = JournalFile.load(file(), NEW_ID, CLOCK);
		SwapJournal<String> journal = SwapJournal.fromContents(r.contents(),
				id -> id.startsWith("removedmod:") ? null : id);
		assertEquals(3, journal.version());
		assertEquals(1, journal.placeholderCount());

		journal.append(JournalFile.RawRule.of("minecraft:sand", "minecraft:clay"), "minecraft:sand", "minecraft:clay");
		JournalFile.writeAtomically(file(),
				JournalFile.serialize(journal.id(), journal.legacyRuleCount(), journal.rawRules()));

		JournalFile.Contents onDisk = JournalFile.parse(Files.readAllBytes(file()));
		assertEquals(4, onDisk.rules().size());
		assertEquals(JournalFile.RawRule.of("removedmod:ruby_ore", "minecraft:sand"), onDisk.rules().get(1));
		assertEquals(JournalFile.RawRule.of("minecraft:sand", "minecraft:clay"), onDisk.rules().get(3));
		assertEquals(journal.id(), onDisk.journalId());
		assertEquals(3, onDisk.legacyRuleCount()); // new rules don't extend the legacy range
	}
}
