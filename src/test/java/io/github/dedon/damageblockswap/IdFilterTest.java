package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdFilterTest {
	@Test
	void tagsAreNormalizedLikeIds() {
		List<String> result = IdFilter.normalize(Arrays.asList(
				"#Logs", " # minecraft:LOGS ", "#c:storage_blocks", "#", "  ", null, "Stone"));

		assertEquals(List.of("#minecraft:logs", "#c:storage_blocks", "minecraft:stone"), result);
	}

	@Test
	void sameIdAsBlockAndAsTagAreDifferentEntries() {
		assertEquals(List.of("minecraft:logs", "#minecraft:logs"),
				IdFilter.normalize(List.of("logs", "#logs")));
	}

	@Test
	void parseSplitsIdsTagsAndMalformedEntries() {
		IdFilter filter = IdFilter.parse(IdFilter.normalize(List.of(
				"stone", "#logs", "mymod:thing", "minecraft:bad id", "#minecraft:", "x:y:z")));

		assertEquals(Set.of("minecraft:stone", "mymod:thing"), filter.ids());
		assertEquals(Set.of("minecraft:logs"), filter.tags());
		assertEquals(List.of(
						"minecraft:bad id (not a valid id)",
						"#minecraft: (not a valid id)",
						"x:y:z (not a valid id)"),
				filter.problems(id -> true, tag -> true));
	}

	@Test
	void validIdFollowsMinecraftRules() {
		assertTrue(IdFilter.isValidId("minecraft:stone"));
		assertTrue(IdFilter.isValidId("my-mod.x:blocks/thing_1"));
		assertFalse(IdFilter.isValidId("minecraft:Stone"));
		assertFalse(IdFilter.isValidId(":stone"));
		assertFalse(IdFilter.isValidId("minecraft:"));
		assertFalse(IdFilter.isValidId("stone"));
		assertFalse(IdFilter.isValidId("my/mod:stone")); // '/' only in the path
		assertFalse(IdFilter.isValidId("..:stone"));
	}

	@Test
	void unknownIdsAndTagsAreReported() {
		IdFilter filter = IdFilter.parse(IdFilter.normalize(List.of("stone", "stnoe", "#logs", "#lgos")));

		List<String> problems = filter.problems(
				id -> id.equals("minecraft:stone"),
				tag -> tag.equals("minecraft:logs"));

		assertEquals(List.of("minecraft:stnoe (unknown id)", "#minecraft:lgos (unknown tag)"), problems);
	}

	@Test
	void nothingToReportForAKnownList() {
		IdFilter filter = IdFilter.parse(IdFilter.normalize(List.of("stone", "#logs")));
		assertTrue(filter.problems(id -> true, tag -> true).isEmpty());
	}

	@Test
	void matchesDirectIdsAndTagMembers() {
		IdFilter filter = IdFilter.parse(IdFilter.normalize(List.of("stone", "#logs")));

		assertTrue(filter.matches("minecraft:stone", tag -> false));
		assertTrue(filter.matches("minecraft:oak_log", tag -> tag.equals("minecraft:logs")));
		assertFalse(filter.matches("minecraft:dirt", tag -> false));
		// A tag that is not in the list never matches, whatever the block is in.
		assertFalse(filter.matches("minecraft:dirt", tag -> tag.equals("minecraft:dirt_like")));
	}

	@Test
	void emptyListMatchesNothing() {
		IdFilter filter = IdFilter.parse(IdFilter.normalize(List.of()));
		assertTrue(filter.isEmpty());
		assertFalse(filter.matches("minecraft:stone", tag -> true));
		assertFalse(IdFilter.EMPTY.matches("minecraft:stone", tag -> true));
	}
}
