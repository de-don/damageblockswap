package io.github.dedon.damageblockswap.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-side setup: a key binding that opens the "swap map" screen.
 *
 * <p>The key is unbound by default (to avoid clashing with anything); the player assigns it
 * in Options → Controls → "Open Swap Map". The screen itself is drawn by Cloth Config, so if
 * Cloth isn't installed we just point the player at the {@code /dbs list} command instead.
 */
public class DamageBlockSwapClient implements ClientModInitializer {
	private static final KeyMapping OPEN_MAP_KEY = new KeyMapping(
			"key.damageblockswap.swapmap",
			-1, // -1 = unbound by default
			KeyMapping.Category.MISC);

	@Override
	public void onInitializeClient() {
		KeyMappingHelper.registerKeyMapping(OPEN_MAP_KEY);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_MAP_KEY.consumeClick()) {
				openSwapMap(client);
			}
		});
	}

	private void openSwapMap(Minecraft client) {
		// Referencing SwapMapScreen only after this guard keeps Cloth's classes from being
		// loaded when Cloth isn't present (which would otherwise throw).
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			if (client.player != null) {
				client.player.sendSystemMessage(Component.literal(
						"Install Cloth Config to open the swap map screen, or use /dbs list."));
			}
			return;
		}
		client.setScreen(SwapMapScreen.create(client.screen));
	}
}
