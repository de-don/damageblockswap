package io.github.dedon.damageblockswap;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The text lines of the swap map screen (and the "Swap Map" tab in Mod Menu).
 *
 * <p>The client reads the rule book straight from {@link SwapTracker}, which only holds this world's
 * rules when the server runs in the same process: single-player, or the player hosting a LAN game.
 * On a dedicated server, or as a LAN guest, it is empty however many rules the server has, so the
 * screen must not claim "no blocks have been swapped". Syncing the rules to clients is a later plan
 * item (docs/decisions.md). Lives in the common source set, free of client classes, so it can be
 * unit-tested.
 */
public final class SwapMapLines {
	static final String UNAVAILABLE = "The swap map is only available in single-player or to the player"
			+ " hosting a LAN world. On this server, use /dbs list to see the swaps.";
	static final String EMPTY = "No blocks have been swapped yet. Take some damage!";

	private SwapMapLines() {
	}

	/**
	 * @param hostsWorld whether the world runs in this process (the client has an integrated server)
	 * @param changes    the rule book as {@link SwapTracker#allChanges()} returns it
	 */
	public static List<Component> of(boolean hostsWorld, Map<Block, Block> changes) {
		if (!hostsWorld) {
			return List.of(Component.literal(UNAVAILABLE));
		}
		if (changes.isEmpty()) {
			return List.of(Component.literal(EMPTY));
		}
		List<Component> lines = new ArrayList<>(changes.size() + 1);
		lines.add(Component.literal(changes.size() + " block type(s) changed:"));
		changes.forEach((original, current) -> lines.add(original.getName()
				.append(Component.literal(" → "))
				.append(current.getName())));
		return lines;
	}
}
