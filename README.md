# MVSwitcher

**MVSwitcher** is a Java 21 command-line tool for migrating Minecraft mod projects between Minecraft versions on **NeoForge only**.

It is designed for bidirectional migrations. The migration engine is rule-driven and can route through multiple intermediate versions when the rule pack contains a connected path. Version/API transformations are explicit, reversible where reverse edges exist, and can be extended without changing the CLI.

## Requirements
- Java 21
- Gradle 8.10+ (the GitHub Actions build uses the Gradle setup action)

## Build
```bash
gradle clean test installDist
```

The executable distribution is created in `build/install/mvswitcher/`.

Linux/macOS:
```bash
./build/install/mvswitcher/bin/mvswitcher --help
```

Windows:
```bat
build\install\mvswitcher\bin\mvswitcher.bat --help
```

## Commands
```bash
mvswitcher analyze ./MyMod
mvswitcher versions
mvswitcher migrate ./MyMod --to 1.21.11
mvswitcher migrate ./MyMod.zip --from 26.3 --to 1.21.11 --dry-run
mvswitcher migrate ./MyMod --from 26.3 --to 1.21.11 --skip-build
mvswitcher migrate ./MyMod --from 1.21.11 --to 26.3 --neoforge-version 26.3.0.99 --java 25
```

By default, output is written next to the source under `converted/<project>-<target>/`. The input project is never edited. Existing output directories are protected unless `--force` is supplied.

## Current support status
The first profile set includes Minecraft/NeoForge **1.21.11 / 21.11.48** and **26.3 / 26.3.0.99**. The real TeroMod project has now been migrated from 26.3 to 1.21.11 and its target Gradle build passed in GitHub Actions. This is a tested one-way migration path; the reverse semantic API patch set is not implemented yet. Other targets require an explicit NeoForge version and Java version. A profile being recognized does not mean every API change is automatically handled.

The built-in rules are intentionally small. Unknown changes are reported for manual work rather than guessed. Add custom transformations using `--rules path/to/rules.json`:

```json
{
  "rules": [
    {
      "id": "example-forward",
      "from": "1.21.11",
      "to": "26.3",
      "includes": ["**/*.java"],
      "find": "OldApiName",
      "replace": "NewApiName"
    }
  ]
}
```

Rules are exact version-edge transformations. Add a reverse rule to support the opposite direction. Use narrow `includes` patterns: broad text replacements can change comments or string literals as well as code.

## Safety and limitations
- NeoForge projects only; Forge and Fabric are rejected.
- Source files and resources are copied before transformations.
- Java sources are parsed for syntax diagnostics after rewriting.
- The target Gradle build is run automatically by default; use `--skip-build` to disable it.
- The tool updates common version properties, synchronizes existing `minecraft_version_range` entries in Gradle and NeoForge TOML configuration files with the target Minecraft version, and applies a small, version-specific set of tested NeoForge API patches.
- A successful TeroMod compile validates the 26.3 -> 1.21.11 path only; runtime behavior in Minecraft still needs in-game testing.
- Reverse semantic patches and broader version coverage are still in development.
- Multi-hop migrations are possible when the rule database contains a connected sequence of version edges.
- A complete migration requires version-specific API rules and validation against the target NeoForge toolchain. The project is built to grow through tested rule packs.
