package io.github.dedon.damageblockswap;

import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.damagesource.DamageType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the damage types and their tags from a live registry into {@link DamageTypeToggles}. */
public final class DamageTypeCatalog {
	private DamageTypeCatalog() {
	}

	/** Damage types and tags as {@code registries} (the server's, or a client's synced copy) sees them now. */
	public static DamageTypeToggles toggles(RegistryAccess registries) {
		Registry<DamageType> registry = registries.lookupOrThrow(Registries.DAMAGE_TYPE);
		List<String> ids = registry.keySet().stream().map(Identifier::toString).toList();
		Map<String, Set<String>> tags = new HashMap<>();
		registry.getTags().forEach((HolderSet.Named<DamageType> tag) -> {
			Set<String> members = new HashSet<>();
			tag.stream().forEach(holder -> holder.unwrapKey()
					.ifPresent(key -> members.add(key.identifier().toString())));
			tags.put(tag.key().location().toString(), members);
		});
		return new DamageTypeToggles(ids, tags);
	}
}
