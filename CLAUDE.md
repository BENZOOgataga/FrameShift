# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FrameShift is a NeoForge 1.21.1 server-side mod for loading and pasting schematics without causing server lag or crashes. Performance safety over speed - the system must degrade gracefully rather than freeze or crash the server.

Author: BENZOOgataga (benzoogataga.com) - Mod ID: `frameshift` - Group: `com.benzoogataga.frameshift`

## Working With This User

The project owner is a Java/modding beginner - do not assume familiarity with NeoForge APIs, Gradle, or Minecraft internals. When explaining or implementing anything:
- Prefer clear, readable code over clever abstractions
- Add a one-line comment to every non-obvious method or field
- Explain NeoForge concepts (event buses, mod containers, config specs, etc.) in plain language when they come up

## Build & Run

```bash
./gradlew build       # compile and package the mod jar
./gradlew runServer   # start a local dev server (stops with 'stop' in console)
./gradlew runClient   # launch the game client for testing
./gradlew runData     # generate JSON data files (models, recipes, etc.)
```

On Windows, `./gradlew` works in Git Bash. Use `gradlew.bat` in CMD/PowerShell.

## Toolchain (Non-Negotiable Versions)

These versions were arrived at after resolving real compatibility conflicts - do not upgrade without careful testing:

| Tool | Version | Reason |
|------|---------|--------|
| NeoForge | `21.1.219` | Production server version |
| NeoGradle plugin | `7.0.165` | Last version compatible with Gradle 8.8 (7.0.166+ requires 8.10, 7.1.x requires 8.14) |
| Gradle | `8.8` | Pinned to match NeoGradle 7.0.165 |
| Java | `21` | Required by Minecraft 1.21+ |

## Known Build Quirks

**ASM version conflict** - NeoForge's bootstraplauncher crashes if different ASM versions appear on the module path vs classpath. `build.gradle` forces all `org.ow2.asm:*` to `9.8` via `resolutionStrategy`. Do not remove this block.

**Mod descriptor filename** - NeoForge 1.21+ uses `META-INF/neoforge.mods.toml`. The old `META-INF/mods.toml` name causes a "for Minecraft Forge or older NeoForge" load error. Do not rename it back.

**processResources** - Uses `def replaceProperties` (not `var`) for Groovy compatibility. The `expand()` call substitutes `${...}` placeholders in `neoforge.mods.toml` at build time. `${file.jarVersion}` is a NeoForge runtime token and must NOT be in that map - use `${mod_version}` instead.

**Gradle wrapper args** - The `gradlew` script uses the `set --` + `xargs` pattern to forward CLI arguments to `GradleWrapperMain`. The simplified single-`eval` form does not pass arguments and silently runs the default `help` task instead.

**NeoForge event subscriptions** - The `@Mod` constructor receives `IEventBus modEventBus` (for mod lifecycle events) and `ModContainer modContainer` (for config registration). Config is registered via `modContainer.registerConfig(ModConfig.Type.SERVER, spec)`, not via `ModLoadingContext`.

**Runs DSL** - In NeoGradle 7.0.165, use `argument`/`arguments` (not the deprecated `programArgument`/`programArguments`).

## Package Structure

```
com.benzoogataga.frameshift/
├── FrameShift.java          @Mod entry point - wires event buses and config
├── command/
│   └── SchemCommand.java    /schem command tree (TODO)
├── schematic/
│   ├── SchematicData.java   parsed file holder (dimensions, blocks, BEs, entities)
│   └── SchematicLoader.java async file parser - returns CompletableFuture<SchematicData> (TODO)
├── job/
│   ├── SchematicPasteJob.java  job model: state enum + 3 ArrayDeque queues
│   └── JobManager.java         static registry, enforces maxConcurrentJobs
├── tick/
│   └── TickHandler.java     @SubscribeEvent ServerTickEvent.Post - drains queues (TODO)
├── config/
│   └── FrameShiftConfig.java   all ModConfigSpec entries, registered SERVER type
└── chunk/
    └── ChunkHelper.java     isLoaded / preloadChunks / releaseChunks utilities (TODO)
```

## Architecture

### Core Rule
- File parsing: async (off server thread) via `CompletableFuture.supplyAsync`
- World modification: server thread only, inside `ServerTickEvent.Post`

### Job Lifecycle
1. Command submits job → `JobManager.submit()` checks concurrent limit
2. `SchematicLoader.loadAsync()` parses file off-thread
3. On completion, job transitions `LOADING → RUNNING`, queues populated
4. Each tick: `TickHandler` drains queues up to configured limits
5. Job transitions to `DONE` or `PAUSED`/`CANCELLED` on control commands

### Adaptive Throttling (MSPT-based)
| MSPT | Throughput |
|------|-----------|
| < 35 ms | 100% |
| 35–45 ms | 50% |
| 45–50 ms | 25% |
| > 50 ms | pause |

### Hard Safety Limits
- `maxSchematicVolume = 50_000_000`
- `maxBlocksTotal = 20_000_000`
- `maxConcurrentJobs = 2`
- `maxBlocksPerTick = 2000`, `maxBlockEntitiesPerTick = 100`, `maxEntitiesPerTick = 20`
- `maxMillisPerTick = 8`

### Supported Formats
1. `.schem` (Sponge - primary)
2. `.schematic` (legacy - optional)

Both require full block state, block entity (NBT), and optional entity support.

### Schematic Search Paths
Configurable via `schematicDirectories` in `frameshift-server.toml`. Defaults:
```
worldedit/schematics/
config/worldedit/schematics/
schematics/
```

## Command Reference

| Command | Purpose |
|---------|---------|
| `/schem list` | List available schematics with dimensions, block count, file size |
| `/schem info <name>` | Show dimensions, volume, block entities, estimated paste time |
| `/schem paste <name> [x y z]` | Paste - flags: `--ignore-air`, `--include-entities`, `--include-block-entities`, `--rotation`, `--mirror` |
| `/schem status` | Progress, blocks placed/remaining, ETA |
| `/schem cancel/pause/resume <jobId>` | Job lifecycle control |
| `/schem reload` | Reload config |

## Failure Handling
On chunk unload, lag spike, or manual cancel: pause the job and preserve its state for resumption. Do not discard partial progress.

## Out of Scope (MVP)
- GUI, undo/rollback, cross-dimension pasting, client-side rendering, instant paste
