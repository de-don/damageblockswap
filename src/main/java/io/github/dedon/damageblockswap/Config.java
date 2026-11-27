package io.github.dedon.damageblockswap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Simple JSON config, stored at {@code config/damageblockswap.json}.
 *
 * <p>Values are read once at startup. If the file is missing it is created with defaults;
 * if it exists but is missing newer keys, it is rewritten so those keys appear (with their
 * defaults). Keeping the fields {@code static} makes them easy to read from anywhere.
 */
public final class Config {
	/** How many separate random swaps happen per hit. Clamped to {@value #MIN_SWAPS}..{@value #MAX_SWAPS}. */
	public static int swapsPerHit = 1;
	public static boolean chatMessages = true;
	public static boolean logging = true;
	public static boolean enableLiquids = true;
	public static boolean enableGravityBlocks = true;
	public static int cooldownTicks = 0;
	/**
	 * When true, swaps use full block updates (neighbours react, unsupported blocks drop). Off (default)
	 * = much faster, neighbours untouched; the placed block's own behaviour (falling, flowing) stays.
	 */
	public static boolean updateNeighbors = false;
	/** When true, the two swapped block types are chosen from blocks actually near the player. */
	public static boolean pickFromNearbyChunks = true;
	/** Area of effect around the player, in chunks: where swaps apply immediately and which blocks are picked. Smaller = less lag. */
	public static int swapRadius = 3;
	/** Block IDs that are never swapped, e.g. {@code "minecraft:bedrock"}. Normalized on load. */
	public static List<String> blacklist = new ArrayList<>();

	public static final int MIN_SWAPS = 1;
	public static final int MAX_SWAPS = 100;
	public static final int MAX_SWAP_RADIUS = 16;

	/** Bumped whenever settings change, so caches built from the config (e.g. the swappable list) can rebuild. */
	public static int generation = 0;

	private Config() {
	}

	/**
	 * Plain data holder that Gson serializes to/from JSON. The {@code _help*} fields are just
	 * comments for whoever edits the file by hand (JSON has no real comment syntax).
	 */
	private static final class Data {
		String _help = "Damage Block Swap config.";
		String _help_swapsPerHit = "How many random block swaps happen each time you take damage (1-100).";
		int swapsPerHit = 1;
		String _help_blacklist = "Block IDs that are never swapped, e.g. [\"minecraft:bedrock\", \"minecraft:diamond_ore\"]. The \"minecraft:\" prefix is optional.";
		List<String> blacklist = new ArrayList<>();
		boolean chatMessages = true;
		boolean logging = true;
		boolean enableLiquids = true;
		boolean enableGravityBlocks = true;
		int cooldownTicks = 0;
		String _help_updateNeighbors = "Full vanilla block updates after swaps: neighbouring blocks react (redstone, connections, unsupported blocks break and drop). WARNING: with many/common blocks this can cause severe multi-second lag. Leave off unless you know you want it. Even when off, swapped-in sand/gravel still falls and swapped-in water/lava still flows.";
		boolean updateNeighbors = false;
		String _help_pickFromNearbyChunks = "Choose the swapped blocks from ones that actually exist near the player, so hits are always visible.";
		boolean pickFromNearbyChunks = true;
		String _help_swapRadius = "Area of effect around the player, in chunks (1..16). Swaps apply immediately within this bubble (which follows you as you move) and to new chunks as they load. Smaller = less lag; larger = more of the world changes at once.";
		int swapRadius = 3;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(DamageBlockSwap.MOD_ID + ".json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		Data data = new Data();
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path)) {
					Data loaded = gson.fromJson(reader, Data.class);
					if (loaded != null) {
						data = loaded;
					}
				}
			}

			apply(data);
			// Always write back: creates the file on first run and adds any newly introduced
			// keys to an older config, while preserving the values we just read.
			save(gson, path);
		} catch (Exception e) {
			DamageBlockSwap.LOGGER.warn("Failed to load config, using defaults", e);
			apply(new Data());
		}
	}

	/**
	 * Writes the current values back to disk. Called by the in-game settings screen after the
	 * player edits and saves. Re-validates first so bad UI input can't corrupt the file.
	 */
	public static void save() {
		swapsPerHit = clamp(swapsPerHit, MIN_SWAPS, MAX_SWAPS);
		blacklist = normalizeBlacklist(blacklist);
		generation++; // the in-game screen just changed settings — invalidate config-derived caches

		Path path = FabricLoader.getInstance().getConfigDir().resolve(DamageBlockSwap.MOD_ID + ".json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
			save(gson, path);
		} catch (Exception e) {
			DamageBlockSwap.LOGGER.warn("Failed to save config", e);
		}
	}

	private static void apply(Data d) {
		swapsPerHit = clamp(d.swapsPerHit, MIN_SWAPS, MAX_SWAPS);
		chatMessages = d.chatMessages;
		logging = d.logging;
		enableLiquids = d.enableLiquids;
		enableGravityBlocks = d.enableGravityBlocks;
		cooldownTicks = d.cooldownTicks;
		updateNeighbors = d.updateNeighbors;
		pickFromNearbyChunks = d.pickFromNearbyChunks;
		swapRadius = clamp(d.swapRadius, 1, MAX_SWAP_RADIUS);
		blacklist = normalizeBlacklist(d.blacklist);
		generation++; // settings changed — invalidate config-derived caches
	}

	private static void save(Gson gson, Path path) throws java.io.IOException {
		Files.createDirectories(path.getParent());

		// Build the output from the (validated) current values so the written file is normalized.
		Data out = new Data();
		out.swapsPerHit = swapsPerHit;
		out.blacklist = blacklist;
		out.chatMessages = chatMessages;
		out.logging = logging;
		out.enableLiquids = enableLiquids;
		out.enableGravityBlocks = enableGravityBlocks;
		out.cooldownTicks = cooldownTicks;
		out.updateNeighbors = updateNeighbors;
		out.pickFromNearbyChunks = pickFromNearbyChunks;
		out.swapRadius = swapRadius;

		try (Writer writer = Files.newBufferedWriter(path)) {
			gson.toJson(out, writer);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * Cleans up blacklist entries so matching is forgiving: trims whitespace, lowercases,
	 * drops blanks, and adds the {@code minecraft:} namespace when the user omitted it.
	 */
	static List<String> normalizeBlacklist(List<String> raw) {
		List<String> result = new ArrayList<>();
		if (raw == null) {
			return result;
		}
		for (String entry : raw) {
			if (entry == null) {
				continue;
			}
			// Locale.ROOT: under e.g. a Turkish default locale "I" would lowercase to a dotless "ı"
			// and the ID would silently stop matching.
			String id = entry.trim().toLowerCase(Locale.ROOT);
			if (id.isEmpty()) {
				continue;
			}
			if (!id.contains(":")) {
				id = "minecraft:" + id;
			}
			if (!result.contains(id)) {
				result.add(id);
			}
		}
		return result;
	}
}
