package io.github.dedon.damageblockswap.client;

import io.github.dedon.damageblockswap.SwapTracker;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.Map;

/**
 * A read-only screen listing every block type that has changed so far ("original → now").
 *
 * <p>Built with Cloth Config's text entries so we get a nice scrollable list for free. It
 * reads {@link SwapTracker} directly, which works in single-player (the integrated server
 * lives in the same process).
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
		Map<Block, Block> changes = SwapTracker.allChanges();
		if (changes.isEmpty()) {
			category.addEntry(entry.startTextDescription(
					Component.literal("No blocks have been swapped yet. Take some damage!")).build());
			return;
		}

		category.addEntry(entry.startTextDescription(
				Component.literal(changes.size() + " block type(s) changed:")).build());
		changes.forEach((original, current) ->
				category.addEntry(entry.startTextDescription(
						original.getName()
								.append(Component.literal(" → "))
								.append(current.getName())).build()));
	}
}
