package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings screen's damage type checkboxes against {@link Config#ignoredDamageTypes}. */
class DamageTypeTogglesTest {
	private static final String DROWN = "minecraft:drown";
	private static final String STARVE = "minecraft:starve";
	private static final String FALL = "minecraft:fall";
	private static final String LAVA = "minecraft:lava";
	private static final String IN_FIRE = "minecraft:in_fire";

	/** A small registry: the mod's default tag holds drown and starve, a vanilla-like tag holds the fires. */
	private static DamageTypeToggles toggles() {
		return new DamageTypeToggles(List.of(FALL, STARVE, DROWN, LAVA, IN_FIRE), Map.of(
				"damageblockswap:ignored", Set.of(DROWN, STARVE),
				"minecraft:is_fire", Set.of(LAVA, IN_FIRE)));
	}

	private static Map<String, Boolean> checkboxes(DamageTypeToggles toggles, List<String> list) {
		Map<String, Boolean> result = new HashMap<>();
		for (String id : toggles.typeIds()) {
			result.put(id, toggles.causesSwaps(list, id));
		}
		return result;
	}

	@Test
	void typesAreSorted() {
		assertEquals(List.of(DROWN, FALL, IN_FIRE, LAVA, STARVE), toggles().typeIds());
	}

	@Test
	void defaultListUnchecksTheTypesOfTheDefaultTag() {
		Map<String, Boolean> shown = checkboxes(toggles(), Config.DEFAULT_IGNORED_DAMAGE_TYPES);

		assertEquals(Map.of(DROWN, false, STARVE, false, FALL, true, LAVA, true, IN_FIRE, true), shown);
	}

	@Test
	void checkboxesFollowIdsTagsAndTheLooseSpellingOfTheList() {
		DamageTypeToggles toggles = toggles();
		List<String> list = List.of(" Fall ", "#is_fire");

		assertFalse(toggles.causesSwaps(list, FALL));
		assertFalse(toggles.causesSwaps(list, LAVA));
		assertTrue(toggles.causesSwaps(list, DROWN));
		assertTrue(toggles.causesSwaps(List.of(), DROWN), "an empty list lets every damage cause swaps");
	}

	@Test
	void unknownTagMatchesNothingLikeOnTheServer() {
		assertTrue(toggles().causesSwaps(List.of("#mymod:missing"), DROWN));
	}

	@Test
	void savingUntouchedCheckboxesKeepsTheListAndItsTags() {
		DamageTypeToggles toggles = toggles();
		List<String> list = List.of("#damageblockswap:ignored", "minecraft:fall", "#mymod:missing");

		assertEquals(list, toggles.apply(list, checkboxes(toggles, list)));
		assertEquals(list, toggles.apply(list, Map.of()));
	}

	@Test
	void uncheckingATypeAddsItsIdAndKeepsTheTag() {
		List<String> result = toggles().apply(Config.DEFAULT_IGNORED_DAMAGE_TYPES, Map.of(FALL, false));

		assertEquals(List.of("#damageblockswap:ignored", FALL), result);
	}

	@Test
	void checkingAListedIdRemovesIt() {
		List<String> result = toggles().apply(List.of("#damageblockswap:ignored", FALL), Map.of(FALL, true));

		assertEquals(Config.DEFAULT_IGNORED_DAMAGE_TYPES, result);
	}

	@Test
	void checkingATypeOfATagReplacesTheTagByItsOtherMembers() {
		List<String> result = toggles().apply(List.of("#damageblockswap:ignored", FALL), Map.of(DROWN, true));

		assertEquals(List.of(STARVE, FALL), result);
		// The other tags are kept.
		assertEquals(List.of("#minecraft:is_fire", STARVE),
				toggles().apply(List.of("#minecraft:is_fire", "#damageblockswap:ignored"), Map.of(DROWN, true)));
	}

	@Test
	void checkingEveryTypeOfATagEmptiesTheList() {
		List<String> result = toggles().apply(Config.DEFAULT_IGNORED_DAMAGE_TYPES, Map.of(DROWN, true, STARVE, true));

		assertEquals(List.of(), result);
	}

	@Test
	void typeListedBothDirectlyAndThroughATagIsFullyRemoved() {
		List<String> result = toggles().apply(List.of(DROWN, "#damageblockswap:ignored"), Map.of(DROWN, true));

		assertEquals(List.of(STARVE), result);
	}

	@Test
	void everyCheckboxShowsWhatItWasSetTo() {
		DamageTypeToggles toggles = toggles();
		Map<String, Boolean> wanted = Map.of(DROWN, true, STARVE, false, FALL, false, LAVA, true, IN_FIRE, false);
		List<String> start = List.of("#damageblockswap:ignored", "#minecraft:is_fire");

		List<String> result = toggles.apply(start, wanted);

		assertEquals(wanted, checkboxes(toggles, result));
	}

	@Test
	void checkboxesApplyOnTopOfAHandEditedList() {
		// The screen saves the text list first, then applies only the checkboxes that were switched.
		List<String> handEdited = List.of("#minecraft:is_fire");

		List<String> result = toggles().apply(handEdited, Map.of(FALL, false));

		assertEquals(List.of("#minecraft:is_fire", FALL), result);
	}
}
