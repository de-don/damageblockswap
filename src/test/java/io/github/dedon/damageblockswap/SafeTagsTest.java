package io.github.dedon.damageblockswap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tags the mod ships must only name things that exist: a single unknown entry (without
 * {@code "required": false}) makes the game drop the WHOLE tag, which would silently unprotect
 * bedrock and the rest.
 */
class SafeTagsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static List<String> values(String resource) throws IOException {
		try (InputStream in = SafeTagsTest.class.getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(in, resource + " is not on the classpath");
			JsonArray array = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonArray("values");
			List<String> result = new ArrayList<>();
			for (JsonElement element : array) {
				result.add(element.getAsString());
			}
			return result;
		}
	}

	private static boolean resourceExists(String path) {
		return SafeTagsTest.class.getClassLoader().getResource(path) != null;
	}

	/** {@code data/<ns>/tags/<registry>/<path>.json} of a tag entry like {@code #minecraft:doors}. */
	private static String tagFile(String entry, String registry) {
		Identifier id = Identifier.parse(entry.substring(1));
		return "data/" + id.getNamespace() + "/tags/" + registry + "/" + id.getPath() + ".json";
	}

	@Test
	void neverSwapNamesOnlyExistingBlocksAndTags() throws IOException {
		List<String> unknown = new ArrayList<>();
		for (String entry : values("data/damageblockswap/tags/block/never_swap.json")) {
			boolean exists = entry.startsWith("#")
					? resourceExists(tagFile(entry, "block"))
					: BuiltInRegistries.BLOCK.containsKey(Identifier.parse(entry));
			if (!exists) {
				unknown.add(entry);
			}
		}
		assertTrue(unknown.isEmpty(), "unknown entries: " + unknown);
	}

	@Test
	void neverSwapCoversTheDangerousBlocks() throws IOException {
		List<String> values = values("data/damageblockswap/tags/block/never_swap.json");
		for (String required : List.of("minecraft:bedrock", "minecraft:end_portal_frame", "#minecraft:portals",
				"minecraft:barrier", "minecraft:light", "minecraft:structure_void", "#minecraft:fire",
				"minecraft:reinforced_deepslate", "#minecraft:doors", "#minecraft:beds", "minecraft:tall_grass")) {
			assertTrue(values.contains(required), required);
		}
	}

	@Test
	void ignoredDamageTypesNameOnlyExistingDamageTypes() throws IOException {
		// Damage types are data-driven: the vanilla ones are files in the game jar.
		List<String> unknown = new ArrayList<>();
		for (String entry : values("data/damageblockswap/tags/damage_type/ignored.json")) {
			boolean exists;
			if (entry.startsWith("#")) {
				exists = resourceExists(tagFile(entry, "damage_type"));
			} else {
				Identifier id = Identifier.parse(entry);
				exists = resourceExists("data/" + id.getNamespace() + "/damage_type/" + id.getPath() + ".json");
			}
			if (!exists) {
				unknown.add(entry);
			}
		}
		assertTrue(unknown.isEmpty(), "unknown entries: " + unknown);
	}

	@Test
	void theDefaultConfigPointsAtTheShippedDamageTag() {
		String tag = Config.DEFAULT_IGNORED_DAMAGE_TYPES.getFirst();
		assertTrue(resourceExists(tagFile(tag, "damage_type")), tag);
	}
}
