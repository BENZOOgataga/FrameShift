# Known Issues

This file tracks bugs, rough edges, and implementation risks that are currently known but not fully solved.

## Active Functional Issues

### Exact Clear Mode Is Extremely Expensive

- The current exact paste flow clears the full schematic bounds first, then places schematic blocks.
- For very sparse but very large schematics, this creates a huge internal operation count.
- Example: a `723 x 352 x 805` schematic has a raw volume of `204,869,280` positions before any actual block placement is counted.
- This is currently correct behavior for exact clearing, but it can make jobs take a very long time.

### ETA Is Still Approximate

- ETA is better than before, but it is still an estimate.
- It does not yet explicitly model:
  - chunk wait time
  - chunk load pauses
  - restart/resume overhead
  - block entity heavy sections
- On huge jobs, ETA can still drift as server conditions change.

### Cancel Does Not Restore Pre-Job World State

- There is currently no real undo/rollback system.
- Cancelling or failing a job does not restore blocks that were already cleared or placed.
- This is one of the highest priority missing safety features.

### Status Can Still Be Confusing On Very Large Jobs

- The status output now separates user-facing block counts from backend op counts, but large exact jobs still expose both concepts.
- Admins may still confuse:
  - schematic blocks
  - clear operations
  - queued tasks
  - total backend ops

### Jobs Do Not Persist Across Server Restart Yet

- Jobs are runtime-only.
- A server restart currently loses active in-memory job state.
- This means long-running clear/place jobs are not yet crash-safe or restart-safe.

### Jobs Continue Independently Of Player Presence, But HUD Does Not Reattach

- The job itself does not depend on the player continuing to stand still.
- However, reconnect behavior for the starter’s HUD/session experience is not designed yet.
- There is no robust reconnect-aware job UX at the moment.

## Command / UX Gaps

### `/schem status` Does Not Show Anchor Bounds

- `/schem status <jobId>` now shows origin, phase, progress, ETA, queue sizes, and placed/unchanged/failed counts.
- It does not yet show the world-space bounding box of the paste (the full min/max corner coordinates).
- This would require tracking schematic dimensions alongside the origin in the job.

### Entity Paste Is Not Implemented

- `SchematicReadOptions.includeEntities` exists but is hardcoded to `false` in the paste command.
- Entities present in schematic files are silently ignored.
- Block entities (chests, signs, etc.) work correctly; only mob/item entities are missing.

### Legacy `.schematic` Format Is Not Supported

- Only Sponge v2 and v3 `.schem` files are supported.
- The `LEGACY_SCHEMATIC` enum value exists as a placeholder but has no reader implementation.
- Attempting to use a `.schematic` (MCEdit/Schematica format) file will result in "unsupported format".

### Freeze-Gravity Barriers Are Permanent

- When `freeze-gravity` is used, barrier blocks are placed below any falling block (sand, gravel, etc.) that has no schematic support beneath it.
- These barriers are intentional and permanent - they hold floating structures in place.
- A warning is shown in chat, but admins should be aware that pasting a large floating sand structure will leave barrier blocks throughout the world below it.
- Barriers can be removed manually with WorldEdit or similar tools.

### `/schem info` Does Not Yet Show Full Planning Data

- Offset is shown now.
- But `/schem info` still does not yet provide all the planning fields users would expect for large jobs, such as:
  - non-air block count
  - estimated clear operations at a target anchor
  - estimated runtime by mode
  - world-space bounds preview for a target position

### No Dry-Run / Plan Command

- There is no command yet to validate a paste plan without starting the job.
- This makes it easier to start an unexpectedly expensive exact clear job by mistake.

## Reliability Risks In Current Implementation

### Gravity Placement Queue Is Unbounded

- `placementQueue` (normal blocks) is a bounded `LinkedBlockingQueue` that blocks the async producer thread when full, capping memory usage.
- `gravityPlacementQueue` (falling blocks) is a `PriorityBlockingQueue` with no capacity limit - it grows unboundedly.
- A schematic with a very large number of gravity blocks (e.g. a sand mountain) could consume significant heap before the tick thread drains it.
- Mitigation: the existing `maxSchematicVolume` and `maxBlocksTotal` config limits reduce the practical worst case.

