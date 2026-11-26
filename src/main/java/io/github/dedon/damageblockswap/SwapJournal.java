package io.github.dedon.damageblockswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The ordered, append-only list of swap rules and the permutation they add up to. Generic over the
 * element type ({@code Block} in game, {@code String} in tests) so it has no Minecraft dependency.
 *
 * <p>Rule {@code i} is the transposition {@code a <-> b}; the journal <b>version</b> is the number of
 * rules. A rule whose element could not be resolved (a block from a removed mod, a malformed entry)
 * is a <b>placeholder</b>: it is a no-op, but it keeps its index and its original text, so the
 * versions chunks are stamped with stay valid and the text is written back unchanged.
 */
final class SwapJournal<T> {
	/** One rule: what is stored on disk, plus the resolved elements (null = placeholder). */
	record Entry<T>(JournalFile.RawRule raw, T a, T b) {
		boolean isPlaceholder() {
			return a == null || b == null;
		}
	}

	private UUID id;
	private final int legacyRuleCount;
	// Ids this journal continues (oldest first), each trusted up to the rule count it had then.
	private final Map<UUID, Integer> previousIds = new LinkedHashMap<>();
	private final List<Entry<T>> entries = new ArrayList<>();

	// The composed permutation over all rules, kept up to date in O(1) per rule (for lookups).
	private final Map<T, T> originalToCurrent = new HashMap<>();
	private final Map<T, T> currentToOriginal = new HashMap<>();

	// compose(0, n) for the last n asked for. Rules are append-only, so a prefix never changes and
	// the cache only has to be rebuilt when n changes.
	private Map<T, T> prefixCache;
	private int prefixCacheTo = -1;

	SwapJournal(UUID id, int legacyRuleCount) {
		this(id, legacyRuleCount, Map.of());
	}

	SwapJournal(UUID id, int legacyRuleCount, Map<UUID, Integer> previousIds) {
		this.id = id;
		this.legacyRuleCount = legacyRuleCount;
		this.previousIds.putAll(previousIds);
	}

	/** Builds a journal from file contents; {@code resolver} returns null for unknown ids. */
	static <T> SwapJournal<T> fromContents(JournalFile.Contents contents, Function<String, T> resolver) {
		SwapJournal<T> journal = new SwapJournal<>(contents.journalId(), contents.legacyRuleCount(),
				contents.previousIds());
		for (JournalFile.RawRule raw : contents.rules()) {
			T a = raw.wellFormed() ? resolver.apply(raw.a()) : null;
			T b = raw.wellFormed() ? resolver.apply(raw.b()) : null;
			journal.append(raw, a, b);
		}
		return journal;
	}

	/** The rules exactly as they should be written to disk (placeholders keep their original text). */
	List<JournalFile.RawRule> rawRules() {
		List<JournalFile.RawRule> raw = new ArrayList<>(entries.size());
		for (Entry<T> e : entries) {
			raw.add(e.raw());
		}
		return raw;
	}

	UUID id() {
		return id;
	}

	/**
	 * Continues this journal under {@code newId}. The old id stays trusted for chunk stamps, but only
	 * up to the current rule count: rules added from now on are only known under the new id.
	 *
	 * <p>Why: if the journal file is ever restored from an older copy, chunks in the world can carry
	 * stamps of the old id beyond what the restored file has. Without a new id, the next rules added
	 * would bring the count back up to those stamps and the chunks would be silently accepted with a
	 * history they don't have. With it, such stamps stay quarantined for good.
	 */
	void startNewId(UUID newId) {
		if (newId.equals(id)) {
			throw new IllegalArgumentException("new journal id must differ from the current one");
		}
		if (version() > 0) { // a stamp at version 0 is "untouched" anyway, no need to remember it
			previousIds.merge(id, version(), Math::max);
		}
		id = newId;
	}

	/** Ids this journal continues, oldest first, each with the rule count it is trusted up to. */
	Map<UUID, Integer> previousIds() {
		return Collections.unmodifiableMap(previousIds);
	}

	/** What a chunk stamp means for this journal, given how many of its rules are on disk. */
	ChunkStamp.Result evaluateStamp(UUID stampId, int stampVersion, int persistedVersion) {
		return ChunkStamp.evaluate(stampId, stampVersion, id, persistedVersion, legacyRuleCount, previousIds);
	}

	/**
	 * How many leading rules were inherited from a format-1 file (mod 1.0.x). Chunk stamps without
	 * a journal id counted in exactly those rules, so they are trusted up to this version.
	 */
	int legacyRuleCount() {
		return legacyRuleCount;
	}

	int version() {
		return entries.size();
	}

	List<Entry<T>> entries() {
		return Collections.unmodifiableList(entries);
	}

	int placeholderCount() {
		int count = 0;
		for (Entry<T> e : entries) {
			if (e.isPlaceholder()) {
				count++;
			}
		}
		return count;
	}

	/** Appends a rule. Pass null for {@code a} or {@code b} to record a placeholder. */
	void append(JournalFile.RawRule raw, T a, T b) {
		Entry<T> entry = new Entry<>(raw, a, b);
		entries.add(entry);
		if (entry.isPlaceholder()) {
			return;
		}
		T originalOfA = currentToOriginal.getOrDefault(a, a);
		T originalOfB = currentToOriginal.getOrDefault(b, b);
		originalToCurrent.put(originalOfA, b);
		originalToCurrent.put(originalOfB, a);
		currentToOriginal.put(b, originalOfA);
		currentToOriginal.put(a, originalOfB);
	}

	/**
	 * "Element as of version {@code from}" -> "element as of version {@code to}" for the rules in
	 * {@code [from, to)}, identity entries dropped. The range is clamped to the journal.
	 */
	Map<T, T> mapping(int fromVersion, int toVersion) {
		int from = Math.max(0, fromVersion);
		int to = Math.min(entries.size(), toVersion);
		if (from == 0) {
			if (prefixCacheTo != to) {
				prefixCache = Collections.unmodifiableMap(compose(entries, 0, to));
				prefixCacheTo = to;
			}
			return prefixCache;
		}
		return compose(entries, from, to);
	}

	/**
	 * Composes the transpositions {@code entries[from, to)} (placeholders are no-ops) in one pass,
	 * with a forward/inverse pair of maps so each rule is O(1).
	 */
	static <T> Map<T, T> compose(List<Entry<T>> entries, int from, int to) {
		Map<T, T> forward = new HashMap<>(); // element-as-of-`from` -> element-as-of-`to`
		Map<T, T> inverse = new HashMap<>(); // and back again
		for (int i = from; i < to; i++) {
			Entry<T> e = entries.get(i);
			if (e.isPlaceholder()) {
				continue;
			}
			T preA = inverse.getOrDefault(e.a(), e.a());
			T preB = inverse.getOrDefault(e.b(), e.b());
			forward.put(preA, e.b());
			forward.put(preB, e.a());
			inverse.put(e.b(), preA);
			inverse.put(e.a(), preB);
		}
		forward.entrySet().removeIf(en -> en.getKey().equals(en.getValue()));
		return forward;
	}

	T currentAppearanceOf(T original) {
		return originalToCurrent.getOrDefault(original, original);
	}

	T originalOf(T current) {
		return currentToOriginal.getOrDefault(current, current);
	}

	/** Every original element that now looks different, with what it looks like (unordered). */
	Map<T, T> changes() {
		Map<T, T> changes = new HashMap<>();
		originalToCurrent.forEach((k, v) -> {
			if (!k.equals(v)) {
				changes.put(k, v);
			}
		});
		return changes;
	}
}
