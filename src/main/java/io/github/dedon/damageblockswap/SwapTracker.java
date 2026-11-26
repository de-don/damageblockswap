package io.github.dedon.damageblockswap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "rule book" of all swaps that have happened, in order, for the currently running world.
 *
 * <p>Each swap is one rule (block A &lt;-&gt; block B). The list order matters, and its length is
 * the current <b>version</b> of the world's swap state. Every chunk is stamped with the journal's
 * id and the version it has been brought up to; when it is behind, just the missing rules are
 * applied to it.
 *
 * <p>Safety rules (see {@code docs/decisions.md}, item 2/5):
 * <ul>
 *   <li>Chunks are only ever advanced up to {@link #persistedVersion()}, the rules known to be on
 *       disk. So a crash can never leave a chunk stamped with a version the journal file lacks.</li>
 *   <li>The file is written atomically, right after rules are added ({@link #flush()}).</li>
 *   <li>Rules referring to unknown blocks stay in place as no-op placeholders; indices never shift.</li>
 *   <li>A chunk stamped by a different journal is left alone (quarantined), see {@link ChunkStamp}.</li>
 * </ul>
 *
 * <p>Everything is global and only touched from the server thread.
 */
public final class SwapTracker {
	private static final String FILE_NAME = DamageBlockSwap.MOD_ID + "_swaps.json";
	private static final UUID NO_JOURNAL = new UUID(0L, 0L);

	private static SwapJournal<Block> journal = new SwapJournal<>(NO_JOURNAL, 0);
	// Where the journal lives; null when no world is loaded.
	private static Path journalPath = null;
	// How many rules are safely on disk. Chunks are never advanced beyond this.
	private static int persistedVersion = 0;
	// False when no world is loaded, or the journal file could not be read (then nothing may be
	// written over it and no rules are recorded).
	private static boolean writable = false;
	// Only log the full stack trace of a failing save once per failure streak.
	private static boolean saveFailing = false;
	// Quarantine warnings already printed this session (one per kind of problem and journal id).
	private static final Set<String> quarantineWarnings = new HashSet<>();

	private SwapTracker() {
	}

	/** Number of rules recorded so far (including any not yet saved). */
	public static int version() {
		return journal.version();
	}

	/** Number of rules known to be on disk. Chunks are brought up to this version, never further. */
	public static int persistedVersion() {
		return persistedVersion;
	}

	/** Whether new rules may be recorded (a world is loaded and its journal file is usable). */
	public static boolean canRecord() {
		return writable;
	}

	/**
	 * Adds a new swap rule (block types a and b are now exchanged everywhere). Call {@link #flush()}
	 * afterwards; chunks only see the rule once it is on disk. Returns false if recording is disabled.
	 */
	public static boolean recordSwap(Block a, Block b) {
		if (!writable) {
			return false;
		}
		journal.append(JournalFile.RawRule.of(idOf(a), idOf(b)), a, b);
		return true;
	}

	/**
	 * "Block as of version {@code fromVersion}" -> "block as of {@code toVersion}", so a chunk that
	 * is behind can catch up in one pass. Unknown-block placeholders are no-ops.
	 */
	public static Map<Block, Block> mappingBetween(int fromVersion, int toVersion) {
		return journal.mapping(fromVersion, toVersion);
	}

	// --- Chunk stamps --------------------------------------------------------------------------

	/**
	 * The version this chunk is at relative to the current journal, or {@link ChunkStamp#QUARANTINED}
	 * if it was stamped by another journal (or is ahead of it) and must not be touched.
	 */
	public static int chunkStartVersion(LevelChunk chunk) {
		int stampVersion = chunk.getAttachedOrElse(DamageBlockSwap.APPLIED_VERSION, 0);
		UUID stampId = chunk.getAttached(DamageBlockSwap.JOURNAL_ID);
		ChunkStamp.Problem problem = ChunkStamp.problem(stampId, stampVersion, journal.id(), persistedVersion,
				journal.legacyRuleCount());
		if (problem == null) {
			return Math.max(0, stampVersion);
		}
		if (writable) { // when the journal is unreadable, everything is skipped and that's logged once at load
			warnQuarantined(chunk, problem, stampId, stampVersion);
		}
		return ChunkStamp.QUARANTINED;
	}

	/** Marks a chunk as brought up to {@code version} of the current journal. */
	public static void stampChunk(LevelChunk chunk, int version) {
		chunk.setAttached(DamageBlockSwap.APPLIED_VERSION, version);
		chunk.setAttached(DamageBlockSwap.JOURNAL_ID, journal.id());
	}

	private static void warnQuarantined(LevelChunk chunk, ChunkStamp.Problem problem, UUID stampId, int stampVersion) {
		String key = problem + "/" + stampId;
		if (!quarantineWarnings.add(key)) {
			return;
		}
		String why = switch (problem) {
			case OTHER_JOURNAL -> "it was swapped under a different journal (" + stampId
					+ "), e.g. the journal file was replaced or recreated after corruption";
			case AHEAD_OF_JOURNAL -> "it is stamped with version " + stampVersion
					+ ", but the journal only has " + persistedVersion + " saved rules (rules were lost)";
			case LEGACY_NOT_ADOPTED -> "it was swapped by an older mod version up to rule " + stampVersion
					+ ", but the current journal only inherited " + journal.legacyRuleCount()
					+ " rules from that version (journal recreated, or rules lost in a crash)";
		};
		DamageBlockSwap.LOGGER.warn("Leaving chunk {} untouched: {}. Further chunks with the same problem are "
						+ "skipped silently. Current journal: {} ({} rules). If you restore the matching journal file, "
						+ "these chunks will catch up again.",
				chunk.getPos(), why, journal.id(), persistedVersion);
	}

	// --- Lookup helpers for /dbs and the screen ------------------------------------------------

	public static Block currentAppearanceOf(Block original) {
		return journal.currentAppearanceOf(original);
	}

	public static Block originalOf(Block current) {
		return journal.originalOf(current);
	}

	public static Map<Block, Block> allChanges() {
		Map<Block, Block> changes = new LinkedHashMap<>();
		journal.changes().entrySet().stream()
				.sorted((x, y) -> idOf(x.getKey()).compareTo(idOf(y.getKey())))
				.forEach(e -> changes.put(e.getKey(), e.getValue()));
		return changes;
	}

	/** Forgets the rule book entirely (once the world is closed). Never writes anything. */
	public static void clear() {
		journal = new SwapJournal<>(NO_JOURNAL, 0);
		journalPath = null;
		persistedVersion = 0;
		writable = false;
		saveFailing = false;
		quarantineWarnings.clear();
	}

	// --- Persistence: the ordered rule list, in the world folder -------------------------------

	public static void load(MinecraftServer server) {
		clear();
		Path path = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
		JournalFile.LoadResult result = JournalFile.load(path, UUID::randomUUID, Clock.systemUTC());

		journal = SwapJournal.fromContents(result.contents(), SwapTracker::blockFromId);
		journalPath = path;
		writable = result.status() != JournalFile.Status.UNREADABLE;
		persistedVersion = writable ? journal.version() : 0;

		switch (result.status()) {
			case NEW -> DamageBlockSwap.LOGGER.info("No swap journal yet; starting journal {}.", journal.id());
			case LOADED -> DamageBlockSwap.LOGGER.info("Loaded swap journal {}: version {}.",
					journal.id(), journal.version());
			case MIGRATED -> {
				DamageBlockSwap.LOGGER.info("Migrated swap journal from the old format: version {}, journal id {}. "
						+ "A copy of the old file was kept at {}.", journal.version(), journal.id(), result.backup());
				if (result.detail() != null) {
					DamageBlockSwap.LOGGER.warn("Swap journal {}: {}. Will retry on the next save.", path, result.detail());
				}
			}
			case RECOVERED_FROM_CORRUPT -> DamageBlockSwap.LOGGER.error("""

					******************************************************************************
					* Damage Block Swap: the swap journal was CORRUPT and could not be read.
					*   File:    {}
					*   Problem: {}
					* It was moved to {}
					* A NEW, EMPTY journal ({}) was started.
					* Chunks swapped under the old journal are left untouched (never re-swapped).
					* To recover the old rules: stop the server, repair the moved file and rename
					* it back to {}.
					******************************************************************************""",
					path, result.detail(), result.backup(), journal.id(), FILE_NAME);
			case UNREADABLE -> DamageBlockSwap.LOGGER.error("""

					******************************************************************************
					* Damage Block Swap: the swap journal could not be read:
					*   {}
					* The mod is DISABLED for this session: no new swaps, no chunk updates, and
					* the file will not be touched. Fix the problem (permissions, disk) and restart.
					******************************************************************************""",
					result.detail());
		}

		int placeholders = journal.placeholderCount();
		if (placeholders > 0) {
			DamageBlockSwap.LOGGER.warn("{} of {} swap rules refer to blocks that don't exist (removed mod?). "
							+ "They are kept as no-op placeholders so rule numbering doesn't shift, "
							+ "and will be saved back unchanged.", placeholders, journal.version());
		}
	}

	/**
	 * Writes the journal if it has unsaved rules. Returns true if everything is on disk afterwards.
	 * Called right after rules are added, periodically (as a retry) and when the server stops.
	 */
	public static boolean flush() {
		if (journal.version() == persistedVersion) {
			return true;
		}
		if (!writable || journalPath == null) {
			return false;
		}
		try {
			JournalFile.writeAtomically(journalPath,
					JournalFile.serialize(journal.id(), journal.legacyRuleCount(), journal.rawRules()));
			persistedVersion = journal.version();
			if (saveFailing) {
				DamageBlockSwap.LOGGER.info("Swap journal saved again ({} rules).", persistedVersion);
			}
			saveFailing = false;
			return true;
		} catch (IOException | RuntimeException ex) {
			if (!saveFailing) {
				DamageBlockSwap.LOGGER.error("Failed to save the swap journal to {}. New rules are NOT applied to the "
						+ "world until they are saved; retrying periodically.", journalPath, ex);
			} else {
				DamageBlockSwap.LOGGER.warn("Swap journal still can't be saved: {}", ex.toString());
			}
			saveFailing = true;
			return false;
		}
	}

	/**
	 * Periodic retry of {@link #flush()}. Returns true only if this call saved rules that weren't
	 * on disk before (so chunks can now catch up to them).
	 */
	public static boolean saveIfDirty() {
		int before = persistedVersion;
		return journal.version() != before && flush() && persistedVersion > before;
	}

	private static String idOf(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	private static Block blockFromId(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return null;
		}
		return BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
	}
}
