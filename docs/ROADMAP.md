# FrameShift Roadmap

This roadmap mixes requested features with adjacent work needed to make them safe and usable in production.

## Principles

- Prefer correctness and recovery over raw speed.
- Long-running jobs must survive admin mistakes, player disconnects, and server restarts.
- User-facing progress should be understandable: phase, real block counts, ETA, and failure reason.
- Expensive automation should remain configurable so server owners can decide how aggressive FrameShift is allowed to be.

---

## Completed

### Implemented Features
- `/schem list` with cursor-based pagination
- `/schem info <name>` - dimensions, offset, volume, file size, data version, block entity / entity counts
- `/schem load <name>` - loads schematic into a per-player session for paste
- `/schem paste` - streams schematic directly into a bounded job queue; supports explicit `exact` / `fast` modes, legacy `no-clear`, `freeze-gravity`, `debug`, and an optional target position
- `/schem plan` - dry-run preview of bounds, chunk span, clear/place counts, and ETA from the loaded schematic
- `/schem status` - phase, percentage, queued block count, blocks placed / unchanged / failed, ETA
- `/schem cancel <jobId>` - starts throttled rollback and restores previously touched blocks when possible
- `/schem cleanup` - previews stale/corrupt persisted job directories, with `/schem cleanup apply` to prune them
- `/schem reload` - hot-reloads config
- `/flyspeed [value]` and `/flyspeed reset` - admin fly-speed utility with clamped range

### Infrastructure and Bug Fixes (April 2026)
- **Block entity position overflow** - `packPosition` used 10-bit masking per axis, silently misrouting block entities for schematics wider than 1023 blocks. Fixed to 16-bit long keys covering the full unsigned-short range.
- **Volume integer overflow** - `ParsedSchematic.volume()` returned `int`, overflowing for very large schematics. Changed to `long`; block stream cursor promoted to `long` to match.
- **Y-interleaved placement** - the tick loop previously drained all non-gravity blocks before starting any gravity blocks, meaning a torch or other attachment placed at the same Y as a supporting sand block could be placed before its support. Now both queues are interleaved by Y level (non-gravity wins ties), so every block's support is guaranteed in place before it is placed.
- **Loaded schematic session leak** - `LOADED_SCHEMATICS` sessions were never evicted. Cleared on `PlayerLoggedOutEvent`.
- **Double file read** - `streamPasteIntoJobAsync` opened and streamed the full schematic twice (count pass then enqueue pass). Collapsed to a single pass; the HUD uses `metadata.nonAirBlocks` as an estimate during streaming.
- **`suggestNames` O(n²) deduplication** - replaced `ArrayList.contains()` with a `LinkedHashSet` for O(1) per-entry dedup.
- **Duplicate `throttleFactor`** - identical method existed in both `TickHandler` and `SchemCommand`. Extracted to `FrameShiftConfig.throttleFactor(mspt)`.
- **Dead code** - `enqueueNormalPlacement()` was an unreachable duplicate of `enqueuePlacement()`. Removed.
- **Shared forward queue cap** - normal and gravity placements now share one bounded capacity budget, so gravity-heavy schematics cannot grow an unbounded queue in memory.
- **Reconnect-aware HUD** - jobs continue independently of player presence and reattach compact HUD/status messaging when the starter reconnects.
- **Persisted restart resume** - running, paused, and rollback jobs now persist to disk on graceful stop and resume on startup when enabled.
- **Rollback persistence** - cancel rollback survives graceful restart and preserves later external edits by skipping conflicted positions.
- **Planning preview** - `/schem plan` now reports bounds, chunk span, operation counts, and full-speed ETA before paste start.
- **Explicit paste modes** - `exact` and `fast` are now first-class command-level choices instead of only implicit flag combinations.
- **Entity pasting** - schematic entities are now read, normalized into world space, spawned under a tick budget, and resumed safely after graceful restart.
- **Malformed sign hardening** - malformed sign block-entity text is sanitized at import time to avoid console spam during paste.

---

## Near Term

### Job Control

- Any admin with permission should be able to cancel a job started by another admin.
- `/schem status` should expose stable job IDs and enough metadata to target the correct job quickly.
- ~~Add `/schem pause <jobId>` and `/schem resume <jobId>` for explicit operator control.~~ ✓ done
- `/schem bounds <jobId>` would still help operators reason about an active paste more quickly.

### Safer Cancellation / Rollback

- ~~Introduce per-job undo snapshots so cancel can revert blocks already cleared or placed.~~ done
- ~~Rollback should include block states and block entities.~~ done
- ~~Rollback should run as a throttled job, not as one huge synchronous revert.~~ done
- Add a config limit for maximum rollback history size to avoid unbounded disk and memory growth.

### Better Schematic Info

- `/schem info <name>` should remain focused on file metadata.
- `/schem plan` is now the anchor-aware surface for bounds, chunk span, clear/place counts, and ETA.
- Add overlap or protected-region warnings to planning output later.

### Flight Utility

- ~~Add `/flyspeed <value>` as an admin utility.~~ ✓ done
- ~~Clamp values to a safe configured range.~~ ✓ done
- ~~Add `/flyspeed reset`.~~ ✓ done
- Consider a convenience preset system such as `slow`, `normal`, `fast`, `survey`.

