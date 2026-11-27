package io.github.dedon.damageblockswap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple JSON config, stored at {@code config/damageblockswap.json}.
 *
 * <p>Values are read once at startup. If the file is missing it is created with defaults;
 * if it exists but is missing newer keys, it is rewritten so those keys appear (with their
 * defaults). Keeping the fields {@code static} makes them easy to read from anywhere.
 */
public final class Config {
	// Own logger (same name as the mod's): touching DamageBlockSwap would run its static init
	// (attachment registration), which the unit tests can't do.
	private static final Logger LOGGER = LoggerFactory.getLogger(DamageBlockSwap.MOD_ID);

	/**
	 * Version of the file layout. 1 (or no {@code configVersion} key): mod 1.0.x / 1.1.0.
	 * 2: default cooldown became {@value #DEFAULT_COOLDOWN_TICKS}, {@code ignoredDamageTypes} added.
	 */
	static final int CONFIG_VERSION = 2;
	static final int DEFAULT_COOLDOWN_TICKS = 20;
	/** By default the damage types in this tag don't trigger swaps (see the tag file for the list). */
	static final List<String> DEFAULT_IGNORED_DAMAGE_TYPES = List.of("#" + DamageBlockSwap.MOD_ID + ":ignored");

	/** How many separate random swaps happen per hit. Clamped to {@value #MIN_SWAPS}..{@value #MAX_SWAPS}. */
	public static int swapsPerHit = 1;
	public static boolean chatMessages = true;
	public static boolean logging = true;
	public static boolean enableLiquids = true;
	public static boolean enableGravityBlocks = true;
	/** Minimum ticks between two swap-triggering hits of the same player. 0 = no cooldown. */
	public static int cooldownTicks = DEFAULT_COOLDOWN_TICKS;
	/**
	 * When true, swaps use full block updates (neighbours react, unsupported blocks drop). Off (default)
	 * = much faster, neighbours untouched; the placed block's own behaviour (falling, flowing) stays.
	 */
	public static boolean updateNeighbors = false;
	/** When true, the two swapped block types are chosen from blocks actually near the player. */
	public static boolean pickFromNearbyChunks = true;
	/** Area of effect around the player, in chunks: where swaps apply immediately and which blocks are picked. Smaller = less lag. */
	public static int swapRadius = 3;
	/**
	 * Block ids ({@code "minecraft:bedrock"}) and block tags ({@code "#minecraft:logs"}) that are never
	 * picked for a new swap rule. Normalized on load. Only affects new rules, never rules already made.
	 */
	public static List<String> blacklist = new ArrayList<>();
	/** Damage type ids and {@code #tags} that don't trigger swaps. Normalized on load. */
	public static List<String> ignoredDamageTypes = new ArrayList<>(DEFAULT_IGNORED_DAMAGE_TYPES);

	public static final int MIN_SWAPS = 1;
	public static final int MAX_SWAPS = 100;
	public static final int MAX_SWAP_RADIUS = 16;

	/** Bumped whenever settings change, so caches built from the config (e.g. the swappable list) can rebuild. */
	public static int generation = 0;

	private Config() {
	}

	/**
	 * Plain data holder that Gson serializes to/from JSON. The {@code _help*} fields are just
	 * comments for whoever edits the file by hand (JSON has no real comment syntax). A key missing
	 * from the file keeps the value given here.
	 */
	static final class Data {
		String _help = "Damage Block Swap config.";
		// Missing in files written before version 2; see migrate().
		int configVersion = 0;
		String _help_swapsPerHit = "How many random block swaps happen each time you take damage (1-100).";
		int swapsPerHit = 1;
		String _help_blacklist = "Blocks that are never picked for a new swap, as ids or #tags, e.g. [\"minecraft:diamond_ore\", \"#minecraft:logs\"]. The \"minecraft:\" prefix is optional. Rules that already exist are not affected. A safe set (bedrock, portals, barriers, fire, doors, tall plants, ...) is always excluded through the block tag damageblockswap:never_swap, which a datapack can change.";
		List<String> blacklist = new ArrayList<>();
		boolean chatMessages = true;
		boolean logging = true;
		boolean enableLiquids = true;
		boolean enableGravityBlocks = true;
		String _help_cooldownTicks = "Minimum ticks between two swaps caused by the same player (20 ticks = 1 second). 0 = no cooldown.";
		int cooldownTicks = DEFAULT_COOLDOWN_TICKS;
		String _help_ignoredDamageTypes = "Damage types that do NOT cause a swap, as ids or #tags, e.g. [\"minecraft:fall\", \"#minecraft:is_fire\"]. The default tag #damageblockswap:ignored holds drowning, starving, suffocation, cramming, burning, freezing, the void, the world border and /kill. Use [] to let every damage type cause swaps.";
		List<String> ignoredDamageTypes = new ArrayList<>(DEFAULT_IGNORED_DAMAGE_TYPES);
		String _help_updateNeighbors = "Full vanilla block updates after swaps: neighbouring blocks react (redstone, connections, unsupported blocks break and drop). WARNING: with many/common blocks this can cause severe multi-second lag. Leave off unless you know you want it. Even when off, swapped-in sand/gravel still falls and swapped-in water/lava still flows.";
		boolean updateNeighbors = false;
		String _help_pickFromNearbyChunks = "Choose the swapped blocks from ones that actually exist near the player, so hits are always visible.";
		boolean pickFromNearbyChunks = true;
		String _help_swapRadius = "Area of effect around the player, in chunks (1..16). Swaps apply immediately within this bubble (which follows you as you move) and to new chunks as they load. Smaller = less lag; larger = more of the world changes at once.";
		int swapRadius = 3;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(DamageBlockSwap.MOD_ID + ".json");
	}

