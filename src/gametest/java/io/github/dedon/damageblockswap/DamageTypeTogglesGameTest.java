package io.github.dedon.damageblockswap;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

import java.util.List;
import java.util.Map;

/**
 * The settings screen's damage type checkboxes, built from the real server registry (the same data a
 * client gets synced), agree with what {@link DamageTypeFilter} does with a hit, for every damage type,
 * with the default list (the datapack tag) and after switching checkboxes.
 */
public class DamageTypeTogglesGameTest {
	private static void assertAgrees(GameTestHelper helper, DamageTypeToggles toggles, List<String> list) {
		RegistryAccess registries = helper.getLevel().registryAccess();
		Registry<DamageType> registry = registries.lookupOrThrow(Registries.DAMAGE_TYPE);
		List<String> saved = Config.ignoredDamageTypes;
		try {
			Config.setIgnoredDamageTypes(list);
			Config.generation++;
			DamageTypeFilter filter = new DamageTypeFilter();
			for (String id : toggles.typeIds()) {
				Holder<DamageType> type = registry.get(Identifier.parse(id)).orElseThrow();
				boolean swaps = !filter.ignores(new DamageSource(type), registries);
				helper.assertTrue(toggles.causesSwaps(list, id) == swaps,
						"checkbox of " + id + " says swaps=" + !swaps + ", the server says " + swaps + " for " + list);
			}
		} finally {
			Config.ignoredDamageTypes = saved;
			Config.generation++;
		}
	}

	@GameTest
	public void checkboxesMatchTheServerFilter(GameTestHelper helper) {
		DamageTypeToggles toggles = DamageTypeCatalog.toggles(helper.getLevel().registryAccess());
		helper.assertTrue(toggles.typeIds().contains("minecraft:drown") && toggles.typeIds().contains("minecraft:fall"),
				"vanilla damage types missing: " + toggles.typeIds());

		List<String> defaults = Config.DEFAULT_IGNORED_DAMAGE_TYPES;
		helper.assertFalse(toggles.causesSwaps(defaults, "minecraft:drown"), "the datapack tag does not reach the checkboxes");
		helper.assertTrue(toggles.causesSwaps(defaults, "minecraft:fall"), "fall damage shown as not swapping");
		assertAgrees(helper, toggles, defaults);

		// Switch drowning on and falling off: the tag is replaced by its other members.
		List<String> switched = toggles.apply(defaults, Map.of("minecraft:drown", true, "minecraft:fall", false));
		helper.assertFalse(switched.contains("#damageblockswap:ignored") || switched.contains("minecraft:drown"),
				"drowning is still listed: " + switched);
		helper.assertTrue(switched.contains("minecraft:starve") && switched.contains("minecraft:fall"), "lost entries: " + switched);
		assertAgrees(helper, toggles, switched);

		assertAgrees(helper, toggles, List.of("#minecraft:is_fire", "minecraft:fall"));
		helper.succeed();
	}
}
