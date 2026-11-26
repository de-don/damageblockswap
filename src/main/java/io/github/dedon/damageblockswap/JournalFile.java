package io.github.dedon.damageblockswap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * On-disk format of the swap journal, and the safe way to read and write it. No Minecraft classes
 * here, so all of it is unit-tested.
 *
 * <p>Current format (2):
 * <pre>{@code
 * {
 *   "format": 2,
 *   "journalId": "6f1c...",   // identifies this journal; chunks remember it in their stamp
 *   "legacyRuleCount": 12,    // rules inherited from a format-1 file (0 if none): chunk stamps
 *                             // without an id are trusted up to this version
 *   "rules": [["minecraft:stone", "minecraft:dirt"], ...]
 * }
 * }</pre>
 * Format 1 (mod 1.0.x) was just the bare {@code rules} array. It is migrated on load.
 *
 * <p>Rule entries are kept <b>verbatim</b>: an entry whose block no longer exists (or that is not a
 * pair of strings at all) still occupies its index, and is written back exactly as it was read.
 * Indices are the versions chunks are stamped with, so they must never shift.
 */
final class JournalFile {
	static final int FORMAT = 2;
	static final String LEGACY_BACKUP_SUFFIX = ".v1-backup";
	static final String TEMP_SUFFIX = ".tmp";
	static final String CORRUPT_SUFFIX = ".corrupt-";

	// Own logger instead of DamageBlockSwap.LOGGER: touching DamageBlockSwap would run its static
	// init (attachment registration), which needs Fabric at runtime and breaks plain unit tests.
	private static final Logger LOGGER = LoggerFactory.getLogger(DamageBlockSwap.MOD_ID);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

	private JournalFile() {
	}

	/**
	 * One journal entry as stored. Either a well-formed pair ({@code a}, {@code b} set, {@code verbatim}
	 * null) or an unparseable entry kept as raw JSON ({@code verbatim} set) so it can be written back.
	 */
	record RawRule(String a, String b, JsonElement verbatim) {
		static RawRule of(String a, String b) {
			return new RawRule(a, b, null);
		}

		boolean wellFormed() {
			return verbatim == null;
		}

		JsonElement toJson() {
			if (verbatim != null) {
				return verbatim.deepCopy();
			}
			JsonArray pair = new JsonArray(2);
			pair.add(a);
			pair.add(b);
			return pair;
		}
	}

	/**
	 * Parsed journal contents. {@code legacyRuleCount}: how many of the rules were inherited from a
	 * format-1 file (chunks stamped by mod 1.0.x counted in those). {@code legacyFormat}: read from
	 * format 1 just now.
	 */
	record Contents(UUID journalId, int legacyRuleCount, List<RawRule> rules, boolean legacyFormat) {
		Contents {
			rules = List.copyOf(rules);
		}
	}

	/** The file exists but cannot be understood. It must be backed up, never silently overwritten. */
	static final class CorruptJournalException extends Exception {
		CorruptJournalException(String message) {
			super(message);
		}

		CorruptJournalException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	// --- Parsing / serializing ----------------------------------------------------------------

	/**
	 * Parses either format. A non-empty format-1 file gets a <b>deterministic</b> id derived from its
	 * bytes, so if the migrated file could not be written, the next attempt yields the same id and
	 * chunks that were stamped in between still match. (An empty one gets a random id: nothing can be
	 * stamped before the first rule is saved, and that save writes the new format with the id.)
	 */
	static Contents parse(byte[] bytes) throws CorruptJournalException {
		JsonElement root;
		try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
			reader.setStrictness(Strictness.STRICT);
			root = GSON.getAdapter(JsonElement.class).read(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new CorruptJournalException("trailing data after the JSON document");
			}
		} catch (CorruptJournalException ex) {
			throw ex;
		} catch (Exception ex) { // syntax errors, truncation (EOF), empty file
			throw new CorruptJournalException("not valid JSON: " + ex.getMessage(), ex);
		}

