package io.github.dedon.damageblockswap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
	private Locale originalLocale;
	// Config is global static state: every test puts it back so other test classes don't depend on the order.
	private int swapsPerHit;
	private boolean chatMessages;
	private boolean logging;
	private boolean enableLiquids;
	private boolean enableGravityBlocks;
	private int cooldownTicks;
	private boolean updateNeighbors;
	private boolean pickFromNearbyChunks;
	private int swapRadius;
	private List<String> blacklist;
	private List<String> ignoredDamageTypes;
	private int generation;

	@BeforeEach
	void rememberGlobals() {
		originalLocale = Locale.getDefault();
		swapsPerHit = Config.swapsPerHit;
		chatMessages = Config.chatMessages;
		logging = Config.logging;
		enableLiquids = Config.enableLiquids;
		enableGravityBlocks = Config.enableGravityBlocks;
		cooldownTicks = Config.cooldownTicks;
		updateNeighbors = Config.updateNeighbors;
		pickFromNearbyChunks = Config.pickFromNearbyChunks;
		swapRadius = Config.swapRadius;
		blacklist = Config.blacklist;
		ignoredDamageTypes = Config.ignoredDamageTypes;
		generation = Config.generation;
	}

	@AfterEach
	void restoreGlobals() {
		Locale.setDefault(originalLocale);
		Config.swapsPerHit = swapsPerHit;
		Config.chatMessages = chatMessages;
		Config.logging = logging;
		Config.enableLiquids = enableLiquids;
		Config.enableGravityBlocks = enableGravityBlocks;
		Config.cooldownTicks = cooldownTicks;
		Config.updateNeighbors = updateNeighbors;
		Config.pickFromNearbyChunks = pickFromNearbyChunks;
		Config.swapRadius = swapRadius;
		Config.blacklist = blacklist;
		Config.ignoredDamageTypes = ignoredDamageTypes;
		Config.generation = generation + 1; // still "changed", so no cache keeps what a test loaded
	}

	@Test
	void blacklistLowercasingIgnoresTurkishDefaultLocale() {
		// In tr-TR, "I".toLowerCase() is the dotless "ı", which would break "minecraft:iron_ore".
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));

		List<String> result = Config.normalizeBlacklist(List.of("MINECRAFT:IRON_ORE", "DIAMOND_ORE"));

		assertEquals(List.of("minecraft:iron_ore", "minecraft:diamond_ore"), result);
	}

	@Test
	void blacklistIsTrimmedNamespacedAndDeduplicated() {
		List<String> result = Config.normalizeBlacklist(
				Arrays.asList("  stone ", "minecraft:stone", "", "   ", null, "mymod:thing"));

		assertEquals(List.of("minecraft:stone", "mymod:thing"), result);
	}

	@Test
	void nullBlacklistBecomesEmpty() {
		assertTrue(Config.normalizeBlacklist(null).isEmpty());
	}

	@Test
	void blacklistAcceptsTags() {
		assertEquals(List.of("#minecraft:logs", "minecraft:stone"),
				Config.normalizeBlacklist(List.of("#Logs", "stone")));
	}

	// --- File migration (version 1 = mod 1.0.x / 1.1.0, which wrote every key, cooldown 0 included) ---

	private static final String OLD_FILE_WITH_DEFAULTS = """
			{
			  "_help": "Damage Block Swap config.",
			  "swapsPerHit": 2,
			  "blacklist": ["minecraft:diamond_ore"],
			  "chatMessages": false,
			  "logging": true,
			  "enableLiquids": false,
			  "enableGravityBlocks": true,
			  "cooldownTicks": 0,
			  "updateNeighbors": false,
			  "pickFromNearbyChunks": true,
			  "swapRadius": 5
			}
			""";

	@Test
	void oldFileWithTheOldDefaultCooldownGetsTheNewDefault(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, OLD_FILE_WITH_DEFAULTS);

		Config.loadFrom(file);

		assertEquals(Config.DEFAULT_COOLDOWN_TICKS, Config.cooldownTicks);
		// Everything else the old file said is kept.
		assertEquals(2, Config.swapsPerHit);
		assertEquals(List.of("minecraft:diamond_ore"), Config.blacklist);
		assertFalse(Config.chatMessages);
		assertFalse(Config.enableLiquids);
		assertEquals(5, Config.swapRadius);
		// New key: the default.
		assertEquals(List.of("#damageblockswap:ignored"), Config.ignoredDamageTypes);

		JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		assertEquals(Config.CONFIG_VERSION, written.get("configVersion").getAsInt());
		assertEquals(Config.DEFAULT_COOLDOWN_TICKS, written.get("cooldownTicks").getAsInt());
		assertTrue(written.has("ignoredDamageTypes"));
	}

	@Test
	void oldFileWithAChosenCooldownKeepsIt(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, OLD_FILE_WITH_DEFAULTS.replace("\"cooldownTicks\": 0", "\"cooldownTicks\": 7"));

		Config.loadFrom(file);

		assertEquals(7, Config.cooldownTicks);
	}

	@Test
	void zeroCooldownChosenAfterTheMigrationSticks(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, OLD_FILE_WITH_DEFAULTS);
		Config.loadFrom(file); // migrates, writes version 2

		String edited = Files.readString(file).replaceAll("\"cooldownTicks\": \\d+", "\"cooldownTicks\": 0");
		Files.writeString(file, edited);
		Config.loadFrom(file);
		assertEquals(0, Config.cooldownTicks);
		Config.loadFrom(file);
		assertEquals(0, Config.cooldownTicks);
	}

	@Test
	void oldFileWithoutVersionOrCooldownGetsTheNewDefault(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, "{\"swapsPerHit\": 3}");

		Config.loadFrom(file);

		assertEquals(Config.DEFAULT_COOLDOWN_TICKS, Config.cooldownTicks);
		assertEquals(3, Config.swapsPerHit);
		JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		assertEquals(Config.CONFIG_VERSION, written.get("configVersion").getAsInt());
		assertEquals(Config.DEFAULT_COOLDOWN_TICKS, written.get("cooldownTicks").getAsInt());
	}

	@Test
	void settingsScreenListsAreStoredNormalized() {
		Config.setBlacklist(Arrays.asList(" Stone ", "#Logs", null));
		Config.setIgnoredDamageTypes(List.of("FALL"));

		assertEquals(List.of("minecraft:stone", "#minecraft:logs"), Config.blacklist);
		assertEquals(List.of("minecraft:fall"), Config.ignoredDamageTypes);
	}

	@Test
	void freshInstallUsesTheNewDefaults(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("sub").resolve("damageblockswap.json");

		Config.loadFrom(file);

		assertEquals(Config.DEFAULT_COOLDOWN_TICKS, Config.cooldownTicks);
		assertEquals(List.of("#damageblockswap:ignored"), Config.ignoredDamageTypes);
		assertEquals(List.of("minecraft:obsidian", "minecraft:crying_obsidian"), Config.blacklist);
		JsonObject written = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		assertEquals(Config.CONFIG_VERSION, written.get("configVersion").getAsInt());
	}

	@Test
	void emptyIgnoredDamageTypesMeansEveryDamageCounts(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, "{\"configVersion\": 2, \"ignoredDamageTypes\": []}");

		Config.loadFrom(file);

		assertTrue(Config.ignoredDamageTypes.isEmpty());
	}

	@Test
	void nullIgnoredDamageTypesMeansTheDefault(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, "{\"configVersion\": 2, \"ignoredDamageTypes\": null, \"cooldownTicks\": -5}");

		Config.loadFrom(file);

		assertEquals(List.of("#damageblockswap:ignored"), Config.ignoredDamageTypes);
		assertEquals(0, Config.cooldownTicks); // negative values are clamped
	}

	@Test
	void ignoredDamageTypesAreNormalized(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, "{\"configVersion\": 2, \"ignoredDamageTypes\": [\" Fall \", \"#IS_FIRE\", \"fall\"]}");

		Config.loadFrom(file);

		assertEquals(List.of("minecraft:fall", "#minecraft:is_fire"), Config.ignoredDamageTypes);
	}

	@Test
	void obsidianCanBeRemovedFromTheBlacklistAndStaysRemoved(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, "{\"configVersion\": 2, \"blacklist\": []}");

		Config.loadFrom(file);

		assertTrue(Config.blacklist.isEmpty());
	}

	@Test
	void oldConfigWithAnEmptyBlacklistGetsTheDefaultsOnce(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, "{\"blacklist\": []}");

		Config.loadFrom(file);

		assertEquals(List.of("minecraft:obsidian", "minecraft:crying_obsidian"), Config.blacklist);
	}

	// --- /dbs reload ------------------------------------------------------------------------------

	@Test
	void reloadAppliesAnEditedFile(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Config.loadFrom(file); // defaults, file created
		int before = Config.generation;
		Files.writeString(file, "{\"configVersion\": 2, \"cooldownTicks\": 5, \"blacklist\": [\"Dirt\"]}");

		assertEquals(null, Config.reloadFrom(file));

		assertEquals(5, Config.cooldownTicks);
		assertEquals(List.of("minecraft:dirt"), Config.blacklist);
		assertTrue(Config.generation > before, "caches built from the config are not rebuilt");
		assertTrue(Files.readString(file).contains("minecraft:dirt"), "file not written back normalized");
	}

	@Test
	void reloadOfABrokenFileKeepsTheCurrentSettingsAndTheFile(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, "{\"configVersion\": 2, \"cooldownTicks\": 7}");
		Config.loadFrom(file);
		String broken = "{\"configVersion\": 2, \"cooldownTicks\": 9,,, oops";
		Files.writeString(file, broken);
		int before = Config.generation;

		String error = Config.reloadFrom(file);

		assertTrue(error != null && !error.isBlank(), "a broken file was reported as reloaded");
		assertEquals(7, Config.cooldownTicks, "settings were changed by a broken file");
		assertEquals(before, Config.generation);
		assertEquals(broken, Files.readString(file), "the broken file was overwritten (the admin's edit is lost)");
	}

	@Test
	void reloadOfAnEmptyFileIsAnError(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("damageblockswap.json");
		Files.writeString(file, "{\"configVersion\": 2, \"cooldownTicks\": 7}");
		Config.loadFrom(file);
		Files.writeString(file, "");

		assertTrue(Config.reloadFrom(file) != null);
		assertEquals(7, Config.cooldownTicks);
	}
}
