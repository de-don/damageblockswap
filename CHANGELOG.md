# Changelog

## 1.1.0

**Back up your world before updating. Downgrading to 1.0.x is not supported.**

### Added
- Operator commands (permission level 2): `/dbs status`, `/dbs pause` / `/dbs resume` (damage stops
  adding rules until resumed or the server restarts), `/dbs add <block> <block>` (a rule by hand,
  refusing air, block-entity, two-block and `never_swap` blocks), `/dbs undo` (cancels the newest rule
  by adding it once more; nothing is erased from the journal) and `/dbs reload` (re-reads the config on
  a running server; a broken or missing file keeps the current settings). `/dbs list` and `/dbs lookup` stay open
  to everyone.
- Chat messages and command replies are translatable (English, Russian), with an English fallback for
  vanilla clients.

### Fixed
- After the journal could not be saved for a long time, the rules are no longer announced one line
  each once it saves again: at most 20 lines, then one "...and N more new rules" line. Hits waiting
  for a save no longer pile up in memory without a limit.
- Crashes and compatibility: Mod Menu works without Cloth Config, `/dbs lookup` accepts block
  ids correctly, locale-independent id handling, fake players ignored, per-dimension player chunk tracking.
- Swap map screen no longer reads the journal while the server thread writes it.
- Multiplayer: a new rule now updates the area around **every** player right away, not only around
  the player who got hurt (others used to see it only after crossing a chunk border).
- Multiplayer: new rules are announced to every player and the server console, with the name of the
  player who got hurt (`Steve got hurt: Stone ↔ Sand`). If the rule book could not be saved, the
  message comes once a retry saves it instead of never.
- The swap map screen and the Mod Menu "Swap Map" tab no longer say "No blocks have been swapped yet"
  on a dedicated server or as a LAN guest, where they can't see the server's rules; they point to
  `/dbs list` instead.

### Changed
- Reliable swap journal: atomic writes with fsync, corrupt files set aside as `.corrupt-<date>`,
  journal ids, placeholders for blocks that no longer exist, quarantine for chunks that do not
  match the journal. Worlds from 1.0.x are converted automatically (`.v1-backup` is kept).
- Chunk processing: per-chunk progress cursor (a chunk is never swapped twice, even after a crash
  or unload), chunks wait for their neighbours, swaps no longer trigger neighbour updates or drops,
  nearest chunks first, much cheaper block scan.
- Safe rules: new rules never pick blocks in the block tag `damageblockswap:never_swap` (bedrock,
  portals and end portal frames, barriers, light, fire, command/structure blocks, infested blocks,
  doors, beds, tall plants and other two-block blocks, ...). Datapacks can change the tag.
- `blacklist` accepts block tags (`#minecraft:logs`); unknown ids and tags are logged at startup.
  It now contains `minecraft:obsidian` and `minecraft:crying_obsidian` by default so a swap cannot
  destroy a Nether portal frame; remove them in the settings to allow that. An old config with an
  empty `blacklist` gets them once on update; a list you filled yourself is left as it is, add
  obsidian manually if you want it excluded.
- Swapped blocks keep their shared properties (log axis, stair facing, waterlogging, ...) instead
  of being reset to the default state; leaves are placed persistent and no longer decay.
  This also applies to rules made before the update.
- Default `cooldownTicks` is now 20. Configs that still have the old default 0 are raised to 20 once.
- New `ignoredDamageTypes` setting: drowning, starving, suffocation, cramming, burning, freezing,
  the void, the world border and `/kill` no longer cause swaps by default (tag `damageblockswap:ignored`).
- A hit that adds no rule no longer starts the cooldown.

### Known issues
- The swap map is not synced to clients: it only works in single-player or for the LAN host.
  On a server use `/dbs list`.
- Damage from lava, magma or cactus still causes swaps, so a rule that surrounds you with them can
  repeat once per cooldown.
- A world whose 1.0.x session crashed right after a rule for an unknown block may apply one rule twice.
