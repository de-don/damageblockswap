package io.github.dedon.damageblockswap;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

import java.util.HashMap;
import java.util.Map;

/**
 * Which kinds of damage don't trigger a swap: {@link Config#ignoredDamageTypes}, by default the tag
 * {@code #damageblockswap:ignored} (drowning, starving, suffocation, cramming, burning, ...). These
 * hurt every few ticks for as long as the situation lasts, and a swap can create that situation
 * (a rule that walls the player in or puts them under water), so they would feed a loop of rules.
 * Server thread only.
 */
final class DamageTypeFilter {
	private IdFilter filter = IdFilter.EMPTY;
	private Map<String, TagKey<DamageType>> tags = Map.of();
	private int cachedGeneration = -1;
	private int cachedTagsGeneration = -1;

	/** Whether this hit must not cause a swap. */
	boolean ignores(DamageSource source, RegistryAccess registries) {
		refresh(registries);
		Holder<DamageType> type = source.typeHolder();
		String id = type.unwrapKey().map(key -> key.identifier().toString()).orElse("");
		return filter.matches(id, tag -> type.is(tags.get(tag)));
	}

	/**
	 * Rebuilds from the config if it or the tags changed since, and then logs a warning for every
	 * entry that matches nothing (typo, unknown damage type or tag).
	 */
	void refresh(RegistryAccess registries) {
		if (cachedGeneration == Config.generation && cachedTagsGeneration == DamageBlockSwap.tagsGeneration()) {
			return;
		}
		cachedGeneration = Config.generation;
		cachedTagsGeneration = DamageBlockSwap.tagsGeneration();

		filter = IdFilter.parse(Config.ignoredDamageTypes);
		Map<String, TagKey<DamageType>> keys = new HashMap<>();
		for (String tag : filter.tags()) {
			keys.put(tag, TagKey.create(Registries.DAMAGE_TYPE, Identifier.parse(tag)));
		}
		tags = keys;

		Registry<DamageType> registry = registries.lookupOrThrow(Registries.DAMAGE_TYPE);
		for (String problem : filter.problems(
				id -> registry.containsKey(Identifier.parse(id)),
				tag -> registry.get(keys.get(tag)).isPresent())) {
			DamageBlockSwap.LOGGER.warn("Config ignoredDamageTypes: {} matches no damage type and is ignored.", problem);
		}
	}
}
