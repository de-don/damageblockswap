package io.github.dedon.damageblockswap.client;

import io.github.dedon.damageblockswap.Config;
import io.github.dedon.damageblockswap.DamageTypeCatalog;
import io.github.dedon.damageblockswap.DamageTypeToggles;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
				.setTitle(Component.literal("Damage Block Swap"));
		// The saving runnable (persist to disk on "Save") is set below, with the damage type checkboxes.

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
				.setDefaultValue(new ArrayList<>(Config.DEFAULT_BLACKLIST))
				.setTooltip(Component.literal("Blocks never picked for a new swap: ids or #tags, e.g. minecraft:diamond_ore or #minecraft:logs. The \"minecraft:\" prefix is optional. Swaps that already happened are not affected. Bedrock, portals, barriers, fire, doors, tall plants and similar are always excluded (block tag damageblockswap:never_swap)."))
				.setSaveConsumer(Config::setBlacklist)
				.build());

		general.addEntry(entry.startStrList(
						Component.literal("Damage types that don't swap"), new ArrayList<>(Config.ignoredDamageTypes))
				.setDefaultValue(new ArrayList<>(Config.DEFAULT_IGNORED_DAMAGE_TYPES))
				.setTooltip(Component.literal("Damage types that do NOT cause a swap: ids or #tags, e.g. minecraft:fall or #minecraft:is_fire. The default list (" + String.join(", ", Config.DEFAULT_IGNORED_DAMAGE_TYPES) + ") holds drowning, starving, suffocation, cramming, burning, freezing, the void, the world border and /kill. Empty = every damage causes swaps. In single player the switches below edit this list."))
				.setSaveConsumer(Config::setIgnoredDamageTypes)
				.build());

		// The checkboxes are saved after the list above (Cloth saves the entries in order, then runs
		// the saving runnable), so they are applied on top of whatever was typed into the list.
		Map<String, Boolean> switched = new HashMap<>();
		DamageTypeToggles toggles = liveDamageTypes();
		general.addEntry(damageTypeSection(entry, toggles, switched));
		builder.setSavingRunnable(() -> {
			if (toggles != null && !switched.isEmpty()) {
				Config.setIgnoredDamageTypes(toggles.apply(Config.ignoredDamageTypes, switched));
			}
			Config.save();
		});

		// Second tab: a read-only view of what has turned into what so far.
		ConfigCategory swapMap = builder.getOrCreateCategory(Component.literal("Swap Map"));
		SwapMapScreen.addSwapEntries(swapMap, entry);

		return builder.build();
	}

	/**
	 * Damage types and tags of the single player world the player is in (the built-in server's
	 * registry as synced to the client: datapack and mod types included), or null when there is no
	 * such world. On the title screen no registry exists. On a dedicated server or as a LAN guest the
	 * registry and tags are the remote server's, while this screen saves the local config, which only
	 * single player reads: the switches would show and write another server's view (see decisions.md).
	 */
	private static @Nullable DamageTypeToggles liveDamageTypes() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null || !minecraft.hasSingleplayerServer()) {
			return null;
		}
		return DamageTypeCatalog.toggles(connection.registryAccess());
	}

	/** True when connected to a server that is not this game's built-in one (dedicated or LAN guest). */
	private static boolean onRemoteServer() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getConnection() != null && !minecraft.hasSingleplayerServer();
	}

	/**
	 * Collapsible "causes swaps" checkbox per damage type. A switched checkbox is recorded in
	 * {@code switched} (type id -> wanted value) and applied to the list by the saving runnable.
	 */
	private static AbstractConfigListEntry<?> damageTypeSection(ConfigEntryBuilder entry, @Nullable DamageTypeToggles toggles,
			Map<String, Boolean> switched) {
		SubCategoryBuilder section = entry.startSubCategory(Component.literal("Damage types that cause swaps"))
				.setExpanded(false)
				.setTooltip(Component.literal("One switch per damage type of this world. \"Swaps\" = getting hurt this way adds a rule. Switching edits the list above: off adds the type, on removes it (a #tag holding it is replaced by its other types). If you also edit the list by hand before saving, the switch wins for its type."));
		if (toggles == null) {
			String note = onRemoteServer()
					? "You are on a server: its own config decides which damage types swap, and these settings only change your single player games. The switches are available in single player. The list above still edits your single player settings."
					: "Open the settings from the pause menu in a single player world to switch damage types one by one. The list above works everywhere.";
			section.add(entry.startTextDescription(Component.literal(note)).build());
			return section.build();
		}
		List<String> current = Config.ignoredDamageTypes;
		for (String id : toggles.typeIds()) {
			boolean shown = toggles.causesSwaps(current, id);
			section.add(entry.startBooleanToggle(Component.literal(label(id)), shown)
					.setDefaultValue(toggles.causesSwaps(Config.DEFAULT_IGNORED_DAMAGE_TYPES, id))
					.setYesNoTextSupplier(swaps -> Component.literal(swaps ? "Swaps" : "No swap"))
					.setTooltip(Component.literal(id))
					.setSaveConsumer(value -> {
						if (value == shown) {
							switched.remove(id);
						} else {
							switched.put(id, value);
						}
					})
					.build());
		}
		return section.build();
	}

	/** {@code minecraft:drown} -> {@code drown}; other namespaces keep the full id. */
	private static String label(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}
}
