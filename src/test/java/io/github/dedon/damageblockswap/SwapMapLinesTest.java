package io.github.dedon.damageblockswap;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The swap map must not claim "nothing swapped" on a client that can't see the server's rules. */
class SwapMapLinesTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Map<Block, Block> oneSwap() {
		Map<Block, Block> changes = new LinkedHashMap<>();
		changes.put(Blocks.STONE, Blocks.SAND);
		changes.put(Blocks.SAND, Blocks.STONE);
		return changes;
	}

	@Test
	void remoteClientGetsHonestHintEvenWithoutRules() {
		List<Component> lines = SwapMapLines.of(false, Map.of());
		assertEquals(1, lines.size());
		String text = lines.getFirst().getString();
		assertEquals(SwapMapLines.UNAVAILABLE, text);
		assertTrue(text.contains("/dbs list"));
		assertFalse(text.contains("No blocks have been swapped"));
	}

	@Test
	void remoteClientIgnoresWhateverTheLocalTrackerHolds() {
		// A stale local rule book must not be shown as if it were the server's.
		List<Component> lines = SwapMapLines.of(false, oneSwap());
		assertEquals(List.of(SwapMapLines.UNAVAILABLE), lines.stream().map(Component::getString).toList());
	}

	@Test
	void hostWithoutRulesSaysNothingSwapped() {
		List<Component> lines = SwapMapLines.of(true, Map.of());
		assertEquals(List.of(SwapMapLines.EMPTY), lines.stream().map(Component::getString).toList());
	}

	@Test
	void hostSeesEveryRule() {
		List<String> lines = SwapMapLines.of(true, oneSwap()).stream().map(Component::getString).toList();
		String stone = Blocks.STONE.getName().getString();
		String sand = Blocks.SAND.getName().getString();
		assertEquals(List.of("2 block type(s) changed:", stone + " → " + sand, sand + " → " + stone), lines);
	}
}
