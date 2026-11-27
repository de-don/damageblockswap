package io.github.dedon.damageblockswap;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

/**
 * The chat line broadcast to every player (and the server console) when a rule is added. Separate
 * from {@link DamageBlockSwap}, whose static fields need a running Fabric loader, so it can be
 * unit-tested.
 */
final class SwapAnnouncement {
	private SwapAnnouncement() {
	}

	/** Why the rule was added; picks the wording. */
	enum Cause {
		/** A player got hurt (the normal case). */
		HURT(Messages.ANNOUNCE_HURT),
		/** An operator ran {@code /dbs add}. */
		ADDED(Messages.ANNOUNCE_ADDED),
		/** An operator ran {@code /dbs undo} (the rule was added again, which cancels it). */
		UNDONE(Messages.ANNOUNCE_UNDONE);

		private final Messages.Msg message;

		Cause(Messages.Msg message) {
			this.message = message;
		}
	}

	/** E.g. "Steve got hurt: Stone ↔ Sand". */
	static Component of(Component playerName, Block a, Block b) {
		return of(Cause.HURT, playerName, a, b);
	}

	/**
	 * The line for one rule. Translatable with an English fallback, as are the block names, so every
	 * client shows it in its own language (a vanilla client sees the English fallback).
	 */
	static Component of(Cause cause, Component who, Block a, Block b) {
		return cause.message.with(who, a.getName(), b.getName());
	}

	/** "...and N more new rules": closes a batch that was cut short (see {@link AnnouncementBacklog}). */
	static Component more(int omittedRules) {
		return Messages.ANNOUNCE_MORE.with(omittedRules);
	}
}
