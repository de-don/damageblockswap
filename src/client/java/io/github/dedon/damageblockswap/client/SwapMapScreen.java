package io.github.dedon.damageblockswap.client;

import io.github.dedon.damageblockswap.SwapMapLines;
import io.github.dedon.damageblockswap.SwapTracker;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A read-only screen listing every block type that has changed so far ("original → now").
 *
 * <p>Built with Cloth Config's text entries so we get a nice scrollable list for free. It
 * reads {@link SwapTracker} directly, which only works where the integrated server lives in the
 * same process (single-player, LAN host); elsewhere it says so and points to {@code /dbs list}.
 */
public final class SwapMapScreen {

	private SwapMapScreen() {
	}

	public static Screen create(Screen parent) {
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("Damage Block Swap — Swap Map"));

		ConfigEntryBuilder entry = builder.entryBuilder();
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("Swaps"));
		addSwapEntries(category, entry);

		return builder.build();
	}

	/**
	 * Fills a category with the current swap map as read-only lines. Shared by the standalone
	 * screen and the "Swap Map" tab inside the Mod Menu config screen.
	 */
	public static void addSwapEntries(ConfigCategory category, ConfigEntryBuilder entry) {
		// Only a client that runs the world itself (single-player, LAN host) has the rule book; on a
		// dedicated server or as a LAN guest SwapTracker is empty, see SwapMapLines.
		boolean hostsWorld = Minecraft.getInstance().getSingleplayerServer() != null;
		for (Component line : SwapMapLines.of(hostsWorld, SwapTracker.allChanges())) {
			category.addEntry(entry.startTextDescription(line).build());
		}
	}
}
