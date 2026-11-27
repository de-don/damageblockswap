package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cap on announcements after the journal couldn't be written for a while. */
class AnnouncementBacklogTest {
	@Test
	void underTheCapEverythingIsKept() {
		AnnouncementBacklog<String> backlog = new AnnouncementBacklog<>(20);
		backlog.add("steve", 1);
		backlog.add("alex", 3);

		AnnouncementBacklog.Batch<String> batch = backlog.drain();
		assertEquals(List.of("steve", "alex"), batch.hits());
		assertEquals(0, batch.omittedRules());
		assertEquals(0, batch.omittedHits());
		assertTrue(backlog.isEmpty(), "drain must empty the backlog");
	}

	@Test
	void aLongFailureKeepsOnlyACappedHeadAndCountsTheRest() {
		AnnouncementBacklog<Integer> backlog = new AnnouncementBacklog<>(20);
		for (int i = 0; i < 10_000; i++) {
			backlog.add(i, 2);
		}

		AnnouncementBacklog.Batch<Integer> batch = backlog.drain();
		assertEquals(10, batch.hits().size(), "kept hits are not bounded");
		assertEquals(9_990, batch.omittedHits());
		assertEquals(19_980, batch.omittedRules());
		assertEquals(20_000, 2 * batch.hits().size() + batch.omittedRules(), "rules lost from the count");
	}

	@Test
	void aSingleBigHitIsKeptWholeAndTheCallerCutsItsLines() {
		AnnouncementBacklog<String> backlog = new AnnouncementBacklog<>(20);
		backlog.add("steve", 100); // swapsPerHit = 100
		backlog.add("alex", 1);

		AnnouncementBacklog.Batch<String> batch = backlog.drain();
		assertEquals(List.of("steve"), batch.hits());
		assertEquals(1, batch.omittedRules());
		assertEquals(20, backlog.maxLines());
	}

	@Test
	void addSaysWhetherTheHitWillBeListed() {
		AnnouncementBacklog<String> backlog = new AnnouncementBacklog<>(2);
		assertTrue(backlog.add("steve", 1));
		assertTrue(backlog.add("alex", 5)); // still under the cap when added: kept whole
		assertFalse(backlog.add("sam", 1), "a hit past the cap must be reported as not listed");
	}

	@Test
	void unlistedCountsTheBigHitsTailPlusOmittedHits() {
		AnnouncementBacklog<String> backlog = new AnnouncementBacklog<>(20);
		backlog.add("steve", 1);
		backlog.add("alex", 30); // kept whole: 31 kept rules, only 20 get a line
		backlog.add("sam", 2);   // omitted
		backlog.add("kim", 3);   // omitted

		AnnouncementBacklog.Batch<String> batch = backlog.drain();
		assertEquals(31, batch.keptRules());
		assertEquals(11 + 5, batch.unlisted(20));
		assertEquals(36, 20 + batch.unlisted(20), "every rule is either a line or in the count");
	}

	@Test
	void unlistedIsZeroWhenEverythingFits() {
		AnnouncementBacklog<String> backlog = new AnnouncementBacklog<>(20);
		backlog.add("steve", 20);
		assertEquals(0, backlog.drain().unlisted(20));
	}

	@Test
	void clearForgetsEverything() {
		AnnouncementBacklog<String> backlog = new AnnouncementBacklog<>(1);
		backlog.add("a", 1);
		backlog.add("b", 1);
		backlog.clear();
		assertTrue(backlog.isEmpty());
		assertEquals(0, backlog.drain().omittedRules());
	}

	@Test
	void capMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> new AnnouncementBacklog<String>(0));
	}
}
