package io.github.dedon.damageblockswap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   "previousJournalIds": {   // ids this journal continues (see SwapJournal#startNewId): chunk
 *     "0a2b...": 12           // stamps with such an id are trusted up to the given version
 *   },                        // (optional, empty if missing)
 *   "rules": [["minecraft:stone", "minecraft:dirt"], ...]
 * }
 * }</pre>
 * Format 1 (mod 1.0.x) was just the bare {@code rules} array. It is migrated on load, reading it as
 * leniently as 1.0.x did (see {@link #parseLegacyText}).
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
	// Exactly how mod 1.0.x read the file: a default Gson, whose fromJson parses leniently.
	private static final Gson LEGACY_GSON = new Gson();
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
	 * format-1 file (chunks stamped by mod 1.0.x counted in those). {@code previousIds}: ids this
	 * journal continues, with the version each is trusted up to. {@code legacyFormat}: read from
	 * format 1 just now. {@code note}: what had to be repaired while reading a format-1 file (null
	 * if nothing), for the log.
	 */
	record Contents(UUID journalId, int legacyRuleCount, Map<UUID, Integer> previousIds, List<RawRule> rules,
	                boolean legacyFormat, String note) {
		Contents {
			previousIds = Collections.unmodifiableMap(new LinkedHashMap<>(previousIds));
			rules = List.copyOf(rules);
		}

		static Contents empty(UUID journalId) {
			return new Contents(journalId, 0, Map.of(), List.of(), false, null);
		}
	}

	/**
	 * The file is a journal of a format this version doesn't know (written by a newer version of the
	 * mod, before a downgrade). It is not corrupt, so it must not be moved aside or replaced.
	 */
	static final class UnsupportedFormatException extends Exception {
		UnsupportedFormatException(String message) {
			super(message);
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
	static Contents parse(byte[] bytes) throws CorruptJournalException, UnsupportedFormatException {
		String text = new String(bytes, StandardCharsets.UTF_8);
		JsonElement root;
		try {
			root = parseStrict(text);
		} catch (CorruptJournalException strictError) {
			// Not valid JSON. It may still be a format-1 file that mod 1.0.x could read (hand-edited),
			// or one that 1.0.x's non-atomic save cut short.
			Contents legacy = parseLegacyText(text, bytes);
			if (legacy == null) {
				throw strictError;
			}
			return legacy;
		}

		if (root != null && root.isJsonArray()) {
			return legacyContents(root.getAsJsonArray(), bytes, null);
		}
		if (root == null || !root.isJsonObject()) {
			throw new CorruptJournalException("expected a JSON object or array at the top level");
		}

		JsonObject obj = root.getAsJsonObject();
		int format = intField(obj, "format");
		if (format > FORMAT) {
			throw new UnsupportedFormatException("the journal has format " + format + ", but this version of the mod"
					+ " only reads format " + FORMAT + " (was it written by a newer version?)");
		}
		if (format != FORMAT) {
			throw new CorruptJournalException("invalid journal format " + format);
		}
		UUID id = uuid(stringField(obj, "journalId"), "journalId");
		int legacyRuleCount = obj.has("legacyRuleCount") ? intField(obj, "legacyRuleCount") : 0;
		if (legacyRuleCount < 0) {
			throw new CorruptJournalException("legacyRuleCount must not be negative");
		}
		Map<UUID, Integer> previousIds = new LinkedHashMap<>();
		JsonElement previous = obj.get("previousJournalIds");
		if (previous != null) {
			if (!previous.isJsonObject()) {
				throw new CorruptJournalException("\"previousJournalIds\" must be an object");
			}
			JsonObject previousObj = previous.getAsJsonObject();
			for (String key : previousObj.keySet()) {
				int trustedUpTo = intField(previousObj, key);
				if (trustedUpTo < 0) {
					throw new CorruptJournalException("previous journal " + key + " has a negative version");
				}
				previousIds.put(uuid(key, "previous journal id"), trustedUpTo);
			}
		}
		JsonElement rules = obj.get("rules");
		if (rules == null || !rules.isJsonArray()) {
			throw new CorruptJournalException("missing \"rules\" array");
		}
		return new Contents(id, legacyRuleCount, previousIds, parseRules(rules.getAsJsonArray()), false, null);
	}

	private static JsonElement parseStrict(String text) throws CorruptJournalException {
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			reader.setStrictness(Strictness.STRICT);
			JsonElement root = GSON.getAdapter(JsonElement.class).read(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new CorruptJournalException("trailing data after the JSON document");
			}
			return root;
		} catch (CorruptJournalException ex) {
			throw ex;
		} catch (Exception ex) { // syntax errors, truncation (EOF), empty file
			throw new CorruptJournalException("not valid JSON: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Second chance for a file that is not strict JSON, for format 1 only (format 2 is only ever
	 * written by this code). Returns null if it isn't a format-1 file this can make sense of.
	 * <ol>
	 *   <li>Mod 1.0.x read the file with {@code Gson.fromJson}, which is lenient (comments, single
	 *       quotes, unquoted strings, extra commas). A hand-edited file 1.0.x accepted is accepted
	 *       the same way, using the very same Gson call.</li>
	 *   <li>1.0.x wrote the file in place, so a crash mid-save could cut it short. The complete
	 *       pairs before the cut are the first rules of the journal, in order, so they are kept;
	 *       {@code legacyRuleCount} is their number, which keeps chunks stamped beyond the cut
	 *       quarantined (their rules are lost).</li>
	 * </ol>
	 */
	private static Contents parseLegacyText(String text, byte[] bytes) {
		JsonElement lenient;
		try {
			lenient = LEGACY_GSON.fromJson(text, JsonElement.class);
		} catch (JsonParseException ex) {
			lenient = null;
		}
		if (lenient != null && lenient.isJsonArray()) {
			return legacyContents(lenient.getAsJsonArray(), bytes,
					"the old-format file is not strict JSON (hand-edited?); it was read leniently, as mod 1.0.x did");
		}

		JsonArray prefix = new JsonArray();
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			reader.setStrictness(Strictness.LENIENT);
			if (reader.peek() != JsonToken.BEGIN_ARRAY) {
				return null;
			}
			reader.beginArray();
			while (true) {
				try {
					if (!reader.hasNext()) {
						return null; // the array is complete, so the problem is elsewhere: not a cut-off file
					}
					prefix.add(GSON.getAdapter(JsonElement.class).read(reader));
				} catch (IOException | RuntimeException ex) {
					if (!endsTooEarly(ex)) {
						return null; // garbled in the middle, not cut off: corrupt
					}
					break; // the element being read was cut off: keep what came before
				}
			}
		} catch (IOException | RuntimeException ex) {
			return null;
		}
		if (prefix.isEmpty()) {
			return null; // nothing to keep: treat it as corrupt
		}
		return legacyContents(prefix, bytes, "the old-format file was cut off (crash while mod 1.0.x was saving it); "
				+ "kept the " + prefix.size() + " complete entries before the cut. Later rules are lost; chunks "
				+ "stamped beyond the kept ones are left untouched");
	}

	/**
	 * Whether a read failed because the text ended in the middle of a value (what a cut-off file
	 * gives), not because of a syntax error. Gson reports the former as an {@link EOFException} or an
	 * "Unterminated ..." {@link MalformedJsonException} (string, escape sequence, array, object). If a
	 * future Gson words this differently, a cut-off file is just treated as corrupt, as before.
	 */
	private static boolean endsTooEarly(Exception ex) {
		return ex instanceof EOFException
				|| (ex instanceof MalformedJsonException && ex.getMessage() != null
				&& ex.getMessage().startsWith("Unterminated"));
	}

	/**
	 * Format 1. Mod 1.0.x skipped {@code null} entries and entries that are not 2 elements long
	 * <b>without counting them</b>, so they are dropped here too: keeping them as placeholders would
	 * shift every later index against the stamps 1.0.x put on chunks. (The original file is kept as
	 * the {@code .v1-backup}.) Anything else keeps its slot, like in format 2.
	 */
	private static Contents legacyContents(JsonArray array, byte[] bytes, String note) {
		JsonArray counted = new JsonArray(array.size());
		int dropped = 0;
		for (JsonElement entry : array) {
			if (entry.isJsonNull() || (entry.isJsonArray() && entry.getAsJsonArray().size() != 2)) {
				dropped++;
			} else {
				counted.add(entry);
			}
		}
		if (dropped > 0) {
			String droppedNote = dropped + " empty or incomplete entries that mod 1.0.x ignored were dropped";
			note = note == null ? droppedNote : note + "; " + droppedNote;
		}
		List<RawRule> rules = parseRules(counted);
		UUID id = rules.isEmpty() ? UUID.randomUUID() : UUID.nameUUIDFromBytes(bytes);
		return new Contents(id, rules.size(), Map.of(), rules, true, note);
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

	private static UUID uuid(String text, String what) throws CorruptJournalException {
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException ex) {
			throw new CorruptJournalException("invalid " + what + " \"" + text + "\"", ex);
		}
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
		return serialize(journalId, legacyRuleCount, Map.of(), rules);
	}

	/** Always writes the current format. */
	static String serialize(UUID journalId, int legacyRuleCount, Map<UUID, Integer> previousIds, List<RawRule> rules) {
		JsonObject obj = new JsonObject();
		obj.add("format", new JsonPrimitive(FORMAT));
		obj.add("journalId", new JsonPrimitive(journalId.toString()));
		obj.add("legacyRuleCount", new JsonPrimitive(legacyRuleCount));
		JsonObject previous = new JsonObject();
		previousIds.forEach((id, trustedUpTo) -> previous.add(id.toString(), new JsonPrimitive(trustedUpTo)));
		obj.add("previousJournalIds", previous);
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
		writeAtomically(target, text.getBytes(StandardCharsets.UTF_8));
	}

	/** Same as {@link #writeAtomically(Path, String)}, for raw bytes. */
	static void writeAtomically(Path target, byte[] bytes) throws IOException {
		Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
		try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
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

	/**
	 * Keeps a one-time copy of a format-1 file (its exact {@code bytes}) before it is rewritten in the
	 * new format. Written atomically: a crash can't leave a truncated backup that would then never be
	 * replaced.
	 */
	static Path backupLegacy(Path file, byte[] bytes) throws IOException {
		Path backup = file.resolveSibling(file.getFileName() + LEGACY_BACKUP_SUFFIX);
		if (!Files.exists(backup)) {
			writeAtomically(backup, bytes);
		}
		return backup;
	}

	// --- Loading with recovery ----------------------------------------------------------------

	enum Status {
		/** No file yet: a brand-new, empty journal (written once the first rule is added). */
		NEW,
		/** Read the current format. */
		LOADED,
		/**
		 * Read format 1 and rewrote it in the current format (a backup copy was kept). If either
		 * step failed, {@code detail} says so; the old file is then still in place.
		 */
		MIGRATED,
		/** The file was corrupt. It was moved aside and a new, empty journal (new id) was started. */
		RECOVERED_FROM_CORRUPT,
		/**
		 * The file exists but could not be read or moved aside (I/O error), or it was written by a
		 * newer version of the mod. Nothing may be written over it, so the mod must not record or
		 * apply rules this session.
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
			return new LoadResult(Status.NEW, Contents.empty(newId.get()), null, null);
		} catch (IOException ex) {
			return unreadable(newId, "could not read " + file + ": " + ex);
		}

		Contents contents;
		try {
			contents = parse(bytes);
		} catch (UnsupportedFormatException ex) {
			return unreadable(newId, ex.getMessage() + ". Update the mod, or restore the "
					+ LEGACY_BACKUP_SUFFIX + " / an older copy of the file to keep using this version");
		} catch (CorruptJournalException ex) {
			try {
				Path backup = moveAsideCorrupt(file, clock);
				return new LoadResult(Status.RECOVERED_FROM_CORRUPT, Contents.empty(newId.get()), backup, ex.getMessage());
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
			backup = backupLegacy(file, bytes);
			writeAtomically(file, serialize(contents.journalId(), contents.legacyRuleCount(),
					contents.previousIds(), contents.rules()));
		} catch (IOException ex) {
			detail = (backup == null ? "the backup copy could not be made, so the old file was not rewritten: "
					: "migration could not be written yet: ") + ex;
		}
		return new LoadResult(Status.MIGRATED, contents, backup, detail);
	}

	private static LoadResult unreadable(Supplier<UUID> newId, String detail) {
		return new LoadResult(Status.UNREADABLE, Contents.empty(newId.get()), null, detail);
	}
}
