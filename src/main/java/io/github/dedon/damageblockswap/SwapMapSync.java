package io.github.dedon.damageblockswap;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Server side of the swap map sync: sends {@link SwapMapPayload} to every player whose client has the
 * mod, when they join and every time new rules are saved. Clients without the mod never announced the
 * channel, so {@link ServerPlayNetworking#canSend} is false for them and they get nothing. (A vanilla
 * client would only read an unknown payload as {@code DiscardedPayload} and drop it; skipping it saves
 * building and sending a packet nobody reads.)
 *
 * <p>Only rules that are on disk are sent (as announced in chat and applied to chunks), never ones
 * still waiting for a write that failed. Server thread only.
 */
final class SwapMapSync {
	private SwapMapSync() {
	}

	static void register() {
		// Gives the server the codec for the packet. The client announces the channel itself, by
		// registering its receiver (ClientSwapMap); that is what makes canSend true for it.
		PayloadTypeRegistry.clientboundPlay().register(SwapMapPayload.TYPE, SwapMapPayload.CODEC);
		// JOIN fires once the play phase is ready; the client's channels are known by then.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(List.of(handler.player)));
	}

	/** After new rules were saved: the new map to everyone who can read it. */
	static void sendToAll(MinecraftServer server) {
		sendTo(server.getPlayerList().getPlayers());
	}

	private static void sendTo(Collection<ServerPlayer> players) {
		sendTo(players, player -> ServerPlayNetworking.canSend(player, SwapMapPayload.TYPE),
				() -> SwapMapPayload.of(SwapTracker.savedChanges(), BuiltInRegistries.BLOCK::getId),
				ServerPlayNetworking::send);
	}

	/**
	 * Sends the payload to the players that {@code canReceive}; builds it at most once, and not at all
	 * if nobody can receive it. Separate from the Fabric calls so that the {@code canSend} check is
	 * unit-tested ({@code SwapMapSyncTest}).
	 */
	static <P> void sendTo(Collection<P> players, Predicate<P> canReceive, Supplier<SwapMapPayload> build,
			BiConsumer<P, SwapMapPayload> send) {
		SwapMapPayload payload = null;
		for (P player : players) {
			if (!canReceive.test(player)) {
				continue;
			}
			if (payload == null) {
				payload = build.get();
			}
			send.accept(player, payload);
		}
	}
}
