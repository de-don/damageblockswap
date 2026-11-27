package io.github.dedon.damageblockswap.client;

import io.github.dedon.damageblockswap.SwapMapPayload;
import io.github.dedon.damageblockswap.SyncedSwapMap;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The client's copy of the server's swap map: the last {@link SwapMapPayload} received on the
 * current connection, or null if none came (server without the mod). Forgotten when a connection
 * starts or ends, so a map from one server is never shown on another.
 */
final class ClientSwapMap {
	private static volatile SyncedSwapMap current = null;

	private ClientSwapMap() {
	}

	static void register() {
		// Fabric runs play payload handlers on the client thread.
		ClientPlayNetworking.registerGlobalReceiver(SwapMapPayload.TYPE,
				(payload, context) -> current = SyncedSwapMap.from(payload));
		ClientPlayConnectionEvents.INIT.register((handler, client) -> current = null);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> current = null);
	}

	/** The last map the server sent on this connection, or null. */
	static SyncedSwapMap get() {
		return current;
	}
}
