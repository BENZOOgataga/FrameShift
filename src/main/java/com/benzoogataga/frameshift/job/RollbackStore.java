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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

// Owns on-disk rollback snapshot storage and reconstructs rollback state on startup.
public final class RollbackStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String SNAPSHOT = "snapshot";
    private static final String UPDATE = "update";
    private static final int FLUSH_THRESHOLD_BYTES = 128 * 1024;
    // Caches total rollback bytes already written under frameshift/jobs to avoid rescanning on every block placement.
    private static final AtomicLong cachedTotalRollbackBytes = new AtomicLong(-1L);
    // Caches SNBT block-state payloads because rollback logging serializes the same states many times.
    private static final Map<BlockState, String> blockStateSnbtCache = new ConcurrentHashMap<>();

    private RollbackStore() {}

    // Ensures this job has a storage directory and rollback log before any snapshots are written.
    public static boolean initializeStorage(SchematicPasteJob job, Path serverRoot) {
        ensureCachedTotalRollbackBytes(serverRoot);
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
            job.rollbackLogBytesOnDisk = Files.size(job.rollbackLogPath);
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
        Path serverRoot = level.getServer().getServerDirectory();
        if ((job.persistenceDirectory == null || job.rollbackLogPath == null) && !initializeStorage(job, serverRoot)) {
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

                queueLogEntry(serverRoot, job, GSON.toJson(LogEvent.snapshot(created)));

                job.rollbackIndex.put(key, created);
                job.rollbackQueued = job.rollbackIndex.size();
                return true;
            }

            existing.expectedState = expectedState;
            existing.expectedBlockEntityTag = expectedBlockEntityTag == null ? null : expectedBlockEntityTag.copy();
            existing.hadExpectedBlockEntity = hadExpectedBlockEntity;
            existing.lastTouchedSequence = sequence;
            queueLogEntry(serverRoot, job, GSON.toJson(LogEvent.update(existing)));
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

        job.rollbackLogBytesOnDisk = Files.size(job.rollbackLogPath);
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
                        deserializeExpectedState(job.level, event),
                        parseExpectedBlockEntity(event),
                        event.clearsToAir ? false : event.hadExpectedBlockEntity,
                        event.sequence
                    );
                    job.rollbackIndex.put(key, task);
                } else if (UPDATE.equals(event.type)) {
                    SchematicPasteJob.RollbackTask task = job.rollbackIndex.get(key);
                    if (task == null) {
                        throw new IOException("Rollback update has no matching snapshot for " + event.x + "," + event.y + "," + event.z);
                    }
                    task.expectedState = deserializeExpectedState(job.level, event);
                    task.expectedBlockEntityTag = parseExpectedBlockEntity(event);
                    task.hadExpectedBlockEntity = event.clearsToAir ? false : event.hadExpectedBlockEntity;
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

    // Flushes buffered rollback log entries to disk in one append.
    public static void flushPending(SchematicPasteJob job) throws IOException {
        if (job.rollbackLogPath == null || job.pendingRollbackLogEntries.isEmpty()) {
            return;
        }
        String payload = String.join(System.lineSeparator(), job.pendingRollbackLogEntries) + System.lineSeparator();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Files.write(job.rollbackLogPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        job.rollbackLogBytesOnDisk += bytes.length;
        cachedTotalRollbackBytes.addAndGet(bytes.length);
        job.pendingRollbackLogEntries.clear();
        job.pendingRollbackLogBytes = 0;
    }

    // Rebuilds the in-memory rollback queue in reverse touch order for execution.
    public static void rebuildRollbackQueue(SchematicPasteJob job) {
        job.rollbackQueue.clear();
        List<SchematicPasteJob.RollbackTask> ordered = orderRollbackTasks(job.rollbackIndex.values());
        int index = 0;
        while (index < ordered.size()) {
            int layerY = ordered.get(index).worldPos.getY();
            List<SchematicPasteJob.RollbackTask> fluidFirst = new ArrayList<>();
            List<SchematicPasteJob.RollbackTask> remaining = new ArrayList<>();

            while (index < ordered.size() && ordered.get(index).worldPos.getY() == layerY) {
                SchematicPasteJob.RollbackTask task = ordered.get(index++);
                if (rollbackFluidPriority(task) > 0) {
                    fluidFirst.add(task);
                } else {
                    remaining.add(task);
                }
            }

            for (SchematicPasteJob.RollbackTask task : fluidFirst) {
                job.rollbackQueue.addLast(task);
            }
            for (SchematicPasteJob.RollbackTask task : remaining) {
                job.rollbackQueue.addLast(task);
            }
        }
        job.rollbackQueued = job.rollbackQueue.size();
    }

    // Builds deterministic rollback order: top-down by Y, then reverse touch order inside a layer.
    static List<SchematicPasteJob.RollbackTask> orderRollbackTasks(Collection<SchematicPasteJob.RollbackTask> tasks) {
        List<SchematicPasteJob.RollbackTask> ordered = new ArrayList<>(tasks);
        ordered.sort(Comparator
            // Keep rollback top-down so unsupported blocks do not drop before their layer is restored.
            .comparingInt((SchematicPasteJob.RollbackTask task) -> task.worldPos.getY()).reversed()
            // Preserve prior rollback behavior inside each priority bucket.
            .thenComparing(Comparator.comparingLong((SchematicPasteJob.RollbackTask task) -> task.lastTouchedSequence).reversed()));
        return ordered;
    }

    private static int rollbackFluidPriority(SchematicPasteJob.RollbackTask task) {
        boolean expectedHasFluid = !task.expectedState.getFluidState().isEmpty();
        boolean originalHasFluid = !task.originalState.getFluidState().isEmpty();
        if (expectedHasFluid && !originalHasFluid) {
            return 2;
        }
        if (expectedHasFluid) {
            return 1;
        }
        return 0;
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

    private static BlockState deserializeExpectedState(ServerLevel level, LogEvent event) throws IOException {
        if (event.clearsToAir) {
            return Blocks.AIR.defaultBlockState();
        }
        return deserializeState(level, event.expectedState);
    }

    @Nullable
    private static CompoundTag parseExpectedBlockEntity(LogEvent event) throws IOException {
        if (event.clearsToAir) {
            return null;
        }
        return parseTag(event.expectedBlockEntity);
    }

    private static void enforceStorageLimits(Path serverRoot, int appendBytes, SchematicPasteJob job) throws IOException {
        long perJobLimit = FrameShiftConfig.maxRollbackSnapshotBytesPerJob.get();
        long totalLimit = FrameShiftConfig.maxTotalRollbackStorageBytes.get();
        long currentJobBytes = job.rollbackLogBytesOnDisk + job.pendingRollbackLogBytes;
        long totalBytes = ensureCachedTotalRollbackBytes(serverRoot);

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

    // Returns the cached total rollback bytes on disk, initializing it from disk once per process.
    private static long ensureCachedTotalRollbackBytes(Path serverRoot) {
        long cached = cachedTotalRollbackBytes.get();
        if (cached >= 0L) {
            return cached;
        }

        synchronized (cachedTotalRollbackBytes) {
            cached = cachedTotalRollbackBytes.get();
            if (cached >= 0L) {
                return cached;
            }

            try {
                cached = directorySize(JobPersistence.jobsDir(serverRoot));
            } catch (IOException e) {
                cached = 0L;
            }
            cachedTotalRollbackBytes.set(cached);
            return cached;
        }
    }

    // Updates the cached rollback byte total after a persisted job directory is removed.
    public static void onJobDirectoryDeleted(Path dir) {
        long cached = cachedTotalRollbackBytes.get();
        if (cached < 0L || !Files.exists(dir)) {
            return;
        }

        try {
            cachedTotalRollbackBytes.addAndGet(-directorySize(dir));
        } catch (IOException ignored) {
            cachedTotalRollbackBytes.set(-1L);
        }
    }

    // Forces the next storage-limit check to refresh totals from disk.
    public static void invalidateCachedTotalRollbackBytes() {
        cachedTotalRollbackBytes.set(-1L);
    }

    // Returns the current total rollback bytes already stored on disk under frameshift/jobs.
    public static long totalRollbackBytes(Path serverRoot) {
        return ensureCachedTotalRollbackBytes(serverRoot);
    }

    private static void queueLogEntry(Path serverRoot, SchematicPasteJob job, String jsonLine) throws IOException {
        byte[] bytes = (jsonLine + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        enforceStorageLimits(serverRoot, bytes.length, job);
        job.pendingRollbackLogEntries.add(jsonLine);
        job.pendingRollbackLogBytes += bytes.length;
        if (job.pendingRollbackLogBytes >= FLUSH_THRESHOLD_BYTES) {
            flushPending(job);
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
        boolean clearsToAir;

        static LogEvent snapshot(SchematicPasteJob.RollbackTask task) {
            LogEvent event = new LogEvent();
            event.type = SNAPSHOT;
            event.x = task.worldPos.getX();
            event.y = task.worldPos.getY();
            event.z = task.worldPos.getZ();
            event.sequence = task.lastTouchedSequence;
            event.originalState = serializeBlockState(task.originalState);
            event.originalBlockEntity = task.originalBlockEntityTag == null ? null : task.originalBlockEntityTag.toString();
            event.hadOriginalBlockEntity = task.hadOriginalBlockEntity;
            event.clearsToAir = isCompactAirClear(task.expectedState, task.hadExpectedBlockEntity);
            if (!event.clearsToAir) {
                event.expectedState = serializeBlockState(task.expectedState);
                event.expectedBlockEntity = task.expectedBlockEntityTag == null ? null : task.expectedBlockEntityTag.toString();
                event.hadExpectedBlockEntity = task.hadExpectedBlockEntity;
            }
            return event;
        }

        static LogEvent update(SchematicPasteJob.RollbackTask task) {
            LogEvent event = new LogEvent();
            event.type = UPDATE;
            event.x = task.worldPos.getX();
            event.y = task.worldPos.getY();
            event.z = task.worldPos.getZ();
            event.sequence = task.lastTouchedSequence;
            event.clearsToAir = isCompactAirClear(task.expectedState, task.hadExpectedBlockEntity);
            if (!event.clearsToAir) {
                event.expectedState = serializeBlockState(task.expectedState);
                event.expectedBlockEntity = task.expectedBlockEntityTag == null ? null : task.expectedBlockEntityTag.toString();
                event.hadExpectedBlockEntity = task.hadExpectedBlockEntity;
            }
            return event;
        }
    }

    // Reuses block-state SNBT strings across rollback entries, which is especially helpful for AIR clears.
    private static String serializeBlockState(BlockState state) {
        return blockStateSnbtCache.computeIfAbsent(state, key -> NbtUtils.writeBlockState(key).toString());
    }

    private static boolean isCompactAirClear(BlockState expectedState, boolean hadExpectedBlockEntity) {
        return expectedState.is(Blocks.AIR) && !hadExpectedBlockEntity;
    }
}
