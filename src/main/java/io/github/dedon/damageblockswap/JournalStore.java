package io.github.dedon.damageblockswap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A {@link SwapJournal} together with what is known to be on its file. No Minecraft classes here,
 * so all of it is unit-tested; {@link SwapTracker} is the thin in-game wrapper (logging, blocks).
 *
 * <p>Invariants:
 * <ul>
 *   <li>Chunks are stamped with {@link #stampId()} and at most {@link #persistedVersion()}: the id
 *       and rule count of the file on disk. A crash or a failed write can never leave a chunk with a
 *       stamp the file doesn't back.</li>
 *   <li>The first rule added in a session moves the journal to a fresh id ({@link SwapJournal#startNewId});
 *       the id that was loaded stays trusted up to the rules it had. So if the file was restored from
 *       an older copy, chunks stamped beyond it stay quarantined instead of being caught up with
 *       other rules once the count is back up.</li>
 * </ul>
 */
final class JournalStore<T> {
	/** Writes the journal text to its file (atomically, in game). */
	@FunctionalInterface
	interface Writer {
		void write(Path target, String text) throws IOException;
	}

	private final SwapJournal<T> journal;
	private final Path path;
	private final Supplier<UUID> newId;
	private final Writer writer;
	private final boolean writable;
	// True once rules added this session have their own id: the journal's id was created this session
	// (new or recreated journal), or it was already rotated away from the loaded one.
	private boolean ownId;
	private int persistedVersion;
	private UUID persistedId;

	/**
	 * @param journal what was loaded from {@code path}
	 * @param status  how it was loaded; {@link JournalFile.Status#UNREADABLE} (or a null path) makes
	 *                the store read-only and its rules never applied
	 */
	JournalStore(SwapJournal<T> journal, JournalFile.Status status, Path path, Supplier<UUID> newId, Writer writer) {
		this.journal = journal;
		this.path = path;
		this.newId = newId;
		this.writer = writer;
		this.writable = status != JournalFile.Status.UNREADABLE && path != null;
		// NEW / RECOVERED_FROM_CORRUPT: the id is brand new, no chunk can carry it yet.
		this.ownId = status == JournalFile.Status.NEW || status == JournalFile.Status.RECOVERED_FROM_CORRUPT;
		this.persistedVersion = writable ? journal.version() : 0;
		this.persistedId = journal.id();
	}

	/** A store for "no world loaded": empty, never written. */
	static <T> JournalStore<T> none(UUID id) {
		return new JournalStore<>(new SwapJournal<>(id, 0), JournalFile.Status.UNREADABLE, null, () -> id,
				(target, text) -> {
				});
	}

	SwapJournal<T> journal() {
		return journal;
	}

	boolean writable() {
		return writable;
	}

	/** Rules known to be on disk. Chunks are brought up to this version, never further. */
	int persistedVersion() {
		return persistedVersion;
	}

	/** The journal id on disk: the one chunks are stamped with. */
	UUID stampId() {
		return persistedId;
	}

	/** Whether something added in memory is not on disk yet. */
	boolean dirty() {
		return journal.version() != persistedVersion || !journal.id().equals(persistedId);
	}

	/** Adds a rule (pass null for a placeholder). Returns false if the store is read-only. */
	boolean record(JournalFile.RawRule raw, T a, T b) {
		if (!writable) {
			return false;
		}
		if (!ownId) {
			UUID id = newId.get();
			while (id.equals(journal.id())) {
				id = newId.get();
			}
			journal.startNewId(id);
			ownId = true;
		}
		journal.append(raw, a, b);
		return true;
	}

	/**
	 * Writes the journal if it is {@link #dirty()}. On success the persisted id and version move up;
	 * on failure (exception) nothing changes: the rules stay in memory, unapplied, until a retry works.
	 */
	void flush() throws IOException {
		if (!writable || !dirty()) {
			return;
		}
		UUID id = journal.id();
		int version = journal.version();
		writer.write(path, JournalFile.serialize(id, journal.legacyRuleCount(), journal.previousIds(),
				journal.rawRules()));
		persistedId = id;
		persistedVersion = version;
	}

	/** What a chunk stamp means against this journal and what is on disk. */
	ChunkStamp.Result evaluateStamp(UUID stampId, int stampVersion) {
		return journal.evaluateStamp(stampId, stampVersion, persistedVersion);
	}
}
