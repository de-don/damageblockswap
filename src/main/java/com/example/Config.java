package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple JSON config, stored at {@code config/damageblockswap.json}.
 *
 * <p>Values are read once at startup. If the file is missing it is created with defaults.
 * Keeping the fields {@code static} makes them easy to read from anywhere in the mod.
 */
public final class Config {
	public static boolean chatMessages = true;
	public static boolean logging = true;
	public static boolean enableLiquids = true;
	public static boolean enableGravityBlocks = true;
	public static int cooldownTicks = 0;
	public static List<String> blacklist = new ArrayList<>();

	private Config() {
	}

	/** Plain data holder that Gson serializes to/from JSON. */
	private static final class Data {
		boolean chatMessages = true;
		boolean logging = true;
		boolean enableLiquids = true;
		boolean enableGravityBlocks = true;
		int cooldownTicks = 0;
		List<String> blacklist = new ArrayList<>();
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(DamageBlockSwap.MOD_ID + ".json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();

		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path)) {
					Data data = gson.fromJson(reader, Data.class);
					if (data != null) {
						apply(data);
					}
				}
			} else {
				// First run: write a default config so the player can edit it later.
				Files.createDirectories(path.getParent());
				try (Writer writer = Files.newBufferedWriter(path)) {
					gson.toJson(new Data(), writer);
				}
			}
		} catch (Exception e) {
			DamageBlockSwap.LOGGER.warn("Failed to load config, using defaults", e);
		}
	}

	private static void apply(Data d) {
		chatMessages = d.chatMessages;
		logging = d.logging;
		enableLiquids = d.enableLiquids;
		enableGravityBlocks = d.enableGravityBlocks;
		cooldownTicks = d.cooldownTicks;
		blacklist = d.blacklist != null ? d.blacklist : new ArrayList<>();
	}
}