## Reliability

### Player Independence

- ~~Paste jobs should continue if the command executor disconnects.~~ done
- ~~HUD display should stop when the player leaves, but the job should keep running.~~ done
- ~~If the player reconnects, FrameShift should be able to resume sending that player job HUD updates for jobs they started.~~ done

### Restart Recovery

- ~~Persist running jobs to disk.~~ done
- ~~On graceful shutdown: flush queue state and metadata to disk.~~ done
- ~~On startup: reload jobs and resume automatically if configured.~~ done
- ~~Persist enough state to resume both clearing and placing phases.~~ done
- ~~Persist progress counters, phase, origin, schematic path, flags, and rollback data.~~ done
- Add an admin workflow to inspect why a persisted job failed to resume, not just clean it up.

### Crash Tolerance

- Design job snapshots so unexpected crashes still leave a recoverable state.
- Add integrity checks for persisted job files.
- ~~Mark corrupted jobs clearly and avoid auto-resuming them blindly.~~ done
- ~~Provide an admin command to inspect or discard broken persisted jobs.~~ done via `/schem cleanup`

## Scheduling and Automation

### Smarter Auto-Run Rules

- Add config options to allow clearing and placement only when:
  - no players are online
  - player count is below a threshold
  - MSPT is below a threshold
  - a configured time window is active
- Allow jobs to auto-pause when players join, if the server owner wants strictly offline pasting.
- Allow jobs to auto-resume after the server has been stable for a configurable period.

### Queue Policy

- Support queue priorities.
- Allow admins to mark a job as `background` or `urgent`.
- Optionally limit one clearing-heavy job at a time to reduce TPS spikes.

## UX and QoL

### Status / Progress

- Keep phase-aware progress for `clearing`, `placing`, and `rollback`.
- Show both:
  - real schematic block progress
  - backend operation progress
- Improve ETA further with chunk-wait penalties and recent phase sampling.
- Add rate display such as blocks/sec.
- Show current world bounds in status for active jobs.

### Preview / Validation

- ~~Add a dry-run command: `/schem plan`~~ done
- ~~The plan should report bounds, volume, non-air blocks, estimated clear ops, estimated runtime, and chunk span.~~ done
- Add overlap warnings if the paste would affect protected or sensitive areas later on.

### Better Messaging

- Standardize command output around:
  - phase
  - job id
  - real blocks
  - ops
  - ETA
  - anchor/origin
- Add a concise "job started" summary with all key numbers.
- Add clearer warnings when a job is slow because the exact clear mode is touching the full schematic volume.

## Performance

### Paste Modes

- ~~Support explicit paste modes instead of one hidden behavior: `exact` and `fast`.~~ done
- Add `replace`: place all blocks including schematic air
- ~~Show the expected operation count before starting each mode.~~ done via `/schem plan`

### Chunk-Aware Work

- Improve chunk scheduling so work is grouped by loaded chunks when possible.
- Measure chunk wait time separately from placement time.
- Consider background preloading of the next chunk window to reduce stalls.

### Bulk Operations

- Investigate whether some clear paths can be batched safely without violating server-thread-only world edits.
- Explore region-slice clearing strategies that remain resumable and rollback-safe.

## Admin Utilities

- `/schem jobs` as a clearer listing alias if `status` grows too dense.
- `/schem bounds <jobId>` to display active world-space bounds.
- `/schem tp <jobId>` to teleport an admin to a job anchor or center.
- `/schem retry <jobId>` for paused or failed jobs that are still recoverable.
- ~~`/schem cleanup` to prune abandoned persisted job files.~~ done

## Configuration Expansion

- Separate limits for clear ops and place ops per tick.
- Separate ETA display toggles and HUD verbosity toggles.
- Configurable auto-resume delay after startup.
- Configurable rollback retention policy.
- Configurable max persisted jobs.
- Configurable job ownership rules:
  - only starter can manage
  - any admin can manage
  - mixed model

## Nice-to-Have

- Optional map/world region safety hooks for future protection mod integration.
- Optional webhook or console notifications when huge jobs finish or fail.
- Per-job notes or labels so admins can remember what a job was for.
- Better support for huge build workflows:
  - survey utilities
  - region measurements
  - anchor bookmarking

## Suggested Milestones

### Milestone 1: Admin Control

- Stable job targeting by ID ✓ done
- cancel by job ID ✓ done
- pause / resume commands ✓ done
- `/flyspeed` ✓ done
- better status output ✓ done (compact all-jobs view + verbose single-job view with origin, phase, counts, ETA, queue sizes)

### Milestone 2: Reliable Recovery

- player disconnect independence done
- persisted paused/running jobs done
- startup resume with stability checks (partial: resume exists; stability gating still open)

### Milestone 3: Safe Undo

- rollback snapshots done
- throttled rollback jobs done
- cancel that truly restores pre-job state done for blocks/block entities/barriers; entity rollback still out of scope

### Milestone 4: Smarter Planning

- richer planning surface mostly done via `/schem plan`
- dry-run planning done
- estimated clear/place counts and bounds preview done

### Milestone 5: Performance Modes

- exact / fast modes done
- replace mode
- better chunk-aware scheduling
- more stable ETA based on phase and chunk wait behavior
