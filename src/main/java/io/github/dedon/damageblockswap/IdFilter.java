package io.github.dedon.damageblockswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A config list of registry entries: plain ids ({@code "minecraft:stone"}) and tags
 * ({@code "#minecraft:logs"}). Used for the block blacklist and the ignored damage types.
 *
 * <p>No Minecraft classes here, so parsing, validation and matching are unit-tested; the game side
 * passes in "does this id / tag exist" and "is this entry in that tag" as predicates.
 */
final class IdFilter {
	static final IdFilter EMPTY = new IdFilter(Set.of(), Set.of(), List.of());

	private final Set<String> ids;
	private final Set<String> tags;
	private final List<String> malformed;

	private IdFilter(Set<String> ids, Set<String> tags, List<String> malformed) {
		this.ids = ids;
		this.tags = tags;
		this.malformed = malformed;
	}

	/**
	 * Cleans up a hand-written list so matching is forgiving: trims whitespace, lowercases
	 * ({@link Locale#ROOT}: under e.g. a Turkish default locale "I" would become a dotless "ı"),
	 * drops blanks, adds the {@code minecraft:} namespace when it was omitted (also after {@code #}),
	 * and removes duplicates. The order is kept.
	 */
	static List<String> normalize(List<String> raw) {
		List<String> result = new ArrayList<>();
		if (raw == null) {
			return result;
		}
		for (String entry : raw) {
			if (entry == null) {
				continue;
			}
			String id = entry.trim().toLowerCase(Locale.ROOT);
			boolean tag = id.startsWith("#");
			if (tag) {
				id = id.substring(1).trim();
			}
			if (id.isEmpty()) {
				continue;
			}
			if (!id.contains(":")) {
				id = "minecraft:" + id;
			}
			String normalized = tag ? "#" + id : id;
			if (!result.contains(normalized)) {
				result.add(normalized);
			}
		}
		return result;
	}

	/** Splits an already {@link #normalize normalized} list into ids, tags and malformed entries. */
	static IdFilter parse(List<String> normalized) {
		Set<String> ids = new LinkedHashSet<>();
		Set<String> tags = new LinkedHashSet<>();
		List<String> malformed = new ArrayList<>();
		for (String entry : normalized) {
			boolean tag = entry.startsWith("#");
			String id = tag ? entry.substring(1) : entry;
			if (!isValidId(id)) {
				malformed.add(entry);
			} else if (tag) {
				tags.add(id);
			} else {
				ids.add(id);
			}
		}
		return new IdFilter(Collections.unmodifiableSet(ids), Collections.unmodifiableSet(tags),
				Collections.unmodifiableList(malformed));
	}

	/** Same rules as Minecraft's {@code Identifier}: {@code [a-z0-9_.-]:[a-z0-9_./-]}, both parts non-empty. */
	static boolean isValidId(String id) {
		int colon = id.indexOf(':');
		if (colon <= 0 || colon == id.length() - 1) {
			return false;
		}
		String namespace = id.substring(0, colon);
		String path = id.substring(colon + 1);
		if (namespace.equals("..")) {
			return false;
		}
		for (int i = 0; i < namespace.length(); i++) {
			char c = namespace.charAt(i);
			if (!(c == '_' || c == '-' || c == '.' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) {
				return false;
			}
		}
		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);
			if (!(c == '_' || c == '-' || c == '.' || c == '/' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9')) {
				return false;
			}
		}
		return true;
	}

	/** Plain ids (without {@code #}). */
	Set<String> ids() {
		return ids;
	}

	/** Tag ids (without {@code #}). */
	Set<String> tags() {
		return tags;
	}

	boolean isEmpty() {
		return ids.isEmpty() && tags.isEmpty();
	}

	/**
	 * Entries worth a warning, as written in the config: malformed ones, ids that aren't registered
	 * and tags that don't exist. Such entries simply match nothing.
	 */
	List<String> problems(Predicate<String> idExists, Predicate<String> tagExists) {
		List<String> result = new ArrayList<>();
		for (String entry : malformed) {
			result.add(entry + " (not a valid id)");
		}
		for (String id : ids) {
			if (!idExists.test(id)) {
				result.add(id + " (unknown id)");
			}
		}
		for (String tag : tags) {
			if (!tagExists.test(tag)) {
				result.add("#" + tag + " (unknown tag)");
			}
		}
		return result;
	}

	/** Whether the entry {@code id} is listed, directly or through one of the tags ({@code inTag}). */
	boolean matches(String id, Predicate<String> inTag) {
		if (ids.contains(id)) {
			return true;
		}
		for (String tag : tags) {
			if (inTag.test(tag)) {
				return true;
			}
		}
		return false;
	}
}
