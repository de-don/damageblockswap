# Release 1.1.0 TODO

Tag `v1.1.0` is set only after plan item 5 is done (see below). Source: the final Opus review of
`main..safe-rules` (before-release list, minors), plus earlier notes.

## Before the 1.1.0 tag

- [ ] **Item 5 of the hardening plan** (the release waits for it): admin commands
      (`/dbs undo|reset|pause|resume|add|reload|status`, permission level 2+), S2C packet that syncs
      the swap map to clients (client stays optional, check `canSend`), server messages through
      `translatableWithFallback` + `ru_ru.json`, pin the Loom version (not SNAPSHOT), Mod Menu/Cloth via
      `clientCompileOnly` + `localRuntime`, `gradle/actions/setup-gradle` in CI.
- [ ] **Supported Minecraft versions.** Now only `~26.1.2` (`fabric.mod.json` `depends.minecraft`,
      `gradle.properties` `minecraft_version`). Decide which versions to support, test each, declare the
      range in `fabric.mod.json`, on Modrinth/CurseForge, and match the Fabric API minimum per version.
- [ ] **Version and CHANGELOG.** `mod_version` stays `1.1.0`. All changes (items 1-5) live in the single
      `1.1.0` section; add item 5 to it and re-read the Known issues (the map sync fixes the first one).
      Keep the "back up your world, no downgrade" warning at the top.
- [ ] **Merge PR #2** (`safe-rules` -> `main`), then run `./gradlew build` on the final commit (unit tests
      + gametests) and wait for CI.
- [ ] **Live checks on the final jar:** old 1.0.x world -> update -> hits -> restart; dedicated server
      without Cloth and Mod Menu; LAN with a guest (both players see rules and the chat message);
      vanilla client joining a modded server.
- [ ] Create tag `v1.1.0`; GitHub Actions builds and attaches the jar. Only on the user's "yes".

## Optional, can wait for 1.1.1

From the Opus review, minor findings:

- [ ] Flood of chat messages after a long journal write failure (`unsavedHits` grows without a limit,
      `DamageBlockSwap.java` ~198/214): cap it or send one "saved N rules" summary.
- [ ] Load on big servers: `refreshAroundAllPlayers` runs on every successful write (20 players with
      `swapRadius=16` is ~22k `planChunk` calls per tick if hits come almost every tick). Mention next to
      `swapRadius` in the README, or add a per-tick limit.
- [ ] Leaves are always persistent now (`SwapStates.java:75`), also leaf -> leaf and for old rules: natural
      leaves in touched chunks stop decaying after tree felling. Consider setting it only when the
      source block is not leaves.
- [ ] Announcement ` got hurt: ` is an English literal (`SwapAnnouncement.java:22`): make it
      translatable with a fallback (fits item 5).
- [ ] Duplicate console line when `logging=true` (`DamageBlockSwap.java:223-230`).
- [ ] A config with `configVersion > 2` (after a downgrade from a future version) is rewritten as 2 and
      migrated again (`Config.java:225`).
- [ ] `ClothConfigScreen.java:111` hardcodes `"#damageblockswap:ignored"`; take it from
      `Config.DEFAULT_IGNORED_DAMAGE_TYPES`.
- [ ] README: say that the mod is server-side only and works with vanilla clients (verify live first).
- [ ] No global limit on the number of rules (10 players in combat can add ~10 rules per second).
- [ ] Settings made in the client UI do not affect a remote server; the screen does not say so.
- [ ] Dedicated server reads the config only at start; `/reload` does not (`/dbs reload` is item 5).
- [ ] Dedicated server without Cloth/Mod Menu is not covered by a test (Cloth is `implementation`).
- [ ] Known-issues candidates: damage from lava/magma/cactus can loop one rule per cooldown (a safe
      zone around the player was not built); some unstable blocks (corals, copper, concrete powder,
      sponge, eggs) are not in `never_swap`; `powered`/`lit` can stay stuck until a neighbour update.
