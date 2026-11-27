package io.github.dedon.damageblockswap;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

/**
 * The chat line broadcast to every player (and the server console) when a hit adds a rule. Separate
 * from {@link DamageBlockSwap}, whose static fields need a running Fabric loader, so it can be
 * unit-tested.
 */
final class SwapAnnouncement {
	private SwapAnnouncement() {
	}

	/**
	 * E.g. "Steve got hurt: Stone ↔ Sand". Block names are translatable, so every client shows them
	 * in its own language.
	 */
	static Component of(Component playerName, Block a, Block b) {
		return Component.empty()
				.append(playerName)
				.append(Component.literal(" got hurt: "))
				.append(a.getName())
				.append(Component.literal(" ↔ "))
				.append(b.getName());
	}
}
