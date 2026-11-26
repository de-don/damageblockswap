# Changelog

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
