# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FrameShift is a NeoForge 1.21.1 server-side mod for loading and pasting schematics without causing server lag or crashes. Performance safety over speed — the system must degrade gracefully rather than freeze or crash the server.

## Build & Run

This is a standard NeoForge/Gradle mod project. Typical commands (populate once build files exist):

```bash
./gradlew build          # compile and package
./gradlew runServer      # start a dev server
./gradlew test           # run tests
```

## Architecture

### Core Rule
- File parsing: async (off server thread)
- World modification: server thread only, every tick

### Job Model
Each paste operation is a `SchematicPasteJob` with:
- `jobId`, `schematicName`, `level`, `origin`, `state`
- Three queues: block placements → block entities → entities

### Placement Flow
1. Parse schematic file asynchronously
2. Convert to placement task queues
3. Each server tick, drain queues up to configured limits
4. Stop tick early if time budget or server load thresholds are exceeded

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
1. `.schem` (Sponge — primary)
2. `.schematic` (legacy — optional)

Both require full block state, block entity (NBT), and optional entity support.

### Schematic Search Paths
```
worldedit/schematics/
config/worldedit/schematics/
schematics/
config/optimizedschematics/
```
Configurable via `schematicDirectories` in the config file.

## Command Reference

| Command | Purpose |
|---------|---------|
| `/schem list` | List available schematics with dimensions, block count, file size |
| `/schem info <name>` | Show dimensions, volume, block entities, estimated paste time |
| `/schem paste <name> [x y z]` | Paste with optional flags: `--ignore-air`, `--include-entities`, `--include-block-entities`, `--rotation`, `--mirror` |
| `/schem status` | Progress, blocks placed/remaining, ETA |
| `/schem cancel/pause/resume <jobId>` | Job lifecycle control |
| `/schem reload` | Reload config |

## Chunk Management
- Preload required chunks before paste begins
- Never place blocks in unloaded chunks
- `chunkRadiusLimit = 8` (configurable)
- `forceLoadChunks = false` by default

## Failure Handling
On chunk unload, lag spike, or manual cancel: pause the job and preserve its state for resumption. Do not discard partial progress.

## Out of Scope (MVP)
- GUI
- Undo/rollback
- Cross-dimension pasting
- Client-side rendering
- Instant paste
