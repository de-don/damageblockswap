package io.github.dedon.damageblockswap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Brings chunks up to date with the swap rule book ({@link SwapTracker}), a little each tick.
 *
 * <p>Each chunk remembers which rule <b>version</b> it has been transformed to (a persistent
 * attachment). When a chunk is behind — because a new swap happened, or because it just loaded
 * for the first time / after exploring — it is added to a queue, closest to a player first. Every
 * tick we apply a bounded number of block changes from that queue, so the world stays consistent
 * everywhere without ever freezing.
 *
 * <p>A chunk can take several ticks. Its progress is saved with it after every slice
 * ({@link ChunkJob}, {@link SwapProgress}), so if it is unloaded or saved half-way it resumes later
 * instead of having the first half swapped twice. A chunk is only worked on while it and all
 * 8 neighbours are fully loaded: placing a block reads (and may update) blocks across the chunk
 * edge, and touching a chunk that isn't loaded makes the server load or even generate it on the
 * spot, which then loads its neighbours, and so on (the review's "cascading loads").
 */
public final class WorldSwapService {
	// Per-tick work limits. Changing a block (especially with physics) is the costly part.
	private static final int CHANGES_PER_TICK_PHYSICS = 6_000;
	private static final int CHANGES_PER_TICK_NO_PHYSICS = 40_000;
	// Hard wall-clock cap: never spend more than this on swaps in one tick, whatever the cost.
	private static final long MAX_NANOS_PER_TICK = 8_000_000L; // 8 ms
	// Re-sorting the queue costs O(n * players + n log n). While players keep crossing chunk borders,
	// it is done at most this often (in ticks); chunks added in between get a fresh priority anyway.
	private static final int REPRIORITIZE_INTERVAL_TICKS = 20; // 1 second

	/**
	 * setBlock flags without physics: send to clients, but don't update the shape of neighbouring
	 * blocks (a torch on a block that became water would otherwise pop off), and never drop items.
	 * Note this doesn't stop the placed block's own {@code onPlace} (sand still falls, water still
	 * flows), see docs/decisions.md [3/5].
	 */
	static final int NO_PHYSICS_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	// Queue priority of chunks in a dimension without players: after every chunk near a player.
	private static final long NO_PLAYER_NEARBY = Long.MAX_VALUE;

	/** One chunk that still needs updating. */
	private record ChunkRef(ServerLevel level, ChunkPos pos) {
	}

	/**
	 * A chunk in a specific dimension. Chunk coordinates alone are ambiguous: after a portal or
	 * {@code /execute in} teleport a player can land on the same (x, z) in another dimension.
	 */
	record DimensionChunk(ResourceKey<Level> dimension, ChunkPos pos) {
	}

	// De-duplicated chunks waiting to be updated, closest to a player first.
	private final ChunkQueue<ChunkRef> queue = new ChunkQueue<>(this::priorityOf);

	// Last chunk (and dimension) each player was in, so we notice when they cross into a new one.
	private final Map<UUID, DimensionChunk> lastPlayerChunk = new HashMap<>();
	// Chunks players are in, per level, for the queue priority. Rebuilt when someone changes chunk.
	private Map<ServerLevel, List<ChunkPos>> playerChunks = Map.of();
	// The queue's priorities are older than playerChunks; re-sorted once the interval has passed.
	private boolean prioritiesStale;
	private int ticksSinceReprioritize = REPRIORITIZE_INTERVAL_TICKS;

	// Loaded chunks that are behind but were skipped because a neighbour isn't loaded; they are queued
	// again when one loads. Only for the log ("done" vs "waiting for neighbours").
	private final Set<ChunkRef> waitingForNeighbours = new HashSet<>();

	private final TickBudget budget = new TickBudget();
	private int placeFlags = NO_PHYSICS_FLAGS;
	// Gametests only: block changes per tick instead of the normal budget (0 pauses), or -1.
	private int changesPerTickOverride = -1;

	// The chunk currently being worked on (may span several ticks).
	private ServerLevel curLevel;
	private LevelChunk curChunk;
	private ChunkJob<Block> curJob;

	// Diagnostics: how much a still-draining morph has done, so we can log when it finishes.
	private long changedSinceIdle;
	private int ticksSinceIdle;

	public void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			trackPlayerMovement(server); // as players cross chunks, catch up the area around them
			pump();
		});
		// New terrain (or a chunk reloaded after exploring) catches up here.
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> onChunkBecameFull(level, chunk));
		// CHUNK_LOAD only fires the first time a chunk object becomes FULL. A chunk that drops below
		// FULL (the player walked a bit away) and comes back without being unloaded only shows up here
		// (docs/decisions.md, [3/5 review-fix]). The queue is de-duplicated, so a first load reported
		// by both events is queued once.
		ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, oldStatus, newStatus) -> {
			if (!oldStatus.isOrAfter(FullChunkStatus.FULL) && newStatus.isOrAfter(FullChunkStatus.FULL)) {
				onChunkBecameFull(level, chunk);
			}
		});
		ServerChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
		// Forget a player's tracked position when they leave.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> lastPlayerChunk.remove(handler.player.getUUID()));
	}

	/** Refreshes the area around the player right after a hit, so the swap shows immediately. */
	public void onHit(ServerLevel level, int centerChunkX, int centerChunkZ) {
		refreshAround(level, centerChunkX, centerChunkZ, Config.swapRadius);
	}

	/**
	 * When a player moves into a new chunk, re-check the chunks around them (within the configured
	 * radius) and update any that are behind. This spreads the work naturally over movement, and the
	 * affected "bubble" follows the player.
	 */
	private void trackPlayerMovement(MinecraftServer server) {
		Map<ServerLevel, List<ChunkPos>> nowIn = new HashMap<>();
		List<ServerPlayer> moved = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player.level() instanceof ServerLevel level)) {
				continue;
			}
			BlockPos pos = player.blockPosition();
			ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
			nowIn.computeIfAbsent(level, l -> new ArrayList<>()).add(chunkPos);
			DimensionChunk current = new DimensionChunk(level.dimension(), chunkPos);
			if (!current.equals(lastPlayerChunk.put(player.getUUID(), current))) {
				moved.add(player);
			}
		}
		// Re-sort the queue only when someone actually changed chunk (or joined / left), and at most
		// once per interval: with many players someone crosses a chunk border nearly every tick.
		if (!nowIn.equals(playerChunks)) {
			playerChunks = nowIn;
			prioritiesStale = true;
		}
		if (ticksSinceReprioritize < REPRIORITIZE_INTERVAL_TICKS) {
			ticksSinceReprioritize++;
		}
		if (prioritiesStale && ticksSinceReprioritize >= REPRIORITIZE_INTERVAL_TICKS) {
			queue.reprioritize();
			prioritiesStale = false;
			ticksSinceReprioritize = 0;
		}
		for (ServerPlayer player : moved) {
			DimensionChunk at = lastPlayerChunk.get(player.getUUID());
			refreshAround((ServerLevel) player.level(), at.pos().x(), at.pos().z(), Config.swapRadius);
		}
	}

	/** Chessboard distance (in chunks) from the chunk to the nearest player in its level. */
	private long priorityOf(ChunkRef ref) {
		List<ChunkPos> players = playerChunks.get(ref.level());
		if (players == null) {
			return NO_PLAYER_NEARBY;
		}
		long best = NO_PLAYER_NEARBY;
		for (ChunkPos p : players) {
			best = Math.min(best, Math.max(Math.abs(p.x() - ref.pos().x()), Math.abs(p.z() - ref.pos().z())));
		}
		return best;
	}

	private void refreshAround(ServerLevel level, int centerChunkX, int centerChunkZ, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
				if (chunk != null) {
					enqueueIfBehind(level, chunk);
				}
			}
		}
	}

	/**
	 * A chunk became fully loaded (first load, or back to FULL without having been unloaded): queue it
	 * if it is behind, and its loaded neighbours too, since a chunk is only worked on once all its
	 * neighbours are loaded and this may have been the missing one.
	 */
	private void onChunkBecameFull(ServerLevel level, LevelChunk chunk) {
		MinecraftServer server = level.getServer();
		if (!server.isSameThread()) {
			// Mods that load chunks off the server thread: our state is server-thread only.
			server.execute(() -> onChunkBecameFull(level, chunk));
			return;
		}
		enqueueIfBehind(level, chunk);
		ChunkPos pos = chunk.getPos();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				LevelChunk neighbour = level.getChunkSource().getChunkNow(pos.x() + dx, pos.z() + dz);
				if (neighbour != null) {
					enqueueIfBehind(level, neighbour);
				}
			}
		}
	}

	/**
	 * A chunk is about to be saved and unloaded. Its progress is already saved with it (after every
	 * slice), so there is nothing to finish: just stop touching it. When it loads again it resumes.
	 */
	private void onChunkUnload(ServerLevel level, LevelChunk chunk) {
		MinecraftServer server = level.getServer();
		if (!server.isSameThread()) {
			server.execute(() -> onChunkUnload(level, chunk));
			return;
		}
		if (chunk == curChunk) {
			dropCurrent();
		}
		// Events moved over from another thread can arrive out of order: the next chunk object at this
		// position may already be loaded and queued. Only forget the entry if it isn't that one's.
		ChunkPos pos = chunk.getPos();
		LevelChunk live = level.getChunkSource().getChunkNow(pos.x(), pos.z());
		if (live == null || live == chunk) {
			ChunkRef ref = new ChunkRef(level, pos);
			queue.remove(ref);
			waitingForNeighbours.remove(ref);
		}
	}

	/** Drops all pending work — called when the world closes so nothing leaks into the next one. */
	public void reset() {
		queue.clear();
		waitingForNeighbours.clear();
		lastPlayerChunk.clear();
		playerChunks = Map.of();
		prioritiesStale = false;
		ticksSinceReprioritize = REPRIORITIZE_INTERVAL_TICKS;
		// Drop every reference into the old world, so a closed world isn't kept alive in memory.
		// Nothing is lost: a chunk's progress is saved with it after every slice.
		dropCurrent();
		changedSinceIdle = 0;
		ticksSinceIdle = 0;
	}

	/**
	 * Re-checks the area around every player on the next tick (used once rules that could not be
	 * saved earlier finally are, since only saved rules are applied to chunks).
	 */
	public void refreshAroundAllPlayers() {
		lastPlayerChunk.clear();
	}

	/** How many chunks are waiting to be updated (for diagnostics). */
	public int queueSize() {
		return queue.size();
	}

	/** Queues the chunk if it is behind the saved rules. Package-private for the gametests. */
	void enqueueIfBehind(ServerLevel level, LevelChunk chunk) {
		// Quarantined chunks (stamped by another journal) have no work and are never queued.
		if (SwapTracker.planChunk(chunk).hasWork()) {
			queue.add(new ChunkRef(level, chunk.getPos()));
		}
	}

	private void pump() {
		if (queue.isEmpty() && curJob == null) {
			return; // idle
		}

		boolean physics = Config.updateNeighbors;
		int changes = changesPerTickOverride >= 0 ? changesPerTickOverride
				: physics ? CHANGES_PER_TICK_PHYSICS : CHANGES_PER_TICK_NO_PHYSICS;
		if (changes == 0) {
			return; // paused by a gametest
		}
		budget.reset(changes, MAX_NANOS_PER_TICK);
		placeFlags = physics ? Block.UPDATE_ALL : NO_PHYSICS_FLAGS;
		ticksSinceIdle++;

		while (budget.hasRoom()) {
			if (curJob == null && !startNextChunk()) {
				break; // nothing left to do, or the budget ran out while looking
			}
			if (!isLive(curLevel, curChunk)) {
				dropCurrent(); // unloaded: its progress is saved, it resumes when it loads again
				continue;
			}
			if (!neighboursLoaded(curLevel, curChunk.getPos())) {
				// A neighbour went away between ticks. Queued again when it (or another) loads, or when
				// a player comes near.
				waitingForNeighbours.add(new ChunkRef(curLevel, curChunk.getPos()));
				dropCurrent();
				continue;
			}
			if (curJob.run(budget)) {
				ServerLevel level = curLevel;
				LevelChunk chunk = curChunk;
				dropCurrent();
				waitingForNeighbours.remove(new ChunkRef(level, chunk.getPos()));
				enqueueIfBehind(level, chunk); // rules saved since this pass started
			}
		}

		// Log once when the queue drains, so we can see how long/heavy a morph was.
		if (queue.isEmpty() && curJob == null) {
			if (Config.logging && changedSinceIdle > 0) {
				if (waitingForNeighbours.isEmpty()) {
					DamageBlockSwap.LOGGER.info("Swap morph done: {} blocks over {} ticks.",
							changedSinceIdle, ticksSinceIdle);
				} else {
					DamageBlockSwap.LOGGER.info("Swap queue drained: {} blocks over {} ticks; {} loaded chunks"
									+ " catch up once their neighbours load.",
							changedSinceIdle, ticksSinceIdle, waitingForNeighbours.size());
				}
			}
			changedSinceIdle = 0;
			ticksSinceIdle = 0;
		}
	}

	/**
	 * Picks the next chunk that actually needs work. Returns false if the queue is exhausted or the
	 * tick's budget ran out while skipping chunks that can't be worked on (each costs a few lookups).
	 */
	private boolean startNextChunk() {
		ChunkRef ref;
		while (budget.hasRoom() && (ref = queue.poll()) != null) {
			LevelChunk chunk = ref.level().getChunkSource().getChunkNow(ref.pos().x(), ref.pos().z());
			if (chunk == null || !isLive(ref.level(), chunk)) {
				waitingForNeighbours.remove(ref);
				continue; // unloaded since it was queued; queued again when it loads
			}
			if (!neighboursLoaded(ref.level(), ref.pos())) {
				waitingForNeighbours.add(ref);
				continue; // queued again when a neighbour loads, see onChunkBecameFull
			}
			ChunkJob.Plan plan = SwapTracker.planChunk(chunk);
			if (!plan.hasWork()) {
				waitingForNeighbours.remove(ref);
				continue; // quarantined, or already up to date
			}
			curLevel = ref.level();
			curChunk = chunk;
			curJob = SwapTracker.startJob(new LoadedChunkData(ref.level(), chunk, () -> placeFlags), plan);
			return true;
		}
		return false;
	}

	/**
	 * Whether {@code chunk} may be worked on: it is still the live, fully loaded chunk at its position
	 * and all 8 neighbours are fully loaded too. Package-private for the gametests.
	 */
	static boolean isWorkable(ServerLevel level, LevelChunk chunk) {
		return isLive(level, chunk) && neighboursLoaded(level, chunk.getPos());
	}

	/**
	 * Whether {@code chunk} is still the live, fully loaded chunk at its position. {@code getChunkNow}
	 * only returns FULL chunks and never loads anything; the identity check catches a chunk that was
	 * unloaded and loaded again (a new object), the status check one that is on its way out.
	 */
	private static boolean isLive(ServerLevel level, LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		return level.getChunkSource().getChunkNow(pos.x(), pos.z()) == chunk
				&& chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL);
	}

	private static boolean neighboursLoaded(ServerLevel level, ChunkPos pos) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if ((dx != 0 || dz != 0) && level.getChunkSource().getChunkNow(pos.x() + dx, pos.z() + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private void dropCurrent() {
		curLevel = null;
		curChunk = null;
		curJob = null;
	}

	// --- Gametest hooks (package-private; the gametests in src/gametest share this package) --------

	/** Block changes per tick instead of the normal budget; 0 pauses the pump, -1 restores it. */
	void setChangesPerTickForTesting(int changes) {
		changesPerTickOverride = changes;
	}

	/** Whether {@code chunk} is the chunk whose pass is in progress. */
	boolean isWorkingOn(LevelChunk chunk) {
		return curChunk == chunk;
	}

	boolean isQueued(ServerLevel level, ChunkPos pos) {
		return queue.contains(new ChunkRef(level, pos));
	}

	/** The per-tick change budget and wall-clock deadline. */
	private final class TickBudget implements ChunkJob.Budget {
		private int changesLeft;
		private long deadlineNanos;

		void reset(int changes, long nanos) {
			changesLeft = changes;
			deadlineNanos = System.nanoTime() + nanos;
		}

		@Override
		public boolean hasRoom() {
			return changesLeft > 0 && System.nanoTime() - deadlineNanos < 0;
		}

		@Override
		public void changed() {
			changesLeft--;
			changedSinceIdle++;
		}
	}
}
