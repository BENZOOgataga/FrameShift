package com.benzoogataga.frameshift.job;

import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.schematic.PreparedBlockPlacement;
import com.benzoogataga.frameshift.schematic.PreparedSchematicPaste;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

// Represents one paste operation from submission through completion and verification.
public class SchematicPasteJob {

    public enum State {
        RUNNING,
        ROLLING_BACK,
        PAUSED,
        CANCELLED,
        DONE,
        FAILED
    }

    public final UUID jobId;
    public final String schematicName;
    public final ServerLevel level;
    public final BlockPos origin;
    // UUID of the player who started the paste, or null when run from console.
    @Nullable public final UUID executorUuid;

    public volatile State state = State.RUNNING;
    public volatile boolean autoPaused;
    @Nullable
    public volatile String failureReason;

    public volatile boolean loadingComplete;
    // Path to the schematic file on disk; required to resume this job after a server restart.
    @Nullable public volatile Path schematicPath;
    // True once the job has switched from forward paste work into rollback work.
    public volatile boolean rollbackMode;
    // Per-job directory under frameshift/jobs/<jobId>/ for metadata and rollback logs.
    @Nullable public volatile Path persistenceDirectory;
    // Append-only rollback event log path used to reconstruct rollback state on restart.
    @Nullable public volatile Path rollbackLogPath;

    public final LinkedBlockingQueue<PlacementTask> placementQueue;
    public final PriorityBlockingQueue<PlacementTask> gravityPlacementQueue;
    public final ArrayDeque<PlacementTask> blockEntityQueue = new ArrayDeque<>();
    public final ArrayDeque<RollbackTask> rollbackQueue = new ArrayDeque<>();
    public final Map<Long, RollbackTask> rollbackIndex = new HashMap<>();

    public int totalBlocks;
    public int expectedTotalBlocks;
    public int displayTotalBlocks;
    public int clearOperationsTotal;
    public int blocksAttempted;
    public int blocksPlaced;
    public int blocksUnchanged;
    public int blocksVerified;
    public int blocksRetried;
    public int blocksFailed;
    public int blockEntitiesApplied;
    public int rollbackQueued;
    public int rollbackApplied;
    public int rollbackSkippedConflicts;
    public int rollbackFailed;
    public final long startedAtNanos;
    public long clearPhaseStartedAtNanos;
    public long placePhaseStartedAtNanos;
    public long rollbackPhaseStartedAtNanos;
    public boolean placePhaseObserved;
    public boolean skipClear;
    public boolean freezeGravity;
    public long rollbackSequence;

    public SchematicPasteJob(String schematicName, ServerLevel level, BlockPos origin, @Nullable UUID executorUuid) {
        this(UUID.randomUUID(), schematicName, level, origin, executorUuid);
    }

    // Resume constructor: reuses a known job ID when reloading a persisted job after restart.
    public SchematicPasteJob(UUID jobId, String schematicName, ServerLevel level, BlockPos origin, @Nullable UUID executorUuid) {
        this.jobId = jobId;
        this.schematicName = schematicName;
        this.level = level;
        this.origin = origin;
        this.executorUuid = executorUuid;
        this.placementQueue = new LinkedBlockingQueue<>(FrameShiftConfig.maxQueuedPlacements.get());
        this.gravityPlacementQueue = new PriorityBlockingQueue<>(
            FrameShiftConfig.maxQueuedPlacements.get(),
            Comparator
                .comparingInt((PlacementTask task) -> task.worldPos.getY())
                .thenComparingInt(task -> task.worldPos.getX())
                .thenComparingInt(task -> task.worldPos.getZ())
        );
        this.startedAtNanos = System.nanoTime();
        this.clearPhaseStartedAtNanos = startedAtNanos;
    }

    // Attaches prepared placements without bulk queue construction to avoid main-thread stalls.
    public void loadPreparedPaste(PreparedSchematicPaste prepared) {
        this.loadingComplete = false;
        this.totalBlocks = prepared.blocks.size();
        this.expectedTotalBlocks = prepared.blocks.size();
        this.displayTotalBlocks = prepared.blocks.size();
        this.clearOperationsTotal = 0;
        this.clearPhaseStartedAtNanos = startedAtNanos;
        this.placePhaseStartedAtNanos = startedAtNanos;
        this.placePhaseObserved = true;
        this.placementQueue.clear();
        this.gravityPlacementQueue.clear();
        this.blockEntityQueue.clear();
        for (PreparedBlockPlacement block : prepared.blocks) {
            BlockPos worldPos = origin.offset(block.relativePos);
            placementQueue.offer(new PlacementTask(worldPos, block.state, block.blockEntityTag == null ? null : block.blockEntityTag.copy(), 0));
        }
        this.loadingComplete = true;
    }

    // Enqueues one parsed placement and blocks the producer when memory buffer is full.
    public void enqueuePlacement(PlacementTask task) throws InterruptedException {
        placementQueue.put(task);
        totalBlocks++;
    }

    public void enqueueGravityPlacement(PlacementTask task) throws InterruptedException {
        gravityPlacementQueue.put(task);
        totalBlocks++;
    }

    // Marks producer completion so tick logic can finish once queues drain.
    public void markLoadingComplete() {
        loadingComplete = true;
    }

    // Total queued and pending verification work.
    public int remaining() {
        if (rollbackMode) {
            return rollbackQueue.size();
        }
        return placementQueue.size() + gravityPlacementQueue.size() + blockEntityQueue.size();
    }

    public void observeProgress(long nowNanos) {
        if (!placePhaseObserved && !isClearingPhase()) {
            placePhaseObserved = true;
            placePhaseStartedAtNanos = nowNanos;
        }
    }

