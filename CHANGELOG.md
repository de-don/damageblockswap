# Changelog

## Unreleased

### Changed
- Safe rules: new rules never pick blocks in the block tag `damageblockswap:never_swap` (bedrock,
  portals and end portal frames, barriers, light, fire, command/structure blocks, infested blocks,
  doors, beds, tall plants and other two-block blocks, ...). Datapacks can change the tag.
- `blacklist` accepts block tags (`#minecraft:logs`); unknown ids and tags are logged at startup.
- Swapped blocks keep their shared properties (log axis, stair facing, waterlogging, ...) instead
  of being reset to the default state; leaves are placed persistent and no longer decay.
  This also applies to rules made before the update.
- Default `cooldownTicks` is now 20. Configs that still have the old default 0 are raised to 20 once.
- New `ignoredDamageTypes` setting: drowning, starving, suffocation, cramming, burning, freezing,
  the void, the world border and `/kill` no longer cause swaps by default (tag `damageblockswap:ignored`).
- A hit that adds no rule no longer starts the cooldown.

## 1.1.0

**Back up your world before updating. Downgrading to 1.0.x is not supported.**

### Fixed
- Crashes and compatibility: Mod Menu works without Cloth Config, `/dbs lookup` accepts block
  ids correctly, locale-independent id handling, fake players ignored, per-dimension player chunk tracking.
- Swap map screen no longer reads the journal while the server thread writes it.

### Changed
- Reliable swap journal: atomic writes with fsync, corrupt files set aside as `.corrupt-<date>`,
  journal ids, placeholders for blocks that no longer exist, quarantine for chunks that do not
  match the journal. Worlds from 1.0.x are converted automatically (`.v1-backup` is kept).
- Chunk processing: per-chunk progress cursor (a chunk is never swapped twice, even after a crash
  or unload), chunks wait for their neighbours, swaps no longer trigger neighbour updates or drops,
  nearest chunks first, much cheaper block scan.

### Known issues
- Default settings are still unsafe: cooldown 0, no block blacklist (bedrock, end portal frames,
  barriers can be swapped), swapped blocks lose their properties. Planned for 1.2.0.
- A world whose 1.0.x session crashed right after a rule for an unknown block may apply one rule twice.
