package io.github.dedon.damageblockswap.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Opens a screen on every supported Minecraft version. Up to 26.1 it is {@code Minecraft.setScreen},
 * from 26.2 it moved to {@code Minecraft.gui.setScreen}. The mod is compiled against one version, so
 * the other one is reached by name (Minecraft 26.x ships unobfuscated, the names are the same at runtime).
 */
final class ScreenOpener {
	private static MethodHandle opener;

	private ScreenOpener() {
	}

	static void open(Minecraft client, Screen screen) {
		try {
			resolve(client).invoke(client, screen);
		} catch (Throwable t) {
			throw new IllegalStateException("Cannot open a screen on this Minecraft version", t);
		}
	}

	private static synchronized MethodHandle resolve(Minecraft client) throws ReflectiveOperationException {
		if (opener == null) {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodType type = MethodType.methodType(void.class, Screen.class);
			try {
				opener = lookup.findVirtual(Minecraft.class, "setScreen", type);
			} catch (NoSuchMethodException e) {
				Object gui = Minecraft.class.getField("gui").get(client);
				MethodHandle onGui = lookup.findVirtual(gui.getClass(), "setScreen", type);
				opener = MethodHandles.filterArguments(onGui, 0, lookup.findGetter(Minecraft.class, "gui", gui.getClass()));
			}
		}
		return opener;
	}
}