    public long etaSeconds(long nowNanos, double configuredBlocksPerSecond) {
        if (rollbackMode) {
            int remaining = rollbackRemaining();
            if (remaining <= 0) {
                return 0L;
            }
            if (configuredBlocksPerSecond <= 0.0D) {
                return -1L;
            }
            return (long) Math.ceil(remaining / configuredBlocksPerSecond);
        }

        int clearRemaining = Math.max(0, clearOperationsTotal - clearCompletedOperations());
        int placeRemaining = Math.max(0, displayTotalBlocks - displayCompletedBlocks());
        if (clearRemaining <= 0 && placeRemaining <= 0) {
            return 0L;
        }

        double clearRate = configuredBlocksPerSecond;
        int clearDone = clearCompletedOperations();
        long clearElapsedNanos = Math.max(1L, nowNanos - clearPhaseStartedAtNanos);
        if (clearDone >= 10_000) {
            clearRate = stabilizeRate(clearDone * 1_000_000_000.0D / clearElapsedNanos, configuredBlocksPerSecond);
        }

        double placeRate = configuredBlocksPerSecond;
        int placeDone = displayCompletedBlocks();
        long placeElapsedNanos = Math.max(1L, nowNanos - placePhaseStartedAtNanos);
        if (placeDone >= 1_000 && placePhaseObserved) {
            placeRate = stabilizeRate(placeDone * 1_000_000_000.0D / placeElapsedNanos, configuredBlocksPerSecond);
        }

        if (clearRate <= 0.0D || placeRate <= 0.0D) {
            return -1L;
        }

        double eta = clearRemaining / clearRate + placeRemaining / placeRate;
        return (long) Math.ceil(eta);
    }

    public boolean isClearingPhase() {
        return !rollbackMode && blocksAttempted < clearOperationsTotal;
    }

    public int displayCompletedBlocks() {
        return blocksPlaced + blocksUnchanged;
    }

    public int displayRemainingBlocks() {
        return Math.max(0, displayTotalBlocks - displayCompletedBlocks());
    }

    public int clearCompletedOperations() {
        return Math.min(blocksAttempted, clearOperationsTotal);
    }

    public int rollbackCompletedOperations() {
        return rollbackApplied + rollbackSkippedConflicts + rollbackFailed;
    }

    public int rollbackRemaining() {
        return Math.max(0, rollbackQueued - rollbackCompletedOperations());
    }

    public boolean hasKnownPlaceTotal() {
        return displayTotalBlocks >= 0 || expectedTotalBlocks >= 0;
    }

    public int knownPlaceTotal() {
        if (displayTotalBlocks >= 0) {
            return displayTotalBlocks;
        }
        return expectedTotalBlocks;
    }

    public void clearForwardQueues() {
        placementQueue.clear();
        gravityPlacementQueue.clear();
        blockEntityQueue.clear();
        loadingComplete = true;
    }

    public void beginRollback() {
        rollbackMode = true;
        state = State.ROLLING_BACK;
        rollbackPhaseStartedAtNanos = System.nanoTime();
        clearForwardQueues();
    }

    private static double stabilizeRate(double observedRate, double configuredRate) {
        if (configuredRate <= 0.0D) {
            return observedRate;
        }
        double minRate = configuredRate * 0.5D;
        double maxRate = configuredRate * 1.1D;
        return Math.max(minRate, Math.min(maxRate, observedRate));
    }

    // Tracks one placement and the retry budget used by verification.
    public static final class PlacementTask {
        public final BlockPos worldPos;
        public final BlockState state;
        @Nullable
        public final net.minecraft.nbt.CompoundTag blockEntityTag;
        public final int attempts;

        public PlacementTask(BlockPos worldPos, BlockState state, @Nullable net.minecraft.nbt.CompoundTag blockEntityTag, int attempts) {
            this.worldPos = worldPos;
            this.state = state;
            this.blockEntityTag = blockEntityTag;
            this.attempts = attempts;
        }

        public PlacementTask retry() {
            return new PlacementTask(worldPos, state, blockEntityTag == null ? null : blockEntityTag.copy(), attempts + 1);
        }
    }

    // Stores the original world state and the job's latest expected state for one touched position.
    public static final class RollbackTask {
        public final BlockPos worldPos;
        public final BlockState originalState;
        @Nullable
        public final CompoundTag originalBlockEntityTag;
        public final boolean hadOriginalBlockEntity;
        public BlockState expectedState;
        @Nullable
        public CompoundTag expectedBlockEntityTag;
        public boolean hadExpectedBlockEntity;
        public long lastTouchedSequence;

        public RollbackTask(
            BlockPos worldPos,
            BlockState originalState,
            @Nullable CompoundTag originalBlockEntityTag,
            boolean hadOriginalBlockEntity,
            BlockState expectedState,
            @Nullable CompoundTag expectedBlockEntityTag,
            boolean hadExpectedBlockEntity,
            long lastTouchedSequence
        ) {
            this.worldPos = worldPos;
            this.originalState = originalState;
            this.originalBlockEntityTag = originalBlockEntityTag == null ? null : originalBlockEntityTag.copy();
            this.hadOriginalBlockEntity = hadOriginalBlockEntity;
            this.expectedState = expectedState;
            this.expectedBlockEntityTag = expectedBlockEntityTag == null ? null : expectedBlockEntityTag.copy();
            this.hadExpectedBlockEntity = hadExpectedBlockEntity;
            this.lastTouchedSequence = lastTouchedSequence;
        }
    }
}