		if (root != null && root.isJsonArray()) {
			List<RawRule> rules = parseRules(root.getAsJsonArray());
			UUID id = rules.isEmpty() ? UUID.randomUUID() : UUID.nameUUIDFromBytes(bytes);
			return new Contents(id, rules.size(), rules, true);
		}
		if (root == null || !root.isJsonObject()) {
			throw new CorruptJournalException("expected a JSON object or array at the top level");
		}

		JsonObject obj = root.getAsJsonObject();
		int format = intField(obj, "format");
		if (format != FORMAT) {
			throw new CorruptJournalException("unsupported journal format " + format
					+ " (this mod version reads format " + FORMAT + "; was the file written by a newer version?)");
		}
		UUID id;
		try {
			id = UUID.fromString(stringField(obj, "journalId"));
		} catch (IllegalArgumentException ex) {
			throw new CorruptJournalException("invalid journalId", ex);
		}
		int legacyRuleCount = obj.has("legacyRuleCount") ? intField(obj, "legacyRuleCount") : 0;
		if (legacyRuleCount < 0) {
			throw new CorruptJournalException("legacyRuleCount must not be negative");
		}
		JsonElement rules = obj.get("rules");
		if (rules == null || !rules.isJsonArray()) {
			throw new CorruptJournalException("missing \"rules\" array");
		}
		return new Contents(id, legacyRuleCount, parseRules(rules.getAsJsonArray()), false);
	}

	private static List<RawRule> parseRules(JsonArray array) {
		List<RawRule> rules = new ArrayList<>(array.size());
		for (JsonElement entry : array) {
			if (entry.isJsonArray() && entry.getAsJsonArray().size() == 2
					&& isString(entry.getAsJsonArray().get(0)) && isString(entry.getAsJsonArray().get(1))) {
				rules.add(RawRule.of(entry.getAsJsonArray().get(0).getAsString(),
						entry.getAsJsonArray().get(1).getAsString()));
			} else {
				// Keep its slot (and its exact content) instead of dropping it: dropping would shift
				// every later index relative to the chunk stamps.
				rules.add(new RawRule(null, null, entry.deepCopy()));
			}
		}
		return rules;
	}

	private static boolean isString(JsonElement e) {
		return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
	}

