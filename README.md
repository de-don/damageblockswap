# Damage Block Swap

A Fabric mod for **Minecraft 26.1.2**. Every time a player takes damage, a new
"swap rule" is created — two random block types are exchanged (e.g. *stone ↔ sand*).
Rules pile up, and the world around you gradually descends into chaos while ordinary
Minecraft mechanics keep working.

Get hit enough times and stone becomes lava becomes glass… and you'll want the built-in
**swap map** to remember what turned into what.

## How it works

Each hit adds a rule to a versioned "rule book" that is saved with your world. Rather than
rewriting the whole world at once (which would freeze the game), the mod brings chunks up to
date a little at a time:

- **Around you, immediately** — chunks within the *area of effect* update right after a hit.
- **As you move** — the affected bubble follows you; new chunks catch up when you reach them.
- **As chunks load** — freshly generated or reloaded chunks apply the rules on load.

Every chunk remembers which rule version it has been brought to (a persistent stamp), so rules
are never applied twice, and the work is throttled to a few milliseconds per tick so the server
stays responsive no matter how large the swap. Chunks closest to a player are done first. A chunk
that is unloaded or saved half-way through also remembers how far it got, and carries on from
there when it loads again. A chunk is only changed while all 8 chunks around it are loaded, so
with `updateNeighbors` off (the default) the mod never makes the server load or generate terrain
on its own; chunks at the very edge of the loaded area catch up once you get closer. With
`updateNeighbors` on, a chain of block updates (redstone, flowing water…) can still reach past
those 8 chunks and load the chunk it runs into, as any vanilla block update would.

Blocks that can't survive where they'd be placed (rails, torches, flowers, redstone…) are left
alone instead of popping off as dropped items. With `updateNeighbors` off (the default), blocks
next to a swapped block are not reshaped or broken either (a torch stays on a wall that became
water). Blocks that store data — chests, furnaces, signs,
shulker boxes, spawners, and anything else with a block entity — are never swapped, so your
inventories and builds are safe.

A swapped block keeps the properties it shares with the block it replaces: logs keep their axis,
stairs and slabs their shape and facing, waterlogged blocks their water. Leaves are always placed
as "player-placed" leaves, so stone that turned into leaves doesn't decay.

### What can be swapped and what triggers a swap

New rules never pick blocks in the block tag **`damageblockswap:never_swap`**: bedrock, reinforced
deepslate, end portal frames, portals, barriers, light blocks, structure voids, fire, command /
structure / jigsaw / test blocks, piston heads, bubble columns, frosted ice, farmland, infested
blocks, and every two-block block (doors, beds, tall grass and flowers, tall seagrass, small
dripleaf, pitcher plants — the mod would place only one half). A datapack can change the tag
(`data/damageblockswap/tags/block/never_swap.json`); two-block blocks stay excluded regardless.
The `blacklist` setting adds more blocks or tags on top.

Not every kind of damage makes a swap: by default drowning, starving, suffocating in a wall,
entity cramming, burning (after leaving the fire), freezing, the void, the world border and
`/kill` don't (damage type tag `damageblockswap:ignored`, see `ignoredDamageTypes`). These hurt
every few ticks for as long as the situation lasts, and a swap can cause that situation.

The blacklist, the tag and the damage settings only affect **new** rules. Rules that already
happened keep being applied everywhere exactly as before — otherwise chunks updated before and
after the change would disagree.

Newly placed or physics-generated blocks (a block you place, cobblestone from lava + water, etc.)
are **not** retroactively swapped — only future rules that target their type will affect them.

### The swap journal file

The rule book lives in your world folder as `damageblockswap_swaps.json`. Each chunk remembers
which journal it was swapped by, so it is never swapped twice or by the wrong rules:

- **Deleting the file does not undo or reset the swaps.** The blocks in already-swapped chunks
  stay as they are, and those chunks are *frozen*: a new journal starts, and its rules are only
  applied to chunks it hasn't touched yet (fresh terrain, unvisited areas). Putting the old file
  back (with the server stopped) unfreezes them — but then the chunks the new journal swapped in
  the meantime are frozen instead, so do it before any new swaps happen.
- Restoring an **older copy** of the file freezes chunks that were swapped by rules the copy
  doesn't have; the rest of the world carries on normally.
