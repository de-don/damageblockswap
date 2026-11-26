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
stays responsive no matter how large the swap.

Blocks that can't survive where they'd be placed (rails, torches, flowers, redstone…) are left
alone instead of popping off as dropped items. Blocks that store data — chests, furnaces, signs,
shulker boxes, spawners, and anything else with a block entity — are never swapped, so your
inventories and builds are safe.

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
| `updateNeighbors` | `false` | Run vanilla physics after swaps (sand falls, water flows). **Can cause severe lag** with many/common blocks — leave off unless you want it. |
| `enableLiquids` | `true` | Allow water and lava to be swapped. |
| `enableGravityBlocks` | `true` | Allow sand, gravel and other falling blocks to be swapped. |
| `cooldownTicks` | `0` | Minimum ticks between swaps (20 = 1 second). 0 = no cooldown. |
| `chatMessages` | `true` | Announce each swap in chat. |
| `logging` | `true` | Log swaps to the console/log file. |
| `blacklist` | `[]` | Block IDs never swapped, e.g. `["minecraft:bedrock", "diamond_ore"]`. The `minecraft:` prefix is optional. |

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

The built jar is in `build/libs/`. To try it in a dev client/server:

```sh
./gradlew runClient
./gradlew runServer
```

## License

CC0-1.0. Do whatever you like with it.
