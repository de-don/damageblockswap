package io.github.dedon.damageblockswap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The {@code /dbs} admin commands on a real server, through the real command dispatcher: permissions,
 * {@code add} / {@code undo} writing to the journal (append-only), refusing unsafe blocks, pause,
 * reload. Each test runs in one go on the server thread, so other tests can't add rules in between.
 */
public class AdminCommandsGameTest {
	private static int run(GameTestHelper helper, CommandSourceStack source, String command) {
		CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
		try {
			return dispatcher.execute(command, source);
		} catch (CommandSyntaxException e) {
			throw helper.assertionException(Component.literal("/" + command + " failed to parse: " + e.getMessage()));
		}
	}

	private static boolean parses(GameTestHelper helper, CommandSourceStack source, String command) {
		CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
		ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, source);
		return !parsed.getReader().canRead() && parsed.getExceptions().isEmpty();
	}

	private static CommandSourceStack operator(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		return server.createCommandSourceStack().withSuppressedOutput();
	}

	@GameTest
	public void adminCommandsNeedPermissionButListAndLookupDoNot(GameTestHelper helper) {
		CommandSourceStack player = operator(helper).withPermission(PermissionSet.NO_PERMISSIONS);
		for (String admin : new String[]{"dbs status", "dbs pause", "dbs resume", "dbs undo", "dbs reload",
				"dbs add minecraft:stone minecraft:sand", "damageblockswap status"}) {
			helper.assertFalse(parses(helper, player, admin), "/" + admin + " is open to everyone");
			helper.assertTrue(parses(helper, operator(helper), admin), "/" + admin + " is missing for operators");
		}
		helper.assertTrue(parses(helper, player, "dbs list"), "/dbs list is no longer open to everyone");
		helper.assertTrue(parses(helper, player, "dbs lookup minecraft:stone"), "/dbs lookup is no longer open to everyone");
		run(helper, player, "dbs list");
		helper.succeed();
	}

	@GameTest
	public void addAndUndoOnlyAppendToTheJournal(GameTestHelper helper) {
		CommandSourceStack op = operator(helper);
		int before = SwapTracker.version();
		// A rule swaps what the two blocks look like now, so whatever now looks like honeycomb is what
		// looked like packed mud before, and the other way round.
		Block underHoneycomb = SwapTracker.originalOf(Blocks.HONEYCOMB_BLOCK);
		Block underMud = SwapTracker.originalOf(Blocks.PACKED_MUD);

		int number = run(helper, op, "dbs add minecraft:honeycomb_block minecraft:packed_mud");
		helper.assertTrue(number == before + 1, "/dbs add returned " + number + ", expected rule " + (before + 1));
		helper.assertTrue(SwapTracker.version() == before + 1, "rule not recorded");
		helper.assertTrue(SwapTracker.originalOf(Blocks.HONEYCOMB_BLOCK) == underMud
				&& SwapTracker.originalOf(Blocks.PACKED_MUD) == underHoneycomb, "the rule did not swap the two blocks");
		SwapTracker.Rule target = SwapTracker.undoTarget();
		helper.assertTrue(target != null && target.number() == number, "the new rule is not the one to undo: " + target);

		int undo = run(helper, op, "dbs undo");
		helper.assertTrue(undo == before + 2, "/dbs undo did not append a rule: " + undo);
		helper.assertTrue(SwapTracker.version() == before + 2, "undo erased instead of appending");
		helper.assertTrue(SwapTracker.originalOf(Blocks.HONEYCOMB_BLOCK) == underHoneycomb
				&& SwapTracker.originalOf(Blocks.PACKED_MUD) == underMud, "undo did not restore the blocks");
		helper.succeed();
	}

	@GameTest
	public void addRefusesUnsafeAndIdenticalBlocks(GameTestHelper helper) {
		CommandSourceStack op = operator(helper);
		int before = SwapTracker.version();
		for (String command : new String[]{
				"dbs add minecraft:bedrock minecraft:stone",      // never_swap
				"dbs add minecraft:stone minecraft:oak_door",     // two-block block
				"dbs add minecraft:chest minecraft:stone",        // block entity
				"dbs add minecraft:air minecraft:stone",          // air
				"dbs add minecraft:stone minecraft:stone"}) {     // same block
			helper.assertTrue(run(helper, op, command) == 0, "/" + command + " was accepted");
		}
		helper.assertTrue(SwapTracker.version() == before, "a refused rule was recorded");
		helper.succeed();
	}

	private static DamageBlockSwap mod() {
		return FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
				.filter(DamageBlockSwap.class::isInstance)
				.map(DamageBlockSwap.class::cast)
				.findFirst()
				.orElseThrow();
	}

	@GameTest
	public void pauseStopsRulesFromDamageWithoutUsingTheCooldown(GameTestHelper helper) {
		CommandSourceStack op = operator(helper);
		DamageBlockSwap mod = mod();
		// A plain survival Player in the test level (not a FakePlayer, not in the player list).
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		DamageSource hit = helper.getLevel().damageSources().generic();
		int before = SwapTracker.version();

		helper.assertTrue(run(helper, op, "dbs pause") == 1, "pause failed");
		RuntimeException failure = null;
		try {
			helper.assertTrue(mod.isPaused(), "pause did not reach the mod");
			helper.assertTrue(run(helper, op, "dbs pause") == 0, "pausing twice was not noticed");
			helper.assertTrue(run(helper, op, "dbs status") == 1, "status failed");
			// Exactly what AFTER_DAMAGE calls on a real hit. Resumed below without hurting anyone again,
			// so this test never adds a random rule to the shared test world.
			mod.onEntityDamaged(player, hit, 4.0F, 4.0F, false);
			helper.assertTrue(SwapTracker.version() == before, "damage added a rule while paused");
			helper.assertFalse(mod.hasCooldownEntry(player.getUUID()), "a paused hit used up the cooldown");
		} catch (RuntimeException e) {
			failure = e;
		}
		// Always resume (pause is global), but don't let that check hide the first failure.
		int resumed = run(helper, op, "dbs resume");
		if (failure != null) {
			throw failure;
		}
		helper.assertTrue(resumed == 1, "resume failed");
		helper.assertFalse(mod.isPaused(), "resume did not reach the mod");
		helper.assertTrue(run(helper, op, "dbs resume") == 0, "resuming twice was not noticed");
		helper.succeed();
	}

	@GameTest
	public void reloadRereadsTheConfig(GameTestHelper helper) {
		helper.assertTrue(run(helper, operator(helper), "dbs reload") == 1, "/dbs reload failed");
		helper.succeed();
	}
}
