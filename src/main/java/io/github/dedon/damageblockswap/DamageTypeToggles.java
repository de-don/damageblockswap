package io.github.dedon.damageblockswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The "causes swaps" checkboxes of the settings screen, one per damage type, on top of
 * {@link Config#ignoredDamageTypes}. The list stays the only thing that is stored: a checkbox shows
 * whether the list matches its type (directly or through a tag), and switching checkboxes edits the list.
 *
 * <p>Switching a type <i>off</i> adds its id. Switching it back <i>on</i> removes its id, and replaces each
 * tag in the list that contains it by the tag's other members (the list can't say "the tag except one").
 * So a tag stays in the list, and keeps following datapack changes, until one of its own types is switched on.
 *
 * <p>No Minecraft classes here (see {@link DamageTypeCatalog} for the registry side), so it is unit-tested.
 */
public final class DamageTypeToggles {
	private final List<String> typeIds;
	private final Map<String, Set<String>> tagMembers;

	/**
	 * @param typeIds    every damage type id, e.g. {@code minecraft:drown}
	 * @param tagMembers every known damage type tag (id without {@code #}) and its member ids; a tag
	 *                   missing here matches nothing, as on the server ({@link DamageTypeFilter})
	 */
	public DamageTypeToggles(Iterable<String> typeIds, Map<String, ? extends Set<String>> tagMembers) {
		TreeSet<String> sorted = new TreeSet<>();
		typeIds.forEach(sorted::add);
		this.typeIds = List.copyOf(sorted);
		this.tagMembers = Map.copyOf(tagMembers);
	}

	/** Every damage type id, sorted. */
	public List<String> typeIds() {
		return typeIds;
	}

	/** Whether a hit of this type causes a swap with this {@code ignoredDamageTypes} list. */
	public boolean causesSwaps(List<String> ignoredDamageTypes, String typeId) {
		IdFilter filter = IdFilter.parse(IdFilter.normalize(ignoredDamageTypes));
		return !filter.matches(typeId, tag -> inTag(tag, typeId));
	}

	private boolean inTag(String tag, String typeId) {
		Set<String> members = tagMembers.get(tag);
		return members != null && members.contains(typeId);
	}

	/**
	 * The list after switching checkboxes: {@code changes} maps type ids to the wanted "causes swaps"
	 * value. Types that already are as wanted leave the list alone, so saving the screen without
	 * touching a checkbox keeps the list (and its tags) exactly as it was. The result is normalized.
	 */
	public List<String> apply(List<String> ignoredDamageTypes, Map<String, Boolean> changes) {
		List<String> list = IdFilter.normalize(ignoredDamageTypes);
		// Sorted, so the result doesn't depend on the map's order.
		for (String typeId : new TreeSet<>(changes.keySet())) {
			boolean wanted = changes.get(typeId);
			if (causesSwaps(list, typeId) == wanted) {
				continue;
			}
			if (!wanted) {
				list.add(typeId);
				continue;
			}
			List<String> next = new ArrayList<>();
			for (String entry : list) {
				if (entry.equals(typeId)) {
					continue;
				}
				String tag = entry.startsWith("#") ? entry.substring(1) : null;
				if (tag != null && inTag(tag, typeId)) {
					for (String member : new TreeSet<>(tagMembers.get(tag))) {
						if (!member.equals(typeId)) {
							next.add(member);
						}
					}
				} else {
					next.add(entry);
				}
			}
			list = IdFilter.normalize(next);
		}
		return Collections.unmodifiableList(list);
	}
}
