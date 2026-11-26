package io.github.dedon.damageblockswap;

import java.util.Map;
import java.util.UUID;

/**
 * Brings one chunk from one rule version to another, a slice per call, so that it can be saved at any
 * point between slices without a rule ever being applied twice to a block. No Minecraft classes here
 * (the chunk is behind {@link ChunkData}), so the whole protocol is unit-tested.
 *
 * <p><b>Cells.</b> A chunk is {@link ChunkData#sectionCount()} sections of {@value #SECTION_SIZE}
 * cells; cell {@code position} is cell {@code position % 4096} of section {@code position / 4096}.
 * A pass goes through the cells in this order once.
 *
 * <p><b>What is saved with the chunk.</b>
 * <ul>
 *   <li>The stamp (journal id + version) only moves when the whole pass is done.</li>
 *   <li>While a pass is unfinished, a {@link SwapProgress} record says which cells are done. It is
 *       written every time {@link #run} returns (also when it throws), so between two slices the chunk
 *       data always matches its blocks, whenever the game saves it.</li>
 *   <li>A cell is counted as done right before it is changed. If placing the block throws half-way
 *       (a mod's neighbour update, say), the block may or may not have changed, and it is never
 *       changed twice: at worst one block keeps its old type.</li>
 * </ul>
 */
final class ChunkJob<T> {
	static final int SECTION_SIZE = 4096;
	// How often (in cells looked at) the budget's clock is checked when nothing is being changed.
	// Reading a cell costs a few ns, so a check every 256 cells is far below the tick budget's precision.
	private static final int CLOCK_CHECK_INTERVAL = 256;

	/** One chunk as {@link ChunkJob} sees it: its cells and the swap data saved with it. */
	interface ChunkData<T> {
		int sectionCount();

		/** False only if the section certainly holds none of the mapping's keys (it is skipped). */
		boolean mayContainAny(int section, Map<T, T> mapping);

		T get(int section, int index);

		/** Puts {@code value} into the cell; false if it was left as is (it couldn't stay there). */
		boolean set(int section, int index, T value);

		/** Moves the chunk's stamp and drops its {@link SwapProgress} record. */
		void writeStamp(UUID journalId, int version);

		void writeProgress(SwapProgress progress);
	}

	/** How much work one tick may still do. Checked after every change and every few hundred cells. */
	interface Budget {
		boolean hasRoom();

		void changed();
	}

	/**
	 * What a chunk needs: nothing, to be left alone (quarantined), or a pass {@code from -> to}
	 * starting at cell {@code position}. For a quarantine, {@code problemId}/{@code problemVersion}
	 * are the stamp (or progress record) that caused it, for the log.
	 */
	record Plan(int from, int to, int position, ChunkStamp.Problem problem, UUID problemId, int problemVersion) {
		static final Plan UP_TO_DATE = new Plan(0, 0, 0, null, null, 0);

		static Plan quarantined(ChunkStamp.Problem problem, UUID id, int version) {
			return new Plan(0, 0, 0, problem, id, version);
		}

		boolean isQuarantined() {
			return problem != null;
		}

		boolean hasWork() {
			return problem == null && from < to;
		}
	}

	private final JournalStore<T> store;
	private final ChunkData<T> chunk;
	private final int from;
	private final int to;
	private final Map<T, T> mapping;
	private final int end;
	private int position;
	// The position the chunk's saved data says; only written again when it changes.
	private int savedPosition;

	private ChunkJob(JournalStore<T> store, ChunkData<T> chunk, Plan plan) {
		this.store = store;
		this.chunk = chunk;
		this.from = plan.from();
		this.to = plan.to();
		this.mapping = store.journal().mapping(from, to);
		this.end = chunk.sectionCount() * SECTION_SIZE;
		this.position = plan.position();
		this.savedPosition = plan.position();
	}

	/**
	 * Decides what a chunk needs, from what is saved with it.
	 *
	 * @param stampId      journal id of the chunk's stamp (null if none)
	 * @param stampVersion version of the chunk's stamp (0 if none)
	 * @param progress     the chunk's unfinished pass, or null
	 */
	static Plan plan(JournalStore<?> store, UUID stampId, int stampVersion, SwapProgress progress) {
		ChunkStamp.Result stamp = store.evaluateStamp(stampId, stampVersion);
		if (stamp.isQuarantined()) {
			return Plan.quarantined(stamp.problem(), stampId, stampVersion);
		}
		int from = stamp.startVersion();
		if (progress != null) {
			// The pass must be finished exactly as it was started: its target is a version the
			// journal on disk still backs, and it starts where the stamp is. Otherwise nobody knows
			// which blocks are at which version, and the chunk is left alone.
			ChunkStamp.Result target = store.evaluateStamp(progress.journalId(), progress.toVersion());
			if (target.isQuarantined()) {
				return Plan.quarantined(target.problem(), progress.journalId(), progress.toVersion());
			}
			if (progress.fromVersion() != from || progress.toVersion() <= from || progress.position() < 0) {
				return Plan.quarantined(ChunkStamp.Problem.PROGRESS_MISMATCH, progress.journalId(),
						progress.toVersion());
			}
			return new Plan(from, progress.toVersion(), progress.position(), null, null, 0);
		}
		int to = store.persistedVersion(); // only rules that are on disk, see JournalStore
		return from < to ? new Plan(from, to, 0, null, null, 0) : Plan.UP_TO_DATE;
	}

	static <T> ChunkJob<T> start(JournalStore<T> store, ChunkData<T> chunk, Plan plan) {
		if (!plan.hasWork()) {
			throw new IllegalArgumentException("nothing to do: " + plan);
		}
		return new ChunkJob<>(store, chunk, plan);
	}

	int from() {
		return from;
	}

	int to() {
		return to;
	}

	int position() {
		return position;
	}

	/**
	 * Works on the chunk until it is done or the budget runs out. Returns true when the chunk is done
	 * (then it is stamped {@link #to()}); otherwise its progress is saved and the next call goes on
	 * from there.
	 */
	boolean run(Budget budget) {
		boolean done = false;
		try {
			done = sweep(budget);
		} finally {
			// Also when the sweep threw: the chunk may be saved (the server saves everything when it
			// crashes), and its data must say which cells were already done.
			if (done) {
				chunk.writeStamp(store.stampId(), to);
			} else if (position != savedPosition) {
				chunk.writeProgress(new SwapProgress(store.stampId(), from, to, position));
				savedPosition = position;
			}
		}
		return done;
	}

	private boolean sweep(Budget budget) {
		if (mapping.isEmpty()) {
			position = Math.max(position, end); // nothing visibly changes in this range
			return true;
		}
		boolean sectionChecked = false;
		while (position < end) {
			int section = position / SECTION_SIZE;
			int index = position % SECTION_SIZE;
			if (!sectionChecked) {
				if (!chunk.mayContainAny(section, mapping)) {
					position = (section + 1) * SECTION_SIZE;
					continue;
				}
				sectionChecked = true;
			}
			if (index % CLOCK_CHECK_INTERVAL == 0 && !budget.hasRoom()) {
				return false;
			}
			T target = mapping.get(chunk.get(section, index));
			position++; // done before it is changed, see the class comment
			if (index == SECTION_SIZE - 1) {
				sectionChecked = false;
			}
			if (target != null && chunk.set(section, index, target)) {
				budget.changed();
				if (!budget.hasRoom()) {
					return position >= end;
				}
			}
		}
		return true;
	}
}
