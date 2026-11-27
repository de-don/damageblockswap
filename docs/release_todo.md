# Release TODO

## Supported Minecraft versions (check before every release)

Now the mod declares only `minecraft: ~26.1.2` (`fabric.mod.json`) and builds against
`minecraft_version=26.1.2` (`gradle.properties`).

Before a release decide which Minecraft versions to support and declare them all:
- list the versions the mod really works on (test each one, API/mappings differ between versions);
- put the range in `fabric.mod.json` (`depends.minecraft`) and in the Modrinth/CurseForge listing;
- if it needs separate builds per version, set up one build/branch per version;
- match the Fabric API minimum per version (`depends.fabric-api`).
