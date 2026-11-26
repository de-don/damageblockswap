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
import net.minecraft.network.chat.Component;
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

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
				Commands.literal("damageblockswap")
						.then(Commands.literal("list")
								.executes(SwapCommand::runList))
						.then(Commands.literal("lookup")
								// A registry argument: accepts "stone" and "minecraft:stone" alike, suggests
								// every block ID and reports unknown IDs with the vanilla error message.
								.then(Commands.argument("block", ResourceArgument.resource(buildContext, Registries.BLOCK))
										.executes(SwapCommand::runLookup))));

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

	private static int runLookup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Block block = ResourceArgument.getResource(ctx, "block", Registries.BLOCK).value();

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