### Console Operators Share One Loaded Schematic Session

- All console-sourced commands share a single session key (`UUID(0, 0)`).
- If multiple operators issue `/schem load` from the console at the same time, each load overwrites the previous one.
- In practice this is rare, but on multi-operator servers where several admins run commands from console simultaneously, one operator's paste could use the wrong schematic.

### No Rollback Snapshot Storage

- The code does not record original world state before clearing/placing.
- Because of that:
  - cancel cannot truly undo
  - crash recovery cannot reconstruct pre-job state
  - failed jobs can leave the world partially modified

### Large Exact Jobs Produce Very High Queue Pressure

- The job model uses a bounded queue, which is correct for memory control.
- But exact clear mode on huge schematics naturally keeps the queue near saturation for long periods.
- This is not a correctness bug by itself, but it is an operational stress point.

### Clear Phase Uses Metadata Bounds, Not Fine-Grained Sparsity

- This is intentional for correctness.
- But it means empty space inside the bounds is still processed as part of the clear phase.
- There is no hybrid mode yet that preserves correctness while reducing full-volume cost.

## Codebase Issues / Technical Debt

### Config Hot-Reload Uses NeoForge Internal Reflection

- `/schem reload` calls `ConfigTracker.loadConfig()` via reflection because NeoForge does not expose a public API for triggering a config reload from commands.
- If NeoForge renames or removes this internal method in a future update, the reload command will throw an exception at runtime with no compile-time warning.
- The method signature is verified at call time, so the failure mode is a runtime error rather than a silent wrong-behavior failure.

### `placeableBlocks` Counter Is An `int`

- In `streamPasteIntoJobAsync`, the counter for queued placements and the job's `displayTotalBlocks` / `clearOperationsTotal` fields are all `int`.
- The default `maxSchematicVolume` of 50,000,000 is safely below `Integer.MAX_VALUE`.
- If the config is raised to an extreme value (above ~2.1 billion), `Math.toIntExact` will throw `ArithmeticException` at paste time rather than silently overflowing.
- This is only reachable by deliberately misconfiguring the server.

### Tick-Side Progress And ETA Logic Is Growing Complex

- Progress, phase display, operation counts, and ETA all now depend on several parallel counters.
- This is manageable today, but the logic is starting to deserve its own small abstraction instead of continuing to grow inside tick/job classes.

### Some Command Surface Promises In `CLAUDE.md` Are Ahead Of The Current Implementation

- The command reference in `CLAUDE.md` lists a broader management surface than what currently exists in code.
- That can create expectation mismatch for future contributors.

### Deprecated NeoGradle Run DSL Warning Still Exists

- Test/build output still shows a deprecation warning about `getProgramArguments()`.
- This is not breaking today, but it should be cleaned up before future toolchain upgrades.

### Current Job Persistence Model Is In-Memory Only

- The job model was not originally built around serialization/resume.
- Adding restart-safe jobs will likely require revisiting job state structure and queue reconstruction.

## Performance / Design Tradeoff Issues

### Exactness Versus Speed Is Not Yet Exposed As A First-Class User Choice

- Right now the current exact behavior favors correctness.
- Users do not yet have a clear command-level choice between:
  - exact full-volume clear
  - faster non-air-only placement
  - other future replacement modes

### Block Entity And Entity Handling Need More Production Hardening

- Block entity placement exists, but long-job reliability around chunk boundaries and pause/resume scenarios needs more hardening.
- Entity handling is not yet a major validated part of the current workflow.

## Suggested Priority Order

### High Priority

- real cancel rollback
- persisted job resume after restart
- richer `/schem info` and planning before paste
- `/schem status <jobId>` focused view

### Medium Priority

- entity paste support
- more stable and more explainable ETA
- explicit paste modes (exact / fast / replace)
- improved status clarity for large jobs
- bound the gravity placement queue (or share the normal queue's capacity)

### Lower Priority

- legacy `.schematic` format support
- multi-console session isolation
- extra admin utilities
- reconnect-aware HUD quality improvements
- cleanup of build/deprecation warnings
- replace `reloadConfig` reflection with a public NeoForge API when one becomes available
