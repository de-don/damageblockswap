package io.github.dedon.damageblockswap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "rule book" of all swaps that have happened, in order.
 *
 * <p>Each swap is one rule (block A &lt;-&gt; block B). The list order matters, and its length is
 * the current <b>version</b> ("seed") of the world's swap state. Every chunk remembers which
 * version it has been brought up to; when it is behind, we apply just the missing rules to it.
 *
 * <p>Two extra maps ({@link #originalToCurrent}/{@link #currentToOriginal}) are kept in sync so
 * {@code /dbs} can answer "what did X turn into" instantly. Everything is global and only touched
 * from the server thread.
 */
public final class SwapTracker {
	private static final List<RandomSwapService.BlockPair> swaps = new ArrayList<>();

	// For /dbs queries: original type <-> what it looks like now (the composed permutation).
	private static final Map<Block, Block> originalToCurrent = new HashMap<>();
	private static final Map<Block, Block> currentToOriginal = new HashMap<>();

	// Cached mapping for the most common range (version 0 -> current), which every freshly loaded
	// chunk needs. Invalidated whenever a new rule is recorded.
	private static Map<Block, Block> fullMappingCache = null;

	// Set when the rule book changes; the actual disk write is deferred (see saveIfDirty).
	private static boolean dirty = false;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = DamageBlockSwap.MOD_ID + "_swaps.json";

	private SwapTracker() {
	}

	/** Current version = number of swaps recorded so far. A chunk at this version is up to date. */
	public static int version() {
		return swaps.size();
	}

	/** Adds a new swap rule (block types a and b are now exchanged everywhere). */
	public static void recordSwap(Block a, Block b) {
		swaps.add(new RandomSwapService.BlockPair(a, b));

		// Update the composed permutation used for lookups.
		Block originalOfA = currentToOriginal.getOrDefault(a, a);
		Block originalOfB = currentToOriginal.getOrDefault(b, b);
		originalToCurrent.put(originalOfA, b);
		originalToCurrent.put(originalOfB, a);
		currentToOriginal.put(b, originalOfA);
		currentToOriginal.put(a, originalOfB);

		fullMappingCache = null;
		dirty = true;
	}

	/**
	 * Combines the swaps in the version range {@code [fromVersion, toVersion)} into a single
	 * "type -> new type" map, so a chunk that is behind can catch up in one pass.
	 *
	 * <p>Built in a single pass over the rules (composing the transpositions with a forward/inverse
	 * pair of maps), and the common {@code (0, current)} range is cached — that's what every newly
	 * loaded chunk needs.
	 */
	public static Map<Block, Block> mappingBetween(int fromVersion, int toVersion) {
		int from = Math.max(0, fromVersion);
		int to = Math.min(swaps.size(), toVersion);

		if (from == 0 && to == swaps.size()) {
			if (fullMappingCache == null) {
				fullMappingCache = compose(0, to);
			}
			return fullMappingCache;
		}
		return compose(from, to);
	}

	/** Composes rules {@code [from, to)} into a "type -> new type" map (identity entries dropped). */
	private static Map<Block, Block> compose(int from, int to) {
		Map<Block, Block> forward = new HashMap<>(); // block-as-of-`from` -> block-as-of-`to`
		Map<Block, Block> inverse = new HashMap<>(); // and back again, to update in O(1) per rule
		for (int i = from; i < to; i++) {
			Block a = swaps.get(i).a();
			Block b = swaps.get(i).b();
			Block preA = inverse.getOrDefault(a, a);
			Block preB = inverse.getOrDefault(b, b);
			forward.put(preA, b);
			forward.put(preB, a);
			inverse.put(b, preA);
			inverse.put(a, preB);
		}

		forward.entrySet().removeIf(e -> e.getKey() == e.getValue());
		return forward;
	}

	// --- Lookup helpers for /dbs and the screen ------------------------------------------------

	public static Block currentAppearanceOf(Block original) {
		return originalToCurrent.getOrDefault(original, original);
	}

	public static Block originalOf(Block current) {
		return currentToOriginal.getOrDefault(current, current);
	}

	public static Map<Block, Block> allChanges() {
		Map<Block, Block> changes = new LinkedHashMap<>();
		originalToCurrent.entrySet().stream()
				.filter(e -> e.getKey() != e.getValue())
				.sorted((x, y) -> idOf(x.getKey()).compareTo(idOf(y.getKey())))
				.forEach(e -> changes.put(e.getKey(), e.getValue()));
		return changes;
	}

	/** Forgets the rule book entirely (used on load and once the world is closed). */
	public static void clear() {
		swaps.clear();
		originalToCurrent.clear();
		currentToOriginal.clear();
		fullMappingCache = null;
		dirty = false; // an empty, forgotten rule book must never be written over a world's file
	}

	// --- Persistence: the ordered rule list, in the world folder -------------------------------

	private static Path filePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
	}

	public static void load(MinecraftServer server) {
		clear();
		Path path = filePath(server);
		if (!Files.exists(path)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			List<List<String>> raw = GSON.fromJson(reader, new TypeToken<List<List<String>>>() {
			}.getType());
			if (raw == null) {
				return;
			}
			for (List<String> pair : raw) {
				if (pair == null || pair.size() != 2) {
					continue;
				}
				Block a = blockFromId(pair.get(0));
				Block b = blockFromId(pair.get(1));
				if (a != null && b != null) {
					recordSwap(a, b); // replays into both the list and the lookup maps
				}
			}
			DamageBlockSwap.LOGGER.info("Loaded swap rules: version {}", version());
		} catch (Exception ex) {
			DamageBlockSwap.LOGGER.warn("Failed to load swap rules", ex);
		} finally {
			dirty = false; // just-loaded state is already on disk
		}
	}

	/** Writes to disk only if something changed since the last save (used for periodic saving). */
	public static void saveIfDirty(MinecraftServer server) {
		if (dirty) {
			save(server);
		}
	}

	public static void save(MinecraftServer server) {
		Path path = filePath(server);

		List<List<String>> raw = new ArrayList<>();
		for (RandomSwapService.BlockPair pair : swaps) {
			raw.add(List.of(idOf(pair.a()), idOf(pair.b())));
		}

		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(raw, writer);
			dirty = false;
		} catch (Exception ex) {
			DamageBlockSwap.LOGGER.warn("Failed to save swap rules", ex);
		}
	}

	private static String idOf(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	private static Block blockFromId(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return null;
		}
		return BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
	}
}
