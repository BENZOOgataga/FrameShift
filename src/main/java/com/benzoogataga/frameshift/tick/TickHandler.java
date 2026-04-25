package com.benzoogataga.frameshift.tick;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.chunk.ChunkHelper;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.job.JobManager;
import com.benzoogataga.frameshift.job.JobPersistence;
import com.benzoogataga.frameshift.job.RollbackStore;
import com.benzoogataga.frameshift.job.SchematicPasteJob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Locale;

// Called at the end of each server tick to advance paste jobs within a time budget.
public class TickHandler {

    private enum PlacementResult {
        CHANGED,
        NO_CHANGE,
        FAILED
    }

    private int hudTick = 0;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        long tickStartNanos = System.nanoTime();
        double mspt = event.getServer().getAverageTickTimeNanos() / 1_000_000.0;
        MinecraftServer server = event.getServer();

        for (SchematicPasteJob job : JobManager.all()) {
            if (job.state == SchematicPasteJob.State.PAUSED && job.autoPaused && mspt <= 45.0D) {
                job.state = job.rollbackMode ? SchematicPasteJob.State.ROLLING_BACK : SchematicPasteJob.State.RUNNING;
                job.autoPaused = false;
            }

            if (job.state != SchematicPasteJob.State.RUNNING && job.state != SchematicPasteJob.State.ROLLING_BACK) {
                continue;
            }

            double throttle = FrameShiftConfig.throttleFactor(mspt);
            if (throttle <= 0.0D) {
                job.state = SchematicPasteJob.State.PAUSED;
                job.autoPaused = true;
                continue;
            }

            if (job.rollbackMode) {
                processRollback(job, tickStartNanos);
                if (job.state == SchematicPasteJob.State.CANCELLED) {
                    FrameShift.LOGGER.info("Rollback finished for job {} ({})", job.jobId, job.schematicName);
                    notifyCancelled(server, job);
                    JobPersistence.delete(job.jobId, server.getServerDirectory());
                }
                continue;
            }

            int targetPlacements = Math.max(1, (int) Math.floor(FrameShiftConfig.maxBlocksPerTick.get() * throttle));
            int processed = 0;
            while (processed < targetPlacements && withinBudget(tickStartNanos)) {
                boolean didPlace;
                if (job.isClearingPhase()) {
                    // Clear phase: drain only the placement queue (AIR blocks, top-down order).
                    if (job.peekNormalPlacement() == null) break;
                    didPlace = placeNext(job);
                } else {
                    // Placement phase: drain the single Y-ascending queue. The schematic stream
                    // guarantees bottom-up order, so falling blocks always have their support placed
                    // before them without needing a separate sorted queue.
                    if (job.peekNormalPlacement() == null) break;
                    didPlace = placeNext(job);
                }
                if (!didPlace) break;
                processed++;
            }

            int blockEntitiesBudget = Math.max(1, (int) Math.floor(FrameShiftConfig.maxBlockEntitiesPerTick.get() * throttle));
            int blockEntitiesProcessed = 0;
            while (blockEntitiesProcessed < blockEntitiesBudget && withinBudget(tickStartNanos)) {
                SchematicPasteJob.PlacementTask task = job.blockEntityQueue.pollFirst();
                if (task == null) {
                    break;
                }

                if (!ChunkHelper.isLoaded(job.level, task.worldPos)) {
                    if (FrameShiftConfig.preloadChunks.get() && ChunkHelper.ensureLoaded(job.level, task.worldPos)) {
                        // chunk is now loaded, continue this tick
                    } else {
                        job.blockEntityQueue.addFirst(task);
                        job.state = SchematicPasteJob.State.PAUSED;
                        job.autoPaused = true;
                        break;
                    }
                }

                if (applyBlockEntity(job.level, task)) {
                    job.blockEntitiesApplied++;
                }
                blockEntitiesProcessed++;
            }

            int finalizeBudget = Math.max(1, targetPlacements / 4);
            int finalized = 0;
            while (finalized < finalizeBudget && withinBudget(tickStartNanos)) {
                if (!finalizeConnections(job)) {
                    break;
                }
                finalized++;
            }

            if (job.loadingComplete
                && job.placementQueue.isEmpty()
                && job.blockEntityQueue.isEmpty()
                && job.connectionFinalizeQueue.isEmpty()) {
                int entityBudget = Math.max(1, (int) Math.floor(FrameShiftConfig.maxEntitiesPerTick.get() * throttle));
                int entitiesProcessed = 0;
                while (entitiesProcessed < entityBudget && withinBudget(tickStartNanos)) {
                    if (!spawnNextEntity(job)) {
                        break;
                    }
                    entitiesProcessed++;
                }
            }

            job.observeProgress(System.nanoTime());

            if (job.loadingComplete
                && job.placementQueue.isEmpty()
                && job.blockEntityQueue.isEmpty()
                && job.connectionFinalizeQueue.isEmpty()
                && job.entityQueue.isEmpty()) {
                job.state = SchematicPasteJob.State.DONE;
                FrameShift.LOGGER.info("Paste job {} finished for schematic {}", job.jobId, job.schematicName);
                notifyComplete(server, job);
                JobPersistence.delete(job.jobId, server.getServerDirectory());
            }
        }

