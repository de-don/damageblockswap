package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Closest-first, de-duplicated, re-sortable work queue. */
class ChunkQueueTest {
	private final Map<String, Long> priorities = new HashMap<>();
	private final ChunkQueue<String> queue = new ChunkQueue<>(key -> priorities.getOrDefault(key, Long.MAX_VALUE));

	private List<String> drain() {
		List<String> out = new ArrayList<>();
		String key;
		while ((key = queue.poll()) != null) {
			out.add(key);
		}
		return out;
	}

	@Test
	void lowestPriorityFirstAndOldestFirstAmongEquals() {
		priorities.putAll(Map.of("far", 9L, "near", 1L, "mid-a", 4L, "mid-b", 4L));
		queue.add("far");
		queue.add("mid-a");
		queue.add("near");
		queue.add("mid-b");
		queue.add("nobody"); // no player in its dimension: last
		assertEquals(List.of("near", "mid-a", "mid-b", "far", "nobody"), drain());
		assertTrue(queue.isEmpty());
	}

	@Test
	void addingTwiceKeepsOneEntry() {
		priorities.put("a", 1L);
		assertTrue(queue.add("a"));
		assertFalse(queue.add("a"));
		assertEquals(1, queue.size());
		assertEquals(List.of("a"), drain());
		assertNull(queue.poll());
	}

	@Test
	void reprioritizeFollowsMovedPlayers() {
		priorities.putAll(Map.of("a", 1L, "b", 5L));
		queue.add("a");
		queue.add("b");
		// The player flew over to b.
		priorities.putAll(Map.of("a", 5L, "b", 0L));
		queue.reprioritize();
		assertEquals(List.of("b", "a"), drain());
	}

	@Test
	void removedKeysAreNotHandedOutAndCanBeAddedAgain() {
		priorities.putAll(Map.of("a", 1L, "b", 2L, "c", 3L));
		queue.add("a");
		queue.add("b");
		queue.add("c");
		assertTrue(queue.remove("a"));
		assertFalse(queue.remove("a"));
		assertFalse(queue.contains("a"));
		assertEquals(2, queue.size());
		queue.add("a"); // re-added: a fresh entry, the stale one is skipped
		assertEquals(List.of("a", "b", "c"), drain());
	}

	@Test
	void manyRemovalsDoNotLeaveStaleEntriesBehind() {
		for (int i = 0; i < 1000; i++) {
			priorities.put("k" + i, (long) i);
			queue.add("k" + i);
		}
		for (int i = 0; i < 1000; i += 2) {
			queue.remove("k" + i);
		}
		List<String> order = drain();
		assertEquals(500, order.size());
		for (int i = 0; i < 500; i++) {
			assertEquals("k" + (2 * i + 1), order.get(i));
		}
	}

	@Test
	void clearEmptiesTheQueue() {
		queue.add("a");
		queue.add("b");
		queue.clear();
		assertTrue(queue.isEmpty());
		assertNull(queue.poll());
		assertTrue(queue.add("a"));
	}
}
