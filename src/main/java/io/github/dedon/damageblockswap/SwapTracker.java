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
 * <p>Safety rules (see {@code docs/decisions.md}, item 2/5); the logic lives in the pure, unit-tested
 * {@link JournalStore}, {@link SwapJournal}, {@link JournalFile} and {@link ChunkStamp}:
 * <ul>
 *   <li>Chunks are only ever advanced up to {@link #persistedVersion()}, the rules known to be on
 *       disk, and stamped with the id on disk. So a crash can never leave a chunk stamped with a
 *       version the journal file lacks.</li>
 *   <li>The file is written atomically, at the end of the tick in which rules were added ({@link #flush()}).</li>
 *   <li>Rules referring to unknown blocks stay in place as no-op placeholders; indices never shift.</li>
 *   <li>A chunk stamped by a different journal, or beyond what its journal had, is left alone
 *       (quarantined) for good, see {@link ChunkStamp}.</li>
 * </ul>
 *
 * <p>Everything is global and only touched from the server thread.
 */
public final class SwapTracker {
	private static final String FILE_NAME = DamageBlockSwap.MOD_ID + "_swaps.json";
	private static final UUID NO_JOURNAL = new UUID(0L, 0L);

	// The journal and what of it is on disk. Read-only and empty when no world is loaded, or when
	// the journal file could not be read (then nothing may be written over it).
	private static volatile JournalStore<Block> store = JournalStore.none(NO_JOURNAL);
	private static Path journalPath = null;
	// Only log the full stack trace of a failing save once per failure streak.
	private static boolean saveFailing = false;
	// Quarantine warnings already printed this session (one per kind of problem and journal id).
	private static final Set<String> quarantineWarnings = new HashSet<>();

	private SwapTracker() {
	}

	/** Number of rules recorded so far (including any not yet saved). */
	public static int version() {
		return store.journal().version();
	}

	/** Number of rules known to be on disk. Chunks are brought up to this version, never further. */
	public static int persistedVersion() {
		return store.persistedVersion();
	}

	/** Whether new rules may be recorded (a world is loaded and its journal file is usable). */
	public static boolean canRecord() {
		return store.writable();
	}

	/**
	 * Adds a new swap rule (block types a and b are now exchanged everywhere). Call {@link #flush()}
	 * afterwards; chunks only see the rule once it is on disk. Returns false if recording is disabled.
	 */
	public static boolean recordSwap(Block a, Block b) {
		return store.record(JournalFile.RawRule.of(idOf(a), idOf(b)), a, b);
	}

	// --- Chunk stamps and unfinished passes ----------------------------------------------------

	/**
	 * What this chunk needs, from its stamp and its unfinished pass (if any): nothing, a pass up to
	 * {@link #persistedVersion()} (or the rest of the unfinished one), or to be left alone because it
	 * was stamped by another journal / beyond it (quarantine; logged once per kind of problem).
	 */
	public static ChunkJob.Plan planChunk(LevelChunk chunk) {
		int stampVersion = chunk.getAttachedOrElse(DamageBlockSwap.APPLIED_VERSION, 0);
		UUID stampId = chunk.getAttached(DamageBlockSwap.JOURNAL_ID);
		SwapProgress progress = chunk.getAttached(DamageBlockSwap.SWAP_PROGRESS);
		ChunkJob.Plan plan = ChunkJob.plan(store, stampId, stampVersion, progress);
		if (plan.staleProgress()) {
			chunk.removeAttached(DamageBlockSwap.SWAP_PROGRESS); // left over from a finished pass
		}
		// When the journal is unreadable, everything is skipped and that's logged once at load.
		if (plan.isQuarantined() && store.writable()) {
			warnQuarantined(chunk, plan.problem(), plan.problemId(), plan.problemVersion());
		}
		return plan;
	}

	/** Starts (or resumes) the pass {@code plan} describes on {@code chunk}. */
	public static ChunkJob<Block> startJob(ChunkJob.ChunkData<Block> chunk, ChunkJob.Plan plan) {
		return ChunkJob.start(store, chunk, plan);
	}

	/**
	 * Marks a chunk as brought up to {@code version} of journal {@code journalId} and drops its
	 * unfinished-pass record. Only {@link ChunkJob} calls this, with the id on disk and at most
	 * {@link #persistedVersion()}.
	 */
	public static void writeStamp(LevelChunk chunk, UUID journalId, int version) {
		chunk.setAttached(DamageBlockSwap.APPLIED_VERSION, version);
		chunk.setAttached(DamageBlockSwap.JOURNAL_ID, journalId);
		chunk.removeAttached(DamageBlockSwap.SWAP_PROGRESS);
	}

	/** Saves how far an unfinished pass got (with the chunk, so it is resumed after a reload). */
	public static void writeProgress(LevelChunk chunk, SwapProgress progress) {
		chunk.setAttached(DamageBlockSwap.SWAP_PROGRESS, progress);
	}

	private static void warnQuarantined(LevelChunk chunk, ChunkStamp.Problem problem, UUID stampId, int stampVersion) {
		String key = problem + "/" + stampId;
		if (!quarantineWarnings.add(key)) {
			return;
		}
		SwapJournal<Block> journal = store.journal();
		String why = switch (problem) {
			case OTHER_JOURNAL -> "it was swapped under a different journal (" + stampId
					+ "), e.g. the journal file was replaced, deleted or recreated after corruption";
			case AHEAD_OF_JOURNAL -> "it is stamped with rule " + stampVersion + " of journal " + stampId
					+ ", which the journal file never had (rules lost: the file was restored from an older copy "
					+ "than the world, or lost in a crash)";
			case LEGACY_NOT_ADOPTED -> "it was swapped by an older mod version up to rule " + stampVersion
					+ ", but the current journal only inherited " + journal.legacyRuleCount()
					+ " rules from that version (journal recreated, or rules lost in a crash)";
			case PROGRESS_MISMATCH -> "its record of an unfinished swap pass (up to rule " + stampVersion
					+ " of journal " + stampId + ") doesn't start where its stamp is (chunk data edited "
					+ "outside the game?), so which of its blocks are swapped is unknown";
		};
		DamageBlockSwap.LOGGER.warn("Leaving chunk {} untouched: {}. Such chunks are never swapped again by this "
						+ "journal; further ones with the same problem are skipped silently. Current journal: {} "
						+ "({} saved rules). If the right journal file still exists, restore it with the server "
						+ "stopped and before new swaps happen, and these chunks will catch up.",
				chunk.getPos(), why, journal.id(), store.persistedVersion());
	}

	// --- Lookup helpers for /dbs and the screen ------------------------------------------------

	public static Block currentAppearanceOf(Block original) {
		return store.journal().currentAppearanceOf(original);
	}

	public static Block originalOf(Block current) {
		return store.journal().originalOf(current);
	}

	/** A rule of the journal, numbered from 1 as players see it. */
	public record Rule(int number, Block a, Block b) {
	}

	/** The rule {@code /dbs undo} cancels next (see {@link SwapJournal#undoTarget()}), or null if none. */
	public static Rule undoTarget() {
		SwapJournal<Block> journal = store.journal();
		int index = journal.undoTarget();
		if (index < 0) {
			return null;
		}
		SwapJournal.Entry<Block> entry = journal.entries().get(index);
		return new Rule(index + 1, entry.a(), entry.b());
	}

	/** The journal's current id (for {@code /dbs status}). */
	public static UUID journalId() {
		return store.journal().id();
	}

	/** Rules that refer to blocks which don't exist (kept as no-ops). */
	public static int placeholderCount() {
		return store.journal().placeholderCount();
	}

	/** The journal file format this version writes. */
	public static int fileFormat() {
		return JournalFile.FORMAT;
	}

	/** Every changed block type (original -> current), sorted by id; includes rules not saved yet. */
	public static Map<Block, Block> allChanges() {
		return sortedById(store.journal().changes());
	}

	/**
	 * Like {@link #allChanges()}, but only the rules on disk: what chunks are brought up to and what
	 * was announced. This is what clients are sent ({@link SwapMapSync}).
	 */
	public static Map<Block, Block> savedChanges() {
		return sortedById(store.journal().mapping(0, store.persistedVersion()));
	}

	private static Map<Block, Block> sortedById(Map<Block, Block> unordered) {
		Map<Block, Block> changes = new LinkedHashMap<>();
		unordered.entrySet().stream()
				.sorted((x, y) -> idOf(x.getKey()).compareTo(idOf(y.getKey())))
				.forEach(e -> changes.put(e.getKey(), e.getValue()));
		return changes;
	}

	/** Forgets the rule book entirely (once the world is closed). Never writes anything. */
	public static void clear() {
		store = JournalStore.none(NO_JOURNAL);
		journalPath = null;
		saveFailing = false;
		quarantineWarnings.clear();
	}

	// --- Persistence: the ordered rule list, in the world folder -------------------------------

	public static void load(MinecraftServer server) {
		clear();
		Path path = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
		JournalFile.LoadResult result = JournalFile.load(path, UUID::randomUUID, Clock.systemUTC());

		SwapJournal<Block> journal = SwapJournal.fromContents(result.contents(), SwapTracker::blockFromId);
		store = new JournalStore<>(journal, result.status(), path, UUID::randomUUID, JournalFile::writeAtomically);
		journalPath = path;

		switch (result.status()) {
			case NEW -> DamageBlockSwap.LOGGER.info("No swap journal yet; starting journal {}.", journal.id());
			case LOADED -> DamageBlockSwap.LOGGER.info("Loaded swap journal {}: version {}.",
					journal.id(), journal.version());
			case MIGRATED -> {
				if (result.backup() != null) {
					DamageBlockSwap.LOGGER.info("Migrated swap journal from the old format: version {}, journal id {}. "
							+ "A copy of the old file was kept at {}.", journal.version(), journal.id(), result.backup());
				} else {
					DamageBlockSwap.LOGGER.info("Read swap journal in the old format: version {}, journal id {}.",
							journal.version(), journal.id());
				}
				if (result.contents().note() != null) {
					DamageBlockSwap.LOGGER.warn("Swap journal {}: {}.", path, result.contents().note());
				}
				if (result.detail() != null) {
					DamageBlockSwap.LOGGER.warn("Swap journal {}: {}. The old file is still in place; it will be written "
							+ "in the new format on the next save{}.", path, result.detail(),
							result.backup() == null ? " (no backup copy of the old format will exist)" : "");
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
					* the file will not be touched. Fix the problem above and restart.
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
	 * Called at the end of a tick in which rules were added, periodically (as a retry) and when the
	 * server stops.
	 */
	public static boolean flush() {
		if (!store.dirty()) {
			return true;
		}
		if (!store.writable()) {
			return false;
		}
		try {
			store.flush();
			if (saveFailing) {
				DamageBlockSwap.LOGGER.info("Swap journal saved again ({} rules).", store.persistedVersion());
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
		int before = store.persistedVersion();
		return store.dirty() && flush() && store.persistedVersion() > before;
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
