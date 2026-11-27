package io.github.dedon.damageblockswap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server half of the swap map sync on a real server: what is sent is the saved rule book, it
 * survives the real codec, and a player whose client never announced the channel (like a vanilla
 * client) is skipped instead of being sent an unknown packet. Adds no rules to the shared test world.
 */
public class SwapMapSyncGameTest {
	@GameTest
	@SuppressWarnings("removal") // makeMockServerPlayerInLevel: the only ready-made ServerPlayer with a connection
	public void serverSendsTheSavedMapOnlyToClientsWithTheMod(GameTestHelper helper) {
		helper.assertTrue(SwapTracker.flush(), "the test journal could not be saved");
		helper.assertTrue(SwapTracker.savedChanges().equals(SwapTracker.allChanges()),
				"with everything on disk, the synced map differs from the server's own");

		SwapMapPayload payload = SwapMapPayload.of(SwapTracker.savedChanges(), BuiltInRegistries.BLOCK::getId);
		ByteBuf buf = Unpooled.buffer();
		SwapMapPayload.CODEC.encode(buf, payload);
		SyncedSwapMap received = SyncedSwapMap.from(SwapMapPayload.CODEC.decode(buf));
		helper.assertTrue(received.compatible() && received.total() == SwapTracker.allChanges().size()
				&& received.changes().equals(SwapTracker.allChanges()), "the map changed on the way to the client");

		// A mock player has no mod on the other end: no channel, so nothing may be sent to it. Joining
		// also runs SwapMapSync's JOIN listener for it.
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		try {
			helper.assertFalse(ServerPlayNetworking.canSend(player, SwapMapPayload.TYPE),
					"a client that never registered the channel counts as able to receive it");
			SwapMapSync.sendToAll(server); // must not throw or disconnect anyone
			helper.assertFalse(player.hasDisconnected(), "the client without the mod was disconnected");
		} finally {
			// Don't leave a player in the list for the rest of the run: it would load chunks around
			// itself and reorder the chunk queue of the other gametests.
			server.getPlayerList().remove(player);
		}
		helper.succeed();
	}
}
