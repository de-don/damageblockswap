package io.github.dedon.damageblockswap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A client's copy of the server's swap map, from the last {@link SwapMapPayload} it received.
 *
 * @param compatible whether the server spoke this client's protocol; if not, the other fields are empty
 * @param total      how many block types the server has changed (may be more than {@code changes}
 *                   holds: the packet is capped, and blocks this client doesn't know are dropped)
 * @param changes    original -> current, in the server's order
 */
public record SyncedSwapMap(boolean compatible, int total, Map<Block, Block> changes) {
	public SyncedSwapMap {
		changes = Collections.unmodifiableMap(new LinkedHashMap<>(changes));
	}

	public static SyncedSwapMap from(SwapMapPayload payload) {
		if (!payload.compatible()) {
			return new SyncedSwapMap(false, 0, Map.of());
		}
		return new SyncedSwapMap(true, payload.total(), payload.resolve(SyncedSwapMap::blockById));
	}

	/** The block with this raw id, or null if there is none (DefaultedRegistry.byId would say "air"). */
	static Block blockById(int id) {
		if (id < 0 || id >= BuiltInRegistries.BLOCK.size()) {
			return null;
		}
		return BuiltInRegistries.BLOCK.byId(id);
	}
}