- If the file is unreadable it is renamed to `damageblockswap_swaps.json.corrupt-<date>` and a new
  journal starts (with a loud error in the log). Worlds from version 1.0.x are converted
  automatically; the original file is kept as `damageblockswap_swaps.json.v1-backup`.
- **Downgrading is not supported.** Version 1.0.x cannot read the new journal format and overwrites it with an empty one when the server stops, after which the world's chunks are frozen. **Back up your world before updating.**
- A journal written by a newer version of the mod is left untouched, and the mod stays inactive
  for that world until you update it.

## Requirements

- Minecraft **26.1.2**
- **Fabric Loader** ≥ 0.19.3
- **Fabric API**
- **Java 25**

## Optional (for the in-game settings screen)

These are *recommended*, not required — the mod runs fine without them (configured via the JSON
file). Install both to get an in-game settings screen and swap-map viewer:

- [Mod Menu](https://modrinth.com/mod/modmenu) 18.0.0
- [Cloth Config API](https://modrinth.com/mod/cloth-config) 26.1.154

With them installed: **main menu / pause → Mods → Damage Block Swap → ⚙**. The screen has a
**General** tab (all settings) and a read-only **Swap Map** tab.

## Configuration

Settings live in `config/damageblockswap.json` (created on first run; new keys are added
automatically when you update the mod). Edit it directly or use the in-game screen.

| Setting | Default | Meaning |
|---|---|---|
| `swapsPerHit` | `1` | How many rules a single hit adds (1–100). |
| `swapRadius` | `3` | Area of effect around the player, in chunks (1–16). Bigger = more of the world changes at once, but heavier. |
| `pickFromNearbyChunks` | `true` | Pick the swapped blocks from ones that actually exist around you, so hits are always visible. |
| `updateNeighbors` | `false` | Full vanilla block updates after swaps: neighbouring blocks react (redstone, connections, unsupported blocks break and drop). **Can cause severe lag** with many/common blocks — leave off unless you want it. Note that even when off, a swapped-in falling block (sand, gravel) still falls and swapped-in water/lava still flows — that is the block itself, not its neighbours. |
| `enableLiquids` | `true` | Allow water and lava to be swapped. |
| `enableGravityBlocks` | `true` | Allow sand, gravel and other falling blocks to be swapped. |
| `cooldownTicks` | `20` | Minimum ticks between two swaps caused by the same player (20 = 1 second). 0 = no cooldown. A config from an older version that still has the old default `0` is raised to `20` once (logged); set `0` again if you want it. |
| `ignoredDamageTypes` | `["#damageblockswap:ignored"]` | Damage types that do **not** cause a swap, as ids or `#tags`, e.g. `["minecraft:fall", "#minecraft:is_fire"]`. `[]` = every damage swaps. |
| `chatMessages` | `true` | Announce each swap in chat. |
| `logging` | `true` | Log swaps to the console/log file. |
| `blacklist` | `[]` | Blocks never picked for a new swap, as ids or `#tags`, e.g. `["diamond_ore", "#minecraft:logs", "#c:storage_blocks"]`. The `minecraft:` prefix is optional. Entries that match no block are reported in the log at server start. Comes on top of the `damageblockswap:never_swap` tag. |

## Commands

- `/damageblockswap list` (alias `/dbs list`) — every block type that has changed, `original → now`.
- `/dbs lookup <block>` — what a block turned into, and what now looks like it. Example:
  `/dbs lookup stone` → *"Stone now looks like Lava — look for that to find it."*

## Swap map key binding

**Options → Controls → Miscellaneous → "Open Swap Map"** (unbound by default — assign a key).
Pressing it opens a scrollable list of every swap so far. Requires Cloth Config; otherwise use
`/dbs list`.

## Building

```sh
./gradlew build
```

The built jar is in `build/libs/`. `build` also runs the gametests (`runGameTest`): it starts a
throw-away test server in `build/run/gameTest` (its world is deleted before every run) and, for
that, **accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA) on your behalf**
(`eula = true` in `build.gradle`). If you don't accept it, run `./gradlew build -x runGameTest`.

To try it in a dev client/server:

```sh
./gradlew runClient
./gradlew runServer
```

## License

CC0-1.0. Do whatever you like with it.
