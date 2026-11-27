package io.github.dedon.damageblockswap;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

/**
 * Server side of the swap map sync: sends {@link SwapMapPayload} to every player whose client has the
 * mod, when they join and every time new rules are saved. Clients without the mod never announced the
 * channel, so {@link ServerPlayNetworking#canSend} is false for them and they get nothing (a vanilla
 * client would be disconnected by an unknown payload otherwise).
 *
 * <p>Only rules that are on disk are sent (as announced in chat and applied to chunks), never ones
 * still waiting for a write that failed. Server thread only.
 */
final class SwapMapSync {
	private SwapMapSync() {
	}

	static void register() {
		// Registered on both sides (this runs in the common initializer), so a client with the mod
		// announces that it can receive it.
		PayloadTypeRegistry.clientboundPlay().register(SwapMapPayload.TYPE, SwapMapPayload.CODEC);
		// JOIN fires once the play phase is ready; the client's channels are known by then.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(List.of(handler.player)));
	}

	/** After new rules were saved: the new map to everyone who can read it. */
	static void sendToAll(MinecraftServer server) {
		sendTo(server.getPlayerList().getPlayers());
	}

	private static void sendTo(Collection<ServerPlayer> players) {
		SwapMapPayload payload = null; // built once, and only if someone can receive it
		for (ServerPlayer player : players) {
			if (!ServerPlayNetworking.canSend(player, SwapMapPayload.TYPE)) {
				continue;
			}
			if (payload == null) {
				payload = SwapMapPayload.of(SwapTracker.savedChanges(), BuiltInRegistries.BLOCK::getId);
			}
			ServerPlayNetworking.send(player, payload);
		}
	}
}
