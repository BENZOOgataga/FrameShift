package com.benzoogataga.frameshift.job;

import com.benzoogataga.frameshift.config.FrameShiftConfig;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Central registry for all paste jobs.
// TickHandler reads from here; commands write to here.
// All methods run on the server thread — no synchronization needed.
public class JobManager {

    // LinkedHashMap preserves insertion order so /schem status lists jobs in the order they started
    private static final Map<UUID, SchematicPasteJob> jobs = new LinkedHashMap<>();

    // Attempt to add a new job. Returns false if the concurrent job limit has been reached.
    public static boolean submit(SchematicPasteJob job) {
        long active = jobs.values().stream()
            .filter(j -> j.state == SchematicPasteJob.State.LOADING
                      || j.state == SchematicPasteJob.State.RUNNING
                      || j.state == SchematicPasteJob.State.PAUSED)
            .count();
        if (active >= FrameShiftConfig.maxConcurrentJobs.get()) {
            return false;
        }
        jobs.put(job.jobId, job);
        return true;
    }

    public static void cancel(UUID jobId) {
        SchematicPasteJob job = jobs.get(jobId);
        if (job != null) job.state = SchematicPasteJob.State.CANCELLED;
    }

    public static void pause(UUID jobId) {
        SchematicPasteJob job = jobs.get(jobId);
        if (job != null && job.state == SchematicPasteJob.State.RUNNING)
            job.state = SchematicPasteJob.State.PAUSED;
    }

    public static void resume(UUID jobId) {
        SchematicPasteJob job = jobs.get(jobId);
        if (job != null && job.state == SchematicPasteJob.State.PAUSED)
            job.state = SchematicPasteJob.State.RUNNING;
    }

    public static SchematicPasteJob get(UUID jobId) {
        return jobs.get(jobId);
    }

    // Returns all jobs (read-only view) — used by TickHandler and /schem status
    public static Collection<SchematicPasteJob> all() {
        return Collections.unmodifiableCollection(jobs.values());
    }

    // Remove completed and cancelled jobs to free memory
    public static void cleanup() {
        jobs.entrySet().removeIf(e ->
            e.getValue().state == SchematicPasteJob.State.DONE ||
            e.getValue().state == SchematicPasteJob.State.CANCELLED
        );
    }
}
