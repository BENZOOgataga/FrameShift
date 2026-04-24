# FrameShift Roadmap

This roadmap mixes requested features with adjacent work needed to make them safe and usable in production.

## Principles

- Prefer correctness and recovery over raw speed.
- Long-running jobs must survive admin mistakes, player disconnects, and server restarts.
- User-facing progress should be understandable: phase, real block counts, ETA, and failure reason.
- Expensive automation should remain configurable so server owners can decide how aggressive FrameShift is allowed to be.

## Near Term

### Job Control

- `/schem cancel <jobId>` should restore the world to the state it had before the job started.
- Any admin with permission should be able to cancel a job started by another admin.
- `/schem status` should expose stable job IDs and enough metadata to target the correct job quickly.
- Add `/schem pause <jobId>` and `/schem resume <jobId>` for explicit operator control.
- Add `/schem status <jobId>` for a focused view of one job.

### Safer Cancellation / Rollback

- Introduce per-job undo snapshots so cancel can revert blocks already cleared or placed.
- Rollback should include block states and block entities.
- Rollback should run as a throttled job, not as one huge synchronous revert.
- Add a config limit for maximum rollback history size to avoid unbounded disk and memory growth.

### Better Schematic Info

- `/schem info <name>` should show:
  - dimensions
  - schematic offset
  - non-air block count
  - full volume
  - estimated clear operations for the current paste anchor
  - estimated placement operations
  - rough ETA based on current config and server load
- Add a variant that evaluates info against a target position, for example:
  - `/schem info <name> <x> <y> <z>`
- Show computed world bounds so admins can verify where the schematic will land before pasting.

### Flight Utility

- Add `/flyspeed <value>` as an admin utility.
- Clamp values to a safe configured range.
- Add `/flyspeed reset`.
- Consider a convenience preset system such as `slow`, `normal`, `fast`, `survey`.

## Reliability

### Player Independence

- Paste jobs should continue if the command executor disconnects.
- HUD display should stop when the player leaves, but the job should keep running.
- If the player reconnects, FrameShift should be able to resume sending that player job HUD updates for jobs they started.

### Restart Recovery

- Persist running jobs to disk.
- On graceful shutdown:
  - pause active jobs
  - flush queue state and metadata to disk
- On startup:
  - reload paused jobs
  - wait until server performance is stable
  - resume jobs automatically if configured
- Persist enough state to resume both clearing and placing phases.
- Persist progress counters, phase, origin, schematic path, flags, and rollback data.

### Crash Tolerance

- Design job snapshots so unexpected crashes still leave a recoverable state.
- Add integrity checks for persisted job files.
- Mark corrupted jobs clearly and avoid auto-resuming them blindly.
- Provide an admin command to inspect or discard broken persisted jobs.

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

- Add a dry-run command:
  - `/schem plan <name> [x y z]`
- The plan should report:
  - bounds
  - volume
  - non-air blocks
  - estimated clear ops
  - estimated runtime
  - chunk span
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

- Support explicit paste modes instead of one hidden behavior:
  - `exact`: full-volume clear, then place non-air
  - `fast`: place non-air only
  - `replace`: place all blocks including schematic air
- Show the expected operation count before starting each mode.

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
- `/schem cleanup` to prune abandoned persisted job files.

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

- Stable job targeting by ID
- cancel / pause / resume by job ID
- better status output
- `/flyspeed`

### Milestone 2: Reliable Recovery

- player disconnect independence
- persisted paused/running jobs
- startup resume with stability checks

### Milestone 3: Safe Undo

- rollback snapshots
- throttled rollback jobs
- cancel that truly restores pre-job state

### Milestone 4: Smarter Planning

- richer `/schem info`
- dry-run planning
- estimated clear/place counts and bounds preview

### Milestone 5: Performance Modes

- exact / fast / replace modes
- better chunk-aware scheduling
- more stable ETA based on phase and chunk wait behavior
