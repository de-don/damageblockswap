package io.github.dedon.damageblockswap;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Every chat line the server sends (announcements and {@code /dbs} replies) and the swap map screen
 * text, as translation keys with an English fallback. A client with the mod shows its own language from
 * {@code lang/*.json}; a vanilla client (the mod is server-side only) doesn't have the keys and shows
 * the fallback.
 *
 * <p>The fallbacks must match {@code en_us.json}; {@code MessagesTest} checks that, and that
 * {@code ru_ru.json} has every key with the same arguments.
 */
final class Messages {
	private Messages() {
	}

	/** One translatable message. Arguments are {@link Component}s, strings or numbers. */
	record Msg(String key, String fallback) {
		MutableComponent with(Object... args) {
			return Component.translatableWithFallback(key, fallback, args);
		}
	}

	private static final String P = DamageBlockSwap.MOD_ID + ".";

	// --- Broadcast when rules are added (to every player and the console) ---------------------------
	static final Msg ANNOUNCE_HURT = new Msg(P + "announce.hurt", "%s got hurt: %s ↔ %s");
	static final Msg ANNOUNCE_ADDED = new Msg(P + "announce.added", "%s added a rule: %s ↔ %s");
	static final Msg ANNOUNCE_UNDONE = new Msg(P + "announce.undone", "%s undid the rule %s ↔ %s");
	static final Msg ANNOUNCE_MORE = new Msg(P + "announce.more", "...and %s more new rules (see /dbs list)");

	// --- /dbs list and /dbs lookup (everyone) -------------------------------------------------------
	static final Msg LIST_EMPTY = new Msg(P + "command.list.empty", "No blocks have been swapped yet.");
	static final Msg LIST_HEADER = new Msg(P + "command.list.header", "Swapped block types (%s):");
	static final Msg LIST_ENTRY = new Msg(P + "command.list.entry", "%s → %s  [%s → %s]");
	static final Msg LOOKUP_UNCHANGED = new Msg(P + "command.lookup.unchanged",
			"%s has not been swapped — it still looks the same.");
	static final Msg LOOKUP_LOOKS_LIKE = new Msg(P + "command.lookup.looks_like",
			"%s now looks like %s — look for that to find it.");
	static final Msg LOOKUP_WAS = new Msg(P + "command.lookup.was", "Blocks that now look like %s were originally %s.");

	// --- Admin commands (permission level 2) ---------------------------------------------------------
	static final Msg STATUS_RULES = new Msg(P + "command.status.rules",
			"Rules: %s (%s saved to disk, %s placeholders for missing blocks)");
	static final Msg STATUS_JOURNAL = new Msg(P + "command.status.journal", "Journal: id %s, file format %s");
	static final Msg STATUS_READ_ONLY = new Msg(P + "command.status.read_only",
			"The swap journal could not be read: the mod is off for this world (see the server log).");
	static final Msg STATUS_ACTIVE = new Msg(P + "command.status.active",
			"New rules from damage: on. Cooldown %s ticks, %s rule(s) per hit.");
	static final Msg STATUS_PAUSED = new Msg(P + "command.status.paused",
			"New rules from damage: PAUSED until /dbs resume or the next server start.");
	static final Msg STATUS_QUEUE = new Msg(P + "command.status.queue", "Chunks waiting to be updated: %s");

	static final Msg PAUSE_DONE = new Msg(P + "command.pause.done",
			"Damage no longer adds swap rules (until /dbs resume or the next server start).");
	static final Msg PAUSE_ALREADY = new Msg(P + "command.pause.already", "Already paused.");
	static final Msg RESUME_DONE = new Msg(P + "command.resume.done", "Damage adds swap rules again.");
	static final Msg RESUME_ALREADY = new Msg(P + "command.resume.already", "Not paused.");

	static final Msg READ_ONLY = new Msg(P + "command.read_only",
			"The swap journal is read-only for this world (see the server log); no rules can be added.");
	static final Msg ADD_DONE = new Msg(P + "command.add.done",
			"Rule #%s recorded: %s ↔ %s. It takes effect once it is saved to the journal, normally at the end of this tick.");
	static final Msg ADD_SAME = new Msg(P + "command.add.same", "Pick two different blocks.");
	static final Msg ADD_UNSAFE = new Msg(P + "command.add.unsafe",
			"%s can't be swapped: it is air, keeps data in a block entity, is a two-block block or is in #damageblockswap:never_swap.");
	static final Msg UNDO_DONE = new Msg(P + "command.undo.done",
			"Rule #%s (%s ↔ %s) undone by adding it again as rule #%s.");
	static final Msg UNDO_NOTHING = new Msg(P + "command.undo.nothing", "There is no rule to undo.");

	static final Msg RELOAD_DONE = new Msg(P + "command.reload.done",
			"Config reloaded: %s blocks can be picked for new rules.");
	static final Msg RELOAD_FAILED = new Msg(P + "command.reload.failed",
			"Config NOT reloaded, the current settings are kept: %s");

	// --- Swap map screen and the Mod Menu tab (client with the mod, so the keys are always there) ------
	static final Msg SCREEN_EMPTY = new Msg(P + "screen.empty", "No blocks have been swapped yet. Take some damage!");
	static final Msg SCREEN_HEADER = new Msg(P + "screen.header", "%s block type(s) changed:");
	static final Msg SCREEN_HIDDEN = new Msg(P + "screen.hidden",
			"...and %s more not shown here. Use /dbs list to see them.");
	static final Msg SCREEN_UNAVAILABLE = new Msg(P + "screen.unavailable",
			"This server has not sent its swap map: it runs without Damage Block Swap or with a version older"
					+ " than 1.1.0. If it has the mod, use /dbs list to see its swaps.");
	static final Msg SCREEN_INCOMPATIBLE = new Msg(P + "screen.incompatible",
			"This server runs a version of Damage Block Swap whose swap map this client can't read."
					+ " Use /dbs list to see its swaps.");

	/** All of the above, for the test that compares them with the language files. */
	static final List<Msg> ALL = List.of(
			SCREEN_EMPTY, SCREEN_HEADER, SCREEN_HIDDEN, SCREEN_UNAVAILABLE, SCREEN_INCOMPATIBLE,
			ANNOUNCE_HURT, ANNOUNCE_ADDED, ANNOUNCE_UNDONE, ANNOUNCE_MORE,
			LIST_EMPTY, LIST_HEADER, LIST_ENTRY, LOOKUP_UNCHANGED, LOOKUP_LOOKS_LIKE, LOOKUP_WAS,
			STATUS_RULES, STATUS_JOURNAL, STATUS_READ_ONLY, STATUS_ACTIVE, STATUS_PAUSED, STATUS_QUEUE,
			PAUSE_DONE, PAUSE_ALREADY, RESUME_DONE, RESUME_ALREADY,
			READ_ONLY, ADD_DONE, ADD_SAME, ADD_UNSAFE, UNDO_DONE, UNDO_NOTHING,
			RELOAD_DONE, RELOAD_FAILED);
}
