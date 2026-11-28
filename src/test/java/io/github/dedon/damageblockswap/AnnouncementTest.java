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
		Object[] args = ((TranslatableContents) line.getContents()).getArgs();
		long translatable = java.util.Arrays.stream(args)
				.filter(arg -> arg instanceof Component c && c.getContents() instanceof TranslatableContents)
				.count();
		assertEquals(2, translatable);
		assertTrue(java.util.Arrays.stream(args).anyMatch(arg -> arg instanceof Component c
				&& c.getContents() instanceof TranslatableContents t && t.getKey().equals(Blocks.DIRT.getDescriptionId())));
	}

	@Test
	void theLineItselfIsTranslatableWithAnEnglishFallback() {
		Component line = SwapAnnouncement.of(Component.literal("Steve"), Blocks.STONE, Blocks.SAND);
		TranslatableContents contents = (TranslatableContents) line.getContents();
		assertEquals(Messages.ANNOUNCE_HURT.key(), contents.getKey());
		assertEquals(Messages.ANNOUNCE_HURT.fallback(), contents.getFallback());
	}

	@Test
	void adminRulesSayWhoAddedOrUndidThem() {
		assertEquals("Server added a rule: " + Blocks.STONE.getName().getString() + " ↔ "
						+ Blocks.SAND.getName().getString(),
				SwapAnnouncement.of(SwapAnnouncement.Cause.ADDED, Component.literal("Server"), Blocks.STONE, Blocks.SAND)
						.getString());
		assertEquals("Alex undid the rule " + Blocks.DIRT.getName().getString() + " ↔ "
						+ Blocks.GRAVEL.getName().getString(),
				SwapAnnouncement.of(SwapAnnouncement.Cause.UNDONE, Component.literal("Alex"), Blocks.DIRT, Blocks.GRAVEL)
						.getString());
	}

	@Test
	void summaryLineCountsTheRest() {
		assertEquals("...and 42 more new rules (see /dbs list)", SwapAnnouncement.more(42).getString());
	}
}
