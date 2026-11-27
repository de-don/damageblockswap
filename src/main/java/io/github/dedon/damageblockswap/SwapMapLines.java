package io.github.dedon.damageblockswap;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The text lines of the swap map screen (and the "Swap Map" tab in Mod Menu), and the choice of where
 * they come from ({@link #source}).
 *
 * <p>A client that runs the world itself (single-player, LAN host) reads the rule book straight from
 * {@link SwapTracker}. Any other client only knows what the server sent it ({@link SyncedSwapMap},
 * see {@link SwapMapPayload}); its local {@link SwapTracker} is empty or stale and is never shown. A
 * server without the mod (or with a version before the sync) sends nothing, and the screen says so
 * instead of claiming "no blocks have been swapped". Lives in the common source set, free of client
 * classes, so it can be unit-tested.
 */
public final class SwapMapLines {
	static final String UNAVAILABLE = "This server has not sent its swap map: it runs without Damage Block"
			+ " Swap or with a version older than 1.1.0. If it has the mod, use /dbs list to see its swaps.";
	static final String INCOMPATIBLE = "This server runs a version of Damage Block Swap whose swap map this"
			+ " client can't read. Use /dbs list to see its swaps.";
	static final String EMPTY = "No blocks have been swapped yet. Take some damage!";

	/** Where the swap map screen takes its lines from. */
	public enum Source {
		/** This client runs the world: the local rule book. */
		LOCAL,
		/** The copy the server sent. */
		SYNCED,
		/** The server sent a map in a protocol this client doesn't know. */
		INCOMPATIBLE,
		/** The server sent nothing (no mod there, or an older one). */
		UNAVAILABLE
	}

	private SwapMapLines() {
	}

	/**
	 * @param hostsWorld whether the world runs in this process (the client has an integrated server)
	 * @param synced     the last map the server sent, or null if none arrived on this connection
	 */
	public static Source source(boolean hostsWorld, SyncedSwapMap synced) {
		if (hostsWorld) {
			return Source.LOCAL;
		}
		if (synced == null) {
			return Source.UNAVAILABLE;
		}
		return synced.compatible() ? Source.SYNCED : Source.INCOMPATIBLE;
	}

	/**
	 * @param hostsWorld whether the world runs in this process (the client has an integrated server)
	 * @param local      the local rule book as {@link SwapTracker#allChanges()} returns it
	 * @param synced     the last map the server sent, or null if none arrived on this connection
	 */
	public static List<Component> of(boolean hostsWorld, Map<Block, Block> local, SyncedSwapMap synced) {
		return switch (source(hostsWorld, synced)) {
			case LOCAL -> lines(local, local.size());
			case SYNCED -> lines(synced.changes(), synced.total());
			case INCOMPATIBLE -> List.of(Component.literal(INCOMPATIBLE));
			case UNAVAILABLE -> List.of(Component.literal(UNAVAILABLE));
		};
	}

	private static List<Component> lines(Map<Block, Block> changes, int total) {
		if (total == 0) {
			return List.of(Component.literal(EMPTY));
		}
		List<Component> lines = new ArrayList<>(changes.size() + 2);
		lines.add(Component.literal(total + " block type(s) changed:"));
		changes.forEach((original, current) -> lines.add(original.getName()
				.append(Component.literal(" → "))
				.append(current.getName())));
		int hidden = total - changes.size();
		if (hidden > 0) {
			// The packet was capped, or the server has blocks this client doesn't know.
			lines.add(Component.literal("...and " + hidden + " more not shown here. Use /dbs list to see them."));
		}
		return lines;
	}
}
