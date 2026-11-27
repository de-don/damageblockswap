package io.github.dedon.damageblockswap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;

import java.util.Map;

/**
 * The {@code /damageblockswap} command (alias {@code /dbs}).
 *
 * <p>For everyone:
 * <ul>
 *     <li>{@code /dbs list} — every block type that has changed, original -> now.</li>
 *     <li>{@code /dbs lookup <block>} — what a block turned into, and what now looks like it.</li>
 * </ul>
 * For operators (permission level 2, like {@code /setblock}):
 * <ul>
 *     <li>{@code /dbs status} — rules, journal, pause, cooldown.</li>
 *     <li>{@code /dbs pause} / {@code /dbs resume} — stop / restart new rules from damage.</li>
 *     <li>{@code /dbs add <a> <b>} — record the rule a &lt;-&gt; b, like a hit would.</li>
 *     <li>{@code /dbs undo} — cancel the last rule by recording it once more (nothing is erased).</li>
 *     <li>{@code /dbs reload} — re-read the config file.</li>
 * </ul>
 * Every reply is translatable with an English fallback ({@link Messages}).
 */
public final class SwapCommand {

	private SwapCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext,
	                            DamageBlockSwap mod) {
		LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
				Commands.literal("damageblockswap")
						.then(Commands.literal("list")
								.executes(SwapCommand::runList))
						.then(Commands.literal("lookup")
								// A registry argument: accepts "stone" and "minecraft:stone" alike, suggests
								// every block ID and reports unknown IDs with the vanilla error message.
								.then(Commands.argument("block", ResourceArgument.resource(buildContext, Registries.BLOCK))
										.executes(SwapCommand::runLookup)))
						// Admin commands. Hidden from (and rejected for) players below level 2.
						.then(Commands.literal("status")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(ctx -> runStatus(ctx, mod)))
						.then(Commands.literal("pause")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(ctx -> runPause(ctx, mod, true)))
						.then(Commands.literal("resume")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(ctx -> runPause(ctx, mod, false)))
						.then(Commands.literal("add")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("a", ResourceArgument.resource(buildContext, Registries.BLOCK))
										.then(Commands.argument("b", ResourceArgument.resource(buildContext, Registries.BLOCK))
												.executes(ctx -> runAdd(ctx, mod)))))
						.then(Commands.literal("undo")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(ctx -> runUndo(ctx, mod)))
						.then(Commands.literal("reload")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(ctx -> runReload(ctx, mod))));

		// Short alias: /dbs -> same command tree.
		dispatcher.register(Commands.literal("dbs").redirect(root));
	}

	// --- Everyone ------------------------------------------------------------------------------------

	private static int runList(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		Map<Block, Block> changes = SwapTracker.allChanges();

		if (changes.isEmpty()) {
			source.sendSuccess(() -> Messages.LIST_EMPTY.with(), false);
			return 0;
		}

		source.sendSuccess(() -> Messages.LIST_HEADER.with(changes.size()), false);
		changes.forEach((original, current) ->
				source.sendSuccess(() -> Messages.LIST_ENTRY.with(original.getName(), current.getName(),
						idOf(original), idOf(current)), false));
		return changes.size();
	}

	private static int runLookup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Block block = ResourceArgument.getResource(ctx, "block", Registries.BLOCK).value();

		Block nowLooksLike = SwapTracker.currentAppearanceOf(block);
		Block wasOriginally = SwapTracker.originalOf(block);

		if (nowLooksLike == block) {
			source.sendSuccess(() -> Messages.LOOKUP_UNCHANGED.with(block.getName()), false);
		} else {
			source.sendSuccess(() -> Messages.LOOKUP_LOOKS_LIKE.with(block.getName(), nowLooksLike.getName()), false);
		}

		if (wasOriginally != block) {
			source.sendSuccess(() -> Messages.LOOKUP_WAS.with(block.getName(), wasOriginally.getName()), false);
		}
		return 1;
	}

	// --- Operators -----------------------------------------------------------------------------------

	private static int runStatus(CommandContext<CommandSourceStack> ctx, DamageBlockSwap mod) {
		CommandSourceStack source = ctx.getSource();
		int rules = SwapTracker.version();
		if (!SwapTracker.canRecord()) {
			source.sendFailure(Messages.STATUS_READ_ONLY.with());
		}
		source.sendSuccess(() -> Messages.STATUS_RULES.with(rules, SwapTracker.persistedVersion(),
				SwapTracker.placeholderCount()), false);
		source.sendSuccess(() -> Messages.STATUS_JOURNAL.with(SwapTracker.journalId().toString(),
				SwapTracker.fileFormat()), false);
		source.sendSuccess(() -> mod.isPaused()
				? Messages.STATUS_PAUSED.with()
				: Messages.STATUS_ACTIVE.with(Config.cooldownTicks, Config.swapsPerHit), false);
		source.sendSuccess(() -> Messages.STATUS_QUEUE.with(mod.chunkQueueSize()), false);
		return rules;
	}

	private static int runPause(CommandContext<CommandSourceStack> ctx, DamageBlockSwap mod, boolean pause) {
		CommandSourceStack source = ctx.getSource();
		if (!mod.setPaused(pause)) {
			source.sendFailure((pause ? Messages.PAUSE_ALREADY : Messages.RESUME_ALREADY).with());
			return 0;
		}
		// Broadcast to the other operators and the log, like vanilla admin commands.
		source.sendSuccess(() -> (pause ? Messages.PAUSE_DONE : Messages.RESUME_DONE).with(), true);
		return 1;
	}

	private static int runAdd(CommandContext<CommandSourceStack> ctx, DamageBlockSwap mod) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Block a = ResourceArgument.getResource(ctx, "a", Registries.BLOCK).value();
		Block b = ResourceArgument.getResource(ctx, "b", Registries.BLOCK).value();
		if (!SwapTracker.canRecord()) {
			source.sendFailure(Messages.READ_ONLY.with());
			return 0;
		}
		switch (ManualRule.check(a, b, RandomSwapService::isUnsafe)) {
			case SAME_BLOCK -> {
				source.sendFailure(Messages.ADD_SAME.with());
				return 0;
			}
			case UNSAFE_A -> {
				source.sendFailure(Messages.ADD_UNSAFE.with(a.getName()));
				return 0;
			}
			case UNSAFE_B -> {
				source.sendFailure(Messages.ADD_UNSAFE.with(b.getName()));
				return 0;
			}
			case OK -> {
			}
		}
		int number = mod.addRule(source.getDisplayName(), a, b, SwapAnnouncement.Cause.ADDED);
		if (number < 0) {
			source.sendFailure(Messages.READ_ONLY.with());
			return 0;
		}
		source.sendSuccess(() -> Messages.ADD_DONE.with(number, a.getName(), b.getName()), true);
		return number;
	}

	private static int runUndo(CommandContext<CommandSourceStack> ctx, DamageBlockSwap mod) {
		CommandSourceStack source = ctx.getSource();
		if (!SwapTracker.canRecord()) {
			source.sendFailure(Messages.READ_ONLY.with());
			return 0;
		}
		SwapTracker.Rule target = SwapTracker.undoTarget();
		if (target == null) {
			source.sendFailure(Messages.UNDO_NOTHING.with());
			return 0;
		}
		// No safety check: the blocks are already in the world through the rule being cancelled.
		int number = mod.addRule(source.getDisplayName(), target.a(), target.b(), SwapAnnouncement.Cause.UNDONE);
		if (number < 0) {
			source.sendFailure(Messages.READ_ONLY.with());
			return 0;
		}
		source.sendSuccess(() -> Messages.UNDO_DONE.with(target.number(), target.a().getName(), target.b().getName(),
				number), true);
		return number;
	}

	private static int runReload(CommandContext<CommandSourceStack> ctx, DamageBlockSwap mod) {
		CommandSourceStack source = ctx.getSource();
		String error = mod.reloadConfig(source.getServer());
		if (error != null) {
			source.sendFailure(Messages.RELOAD_FAILED.with(error));
			return 0;
		}
		int count = mod.swappableBlockCount();
		source.sendSuccess(() -> Messages.RELOAD_DONE.with(count), true);
		return 1;
	}

	private static String idOf(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}
}
