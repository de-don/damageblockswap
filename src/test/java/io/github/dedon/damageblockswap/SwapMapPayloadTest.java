package io.github.dedon.damageblockswap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.VarInt;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Encoding/decoding of the swap map packet, its size cap, protocol version and unknown block ids. */
class SwapMapPayloadTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ByteBuf encode(SwapMapPayload payload) {
		ByteBuf buf = Unpooled.buffer();
		SwapMapPayload.CODEC.encode(buf, payload);
		return buf;
	}

	private static SwapMapPayload roundTrip(SwapMapPayload payload) {
		ByteBuf buf = encode(payload);
		SwapMapPayload decoded = SwapMapPayload.CODEC.decode(buf);
		assertEquals(0, buf.readableBytes(), "the whole packet must be consumed");
		return decoded;
	}

	@Test
	void roundTripKeepsEveryChangeInOrder() {
		Map<String, String> changes = new LinkedHashMap<>();
		changes.put("c", "a");
		changes.put("a", "b");
		changes.put("b", "c");
		Map<String, Integer> ids = Map.of("a", 1, "b", 300, "c", 70000); // 1, 2 and 3 byte VarInts
		SwapMapPayload payload = SwapMapPayload.of(changes, ids::get);

		SwapMapPayload decoded = roundTrip(payload);
		assertEquals(payload, decoded);
		assertTrue(decoded.compatible());
		assertEquals(3, decoded.total());
		assertEquals(List.of(new SwapMapPayload.Change(70000, 1), new SwapMapPayload.Change(1, 300),
				new SwapMapPayload.Change(300, 70000)), decoded.changes());

		Map<Integer, String> names = Map.of(1, "a", 300, "b", 70000, "c");
		assertEquals(List.copyOf(changes.entrySet()), List.copyOf(decoded.resolve(names::get).entrySet()));
	}

	@Test
	void emptyMapRoundTrips() {
		SwapMapPayload decoded = roundTrip(SwapMapPayload.of(Map.<String, String>of(), s -> 0));
		assertEquals(0, decoded.total());
		assertTrue(decoded.changes().isEmpty());
	}

	@Test
	void packetIsCompact() {
		Map<Block, Block> changes = new LinkedHashMap<>();
		changes.put(Blocks.STONE, Blocks.SAND);
		changes.put(Blocks.SAND, Blocks.STONE);
		ByteBuf buf = encode(SwapMapPayload.of(changes, BuiltInRegistries.BLOCK::getId));
		// protocol + total + count + 4 ids of at most 2 bytes each (vanilla has < 16384 blocks)
		assertTrue(buf.readableBytes() <= 3 + 4 * 2, "was " + buf.readableBytes() + " bytes");
	}

	@Test
	void realBlocksSurviveTheTrip() {
		Map<Block, Block> changes = new LinkedHashMap<>();
		changes.put(Blocks.STONE, Blocks.SAND);
		changes.put(Blocks.SAND, Blocks.OAK_LOG);
		changes.put(Blocks.OAK_LOG, Blocks.STONE);
		SyncedSwapMap synced = SyncedSwapMap.from(roundTrip(SwapMapPayload.of(changes, BuiltInRegistries.BLOCK::getId)));
		assertTrue(synced.compatible());
		assertEquals(3, synced.total());
		assertEquals(List.copyOf(changes.entrySet()), List.copyOf(synced.changes().entrySet()));
	}

	@Test
	void unknownBlockIdsAreDroppedButStillCounted() {
		int unknown = BuiltInRegistries.BLOCK.size() + 5; // e.g. a block of a mod this client lacks
		int stone = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
		int sand = BuiltInRegistries.BLOCK.getId(Blocks.SAND);
		SwapMapPayload payload = new SwapMapPayload(SwapMapPayload.PROTOCOL, 3, List.of(
				new SwapMapPayload.Change(stone, sand),
				new SwapMapPayload.Change(sand, unknown),
				new SwapMapPayload.Change(unknown, stone)));
		SyncedSwapMap synced = SyncedSwapMap.from(roundTrip(payload));
		assertEquals(3, synced.total());
		assertEquals(Map.of(Blocks.STONE, Blocks.SAND), synced.changes());
		// Not silently turned into air (DefaultedRegistry.byId's answer for an unknown id).
		assertFalse(synced.changes().containsValue(Blocks.AIR));
	}

	@Test
	void bigMapIsCutToTheCapButKeepsItsTotal() {
		Map<Integer, Integer> changes = new LinkedHashMap<>();
		int size = SwapMapPayload.MAX_ENTRIES + 10;
		for (int i = 0; i < size; i++) {
			changes.put(i, (i + 1) % size);
		}
		SwapMapPayload payload = SwapMapPayload.of(changes, Integer::intValue);
		assertEquals(SwapMapPayload.MAX_ENTRIES, payload.changes().size());
		assertEquals(size, payload.total());

		ByteBuf buf = encode(payload);
		assertTrue(buf.readableBytes() < 1_048_576, "must fit the custom payload limit");
		assertEquals(payload, SwapMapPayload.CODEC.decode(buf));
	}

	@Test
	void encoderRefusesAnOversizedOrInconsistentPayload() {
		List<SwapMapPayload.Change> tooMany = Collections.nCopies(SwapMapPayload.MAX_ENTRIES + 1,
				new SwapMapPayload.Change(1, 2));
		assertThrows(EncoderException.class, () -> encode(new SwapMapPayload(SwapMapPayload.PROTOCOL,
				tooMany.size(), tooMany)));
		assertThrows(EncoderException.class, () -> encode(new SwapMapPayload(SwapMapPayload.PROTOCOL, 0,
				List.of(new SwapMapPayload.Change(1, 2)))));
		assertThrows(EncoderException.class, () -> encode(new SwapMapPayload(SwapMapPayload.PROTOCOL + 1, 0,
				List.of())));
	}

	@Test
	void unknownProtocolIsSkippedNotMisread() {
		// What a future server might send: protocol 2 with a layout this client doesn't know.
		ByteBuf buf = Unpooled.buffer();
		VarInt.write(buf, SwapMapPayload.PROTOCOL + 1);
		buf.writeLong(0x1234_5678_9abc_def0L);
		buf.writeByte(7);

		SwapMapPayload decoded = SwapMapPayload.CODEC.decode(buf);
		assertEquals(0, buf.readableBytes(), "the rest must be skipped, or the connection would fail");
		assertFalse(decoded.compatible());
		SyncedSwapMap synced = SyncedSwapMap.from(decoded);
		assertFalse(synced.compatible());
		assertTrue(synced.changes().isEmpty());
	}

	@Test
	void malformedCountsAreRejected() {
		assertThrows(DecoderException.class, () -> SwapMapPayload.CODEC.decode(header(2, 3)),
				"more entries than the total");
		assertThrows(DecoderException.class, () -> SwapMapPayload.CODEC.decode(
				header(SwapMapPayload.MAX_ENTRIES + 1, SwapMapPayload.MAX_ENTRIES + 1)), "over the cap");
		assertThrows(DecoderException.class, () -> SwapMapPayload.CODEC.decode(header(-1, 0)), "negative total");
	}

	@Test
	void truncatedPacketFails() {
		ByteBuf buf = header(2, 2);
		VarInt.write(buf, 1); // only half of the first pair
		assertThrows(IndexOutOfBoundsException.class, () -> SwapMapPayload.CODEC.decode(buf));
	}

	private static ByteBuf header(int total, int count) {
		ByteBuf buf = Unpooled.buffer();
		VarInt.write(buf, SwapMapPayload.PROTOCOL);
		VarInt.write(buf, total);
		VarInt.write(buf, count);
		return buf;
	}
}
