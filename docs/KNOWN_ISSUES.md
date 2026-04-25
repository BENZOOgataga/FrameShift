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

### Rollback Does Not Undo Spawned Entities

- Block and block-entity rollback is implemented.
- But schematic entities spawned during the final entity phase are not yet included in rollback.
- Cancelling after entities have spawned will not despawn them automatically.

### Status Can Still Be Confusing On Very Large Jobs

- The status output now separates user-facing block counts from backend op counts, but large exact jobs still expose both concepts.
- Admins may still confuse:
  - schematic blocks
  - clear operations
  - queued tasks
  - total backend ops

### Restart Resume Is Graceful-Stop Focused

- Jobs now persist across graceful shutdown and resume on startup.
- A hard crash or kill can still lose recent in-memory progress that was never flushed to disk.
- Resume reliability is much better than before, but not fully crash-proof yet.

### Reconnect HUD / ETA Still Needs Polish

- The HUD now reattaches when the starter reconnects.
- ETA and progress are much better than before, but the display still needs more refinement on huge jobs and chunk-wait-heavy runs.

## Command / UX Gaps

### `/schem status` Does Not Show Anchor Bounds

- `/schem status <jobId>` now shows origin, phase, progress, ETA, queue sizes, and placed/unchanged/failed counts.
- It does not yet show the world-space bounding box of the paste (the full min/max corner coordinates).
- This would require tracking schematic dimensions alongside the origin in the job.

### Entity Paste Needs Wider Validation

- Entity pasting is implemented for Sponge `.schem` files and resumes after graceful restart.
- It still needs more production validation across mobs, item frames, paintings, boats, minecarts, and passenger stacks.
- Entity rollback is not implemented.

### Legacy `.schematic` Format Is Not Supported

- Only Sponge v2 and v3 `.schem` files are supported.
- The `LEGACY_SCHEMATIC` enum value exists as a placeholder but has no reader implementation.
- Attempting to use a `.schematic` (MCEdit/Schematica format) file will result in "unsupported format".

### Freeze-Gravity Barriers Are Permanent

- When `freeze-gravity` is used, barrier blocks are placed below any gravity-affected block that has no schematic support beneath it.
- This covers all blocks that implement Minecraft's `Fallable` interface: sand, red sand, gravel, concrete powder (all 16 colours), anvils, suspicious sand, suspicious gravel, pointed dripstone (stalactites), and any modded blocks that correctly implement `Fallable`.
- These barriers are intentional and permanent - they hold floating structures in place.
- A warning is shown in chat, but admins should be aware that pasting a large floating structure will leave barrier blocks throughout the world below it.
- Barriers can be removed manually with WorldEdit or similar tools.

### `/schem info` Still Does Not Show Anchor-Aware Planning Data

- `/schem info` is now file-metadata focused.
- Anchor-aware bounds, clear/place counts, and ETA are handled by `/schem plan`.
- If desired later, `/schem info` could link more explicitly to that workflow.

### `/schem plan` Does Not Yet Check Protected/Unsafe Targets

- Dry-run planning now exists.
- It does not yet warn about protected regions, sensitive overlap, or custom server safety rules.

## Reliability Risks In Current Implementation

### Chunk Radius Limit Is Still Informational

- `/schem plan` now reports chunk span/radius and warns when the preview exceeds `chunkRadiusLimit`.
- But the runtime path still does not hard-enforce that limit before paste start.
- That means the config is not yet a true safety guard.

### Console Operators Share One Loaded Schematic Session

- All console-sourced commands share a single session key (`UUID(0, 0)`).
- If multiple operators issue `/schem load` from the console at the same time, each load overwrites the previous one.
- In practice this is rare, but on multi-operator servers where several admins run commands from console simultaneously, one operator's paste could use the wrong schematic.

### Entity Resume Relies On Count-Based Skipping

- Entity resume currently skips the first `entitiesApplied` entries from the schematic entity list on restart.
- That is good enough for sequential spawn order, but it is less explicit than per-entity persisted checkpoints.
- If future entity logic becomes more complex, this may deserve a stronger persistence model.

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

### Job Persistence Still Needs More Operator-Facing Diagnostics

- Persistence and cleanup now exist.
- But there is still no dedicated inspect command that explains why a specific persisted job failed to resume.
- Today the operator mostly has logs plus `/schem cleanup`.

## Performance / Design Tradeoff Issues

### `replace` Paste Mode Still Does Not Exist

- `exact` and `fast` are now explicit first-class modes.
- The remaining mode gap is `replace`, which would place schematic air as authored instead of using the current exact/fast behaviors.

### Block Entity And Entity Handling Need More Production Hardening

- Block entity placement exists, but long-job reliability around chunk boundaries and pause/resume scenarios still needs more hardening.
- Entity handling is now implemented, but it is not yet a major validated part of the workflow.

## Suggested Priority Order

### High Priority

- hard-enforce `chunkRadiusLimit`
- add `replace` paste mode
- improve persisted-job diagnostics beyond cleanup
- better bounds/anchor surfacing in active job inspection

### Medium Priority

- more stable and more explainable ETA
- entity rollback support
- explicit `replace` mode
- improved status clarity for large jobs
- protection/overlap warnings in `/schem plan`

### Lower Priority

- legacy `.schematic` format support
- multi-console session isolation
- extra admin utilities
- reconnect-aware HUD quality improvements
- cleanup of build/deprecation warnings
- replace `reloadConfig` reflection with a public NeoForge API when one becomes available
