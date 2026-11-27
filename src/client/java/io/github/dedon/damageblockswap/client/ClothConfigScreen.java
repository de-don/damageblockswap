package io.github.dedon.damageblockswap.client;

import io.github.dedon.damageblockswap.Config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cloth Config settings screen opened from Mod Menu's "Config" button.
 *
 * <p>This class links against Cloth Config directly, so it must only be loaded when Cloth is
 * installed; {@link ModMenuIntegration} checks that before touching it. Editing values here
 * writes them straight back into the same {@code damageblockswap.json} config file.
 */
final class ClothConfigScreen {

	private ClothConfigScreen() {
	}

	static Screen create(Screen parent) {
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("Damage Block Swap"))
				// When the player clicks "Save", persist the values to disk.
				.setSavingRunnable(Config::save);

		ConfigEntryBuilder entry = builder.entryBuilder();
		ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));

		general.addEntry(entry.startIntSlider(
						Component.literal("Swaps per hit"),
						Config.swapsPerHit, Config.MIN_SWAPS, Config.MAX_SWAPS)
				.setDefaultValue(1)
				.setTooltip(Component.literal("How many random block swaps happen each time you take damage."))
				.setSaveConsumer(value -> Config.swapsPerHit = value)
				.build());

		general.addEntry(entry.startBooleanToggle(
						Component.literal("Swap liquids"), Config.enableLiquids)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Allow water and lava to take part in swaps."))
				.setSaveConsumer(value -> Config.enableLiquids = value)
				.build());

		general.addEntry(entry.startBooleanToggle(
						Component.literal("Swap gravity blocks"), Config.enableGravityBlocks)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Allow sand, gravel and other falling blocks to be swapped."))
				.setSaveConsumer(value -> Config.enableGravityBlocks = value)
				.build());

		general.addEntry(entry.startBooleanToggle(
						Component.literal("Physics (neighbor updates)"), Config.updateNeighbors)
				.setDefaultValue(false)
				.setTooltip(Component.literal("ON: full block updates after swaps - neighbouring blocks react, unsupported ones break and drop. WARNING: can cause severe multi-second lag with many/common blocks. OFF (default) is smooth and leaves neighbours alone (swapped-in sand still falls, water still flows)."))
				.setSaveConsumer(value -> Config.updateNeighbors = value)
				.build());

		general.addEntry(entry.startBooleanToggle(
						Component.literal("Chat messages"), Config.chatMessages)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Announce each swap in the chat."))
				.setSaveConsumer(value -> Config.chatMessages = value)
				.build());

		general.addEntry(entry.startBooleanToggle(
						Component.literal("Console logging"), Config.logging)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Log each swap to the console/log file."))
				.setSaveConsumer(value -> Config.logging = value)
				.build());

		general.addEntry(entry.startBooleanToggle(
						Component.literal("Pick blocks from nearby chunks"), Config.pickFromNearbyChunks)
				.setDefaultValue(true)
				.setTooltip(Component.literal("Choose the swapped blocks from ones that actually exist around you, so hits are always visible."))
				.setSaveConsumer(value -> Config.pickFromNearbyChunks = value)
				.build());

		general.addEntry(entry.startIntSlider(
						Component.literal("Area of effect (chunks)"), Config.swapRadius, 1, Config.MAX_SWAP_RADIUS)
				.setDefaultValue(3)
				.setTooltip(Component.literal("Radius around you (in chunks) that swaps affect. The bubble follows you as you move. Smaller = less lag; larger = more changes at once."))
				.setSaveConsumer(value -> Config.swapRadius = value)
				.build());

		general.addEntry(entry.startIntField(
						Component.literal("Cooldown (ticks)"), Config.cooldownTicks)
				.setDefaultValue(20)
				.setMin(0)
				.setTooltip(Component.literal("Minimum ticks between two swaps caused by the same player (20 ticks = 1 second). 0 = no cooldown."))
				.setSaveConsumer(value -> Config.cooldownTicks = value)
				.build());

		general.addEntry(entry.startStrList(
						Component.literal("Blacklisted blocks"), new ArrayList<>(Config.blacklist))
				.setDefaultValue(new ArrayList<>())
				.setTooltip(Component.literal("Blocks never picked for a new swap: ids or #tags, e.g. minecraft:diamond_ore or #minecraft:logs. The \"minecraft:\" prefix is optional. Swaps that already happened are not affected. Bedrock, portals, barriers, fire, doors, tall plants and similar are always excluded (block tag damageblockswap:never_swap)."))
				.setSaveConsumer(value -> Config.blacklist = value)
				.build());

		general.addEntry(entry.startStrList(
						Component.literal("Damage types that don't swap"), new ArrayList<>(Config.ignoredDamageTypes))
				.setDefaultValue(new ArrayList<>(List.of("#damageblockswap:ignored")))
				.setTooltip(Component.literal("Damage types that do NOT cause a swap: ids or #tags, e.g. minecraft:fall or #minecraft:is_fire. The default tag #damageblockswap:ignored holds drowning, starving, suffocation, cramming, burning, freezing, the void, the world border and /kill. Empty = every damage causes swaps."))
				.setSaveConsumer(value -> Config.ignoredDamageTypes = value)
				.build());

		// Second tab: a read-only view of what has turned into what so far.
		ConfigCategory swapMap = builder.getOrCreateCategory(Component.literal("Swap Map"));
		SwapMapScreen.addSwapEntries(swapMap, entry);

		return builder.build();
	}
}
