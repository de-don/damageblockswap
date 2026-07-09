package com.example;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps track of which chunks are currently loaded, per world.
 *
 * <p>Fabric doesn't hand us a ready-made "all loaded chunks" list, so we build one
 * ourselves by listening to chunk load/unload events. All of these callbacks and the
 * damage handler run on the single server thread, so a plain {@link HashMap} is safe.
 */
public final class ChunkTracker {
	private static final Map<ServerLevel, Set<LevelChunk>> LOADED = new HashMap<>();

	private ChunkTracker() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) ->
				LOADED.computeIfAbsent(level, l -> new HashSet<>()).add(chunk));

		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			Set<LevelChunk> set = LOADED.get(level);
			if (set != null) {
				set.remove(chunk);
			}
		});
	}

	public static Set<LevelChunk> getLoadedChunks(ServerLevel level) {
		return LOADED.getOrDefault(level, Collections.emptySet());
	}
}
