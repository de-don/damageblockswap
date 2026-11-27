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

/**
 * Where the swap map screen takes its lines from (local rule book, the server's copy, or neither) and
 * what it says; it must never claim "nothing swapped" when it can't see the server's rules.
 */
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

	private static List<String> text(List<Component> lines) {
		return lines.stream().map(Component::getString).toList();
	}

	private static SyncedSwapMap dirtGlass() {
		return new SyncedSwapMap(true, 2, Map.of(Blocks.DIRT, Blocks.GLASS, Blocks.GLASS, Blocks.DIRT));
	}

	private static SyncedSwapMap incompatible() {
		return new SyncedSwapMap(false, 0, Map.of());
	}

	// --- source choice -------------------------------------------------------------------------

	@Test
	void hostReadsTheLocalRuleBookEvenIfTheServerSentACopy() {
		assertEquals(SwapMapLines.Source.LOCAL, SwapMapLines.source(true, null));
		assertEquals(SwapMapLines.Source.LOCAL, SwapMapLines.source(true, dirtGlass()));
		assertEquals(SwapMapLines.Source.LOCAL, SwapMapLines.source(true, incompatible()));
	}

	@Test
	void remoteClientUsesTheServersCopy() {
		assertEquals(SwapMapLines.Source.SYNCED, SwapMapLines.source(false, dirtGlass()));
	}

	@Test
	void remoteClientWithoutCopyHasNoMap() {
		assertEquals(SwapMapLines.Source.UNAVAILABLE, SwapMapLines.source(false, null));
	}

	@Test
	void remoteClientWithUnreadableCopyIsTold() {
		assertEquals(SwapMapLines.Source.INCOMPATIBLE, SwapMapLines.source(false, incompatible()));
	}

	// --- lines ---------------------------------------------------------------------------------

	@Test
	void serverWithoutTheModGetsHonestHint() {
		List<Component> lines = SwapMapLines.of(false, Map.of(), null);
		assertEquals(1, lines.size());
		String text = lines.getFirst().getString();
		assertEquals(SwapMapLines.UNAVAILABLE, text);
		assertTrue(text.contains("/dbs list"));
		assertFalse(text.contains("No blocks have been swapped"));
	}

	@Test
	void remoteClientIgnoresWhateverTheLocalTrackerHolds() {
		// A stale local rule book must not be shown as if it were the server's.
		assertEquals(List.of(SwapMapLines.UNAVAILABLE), text(SwapMapLines.of(false, oneSwap(), null)));
		List<String> synced = text(SwapMapLines.of(false, oneSwap(), dirtGlass()));
		assertEquals("2 block type(s) changed:", synced.getFirst());
		assertFalse(synced.stream().anyMatch(l -> l.contains(Blocks.STONE.getName().getString())));
	}

	@Test
	void incompatibleServerSaysSo() {
		assertEquals(List.of(SwapMapLines.INCOMPATIBLE), text(SwapMapLines.of(false, Map.of(), incompatible())));
	}

	@Test
	void syncedEmptyMapSaysNothingSwapped() {
		SyncedSwapMap empty = new SyncedSwapMap(true, 0, Map.of());
		assertEquals(List.of(SwapMapLines.EMPTY), text(SwapMapLines.of(false, Map.of(), empty)));
	}

	@Test
	void syncedMapListsTheServersRulesInOrder() {
		Map<Block, Block> changes = new LinkedHashMap<>();
		changes.put(Blocks.DIRT, Blocks.GLASS);
		changes.put(Blocks.GLASS, Blocks.DIRT);
		String dirt = Blocks.DIRT.getName().getString();
		String glass = Blocks.GLASS.getName().getString();
		assertEquals(List.of("2 block type(s) changed:", dirt + " → " + glass, glass + " → " + dirt),
				text(SwapMapLines.of(false, Map.of(), new SyncedSwapMap(true, 2, changes))));
	}

	@Test
	void syncedMapSaysHowManyAreNotShown() {
		// Capped packet, or blocks this client doesn't know: the total still counts them.
		SyncedSwapMap partial = new SyncedSwapMap(true, 5, Map.of(Blocks.DIRT, Blocks.GLASS));
		List<String> lines = text(SwapMapLines.of(false, Map.of(), partial));
		assertEquals(3, lines.size());
		assertEquals("5 block type(s) changed:", lines.getFirst());
		assertTrue(lines.getLast().contains("4 more"));
		assertTrue(lines.getLast().contains("/dbs list"));
	}

	@Test
	void hostWithoutRulesSaysNothingSwapped() {
		assertEquals(List.of(SwapMapLines.EMPTY), text(SwapMapLines.of(true, Map.of(), null)));
	}

	@Test
	void hostSeesEveryRule() {
		List<String> lines = text(SwapMapLines.of(true, oneSwap(), dirtGlass()));
		String stone = Blocks.STONE.getName().getString();
		String sand = Blocks.SAND.getName().getString();
		assertEquals(List.of("2 block type(s) changed:", stone + " → " + sand, sand + " → " + stone), lines);
	}
}
