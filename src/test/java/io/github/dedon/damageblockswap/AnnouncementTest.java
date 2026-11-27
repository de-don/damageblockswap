package io.github.dedon.damageblockswap;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat line broadcast to every player when a hit adds a rule. */
class AnnouncementTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void namesTheHitPlayerAndBothBlocks() {
		Component line = SwapAnnouncement.of(Component.literal("Steve"), Blocks.STONE, Blocks.SAND);
		assertEquals("Steve got hurt: " + Blocks.STONE.getName().getString() + " ↔ "
				+ Blocks.SAND.getName().getString(), line.getString());
	}

	@Test
	void blockNamesStayTranslatableForEachClient() {
		Component line = SwapAnnouncement.of(Component.literal("Alex"), Blocks.DIRT, Blocks.GRAVEL);
		long translatable = line.getSiblings().stream()
				.filter(c -> c.getContents() instanceof TranslatableContents)
				.count();
		assertEquals(2, translatable);
		assertTrue(line.getSiblings().stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t
				&& t.getKey().equals(Blocks.DIRT.getDescriptionId())));
	}
}
