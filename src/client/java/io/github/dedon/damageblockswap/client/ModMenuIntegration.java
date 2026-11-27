package io.github.dedon.damageblockswap.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu entrypoint. Only loaded when Mod Menu is installed.
 *
 * <p>The settings screen itself is drawn by Cloth Config, which is a separate optional mod. Both
 * are only "recommends", so a player can have Mod Menu without Cloth. In that case we fall back to
 * Mod Menu's default "no config screen" factory and never touch {@link ClothConfigScreen}, so no
 * Cloth class is ever loaded (which would crash with {@code NoClassDefFoundError}).
 */
public class ModMenuIntegration implements ModMenuApi {
	private static final String CLOTH_CONFIG_ID = "cloth-config";

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded(CLOTH_CONFIG_ID)) {
			// Mod Menu's default: it recognises this factory and simply shows no "Config" button.
			return ModMenuApi.super.getModConfigScreenFactory();
		}
		return ClothConfigScreen::create;
	}
}
