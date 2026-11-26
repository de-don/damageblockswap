package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
	private Locale originalLocale;

	@BeforeEach
	void rememberLocale() {
		originalLocale = Locale.getDefault();
	}

	@AfterEach
	void restoreLocale() {
		Locale.setDefault(originalLocale);
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
}