	private static int intField(JsonObject obj, String name) throws CorruptJournalException {
		JsonElement e = obj.get(name);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new CorruptJournalException("missing or non-numeric \"" + name + "\"");
		}
		try {
			return new BigDecimal(e.getAsString()).intValueExact();
		} catch (ArithmeticException | NumberFormatException ex) {
			throw new CorruptJournalException("\"" + name + "\" is not an integer", ex);
		}
	}

	private static String stringField(JsonObject obj, String name) throws CorruptJournalException {
		JsonElement e = obj.get(name);
		if (e == null || !isString(e)) {
			throw new CorruptJournalException("missing or non-string \"" + name + "\"");
		}
		return e.getAsString();
	}

	/** Always writes the current format. */
	static String serialize(UUID journalId, int legacyRuleCount, List<RawRule> rules) {
		JsonObject obj = new JsonObject();
		obj.add("format", new JsonPrimitive(FORMAT));
		obj.add("journalId", new JsonPrimitive(journalId.toString()));
		obj.add("legacyRuleCount", new JsonPrimitive(legacyRuleCount));
		JsonArray array = new JsonArray(rules.size());
		for (RawRule rule : rules) {
			array.add(rule.toJson());
		}
		obj.add("rules", array);
		return GSON.toJson(obj) + "\n";
	}

	// --- File operations ----------------------------------------------------------------------

	/**
	 * Replaces {@code target} with {@code text} so that, whatever happens, the file on disk is either
	 * the complete old version or the complete new one: write a sibling temp file, flush it to the
	 * device, then rename it over the target in one atomic step.
	 */
	static void writeAtomically(Path target, String text) throws IOException {
		Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
		try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(true); // data must be on disk before the rename makes it visible
		}
		try {
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ex) {
			// Only on exotic file systems. Still better than truncating the target in place.
			LOGGER.warn("Atomic rename is not supported for {}, falling back to a plain replace", target);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		syncDirectory(target.toAbsolutePath().getParent());
	}

	/** Makes the rename itself durable. Possible on Linux/macOS; not on Windows, where it is skipped. */
	private static void syncDirectory(Path dir) {
		if (dir == null) {
			return;
		}
		try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException | UnsupportedOperationException ignored) {
			// Best effort only.
		}
	}

	/** Moves an unreadable journal aside to {@code <name>.corrupt-<utc timestamp>} and returns the new path. */
	static Path moveAsideCorrupt(Path file, Clock clock) throws IOException {
		String base = file.getFileName() + CORRUPT_SUFFIX + TIMESTAMP.format(clock.instant());
		Path backup = file.resolveSibling(base);
		for (int i = 1; Files.exists(backup); i++) {
			backup = file.resolveSibling(base + "-" + i);
		}
		Files.move(file, backup);
		return backup;
	}

	/** Keeps a one-time copy of a format-1 file before it is rewritten in the new format. */
	static Path backupLegacy(Path file) throws IOException {
		Path backup = file.resolveSibling(file.getFileName() + LEGACY_BACKUP_SUFFIX);
		if (!Files.exists(backup)) {
			Files.copy(file, backup);
		}
		return backup;
	}

	// --- Loading with recovery ----------------------------------------------------------------

	enum Status {
		/** No file yet: a brand-new, empty journal (written once the first rule is added). */
		NEW,
		/** Read the current format. */
		LOADED,
		/** Read format 1 and rewrote it in the current format (a backup copy was kept). */
		MIGRATED,
		/** The file was corrupt. It was moved aside and a new, empty journal (new id) was started. */
		RECOVERED_FROM_CORRUPT,
		/**
		 * The file exists but could not be read or moved aside (I/O error). Nothing may be written
		 * over it, so the mod must not record or apply rules this session.
		 */
		UNREADABLE
	}

	/** Result of {@link #load}. {@code backup} is the file made along the way, {@code detail} explains problems. */
	record LoadResult(Status status, Contents contents, Path backup, String detail) {
	}

	/**
	 * Reads the journal, migrating or recovering as needed. Never throws and never loses the file:
	 * a corrupt file is renamed, a legacy file is copied before it is rewritten.
	 */
	static LoadResult load(Path file, Supplier<UUID> newId, Clock clock) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		} catch (NoSuchFileException ex) {
			return new LoadResult(Status.NEW, new Contents(newId.get(), 0, List.of(), false), null, null);
		} catch (IOException ex) {
			return unreadable(newId, "could not read " + file + ": " + ex);
		}

		Contents contents;
		try {
			contents = parse(bytes);
		} catch (CorruptJournalException ex) {
			try {
				Path backup = moveAsideCorrupt(file, clock);
				return new LoadResult(Status.RECOVERED_FROM_CORRUPT,
						new Contents(newId.get(), 0, List.of(), false), backup, ex.getMessage());
			} catch (IOException moveEx) {
				return unreadable(newId, "journal is corrupt (" + ex.getMessage()
						+ ") and could not be moved aside: " + moveEx);
			}
		}

		if (!contents.legacyFormat()) {
			return new LoadResult(Status.LOADED, contents, null, null);
		}

		// Format 1: keep a copy, then rewrite in the current format. If either step fails the
		// rules are still on disk (in the old format), and the id is derived from those exact
		// bytes, so carrying on is safe; the next save will write the new format.
		Path backup = null;
		String detail = null;
		try {
			backup = backupLegacy(file);
			writeAtomically(file, serialize(contents.journalId(), contents.legacyRuleCount(), contents.rules()));
		} catch (IOException ex) {
			detail = "migration could not be written yet: " + ex;
		}
		return new LoadResult(Status.MIGRATED, contents, backup, detail);
	}

	private static LoadResult unreadable(Supplier<UUID> newId, String detail) {
		return new LoadResult(Status.UNREADABLE, new Contents(newId.get(), 0, List.of(), false), null, detail);
	}
}
