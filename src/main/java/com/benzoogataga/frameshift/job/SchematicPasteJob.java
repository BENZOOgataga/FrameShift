package com.benzoogataga.frameshift.job;

import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.schematic.PreparedBlockPlacement;
import com.benzoogataga.frameshift.schematic.PreparedSchematicPaste;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

// Represents one paste operation from submission through completion and verification.
public class SchematicPasteJob {

    public enum State {
        RUNNING,
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

    public final LinkedBlockingQueue<PlacementTask> placementQueue;
    public final PriorityBlockingQueue<PlacementTask> gravityPlacementQueue;
    public final ArrayDeque<PlacementTask> blockEntityQueue = new ArrayDeque<>();

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
    public final long startedAtNanos;
    public long clearPhaseStartedAtNanos;
    public long placePhaseStartedAtNanos;
    public boolean placePhaseObserved;
    public boolean skipClear;
    public boolean freezeGravity;

    public SchematicPasteJob(String schematicName, ServerLevel level, BlockPos origin, @Nullable UUID executorUuid) {
        this.jobId = UUID.randomUUID();
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

    // Enqueues one parsed placement and blocks the producer when memory buffer is full.
    public void enqueueNormalPlacement(PlacementTask task) throws InterruptedException {
        placementQueue.put(task);
        totalBlocks++;
    }

    // Marks producer completion so tick logic can finish once queues drain.
    public void markLoadingComplete() {
        loadingComplete = true;
    }

    // Total queued and pending verification work.
    public int remaining() {
        return placementQueue.size() + gravityPlacementQueue.size() + blockEntityQueue.size();
    }

    public void observeProgress(long nowNanos) {
        if (!placePhaseObserved && !isClearingPhase()) {
            placePhaseObserved = true;
            placePhaseStartedAtNanos = nowNanos;
        }
    }

    public long etaSeconds(long nowNanos, double configuredBlocksPerSecond) {
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
        return blocksAttempted < clearOperationsTotal;
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
}
