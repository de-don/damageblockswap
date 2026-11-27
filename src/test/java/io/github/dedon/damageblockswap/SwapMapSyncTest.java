package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who gets the swap map packet: only clients that announced the channel, with one shared payload. */
class SwapMapSyncTest {
	private static final SwapMapPayload PAYLOAD = SwapMapPayload.of(Map.of(1, 2, 2, 1), Integer::intValue);

	private record Sent(String player, SwapMapPayload payload) {
	}

	@Test
	void onlyClientsWithTheChannelGetThePacket() {
		Set<String> withMod = Set.of("modded1", "modded2");
		List<Sent> sent = new ArrayList<>();
		AtomicInteger builds = new AtomicInteger();
		SwapMapSync.sendTo(List.of("vanilla", "modded1", "old", "modded2"), withMod::contains,
				() -> {
					builds.incrementAndGet();
					return PAYLOAD;
				},
				(player, payload) -> sent.add(new Sent(player, payload)));

		assertEquals(List.of("modded1", "modded2"), sent.stream().map(Sent::player).toList());
		assertTrue(sent.stream().allMatch(s -> s.payload() == PAYLOAD));
		assertEquals(1, builds.get(), "the payload is built once for everyone");
	}

	@Test
	void nothingIsBuiltOrSentWhenNobodyHasTheMod() {
		List<Sent> sent = new ArrayList<>();
		AtomicInteger builds = new AtomicInteger();
		SwapMapSync.sendTo(List.of("vanilla1", "vanilla2"), player -> false,
				() -> {
					builds.incrementAndGet();
					return PAYLOAD;
				},
				(player, payload) -> sent.add(new Sent(player, payload)));

		assertTrue(sent.isEmpty());
		assertEquals(0, builds.get());
	}

	@Test
	void singleJoiningClientWithTheModGetsIt() {
		List<Sent> sent = new ArrayList<>();
		SwapMapSync.sendTo(List.of("joining"), player -> true, () -> PAYLOAD,
				(player, payload) -> sent.add(new Sent(player, payload)));
		assertEquals(1, sent.size());
		assertSame(PAYLOAD, sent.getFirst().payload());
	}
}
