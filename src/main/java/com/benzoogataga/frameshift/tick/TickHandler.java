package com.benzoogataga.frameshift.tick;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.chunk.ChunkHelper;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.job.JobManager;
import com.benzoogataga.frameshift.job.SchematicPasteJob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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
        double configuredBlocksPerSecond = FrameShiftConfig.maxBlocksPerTick.get() * throttleFactor(mspt) * 20.0D;

        for (SchematicPasteJob job : JobManager.all()) {
            if (job.state == SchematicPasteJob.State.PAUSED && job.autoPaused && mspt <= 45.0D) {
                job.state = SchematicPasteJob.State.RUNNING;
                job.autoPaused = false;
            }

            if (job.state != SchematicPasteJob.State.RUNNING) {
                continue;
            }

            double throttle = throttleFactor(mspt);
            if (throttle <= 0.0D) {
                job.state = SchematicPasteJob.State.PAUSED;
                job.autoPaused = true;
                continue;
            }

            int targetPlacements = Math.max(1, (int) Math.floor(FrameShiftConfig.maxBlocksPerTick.get() * throttle));
            int processed = 0;
            while (processed < targetPlacements && withinBudget(tickStartNanos)) {
                if (!job.placementQueue.isEmpty()) {
                    if (!placeNext(job, job.placementQueue)) {
                        break;
                    }
                    processed++;
                    continue;
                }
                if (!job.gravityPlacementQueue.isEmpty()) {
                    if (!placeNext(job, job.gravityPlacementQueue)) {
                        break;
                    }
                    processed++;
                    continue;
                }
                break;
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

            job.observeProgress(System.nanoTime());

            if (job.loadingComplete
                && job.placementQueue.isEmpty()
                && job.gravityPlacementQueue.isEmpty()
                && job.blockEntityQueue.isEmpty()) {
                job.state = SchematicPasteJob.State.DONE;
                FrameShift.LOGGER.info("Paste job {} finished for schematic {}", job.jobId, job.schematicName);
                notifyComplete(server, job);
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

    private static double throttleFactor(double mspt) {
        if (!FrameShiftConfig.adaptiveThrottling.get()) {
            return 1.0D;
        }
        if (mspt < 35.0D) {
            return 1.0D;
        }
        if (mspt < 45.0D) {
            return 0.5D;
        }
        if (mspt < 50.0D) {
            return 0.25D;
        }
        return 0.0D;
    }

    private static boolean withinBudget(long tickStartNanos) {
        long elapsedMillis = (System.nanoTime() - tickStartNanos) / 1_000_000L;
        return elapsedMillis < FrameShiftConfig.maxMillisPerTick.get();
    }

    private static boolean placeNext(
        SchematicPasteJob job,
        java.util.Queue<SchematicPasteJob.PlacementTask> queue
    ) {
        SchematicPasteJob.PlacementTask task = queue.peek();
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

        queue.poll();
        job.blocksAttempted++;
        PlacementResult placementResult = applyBlockState(job.level, task);
        if (placementResult == PlacementResult.FAILED) {
            job.blocksFailed++;
            return true;
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
        job.blocksVerified++;
        return true;
    }

    private static PlacementResult applyBlockState(ServerLevel level, SchematicPasteJob.PlacementTask task) {
        int flags = task.state.isAir()
            ? Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            : Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
        boolean changed = level.setBlock(task.worldPos, task.state, flags);
        if (changed) {
            return PlacementResult.CHANGED;
        }
        if (!level.getBlockState(task.worldPos).equals(task.state)) {
            return PlacementResult.FAILED;
        }
        return PlacementResult.NO_CHANGE;
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

    private static void sendHud(MinecraftServer server, SchematicPasteJob job) {
        if (job.executorUuid == null) return;
        if (job.state != SchematicPasteJob.State.RUNNING && job.state != SchematicPasteJob.State.PAUSED) return;
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

    private static Component buildHudComponent(SchematicPasteJob job) {
        boolean paused = job.state == SchematicPasteJob.State.PAUSED;
        ChatFormatting accent = paused ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
        Component stateTag = paused
            ? Component.literal(" [PAUSED]").withStyle(ChatFormatting.YELLOW)
            : Component.empty();
        boolean clearing = job.isClearingPhase();
        int total = clearing
            ? job.clearOperationsTotal
            : (job.displayTotalBlocks > 0 ? job.displayTotalBlocks : job.expectedTotalBlocks);
        int completed = clearing
            ? job.clearCompletedOperations()
            : job.displayCompletedBlocks();
        double pct = total > 0
            ? Math.min(100.0D, completed * 100.0D / total)
            : 0.0D;
        String bar = progressBar((int) pct, 10);
        String counts = formatCount(completed) + " / " + formatCount(total);
        double configuredBlocksPerSecond = FrameShiftConfig.maxBlocksPerTick.get() * throttleFactor(job.level.getServer().getAverageTickTimeNanos() / 1_000_000.0) * 20.0D;
        String eta = formatEta(job.etaSeconds(System.nanoTime(), configuredBlocksPerSecond));
        return Component.empty()
            .append(Component.literal("[FS] ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(job.schematicName + " ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(clearing ? "clearing " : "placing ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(formatPercent(pct) + "% ").withStyle(accent))
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
