package io.github.dedon.damageblockswap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's chat lines and the language files agree: a vanilla client sees the fallback, a client
 * with the mod the language file, so the English fallback must say the same as {@code en_us.json},
 * and every translation must take the same arguments.
 */
class MessagesTest {
	private static JsonObject lang(String name) throws IOException {
		String path = "/assets/damageblockswap/lang/" + name + ".json";
		try (InputStream in = MessagesTest.class.getResourceAsStream(path)) {
			assertNotNull(in, path + " is missing from the main resources");
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static int arguments(String text) {
		Matcher m = Pattern.compile("%(\\d+\\$)?s").matcher(text);
		int count = 0;
		while (m.find()) {
			count++;
		}
		return count;
	}

	@Test
	void everyMessageIsListedInAll() throws IllegalAccessException {
		Set<Messages.Msg> declared = new HashSet<>();
		for (Field field : Messages.class.getDeclaredFields()) {
			if (field.getType() == Messages.Msg.class && Modifier.isStatic(field.getModifiers())) {
				declared.add((Messages.Msg) field.get(null));
			}
		}
		assertEquals(declared, new HashSet<>(Messages.ALL));
	}

	@Test
	void fallbacksMatchEnglishFile() throws IOException {
		JsonObject en = lang("en_us");
		for (Messages.Msg msg : Messages.ALL) {
			assertTrue(en.has(msg.key()), msg.key() + " is missing from en_us.json");
			assertEquals(msg.fallback(), en.get(msg.key()).getAsString(), msg.key());
		}
		// No stale server keys in the file (client keys, e.g. the key binding, start with "key.").
		Set<String> keys = new HashSet<>();
		Messages.ALL.forEach(m -> keys.add(m.key()));
		for (String key : en.keySet()) {
			assertTrue(key.startsWith("key.") || keys.contains(key), key + " in en_us.json is not used");
		}
	}

	@Test
	void russianHasEveryKeyWithTheSameArguments() throws IOException {
		JsonObject en = lang("en_us");
		JsonObject ru = lang("ru_ru");
		assertEquals(en.keySet(), ru.keySet());
		for (String key : en.keySet()) {
			assertEquals(arguments(en.get(key).getAsString()), arguments(ru.get(key).getAsString()), key);
		}
	}
}
