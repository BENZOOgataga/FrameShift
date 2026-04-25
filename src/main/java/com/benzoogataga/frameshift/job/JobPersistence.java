package com.benzoogataga.frameshift.job;

import com.benzoogataga.frameshift.FrameShift;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

// Saves and loads per-job metadata from frameshift/jobs/<jobId>/job.json.
public final class JobPersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JobPersistence() {}

    // Intermediate class used for JSON serialization; all fields are plain strings/ints.
    private static final class JobJson {
        String jobId;
        String schematicName;
        String schematicPath;
        String dimension;
        int originX;
        int originY;
        int originZ;
        boolean skipClear;
        boolean freezeGravity;
        @Nullable String executorUuid;
        String state;
        boolean rollbackMode;
        int clearOperationsTotal;
        int expectedTotalBlocks;
        int displayTotalBlocks;
        int blocksAttempted;
        int blocksPlaced;
        int blocksUnchanged;
        int blocksVerified;
        int blocksRetried;
        int blocksFailed;
        int blockEntitiesApplied;
        int rollbackQueued;
        int rollbackApplied;
        int rollbackSkippedConflicts;
        int rollbackFailed;
        long rollbackSequence;
    }

    // Holds the deserialized data needed to reconstruct a job object.
    public record SavedJob(
        UUID jobId,
        String schematicName,
        Path schematicPath,
        String dimension,
        BlockPos origin,
        boolean skipClear,
        boolean freezeGravity,
        @Nullable UUID executorUuid,
        SchematicPasteJob.State state,
        boolean rollbackMode,
        int clearOperationsTotal,
        int expectedTotalBlocks,
        int displayTotalBlocks,
        int blocksAttempted,
        int blocksPlaced,
        int blocksUnchanged,
        int blocksVerified,
        int blocksRetried,
        int blocksFailed,
        int blockEntitiesApplied,
        int rollbackQueued,
        int rollbackApplied,
        int rollbackSkippedConflicts,
        int rollbackFailed,
        long rollbackSequence
    ) {}

    public static Path jobsDir(Path serverRoot) {
        return serverRoot.resolve("frameshift").resolve("jobs");
    }

    public static Path jobDir(Path serverRoot, UUID jobId) {
        return jobsDir(serverRoot).resolve(jobId.toString());
    }

    public static Path jobMetadataPath(Path serverRoot, UUID jobId) {
        return jobDir(serverRoot, jobId).resolve("job.json");
    }

    // Writes one job's metadata to frameshift/jobs/<jobId>/job.json.
    public static void save(SchematicPasteJob job, Path serverRoot) {
        if (job.schematicPath == null) return;
        try {
            Path dir = jobDir(serverRoot, job.jobId);
            Files.createDirectories(dir);
            job.persistenceDirectory = dir;
            if (job.rollbackLogPath == null) {
                job.rollbackLogPath = dir.resolve("rollback.log");
            }
            RollbackStore.flushPending(job);

            JobJson json = new JobJson();
            json.jobId = job.jobId.toString();
            json.schematicName = job.schematicName;
            json.schematicPath = job.schematicPath.toAbsolutePath().toString();
            json.dimension = job.level.dimension().location().toString();
            json.originX = job.origin.getX();
            json.originY = job.origin.getY();
            json.originZ = job.origin.getZ();
            json.skipClear = job.skipClear;
            json.freezeGravity = job.freezeGravity;
            json.executorUuid = job.executorUuid != null ? job.executorUuid.toString() : null;
            json.state = job.state.name();
            json.rollbackMode = job.rollbackMode;
            json.clearOperationsTotal = job.clearOperationsTotal;
            json.expectedTotalBlocks = job.expectedTotalBlocks;
            json.displayTotalBlocks = job.displayTotalBlocks;
            json.blocksAttempted = job.blocksAttempted;
            json.blocksPlaced = job.blocksPlaced;
            json.blocksUnchanged = job.blocksUnchanged;
            json.blocksVerified = job.blocksVerified;
            json.blocksRetried = job.blocksRetried;
            json.blocksFailed = job.blocksFailed;
            json.blockEntitiesApplied = job.blockEntitiesApplied;
            json.rollbackQueued = job.rollbackQueued;
            json.rollbackApplied = job.rollbackApplied;
            json.rollbackSkippedConflicts = job.rollbackSkippedConflicts;
            json.rollbackFailed = job.rollbackFailed;
            json.rollbackSequence = job.rollbackSequence;
            Files.writeString(jobMetadataPath(serverRoot, job.jobId), GSON.toJson(json));
        } catch (IOException e) {
            FrameShift.LOGGER.error("Failed to persist job {} ({}): {}", job.jobId, job.schematicName, e.getMessage());
        }
    }

    // Reads all frameshift/jobs/*/job.json files and returns the valid ones.
    public static List<SavedJob> loadAll(Path serverRoot) {
        Path dir = jobsDir(serverRoot);
        if (!Files.isDirectory(dir)) return List.of();
        List<SavedJob> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(jobDir -> {
                    Path metadata = jobDir.resolve("job.json");
                    if (!Files.isRegularFile(metadata)) {
                        return;
                    }
                    try {
                        String content = Files.readString(metadata);
                        JobJson json = GSON.fromJson(content, JobJson.class);
                        if (json == null || json.jobId == null || json.schematicPath == null || json.state == null) {
                            FrameShift.LOGGER.warn("Skipping malformed job file: {}", metadata);
                            return;
                        }
                        result.add(new SavedJob(
                            UUID.fromString(json.jobId),
                            json.schematicName,
                            Path.of(json.schematicPath),
                            json.dimension,
                            new BlockPos(json.originX, json.originY, json.originZ),
                            json.skipClear,
                            json.freezeGravity,
                            json.executorUuid != null ? UUID.fromString(json.executorUuid) : null,
                            SchematicPasteJob.State.valueOf(json.state),
                            json.rollbackMode,
                            json.clearOperationsTotal,
                            json.expectedTotalBlocks,
                            json.displayTotalBlocks,
                            json.blocksAttempted,
                            json.blocksPlaced,
                            json.blocksUnchanged,
                            json.blocksVerified,
                            json.blocksRetried,
                            json.blocksFailed,
                            json.blockEntitiesApplied,
                            json.rollbackQueued,
                            json.rollbackApplied,
                            json.rollbackSkippedConflicts,
                            json.rollbackFailed,
                            json.rollbackSequence
                        ));
                    } catch (IOException | JsonSyntaxException | IllegalArgumentException e) {
                        FrameShift.LOGGER.warn("Skipping malformed job file {}: {}", metadata, e.getMessage());
                    }
                });
        } catch (IOException e) {
            FrameShift.LOGGER.error("Failed to list persisted job files: {}", e.getMessage());
        }
        return result;
    }

    // Removes a persisted job directory once the job has fully completed or fully rolled back.
    public static void delete(UUID jobId, Path serverRoot) {
        Path dir = jobDir(serverRoot, jobId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    FrameShift.LOGGER.warn("Failed to delete persisted path {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            FrameShift.LOGGER.warn("Failed to delete persisted job directory for {}: {}", jobId, e.getMessage());
        }
    }
}