	private static Gson gson() {
		return new GsonBuilder().setPrettyPrinting().create();
	}

	public static void load() {
		loadFrom(path());
	}

	/** Reads the file (if any), applies it, and writes it back normalized. Package-private for tests. */
	static void loadFrom(Path path) {
		Gson gson = gson();
		Data data = new Data();
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path)) {
					Data loaded = gson.fromJson(reader, Data.class);
					if (loaded != null) {
						data = loaded;
					}
				}
			} else {
				data.configVersion = CONFIG_VERSION; // a fresh file has nothing to migrate
			}

			apply(migrate(data));
			// Always write back: creates the file on first run and adds any newly introduced
			// keys to an older config, while preserving the values we just read.
			save(gson, path);
		} catch (Exception e) {
			LOGGER.warn("Failed to load config, using defaults", e);
			Data defaults = new Data();
			defaults.configVersion = CONFIG_VERSION;
			apply(defaults);
		}
	}

	/**
	 * Brings a file written by an older version up to {@link #CONFIG_VERSION}.
	 *
	 * <p>Version 1 -> 2: the old default cooldown was 0, and the old mod wrote every key into the file,
	 * so a 0 there almost always means "never changed". A cooldown of 0 lets a damage loop (a rule
	 * that creates lava or cactus around the player) add a rule every few ticks, so 0 becomes the new
	 * default once. Any other value was chosen by someone and is kept. Setting 0 again afterwards
	 * sticks: the file then says version 2.
	 */
	static Data migrate(Data data) {
		if (data.configVersion < 2) {
			if (data.cooldownTicks == 0) {
				data.cooldownTicks = DEFAULT_COOLDOWN_TICKS;
				LOGGER.info("Config: cooldownTicks raised from 0 (the old default) to {} ticks. Set it back to 0"
						+ " in config/{}.json if you really want no cooldown.", DEFAULT_COOLDOWN_TICKS, DamageBlockSwap.MOD_ID);
			}
			data.configVersion = 2;
		}
		return data;
	}

	/**
	 * Writes the current values back to disk. Called by the in-game settings screen after the
	 * player edits and saves. Re-validates first so bad UI input can't corrupt the file.
	 */
	public static void save() {
		swapsPerHit = clamp(swapsPerHit, MIN_SWAPS, MAX_SWAPS);
		cooldownTicks = Math.max(0, cooldownTicks);
		blacklist = IdFilter.normalize(blacklist);
		ignoredDamageTypes = IdFilter.normalize(ignoredDamageTypes);
		generation++; // the in-game screen just changed settings — invalidate config-derived caches

		try {
			save(gson(), path());
		} catch (Exception e) {
			LOGGER.warn("Failed to save config", e);
		}
	}

	private static void apply(Data d) {
		swapsPerHit = clamp(d.swapsPerHit, MIN_SWAPS, MAX_SWAPS);
		chatMessages = d.chatMessages;
		logging = d.logging;
		enableLiquids = d.enableLiquids;
		enableGravityBlocks = d.enableGravityBlocks;
		cooldownTicks = Math.max(0, d.cooldownTicks);
		updateNeighbors = d.updateNeighbors;
		pickFromNearbyChunks = d.pickFromNearbyChunks;
		swapRadius = clamp(d.swapRadius, 1, MAX_SWAP_RADIUS);
		blacklist = IdFilter.normalize(d.blacklist);
		// An explicit "ignoredDamageTypes": null is treated like a missing key, not like [].
		ignoredDamageTypes = IdFilter.normalize(d.ignoredDamageTypes != null ? d.ignoredDamageTypes : DEFAULT_IGNORED_DAMAGE_TYPES);
		generation++; // settings changed — invalidate config-derived caches
	}

	private static void save(Gson gson, Path path) throws java.io.IOException {
		Files.createDirectories(path.getParent());

		// Build the output from the (validated) current values so the written file is normalized.
		Data out = new Data();
		out.configVersion = CONFIG_VERSION;
		out.swapsPerHit = swapsPerHit;
		out.blacklist = blacklist;
		out.chatMessages = chatMessages;
		out.logging = logging;
		out.enableLiquids = enableLiquids;
		out.enableGravityBlocks = enableGravityBlocks;
		out.cooldownTicks = cooldownTicks;
		out.ignoredDamageTypes = ignoredDamageTypes;
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

	/** See {@link IdFilter#normalize}; kept under the old name for the blacklist. */
	static List<String> normalizeBlacklist(List<String> raw) {
		return IdFilter.normalize(raw);
	}
}
