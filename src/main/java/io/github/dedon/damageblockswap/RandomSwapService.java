package io.github.dedon.damageblockswap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Owns the list of blocks that are allowed to be swapped, and picks a random pair.
 *
 * <p>The list is built lazily the first time it is needed (and again when the config or the tags
 * change). Doing it lazily (instead of during mod init) guarantees the block registry is already fully
 * populated and the world's block tags are loaded.
 *
 * <p>The list only decides which blocks NEW rules may pick. Rules already in the journal keep being
 * applied as they are, whatever the blacklist or the tag say now: changing what a rule means after
 * chunks have been stamped with it would make chunks disagree with each other.
 */
public final class RandomSwapService {
	/**
	 * Blocks never picked for a new rule: a safe default set shipped as a datapack tag
	 * ({@code data/damageblockswap/tags/block/never_swap.json}), so a datapack can change it per world.
	 */
	public static final TagKey<Block> NEVER_SWAP =
			TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DamageBlockSwap.MOD_ID, "never_swap"));

	private final Random random = new Random();
	private List<Block> allowedBlocks = null;
	private Set<Block> allowedSet = null; // same contents as allowedBlocks, for fast membership tests
	private int cachedGeneration = -1;
	private int cachedTagsGeneration = -1;
	// The config blacklist, split into ids and tags; rebuilt with the list.
	private IdFilter blacklist = IdFilter.EMPTY;
	private Map<String, TagKey<Block>> blacklistTags = Map.of();

	/** A pair of two different block types to swap. */
	public record BlockPair(Block a, Block b) {
	}

	/** (Re)builds the swappable block list if it's missing or the config has changed since. */
	private void ensureInitialized() {
		if (allowedBlocks == null || cachedGeneration != Config.generation || cachedTagsGeneration != DamageBlockSwap.tagsGeneration()) {
			initialize();
		}
	}

	/**
	 * Scans the whole block registry once and keeps only the blocks we may swap. Also logs a warning
	 * for every blacklist entry that matches nothing (typo, block of a removed mod, unknown tag).
	 */
	public void initialize() {
		cachedGeneration = Config.generation;
		cachedTagsGeneration = DamageBlockSwap.tagsGeneration();
		buildBlacklist();

		List<Block> list = new ArrayList<>();
		Set<Block> set = new HashSet<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (isAllowed(block)) {
				list.add(block);
				set.add(block);
			}
		}
		allowedBlocks = list;
		allowedSet = set;
		DamageBlockSwap.LOGGER.info("Built swappable block list: {} blocks", list.size());
	}

	private void buildBlacklist() {
		blacklist = IdFilter.parse(Config.blacklist);
		Map<String, TagKey<Block>> tags = new HashMap<>();
		for (String tag : blacklist.tags()) {
			tags.put(tag, TagKey.create(Registries.BLOCK, Identifier.parse(tag)));
		}
		blacklistTags = tags;

		List<String> problems = blacklist.problems(
				id -> BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id)),
				tag -> BuiltInRegistries.BLOCK.get(blacklistTags.get(tag)).isPresent());
		for (String problem : problems) {
			DamageBlockSwap.LOGGER.warn("Config blacklist: {} matches no block and is ignored.", problem);
		}
		if (BuiltInRegistries.BLOCK.get(NEVER_SWAP).isEmpty()) {
			DamageBlockSwap.LOGGER.warn("Block tag #{} is missing (broken or removed by a datapack?): no blocks are"
					+ " protected by it.", NEVER_SWAP.location());
		}
	}

	private boolean isAllowed(Block block) {
		BlockState state = block.defaultBlockState();

		// Never swap the various flavors of air.
		if (state.isAir()) return false;

		// Any block that stores data in a BlockEntity (chests, furnaces, signs, banners,
		// shulker boxes, spawners, beehives, decorated pots, vaults, ...) implements
		// EntityBlock. Skipping them protects inventories and NBT data.
		if (block instanceof EntityBlock) return false;

		if (!Config.enableLiquids && block instanceof LiquidBlock) return false;
		if (!Config.enableGravityBlocks && block instanceof FallingBlock) return false;

		// Doors, tall plants, beds: a swap places one block, never the other half.
		if (SwapStates.isMultiBlock(state)) return false;

		// The safe default set (bedrock, portals, barriers, fire, ...), see NEVER_SWAP.
		Holder<Block> holder = block.builtInRegistryHolder();
		if (holder.is(NEVER_SWAP)) return false;

		String id = BuiltInRegistries.BLOCK.getKey(block).toString();
		if (blacklist.matches(id, tag -> holder.is(blacklistTags.get(tag)))) return false;

		return true;
	}

	/** The blocks new rules may pick from, rebuilt first if needed. Read-only; for the gametests. */
	List<Block> allowedBlocks() {
		ensureInitialized();
		return java.util.Collections.unmodifiableList(allowedBlocks);
	}

	/**
	 * Returns two different random blocks, or {@code null} if fewer than two blocks
	 * are available (which should never realistically happen).
	 */
	public BlockPair getRandomPair() {
		ensureInitialized();
		if (allowedBlocks.size() < 2) {
			return null;
		}

		return pickTwo(allowedBlocks);
	}

	/**
	 * Picks a pair from the given pool (e.g. blocks found near the player). Falls back to a fully
	 * random global pair if the pool has fewer than two distinct blocks.
	 */
	public BlockPair getRandomPairFrom(List<Block> pool) {
		if (pool == null || pool.size() < 2) {
			return getRandomPair();
		}
		return pickTwo(pool);
	}

	private BlockPair pickTwo(List<Block> pool) {
		Block a = pool.get(random.nextInt(pool.size()));
		Block b;
		do {
			b = pool.get(random.nextInt(pool.size()));
		} while (b == a); // retry until the two picks differ
		return new BlockPair(a, b);
	}

	/**
	 * Collects the distinct swappable block types that actually exist in the chunks around
	 * {@code center} (a {@code chunkRadius} of 1 means the 3x3 = 9 chunks). Only already-loaded
	 * chunks are inspected. Used so swaps always hit blocks the player can see.
	 */
	public List<Block> collectSwappableNearby(ServerLevel level, BlockPos center, int chunkRadius) {
		ensureInitialized();

		Set<Block> found = new HashSet<>();
		int centerChunkX = center.getX() >> 4;
		int centerChunkZ = center.getZ() >> 4;

		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
				if (chunk == null) {
					continue; // not loaded — skip
				}

				for (LevelChunkSection section : chunk.getSections()) {
					if (section == null || section.hasOnlyAir()) {
						continue;
					}
					collectFromPalette(section, found);
				}
			}
		}
		return new ArrayList<>(found);
	}

	/**
	 * Adds the swappable block types in the section's palette (usually a few dozen entries) to
	 * {@code found}, without reading its 4096 positions.
	 *
	 * <p>{@code maybeHas} hands every palette entry to the predicate until it returns true; ours never
	 * does, so it sees them all. A palette may still list a state that was since removed from the
	 * section; picking such a block now and then is harmless. Only the global palette (sections with
	 * hundreds of different states, rare) doesn't list its entries: it answers true without asking,
	 * and then the section is counted the slow way. ({@code PalettedContainer.count}, used for every
	 * section before, reads all 4096 positions whenever the palette has more than one entry.)
	 */
	private void collectFromPalette(LevelChunkSection section, Set<Block> found) {
		boolean unlisted = section.maybeHas(state -> {
			Block block = state.getBlock();
			if (allowedSet.contains(block)) {
				found.add(block);
			}
			return false;
		});
		if (unlisted) {
			section.getStates().count((state, count) -> {
				Block block = state.getBlock();
				if (allowedSet.contains(block)) {
					found.add(block);
				}
			});
		}
	}
}
