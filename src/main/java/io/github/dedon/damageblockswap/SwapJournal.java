package io.github.dedon.damageblockswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

	private final UUID id;
	private final int legacyRuleCount;
	private final List<Entry<T>> entries = new ArrayList<>();

	// The composed permutation over all rules, kept up to date in O(1) per rule (for lookups).
	private final Map<T, T> originalToCurrent = new HashMap<>();
	private final Map<T, T> currentToOriginal = new HashMap<>();

	// compose(0, n) for the last n asked for. Rules are append-only, so a prefix never changes and
	// the cache only has to be rebuilt when n changes.
	private Map<T, T> prefixCache;
	private int prefixCacheTo = -1;

	SwapJournal(UUID id, int legacyRuleCount) {
		this.id = id;
		this.legacyRuleCount = legacyRuleCount;
	}

	/** Builds a journal from file contents; {@code resolver} returns null for unknown ids. */
	static <T> SwapJournal<T> fromContents(JournalFile.Contents contents, Function<String, T> resolver) {
		SwapJournal<T> journal = new SwapJournal<>(contents.journalId(), contents.legacyRuleCount());
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
