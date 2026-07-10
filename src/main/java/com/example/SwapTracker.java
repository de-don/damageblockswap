package com.example;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	}

	/**
	 * Combines the swaps in the version range {@code [fromVersion, toVersion)} into a single
	 * "type -> new type" map, so a chunk that is behind can catch up in one pass.
	 */
	public static Map<Block, Block> mappingBetween(int fromVersion, int toVersion) {
		int from = Math.max(0, fromVersion);
		int to = Math.min(swaps.size(), toVersion);

		Set<Block> involved = new HashSet<>();
		for (int i = from; i < to; i++) {
			involved.add(swaps.get(i).a());
			involved.add(swaps.get(i).b());
		}

		Map<Block, Block> mapping = new HashMap<>();
		for (Block type : involved) {
			Block result = type;
			for (int i = from; i < to; i++) {
				RandomSwapService.BlockPair pair = swaps.get(i);
				if (result == pair.a()) {
					result = pair.b();
				} else if (result == pair.b()) {
					result = pair.a();
				}
			}
			if (result != type) {
				mapping.put(type, result);
			}
		}
		return mapping;
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

	public static void clear() {
		swaps.clear();
		originalToCurrent.clear();
		currentToOriginal.clear();
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

		Gson gson = new Gson();
		try (Reader reader = Files.newBufferedReader(path)) {
			List<List<String>> raw = gson.fromJson(reader, new TypeToken<List<List<String>>>() {
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
		}
	}

	public static void save(MinecraftServer server) {
		Path path = filePath(server);
		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		List<List<String>> raw = new ArrayList<>();
		for (RandomSwapService.BlockPair pair : swaps) {
			raw.add(List.of(idOf(pair.a()), idOf(pair.b())));
		}

		try (Writer writer = Files.newBufferedWriter(path)) {
			gson.toJson(raw, writer);
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
