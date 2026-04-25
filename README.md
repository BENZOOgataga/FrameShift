# FrameShift

A NeoForge 1.21.1 server-side mod for loading and pasting schematics without causing lag spikes or crashes.

**Performance safety over speed** - the system degrades gracefully under load instead of freezing the server.

---

## Features

- Progressive block placement - schematics are pasted over many ticks, never all at once
- Adaptive throttling - automatically slows down when the server is struggling (based on MSPT)
- Job control - pause, resume, and cancel pastes mid-way through
- Rollback on cancel - cancelling a job restores every block the paste already changed
- Full NBT support - block entities (chests, signs, spawners) and entities are preserved
- Gravity block safety - detects all gravity-affected blocks (sand, gravel, concrete powder, suspicious sand/gravel, pointed dripstone, and modded equivalents) and optionally freezes them during paste to prevent premature falling
- Configurable limits - tune blocks-per-tick, time budget, concurrent jobs, and more

## Supported Formats

| Format | Support |
|--------|---------|
| `.schem` (Sponge) | ✅ Primary |
| `.schematic` (Legacy) | 🔄 Planned |

## Commands

| Command | Description |
|---------|-------------|
| `/schem list` | List available schematics |
| `/schem info <name>` | Show dimensions, block count, estimated paste time |
| `/schem paste <name> [x y z]` | Paste a schematic at a position |
| `/schem status` | Show progress of active pastes |
| `/schem pause <id>` | Pause a running paste |
| `/schem resume <id>` | Resume a paused paste |
| `/schem cancel <id>` | Cancel a paste |
| `/schem reload` | Reload the config file |

### Paste Flags

`--ignore-air` `--include-entities` `--include-block-entities` `--rotation <0|90|180|270>` `--mirror`

## Configuration

Generated at `config/frameshift-server.toml` on first launch.

```toml
[tick]
maxBlocksPerTick = 2000        # blocks placed per tick at full speed
maxMillisPerTick = 8           # hard time limit per tick (ms)
adaptiveThrottling = true      # slow down automatically when server lags

[chunks]
preloadChunks = true           # load chunks before starting a paste
forceLoadChunks = false        # keep chunks loaded for the paste duration
chunkRadiusLimit = 8           # max chunk radius around the paste origin

[limits]
maxSchematicVolume = 50000000  # reject schematics larger than this (X*Y*Z)
maxBlocksTotal = 20000000      # reject schematics with more non-air blocks
maxConcurrentJobs = 2          # max simultaneous paste jobs

[filesystem]
schematicDirectories = [
  "worldedit/schematics",
  "config/worldedit/schematics",
  "schematics"
]
```

## Compatibility

- **Minecraft:** 1.21.1
- **NeoForge:** 21.1.219+
- **Side:** Server-side only (no client install required)

## Building from Source

Requires Java 21.

```bash
./gradlew build
```

Output jar: `build/libs/frameshift-1.0.0.jar`

---

Made by [BENZOOgataga](https://benzoogataga.com)
