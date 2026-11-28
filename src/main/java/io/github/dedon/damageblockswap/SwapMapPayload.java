package io.github.dedon.damageblockswap;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * Server -> client: the server's swap map ("original block -> what it looks like now"), so the swap
 * map screen of a client with the mod works on a dedicated server and for LAN guests too.
 *
 * <p>Always the whole map (it is small and bounded by the number of block types), never a delta, so a
 * lost or reordered update can't leave a client with a wrong map. Blocks travel as raw registry ids
 * (a few bytes each); Fabric's registry sync makes those the same on server and client.
 *
 * <p>Wire format: {@code protocol} (VarInt), then, for {@link #PROTOCOL} only: {@code total} (VarInt,
 * the number of changed block types), {@code count} (VarInt, at most {@link #MAX_ENTRIES}) and
 * {@code count} pairs of raw block ids (VarInt each). A client that gets a protocol it doesn't know
 * skips the rest of the packet and reports the map as incompatible instead of misreading it.
 */
public record SwapMapPayload(int protocol, int total, List<Change> changes) implements CustomPacketPayload {
	/** Bump when the layout after the protocol number changes. */
	public static final int PROTOCOL = 1;
	/**
	 * At most this many pairs per packet (at most 6 bytes each with today's registry sizes, so ~100 KB,
	 * far below the 1 MiB limit of a custom payload). Vanilla has ~1,200 block types; a bigger map is
	 * cut and the screen says how many changes it doesn't show.
	 */
	public static final int MAX_ENTRIES = 16384;

	public static final Type<SwapMapPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(DamageBlockSwap.MOD_ID, "swap_map"));
	public static final StreamCodec<ByteBuf, SwapMapPayload> CODEC =
			StreamCodec.of(SwapMapPayload::write, SwapMapPayload::read);

	/** One changed block type, as raw registry ids. */
	public record Change(int original, int current) {
	}

	public SwapMapPayload {
		changes = List.copyOf(changes);
	}

	/**
	 * The payload for {@code changes} (in its iteration order), cut to {@link #MAX_ENTRIES} pairs;
	 * {@link #total()} keeps the full count.
	 */
	public static <T> SwapMapPayload of(Map<T, T> changes, ToIntFunction<T> idOf) {
		List<Change> list = new ArrayList<>(Math.min(changes.size(), MAX_ENTRIES));
		for (Map.Entry<T, T> e : changes.entrySet()) {
			if (list.size() >= MAX_ENTRIES) {
				break;
			}
			list.add(new Change(idOf.applyAsInt(e.getKey()), idOf.applyAsInt(e.getValue())));
		}
		return new SwapMapPayload(PROTOCOL, changes.size(), list);
	}

	/** Whether this client can read the payload (same protocol as the server's). */
	public boolean compatible() {
		return protocol == PROTOCOL;
	}

	/**
	 * The changes as elements, in packet order. {@code byId} returns null for an id this side doesn't
	 * know; such pairs are dropped (they still count in {@link #total()}).
	 */
	public <T> Map<T, T> resolve(IntFunction<T> byId) {
		Map<T, T> resolved = new LinkedHashMap<>();
		for (Change c : changes) {
			T original = byId.apply(c.original());
			T current = byId.apply(c.current());
			if (original != null && current != null) {
				resolved.put(original, current);
			}
		}
		return resolved;
	}

	@Override
	public Type<SwapMapPayload> type() {
		return TYPE;
	}

	private static void write(ByteBuf buf, SwapMapPayload payload) {
		if (!payload.compatible()) {
			throw new EncoderException("Can only write swap map protocol " + PROTOCOL + ", not " + payload.protocol());
		}
		if (payload.changes().size() > MAX_ENTRIES || payload.changes().size() > payload.total()) {
			throw new EncoderException("Swap map with " + payload.changes().size() + " entries for a total of "
					+ payload.total() + " (max " + MAX_ENTRIES + ")");
		}
		VarInt.write(buf, PROTOCOL);
		VarInt.write(buf, payload.total());
		VarInt.write(buf, payload.changes().size());
		for (Change c : payload.changes()) {
			VarInt.write(buf, c.original());
			VarInt.write(buf, c.current());
		}
	}

	private static SwapMapPayload read(ByteBuf buf) {
		int protocol = VarInt.read(buf);
		if (protocol != PROTOCOL) {
			// A newer (or older) server: its layout is unknown, so don't try to read it.
			buf.skipBytes(buf.readableBytes());
			return new SwapMapPayload(protocol, 0, List.of());
		}
		int total = VarInt.read(buf);
		int count = VarInt.read(buf);
		if (total < 0 || count < 0 || count > MAX_ENTRIES || count > total) {
			throw new DecoderException("Bad swap map: " + count + " entries for a total of " + total
					+ " (max " + MAX_ENTRIES + ")");
		}
		List<Change> changes = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			changes.add(new Change(VarInt.read(buf), VarInt.read(buf)));
		}
		return new SwapMapPayload(protocol, total, changes);
	}
}
