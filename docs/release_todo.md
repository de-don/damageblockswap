# Release 1.1.0 TODO

Tag `v1.1.0` is set only after plan item 5 is done (see below). Source: the final Opus review of
`main..safe-rules` (before-release list, minors), plus earlier notes.

## Before the 1.1.0 tag

- [x] (5a-5c) **Item 5 of the hardening plan** (the release waits for it): ~~admin commands
      (`/dbs undo|pause|resume|add|reload|status`, permission level 2+)~~ done in 5a, `reset` deferred
      (docs/decisions.md); ~~S2C packet that syncs
      the swap map to clients (client stays optional, check `canSend`)~~ done in 5b, ~~server messages through
      `translatableWithFallback` + `ru_ru.json`~~ (5a), ~~pin the Loom version (not SNAPSHOT), Mod Menu/Cloth via
      `clientCompileOnly` + `localRuntime`, `gradle/actions/setup-gradle` in CI~~ (5c).
- [x] (5c) **Settings UI for ignored damage types.** Today `ignoredDamageTypes` is a plain list of ids/#tags
      (default `#damageblockswap:ignored`: drowning, starving, suffocation, cramming, burning, freezing,
      the void, the world border, `/kill`). Make it easy to configure in the mod's settings screen: a
      collapsible section with one checkbox per damage type ("causes swaps" on/off), so players who want
      swaps from drowning, hunger etc. can switch them on. Keep the config file format compatible (the list
      stays the source of truth), list types from the live damage-type registry, and keep the datapack tag
      working. Needs a client-side screen change in `ClothConfigScreen` and a test for the checkbox ->
      `Config.ignoredDamageTypes` mapping.
- [x] **Supported Minecraft versions:** 26.1, 26.1.1, 26.1.2, 26.2, 26.3 (26.x only, older is out of scope),
      declared in `fabric.mod.json`, checked by the CI matrix (build + unit tests + gametests on each).
      Known limit: the client-only code (screens, key binding) is compile-checked on every version but
      not run in CI; the only API difference found (`setScreen` moved to `Minecraft.gui` in 26.2) is
      handled by `ScreenOpener`. When a new Minecraft version comes out: add it to the CI matrix, fix what
      breaks, then add it to `depends.minecraft`.
- [ ] **Version and CHANGELOG.** `mod_version` stays `1.1.0`. All changes (items 1-5) live in the single
      `1.1.0` section; add item 5 to it and re-read the Known issues (the map sync fixes the first one).
      Keep the "back up your world, no downgrade" warning at the top.
- [ ] **Merge the item 5 PR** (`commands-sync` -> `main`; PR #2 is already merged), then run `./gradlew build` on the final commit (unit tests
      + gametests) and wait for CI.
- [ ] **Live checks on the final jar:** old 1.0.x world -> update -> hits -> restart; dedicated server
      without Cloth and Mod Menu; LAN with a guest (both players see rules and the chat message);
      vanilla client joining a modded server. Item 5a adds: `/dbs` admin commands by an op and a
      non-op on a dedicated server; chat messages in Russian with the mod and in English on a vanilla
      client; `/dbs reload` after editing the config of a running server. Item 5b adds: swap map
      screen of a modded client on a dedicated server and as a LAN guest (shows the server's rules,
      updated after a hit when reopened, nothing stale after switching servers); a vanilla client
      is not kicked. Item 5c adds: the "Damage types that cause swaps" switches in single player (open
      from the pause menu, switch drowning on, save, drown: a rule appears; the config file has the tag
      replaced); on a dedicated server and as a LAN guest the section shows only the "server settings"
      note; `runClient` shows Mod Menu and Cloth.
- [ ] Create tag `v1.1.0`; GitHub Actions builds and attaches the jar. Only on the user's "yes".

## Optional, can wait for 1.1.1

From the Opus review, minor findings:

- [x] (5a) Flood of chat messages after a long journal write failure (`unsavedHits` grows without a limit,
      `DamageBlockSwap.java` ~198/214): cap it or send one "saved N rules" summary.
- [ ] Load on big servers: `refreshAroundAllPlayers` runs on every successful write (20 players with
      `swapRadius=16` is ~22k `planChunk` calls per tick if hits come almost every tick). Mention next to
      `swapRadius` in the README, or add a per-tick limit.
- [ ] Leaves are always persistent now (`SwapStates.java:75`), also leaf -> leaf and for old rules: natural
      leaves in touched chunks stop decaying after tree felling. Consider setting it only when the
      source block is not leaves.
- [x] (5a) Announcement ` got hurt: ` is an English literal (`SwapAnnouncement.java:22`): make it
      translatable with a fallback (fits item 5).
- [ ] Duplicate console line when `logging=true` (`DamageBlockSwap.java:223-230`).
- [ ] A config with `configVersion > 2` (after a downgrade from a future version) is rewritten as 2 and
      migrated again (`Config.java:225`).
- [x] (5c) `ClothConfigScreen.java:111` hardcodes `"#damageblockswap:ignored"`; take it from
      `Config.DEFAULT_IGNORED_DAMAGE_TYPES`.
- [x] (5c) README: say that the mod is server-side only and works with vanilla clients (verify live first).
- [ ] No global limit on the number of rules (10 players in combat can add ~10 rules per second).
- [x] (5c review-fix) Settings made in the client UI do not affect a remote server; the screen does not say so.
      The damage type section says so and has no switches there; the other settings still don't.
- [x] (5a) Dedicated server reads the config only at start; `/reload` does not (`/dbs reload` is item 5).
- [ ] Dedicated server without Cloth/Mod Menu is not covered by a test. Half done in 5c: they are
      `clientCompileOnly`, so main/gametest code can't reference them, but `localRuntime` still loads
      Cloth into `runGameTest`; a server run without them is still open.
- [ ] Known-issues candidates: damage from lava/magma/cactus can loop one rule per cooldown (a safe
      zone around the player was not built); some unstable blocks (corals, copper, concrete powder,
      sponge, eggs) are not in `never_swap`; `powered`/`lit` can stay stuck until a neighbour update.

## Found in the item 5 review (minor, can wait for 1.1.1)

- [ ] Map-sync packets are not rate limited: every saved rule sends the whole map (~1200 pairs, a few KB)
      to every modded client, up to 20 times a second if an op's command block calls `/dbs add` every tick.
- [ ] The Swap Map tab opened from the main menu (no connection) shows "this server has not sent its map".
      Add a separate "not in a world" state (`SwapMapScreen.addSwapEntries`, `getConnection() == null`).
- [ ] Test gaps: `/dbs reload` only checks the return code (not that the new value is applied),
      no gametests for `/dbs undo` on an empty journal and `/dbs add` on a read-only journal.
