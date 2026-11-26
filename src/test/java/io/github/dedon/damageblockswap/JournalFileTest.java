package io.github.dedon.damageblockswap;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
		String text = "[[\"a:x\",\"a:y\"], null, [\"a:x\"], 5, [\"a:x\", 3], [\"a:z\",\"a:w\"]]";
		JournalFile.Contents c = JournalFile.parse(utf8(text));
		assertEquals(6, c.rules().size());
		assertEquals(JournalFile.RawRule.of("a:z", "a:w"), c.rules().get(5));
		for (int i = 1; i <= 4; i++) {
			assertFalse(c.rules().get(i).wellFormed(), "entry " + i);
		}
		// Written back verbatim.
		String written = JournalFile.serialize(c.journalId(), c.legacyRuleCount(), c.rules());
		assertEquals(c.rules(), JournalFile.parse(utf8(written)).rules());
		assertEquals(JsonParser.parseString(text), JsonParser.parseString(written).getAsJsonObject().get("rules"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"   \n",
			"[[\"minecraft:stone\",\"minecraft:dirt\"]",   // truncated mid-write
			"{\"format\":2,\"journalId\":\"",               // truncated mid-write
			"null",
			"\"text\"",
			"42",
			"[] []",
			"{}",
			"{\"format\":2,\"rules\":[]}",
			"{\"format\":2,\"journalId\":\"not-a-uuid\",\"rules\":[]}",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\"}",
			"{\"format\":2.5,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[]}",
			"{\"format\":3,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[]}",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[],\"legacyRuleCount\":\"3\"}",
			"{\"format\":2,\"journalId\":\"11111111-1111-1111-1111-111111111111\",\"rules\":[],\"legacyRuleCount\":-1}",
			"[['single', 'quotes']]",
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
