package com.example;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.Map;

/**
 * The {@code /damageblockswap} command (alias {@code /dbs}) for inspecting the swap map:
 * <ul>
 *     <li>{@code /dbs list} — every block type that has changed, original -> now.</li>
 *     <li>{@code /dbs lookup <block>} — what a block turned into, and what now looks like it.</li>
 * </ul>
 */
public final class SwapCommand {

	private SwapCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
				Commands.literal("damageblockswap")
						.then(Commands.literal("list")
								.executes(SwapCommand::runList))
						.then(Commands.literal("lookup")
								.then(Commands.argument("block", StringArgumentType.string())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
												BuiltInRegistries.BLOCK.keySet().stream().map(Identifier::toString),
												builder))
										.executes(ctx -> runLookup(ctx, StringArgumentType.getString(ctx, "block"))))));

		// Short alias: /dbs -> same command tree.
		dispatcher.register(Commands.literal("dbs").redirect(root));
	}

	private static int runList(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		Map<Block, Block> changes = SwapTracker.allChanges();

		if (changes.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No blocks have been swapped yet."), false);
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Swapped block types (" + changes.size() + "):"), false);
		changes.forEach((original, current) ->
				source.sendSuccess(() -> original.getName()
						.append(Component.literal(" → "))
						.append(current.getName())
						.append(Component.literal("  [" + idOf(original) + " → " + idOf(current) + "]")), false));
		return changes.size();
	}

	private static int runLookup(CommandContext<CommandSourceStack> ctx, String input) {
		CommandSourceStack source = ctx.getSource();

		Identifier id = Identifier.tryParse(input);
		Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if (block == null) {
			source.sendFailure(Component.literal("Unknown block: " + input));
			return 0;
		}

		Block nowLooksLike = SwapTracker.currentAppearanceOf(block);
		Block wasOriginally = SwapTracker.originalOf(block);

		if (nowLooksLike == block) {
			source.sendSuccess(() -> block.getName().append(Component.literal(" has not been swapped — it still looks the same.")), false);
		} else {
			source.sendSuccess(() -> block.getName()
					.append(Component.literal(" now looks like "))
					.append(nowLooksLike.getName())
					.append(Component.literal(" — look for that to find it.")), false);
		}

		if (wasOriginally != block) {
			source.sendSuccess(() -> Component.literal("Blocks that now look like ")
					.append(block.getName())
					.append(Component.literal(" were originally "))
					.append(wasOriginally.getName())
					.append(Component.literal(".")), false);
		}
		return 1;
	}

	private static String idOf(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}
}
