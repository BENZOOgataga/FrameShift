package com.benzoogataga.frameshift.job;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// Owns on-disk rollback snapshot storage and reconstructs rollback state on startup.
public final class RollbackStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String SNAPSHOT = "snapshot";
    private static final String UPDATE = "update";

    private RollbackStore() {}

    // Ensures this job has a storage directory and rollback log before any snapshots are written.
    public static boolean initializeStorage(SchematicPasteJob job, Path serverRoot) {
        try {
            Path jobsDir = JobPersistence.jobsDir(serverRoot);
            Files.createDirectories(jobsDir);

            Path dir = JobPersistence.jobDir(serverRoot, job.jobId);
            if (!Files.isDirectory(dir)) {
                long existingJobDirs;
                try (Stream<Path> entries = Files.list(jobsDir)) {
                    existingJobDirs = entries.filter(Files::isDirectory).count();
                }
                if (existingJobDirs >= FrameShiftConfig.maxPersistedRollbackJobs.get()) {
                    job.state = SchematicPasteJob.State.FAILED;
                    job.failureReason = "Rollback storage limit reached for persisted jobs.";
                    return false;
                }
                Files.createDirectories(dir);
            }

            job.persistenceDirectory = dir;
            job.rollbackLogPath = dir.resolve("rollback.log");
            if (!Files.exists(job.rollbackLogPath)) {
                Files.createFile(job.rollbackLogPath);
            }
            return true;
        } catch (IOException e) {
            job.state = SchematicPasteJob.State.FAILED;
            job.failureReason = "Could not initialize rollback storage: " + e.getMessage();
            return false;
        }
    }

    // Records the original world state on first touch and the job's latest expected state on every touch.
    public static boolean snapshotBeforeMutation(
        SchematicPasteJob job,
        ServerLevel level,
        BlockPos worldPos,
        BlockState expectedState,
        @Nullable CompoundTag expectedBlockEntityTag
    ) {
        if (!initializeStorage(job, level.getServer().getServerDirectory())) {
            return false;
        }

        long key = worldPos.asLong();
        SchematicPasteJob.RollbackTask existing = job.rollbackIndex.get(key);
        long sequence = ++job.rollbackSequence;
        boolean hadExpectedBlockEntity = expectedBlockEntityTag != null;

        try {
            if (existing == null) {
                BlockState originalState = level.getBlockState(worldPos);
                CompoundTag originalBlockEntityTag = captureBlockEntity(level, worldPos);
                boolean hadOriginalBlockEntity = originalBlockEntityTag != null;
                SchematicPasteJob.RollbackTask created = new SchematicPasteJob.RollbackTask(
                    worldPos,
                    originalState,
                    originalBlockEntityTag,
                    hadOriginalBlockEntity,
                    expectedState,
                    expectedBlockEntityTag,
                    hadExpectedBlockEntity,
                    sequence
                );

                byte[] bytes = (GSON.toJson(LogEvent.snapshot(created)) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                enforceStorageLimits(level.getServer().getServerDirectory(), bytes.length, job);
                Files.write(job.rollbackLogPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                job.rollbackIndex.put(key, created);
                job.rollbackQueued = job.rollbackIndex.size();
                return true;
            }

            existing.expectedState = expectedState;
            existing.expectedBlockEntityTag = expectedBlockEntityTag == null ? null : expectedBlockEntityTag.copy();
            existing.hadExpectedBlockEntity = hadExpectedBlockEntity;
            existing.lastTouchedSequence = sequence;
            byte[] bytes = (GSON.toJson(LogEvent.update(existing)) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            Files.write(job.rollbackLogPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            job.state = SchematicPasteJob.State.FAILED;
            job.failureReason = "Rollback snapshot write failed: " + e.getMessage();
            FrameShift.LOGGER.error("Failed to write rollback snapshot for job {}: {}", job.jobId, e.getMessage());
            return false;
        }
    }

    // Replays rollback.log into the job so cancel or startup resume can build the rollback queue.
    public static void loadRollbackState(SchematicPasteJob job) throws IOException {
        if (job.rollbackLogPath == null || !Files.exists(job.rollbackLogPath)) {
            return;
        }

        List<String> lines = Files.readAllLines(job.rollbackLogPath, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                LogEvent event = GSON.fromJson(line, LogEvent.class);
                if (event == null || event.type == null) {
                    throw new IOException("Missing rollback event type");
                }

                long key = new BlockPos(event.x, event.y, event.z).asLong();
                if (SNAPSHOT.equals(event.type)) {
                    SchematicPasteJob.RollbackTask task = new SchematicPasteJob.RollbackTask(
                        new BlockPos(event.x, event.y, event.z),
                        deserializeState(job.level, event.originalState),
                        parseTag(event.originalBlockEntity),
                        event.hadOriginalBlockEntity,
                        deserializeState(job.level, event.expectedState),
                        parseTag(event.expectedBlockEntity),
                        event.hadExpectedBlockEntity,
                        event.sequence
                    );
                    job.rollbackIndex.put(key, task);
                } else if (UPDATE.equals(event.type)) {
                    SchematicPasteJob.RollbackTask task = job.rollbackIndex.get(key);
                    if (task == null) {
                        throw new IOException("Rollback update has no matching snapshot for " + event.x + "," + event.y + "," + event.z);
                    }
                    task.expectedState = deserializeState(job.level, event.expectedState);
                    task.expectedBlockEntityTag = parseTag(event.expectedBlockEntity);
                    task.hadExpectedBlockEntity = event.hadExpectedBlockEntity;
                    task.lastTouchedSequence = event.sequence;
                } else {
                    throw new IOException("Unknown rollback event type: " + event.type);
                }
                job.rollbackSequence = Math.max(job.rollbackSequence, event.sequence);
            } catch (JsonSyntaxException | IllegalArgumentException e) {
                throw new IOException("Malformed rollback log entry: " + e.getMessage(), e);
            }
        }

        job.rollbackQueued = job.rollbackIndex.size();
    }

    // Rebuilds the in-memory rollback queue in reverse touch order for execution.
    public static void rebuildRollbackQueue(SchematicPasteJob job) {
        job.rollbackQueue.clear();
        List<SchematicPasteJob.RollbackTask> ordered = new ArrayList<>(job.rollbackIndex.values());
        ordered.sort(Comparator.comparingLong((SchematicPasteJob.RollbackTask task) -> task.lastTouchedSequence).reversed());
        for (SchematicPasteJob.RollbackTask task : ordered) {
            job.rollbackQueue.addLast(task);
        }
        job.rollbackQueued = ordered.size();
    }

    // Returns a normalized block entity tag with position/id for comparisons and restore.
    @Nullable
    public static CompoundTag captureBlockEntity(ServerLevel level, BlockPos worldPos) {
        BlockEntity blockEntity = level.getBlockEntity(worldPos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
    }

    private static BlockState deserializeState(ServerLevel level, @Nullable String snbt) throws IOException {
        if (snbt == null || snbt.isBlank()) {
            throw new IOException("Missing block state snapshot");
        }
        try {
            return NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), TagParser.parseTag(snbt));
        } catch (Exception e) {
            throw new IOException("Invalid block state snapshot", e);
        }
    }

    @Nullable
    private static CompoundTag parseTag(@Nullable String snbt) throws IOException {
        if (snbt == null || snbt.isBlank()) {
            return null;
        }
        try {
            return TagParser.parseTag(snbt);
        } catch (Exception e) {
            throw new IOException("Invalid SNBT payload", e);
        }
    }

    private static void enforceStorageLimits(Path serverRoot, int appendBytes, SchematicPasteJob job) throws IOException {
        long perJobLimit = FrameShiftConfig.maxRollbackSnapshotBytesPerJob.get();
        long totalLimit = FrameShiftConfig.maxTotalRollbackStorageBytes.get();
        long currentJobBytes = job.rollbackLogPath != null && Files.exists(job.rollbackLogPath) ? Files.size(job.rollbackLogPath) : 0L;
        long totalBytes = directorySize(JobPersistence.jobsDir(serverRoot));

        if (currentJobBytes + appendBytes > perJobLimit) {
            throw new IOException("Per-job rollback snapshot limit exceeded");
        }
        if (totalBytes + appendBytes > totalLimit) {
            throw new IOException("Total rollback snapshot limit exceeded");
        }
    }

    private static long directorySize(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
    }

    // One append-only record in rollback.log.
    private static final class LogEvent {
        String type;
        int x;
        int y;
        int z;
        long sequence;
        @Nullable String originalState;
        @Nullable String originalBlockEntity;
        boolean hadOriginalBlockEntity;
        @Nullable String expectedState;
        @Nullable String expectedBlockEntity;
        boolean hadExpectedBlockEntity;

        static LogEvent snapshot(SchematicPasteJob.RollbackTask task) {
            LogEvent event = new LogEvent();
            event.type = SNAPSHOT;
            event.x = task.worldPos.getX();
            event.y = task.worldPos.getY();
            event.z = task.worldPos.getZ();
            event.sequence = task.lastTouchedSequence;
            event.originalState = NbtUtils.writeBlockState(task.originalState).toString();
            event.originalBlockEntity = task.originalBlockEntityTag == null ? null : task.originalBlockEntityTag.toString();
            event.hadOriginalBlockEntity = task.hadOriginalBlockEntity;
            event.expectedState = NbtUtils.writeBlockState(task.expectedState).toString();
            event.expectedBlockEntity = task.expectedBlockEntityTag == null ? null : task.expectedBlockEntityTag.toString();
            event.hadExpectedBlockEntity = task.hadExpectedBlockEntity;
            return event;
        }

        static LogEvent update(SchematicPasteJob.RollbackTask task) {
            LogEvent event = new LogEvent();
            event.type = UPDATE;
            event.x = task.worldPos.getX();
            event.y = task.worldPos.getY();
            event.z = task.worldPos.getZ();
            event.sequence = task.lastTouchedSequence;
            event.expectedState = NbtUtils.writeBlockState(task.expectedState).toString();
            event.expectedBlockEntity = task.expectedBlockEntityTag == null ? null : task.expectedBlockEntityTag.toString();
            event.hadExpectedBlockEntity = task.hadExpectedBlockEntity;
            return event;
        }
    }
}