        if (++hudTick >= 4) {
            hudTick = 0;
            for (SchematicPasteJob job : JobManager.all()) {
                sendHud(server, job);
            }
        }

        JobManager.cleanup();
    }

    private static boolean withinBudget(long tickStartNanos) {
        long elapsedMillis = (System.nanoTime() - tickStartNanos) / 1_000_000L;
        return elapsedMillis < FrameShiftConfig.maxMillisPerTick.get();
    }

    private static boolean placeNext(SchematicPasteJob job) {
        SchematicPasteJob.PlacementTask task = job.peekNormalPlacement();
        if (task == null) {
            return false;
        }

        if (!ChunkHelper.isLoaded(job.level, task.worldPos)) {
            if (FrameShiftConfig.preloadChunks.get() && ChunkHelper.ensureLoaded(job.level, task.worldPos)) {
                // chunk is now loaded, continue placement
            } else {
                job.state = SchematicPasteJob.State.PAUSED;
                job.autoPaused = true;
                return false;
            }
        }

        task = job.pollNormalPlacement();
        if (task == null) {
            return false;
        }
        job.blocksAttempted++;
        PlacementResult placementResult = applyBlockState(job, task);
        if (placementResult == PlacementResult.FAILED) {
            job.blocksFailed++;
            return job.state != SchematicPasteJob.State.FAILED;
        }

        boolean isClearTask = task.state.isAir();
        if (placementResult == PlacementResult.CHANGED && !isClearTask) {
            job.blocksPlaced++;
        } else if (placementResult == PlacementResult.NO_CHANGE && !isClearTask) {
            job.blocksUnchanged++;
        }
        if (!isClearTask && task.blockEntityTag != null) {
            job.blockEntityQueue.addLast(task);
        }
        if (!isClearTask && needsConnectionFinalize(task.state)) {
            job.connectionFinalizeQueue.addLast(task);
        }
        job.blocksVerified++;
        return true;
    }

    private static PlacementResult applyBlockState(SchematicPasteJob job, SchematicPasteJob.PlacementTask task) {
        ServerLevel level = job.level;
        int flags = placementFlags(task.state.isAir());
        if (shouldFreezeGravity(job, level, task)) {
            CompoundTag barrierBlockEntity = null;
            if (!RollbackStore.snapshotBeforeMutation(job, level, task.worldPos.below(), Blocks.BARRIER.defaultBlockState(), barrierBlockEntity)) {
                return PlacementResult.FAILED;
            }
            level.setBlock(task.worldPos.below(), Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
        }
        if (!RollbackStore.snapshotBeforeMutation(job, level, task.worldPos, task.state, task.blockEntityTag)) {
            return PlacementResult.FAILED;
        }
        boolean changed = level.setBlock(task.worldPos, task.state, flags);
        scheduleFluidTickIfNeeded(level, task.worldPos, task.state);
        if (changed) {
            return PlacementResult.CHANGED;
        }
        if (!level.getBlockState(task.worldPos).equals(task.state)) {
            return PlacementResult.FAILED;
        }
        return PlacementResult.NO_CHANGE;
    }

    // Applies schematic states without asking vanilla to immediately revalidate neighbors and fluids.
    private static int placementFlags(boolean isAir) {
        if (isAir) {
            return Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
        }
        return Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    }

    private static boolean shouldFreezeGravity(SchematicPasteJob job, ServerLevel level, SchematicPasteJob.PlacementTask task) {
        if (!job.freezeGravity || !(task.state.getBlock() instanceof FallingBlock)) {
            return false;
        }
        if (task.worldPos.getY() <= level.getMinBuildHeight()) {
            return false;
        }
        return FallingBlock.isFree(level.getBlockState(task.worldPos.below()));
    }

    private static boolean applyBlockEntity(ServerLevel level, SchematicPasteJob.PlacementTask task) {
        if (task.blockEntityTag == null) {
            return true;
        }
        HolderLookup.Provider registries = level.registryAccess();
        BlockEntity blockEntity = BlockEntity.loadStatic(task.worldPos, task.state, task.blockEntityTag.copy(), registries);
        if (blockEntity != null) {
            level.setBlockEntity(blockEntity);
            blockEntity.setChanged();
            return true;
        }
        return false;
    }

    private static boolean finalizeConnections(SchematicPasteJob job) {
        SchematicPasteJob.PlacementTask task = job.connectionFinalizeQueue.pollFirst();
        if (task == null) {
            return false;
        }
        if (!ChunkHelper.isLoaded(job.level, task.worldPos)) {
            if (FrameShiftConfig.preloadChunks.get() && ChunkHelper.ensureLoaded(job.level, task.worldPos)) {
                // chunk is now loaded, continue this tick
            } else {
                job.connectionFinalizeQueue.addFirst(task);
                job.state = SchematicPasteJob.State.PAUSED;
                job.autoPaused = true;
                return false;
            }
        }

        ServerLevel level = job.level;
        BlockState currentState = level.getBlockState(task.worldPos);
        if (!currentState.is(task.state.getBlock())) {
            return true;
        }

        BlockState finalizedState = Block.updateFromNeighbourShapes(currentState, level, task.worldPos);
        if (finalizedState.isAir()) {
            finalizedState = currentState;
        }
        if (finalizedState.equals(currentState)) {
            scheduleFluidTickIfNeeded(level, task.worldPos, currentState);
            return true;
        }

        CompoundTag blockEntityTag = RollbackStore.captureBlockEntity(level, task.worldPos);
        if (!RollbackStore.snapshotBeforeMutation(job, level, task.worldPos, finalizedState, blockEntityTag)) {
            return false;
        }
        if (!level.setBlock(task.worldPos, finalizedState, placementFlags(finalizedState.isAir()))
            && !level.getBlockState(task.worldPos).equals(finalizedState)) {
            job.blocksFailed++;
            return false;
        }
        scheduleFluidTickIfNeeded(level, task.worldPos, finalizedState);
        return true;
    }

    private static boolean spawnNextEntity(SchematicPasteJob job) {
        SchematicPasteJob.EntityTask task = job.entityQueue.pollFirst();
        if (task == null) {
            return false;
        }

        BlockPos entityPos = entityBlockPos(task.entityTag);
        if (!ChunkHelper.isLoaded(job.level, entityPos)) {
            if (FrameShiftConfig.preloadChunks.get() && ChunkHelper.ensureLoaded(job.level, entityPos)) {
                // chunk is now loaded, continue this tick
            } else {
                job.entityQueue.addFirst(task);
                job.state = SchematicPasteJob.State.PAUSED;
                job.autoPaused = true;
                return false;
            }
        }

        if (spawnEntity(job.level, task.entityTag)) {
            job.entitiesApplied++;
        } else {
            job.entitiesFailed++;
        }
        return true;
    }

    private static void processRollback(SchematicPasteJob job, long tickStartNanos) {
        double mspt = job.level.getServer().getAverageTickTimeNanos() / 1_000_000.0D;
        double throttle = FrameShiftConfig.throttleFactor(mspt);
        int targetPlacements = Math.max(1, (int) Math.floor(FrameShiftConfig.maxBlocksPerTick.get() * throttle));
        int processed = 0;

        while (processed < targetPlacements && withinBudget(tickStartNanos)) {
            SchematicPasteJob.RollbackTask task = job.rollbackQueue.pollFirst();
            if (task == null) {
                job.rollbackMode = false;
                job.state = SchematicPasteJob.State.CANCELLED;
                return;
            }

            if (!ChunkHelper.isLoaded(job.level, task.worldPos)) {
                if (FrameShiftConfig.preloadChunks.get() && ChunkHelper.ensureLoaded(job.level, task.worldPos)) {
                    // chunk is now loaded, continue rollback
                } else {
                    job.rollbackQueue.addFirst(task);
                    job.state = SchematicPasteJob.State.PAUSED;
                    job.autoPaused = true;
                    break;
                }
            }

            if (!matchesExpectedState(job.level, task)) {
                job.rollbackSkippedConflicts++;
                processed++;
                continue;
            }

            if (restoreOriginalState(job.level, task)) {
                job.rollbackApplied++;
            } else {
                job.rollbackFailed++;
            }
            processed++;
        }
    }

    private static boolean matchesExpectedState(ServerLevel level, SchematicPasteJob.RollbackTask task) {
        if (!level.getBlockState(task.worldPos).equals(task.expectedState)) {
            return false;
        }
        CompoundTag currentBlockEntityTag = RollbackStore.captureBlockEntity(level, task.worldPos);
        if (!task.hadExpectedBlockEntity) {
            return currentBlockEntityTag == null;
        }
        return currentBlockEntityTag != null && NbtUtils.compareNbt(task.expectedBlockEntityTag, currentBlockEntityTag, false);
    }

    private static boolean restoreOriginalState(ServerLevel level, SchematicPasteJob.RollbackTask task) {
        if (!level.setBlock(task.worldPos, task.originalState, placementFlags(task.originalState.isAir()))) {
            if (!level.getBlockState(task.worldPos).equals(task.originalState)) {
                return false;
            }
        }

        if (task.hadOriginalBlockEntity && task.originalBlockEntityTag != null) {
            HolderLookup.Provider registries = level.registryAccess();
            BlockEntity blockEntity = BlockEntity.loadStatic(task.worldPos, task.originalState, task.originalBlockEntityTag.copy(), registries);
            if (blockEntity == null) {
                return false;
            }
            level.setBlockEntity(blockEntity);
            blockEntity.setChanged();
        }
        return true;
    }

    private static boolean needsConnectionFinalize(BlockState state) {
        Block block = state.getBlock();
        return block instanceof WallBlock || block instanceof CrossCollisionBlock;
    }

    private static void scheduleFluidTickIfNeeded(ServerLevel level, net.minecraft.core.BlockPos pos, BlockState state) {
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            level.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(level));
        }
    }

    private static BlockPos entityBlockPos(CompoundTag entityTag) {
        if (entityTag.contains("Pos", 9)) {
            net.minecraft.nbt.ListTag pos = entityTag.getList("Pos", 6);
            if (pos.size() >= 3) {
                return BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2));
            }
        }
        return new BlockPos(
            entityTag.getInt("TileX"),
            entityTag.getInt("TileY"),
            entityTag.getInt("TileZ")
        );
    }

    private static boolean spawnEntity(ServerLevel level, CompoundTag entityTag) {
        Entity entity = EntityType.loadEntityRecursive(entityTag.copy(), level, loaded -> {
            BlockPos pos = entityBlockPos(entityTag);
            if (entityTag.contains("Pos", 9)) {
                net.minecraft.nbt.ListTag posList = entityTag.getList("Pos", 6);
                loaded.moveTo(posList.getDouble(0), posList.getDouble(1), posList.getDouble(2), loaded.getYRot(), loaded.getXRot());
            } else {
                loaded.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, loaded.getYRot(), loaded.getXRot());
            }
            return loaded;
        });
        return entity != null && level.tryAddFreshEntityWithPassengers(entity);
    }

    private static void sendHud(MinecraftServer server, SchematicPasteJob job) {
        if (job.executorUuid == null) return;
        if (job.state != SchematicPasteJob.State.RUNNING
            && job.state != SchematicPasteJob.State.PAUSED
            && job.state != SchematicPasteJob.State.ROLLING_BACK) return;
        ServerPlayer player = server.getPlayerList().getPlayer(job.executorUuid);
        if (player == null) return;
        player.displayClientMessage(buildHudComponent(job), true);
    }

    private static void notifyComplete(MinecraftServer server, SchematicPasteJob job) {
        if (job.executorUuid == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(job.executorUuid);
        if (player == null) return;
        player.displayClientMessage(Component.empty(), true);
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("[FrameShift] ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(job.schematicName).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" complete - ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(formatCount(job.blocksPlaced) + " placed, "
                + formatCount(job.blocksUnchanged) + " unchanged").withStyle(ChatFormatting.WHITE)));
    }

    private static void notifyCancelled(MinecraftServer server, SchematicPasteJob job) {
        if (job.executorUuid == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(job.executorUuid);
        if (player == null) return;
        player.displayClientMessage(Component.empty(), true);
        player.sendSystemMessage(Component.empty()
            .append(Component.literal("[FrameShift] ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(job.schematicName).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" rollback complete - ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(
                formatCount(job.rollbackApplied) + " restored, "
                    + formatCount(job.rollbackSkippedConflicts) + " skipped, "
                    + formatCount(job.rollbackFailed) + " failed"
            ).withStyle(ChatFormatting.WHITE)));
    }

    private static Component buildHudComponent(SchematicPasteJob job) {
        boolean paused = job.state == SchematicPasteJob.State.PAUSED;
        ChatFormatting accent = paused ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
        Component stateTag = paused
            ? Component.literal(" [PAUSED]").withStyle(ChatFormatting.YELLOW)
            : Component.empty();
        if (job.rollbackMode) {
            int total = Math.max(job.rollbackQueued, job.rollbackCompletedOperations());
            int completed = job.rollbackCompletedOperations();
            double pct = total > 0 ? Math.min(100.0D, completed * 100.0D / total) : 100.0D;
            String bar = progressBar((int) pct, 10);
            String counts = formatCount(completed) + " / " + formatCount(total);
            double configuredBlocksPerSecond = FrameShiftConfig.maxBlocksPerTick.get()
                * FrameShiftConfig.throttleFactor(job.level.getServer().getAverageTickTimeNanos() / 1_000_000.0) * 20.0D;
            String eta = formatEta(job.etaSeconds(System.nanoTime(), configuredBlocksPerSecond));
            return Component.empty()
                .append(Component.literal("[FS] ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(job.schematicName + " ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("rollback ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatPercent(pct) + "% ").withStyle(accent))
                .append(Component.literal(bar + " ").withStyle(accent))
                .append(Component.literal(counts).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" ETA " + eta).withStyle(ChatFormatting.GRAY))
                .append(stateTag);
        }
        boolean clearing = job.isClearingPhase();
        int total = clearing ? job.clearOperationsTotal : job.knownPlaceTotal();
        int completed = clearing
            ? job.clearCompletedOperations()
            : job.displayCompletedBlocks();
        boolean totalKnown = clearing || job.hasKnownPlaceTotal();
        double pct = totalKnown && total > 0
            ? Math.min(100.0D, completed * 100.0D / total)
            : 0.0D;
        String bar = progressBar(totalKnown ? (int) pct : 0, 10);
        String counts = formatCount(completed) + " / " + (totalKnown ? formatCount(total) : "streaming");
        double configuredBlocksPerSecond = FrameShiftConfig.maxBlocksPerTick.get() * FrameShiftConfig.throttleFactor(job.level.getServer().getAverageTickTimeNanos() / 1_000_000.0) * 20.0D;
        String eta = formatEta(totalKnown ? job.etaSeconds(System.nanoTime(), configuredBlocksPerSecond) : -1L);
        return Component.empty()
            .append(Component.literal("[FS] ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(job.schematicName + " ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(clearing ? "clearing " : "placing ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(totalKnown ? formatPercent(pct) + "% " : "streaming ").withStyle(accent))
            .append(Component.literal(bar + " ").withStyle(accent))
            .append(Component.literal(counts).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" ETA " + eta).withStyle(ChatFormatting.GRAY))
            .append(stateTag);
    }

    private static String progressBar(int pct, int width) {
        int filled = pct * width / 100;
        return "#".repeat(filled) + "-".repeat(width - filled);
    }

    private static String formatCount(int count) {
        if (count < 1_000) return String.valueOf(count);
        if (count < 1_000_000) return String.format("%.1fK", count / 1_000.0);
        return String.format("%.2fM", count / 1_000_000.0);
    }

    private static String formatPercent(double pct) {
        if (Math.abs(pct - Math.rint(pct)) < 0.0005D) {
            return Integer.toString((int) Math.rint(pct));
        }
        if (Math.abs(pct * 10.0D - Math.rint(pct * 10.0D)) < 0.0005D) {
            return String.format(Locale.ROOT, "%.1f", pct);
        }
        return String.format(Locale.ROOT, "%.2f", pct);
    }

    private static String formatEta(long etaSeconds) {
        if (etaSeconds < 0L) {
            return "--:--";
        }
        long hours = etaSeconds / 3600L;
        long minutes = (etaSeconds % 3600L) / 60L;
        long seconds = etaSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }
}
