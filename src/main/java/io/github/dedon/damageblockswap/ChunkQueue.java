package io.github.dedon.damageblockswap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.ToLongFunction;

/**
 * De-duplicated work queue that hands out the key with the lowest priority value first (in game: the
 * chunk closest to a player), oldest first among equals. No Minecraft classes, unit-tested.
 *
 * <p>Priorities are computed when a key is added and again on {@link #reprioritize()} (when players
 * move to another chunk), not on every poll: polling stays O(log n) however long the queue gets.
 */
final class ChunkQueue<K> {
	private record Entry<K>(K key, long priority, long seq) {
	}

	private final ToLongFunction<K> priority;
	private final Comparator<Entry<K>> order = Comparator.<Entry<K>>comparingLong(Entry::priority)
			.thenComparingLong(Entry::seq);
	// Keys in the queue, each with the sequence number of its live heap entry. Heap entries whose key
	// was removed (or removed and added again) are stale and skipped when they come up.
	private final Map<K, Long> members = new HashMap<>();
	private PriorityQueue<Entry<K>> heap = new PriorityQueue<>(order);
	private long nextSeq;

	ChunkQueue(ToLongFunction<K> priority) {
		this.priority = priority;
	}

	/** Adds the key unless it is already queued. Returns true if it was added. */
	boolean add(K key) {
		if (members.containsKey(key)) {
			return false;
		}
		long seq = nextSeq++;
		members.put(key, seq);
		heap.add(new Entry<>(key, priority.applyAsLong(key), seq));
		return true;
	}

	/** Removes and returns the key with the lowest priority value, or null if the queue is empty. */
	K poll() {
		Entry<K> entry;
		while ((entry = heap.poll()) != null) {
			Long seq = members.get(entry.key());
			if (seq != null && seq == entry.seq()) {
				members.remove(entry.key());
				return entry.key();
			}
		}
		return null;
	}

	boolean remove(K key) {
		if (members.remove(key) == null) {
			return false;
		}
		if (heap.size() > 2 * members.size() + 64) {
			rebuild(); // don't let stale entries pile up
		}
		return true;
	}

	/** Recomputes every key's priority (e.g. after players moved). */
	void reprioritize() {
		rebuild();
	}

	boolean contains(K key) {
		return members.containsKey(key);
	}

	int size() {
		return members.size();
	}

	boolean isEmpty() {
		return members.isEmpty();
	}

	void clear() {
		members.clear();
		heap = new PriorityQueue<>(order);
	}

	private void rebuild() {
		List<Entry<K>> entries = new ArrayList<>(members.size());
		members.forEach((key, seq) -> entries.add(new Entry<>(key, priority.applyAsLong(key), seq)));
		PriorityQueue<Entry<K>> fresh = new PriorityQueue<>(Math.max(1, entries.size()), order);
		fresh.addAll(entries);
		heap = fresh;
	}
}
