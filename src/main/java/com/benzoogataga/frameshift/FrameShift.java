package com.benzoogataga.frameshift;

import com.benzoogataga.frameshift.command.SchemCommand;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.job.JobManager;
import com.benzoogataga.frameshift.job.JobPersistence;
import com.benzoogataga.frameshift.job.RollbackStore;
import com.benzoogataga.frameshift.job.SchematicPasteJob;
import com.benzoogataga.frameshift.schematic.SchematicReadOptions;
import com.benzoogataga.frameshift.schematic.SchematicLoader;
import com.benzoogataga.frameshift.tick.TickHandler;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

// Main mod entry point. Wires config, lifecycle hooks, and server-side systems.
@Mod(FrameShift.MOD_ID)
public class FrameShift {

    public static final String MOD_ID = "frameshift";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final SchematicLoader loader;

    public FrameShift(IEventBus modEventBus, ModContainer modContainer) {
        FrameShiftConfig.register(modContainer);
        this.loader = new SchematicLoader();

        NeoForge.EVENT_BUS.register(new TickHandler());
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(SchemCommand::onPlayerLoggedOut);

        LOGGER.info("FrameShift loaded: Yet Another Schematics Manager");
        LOGGER.info("Schematic metadata loader is ready");
    }

    // Reloads persisted jobs after the server is fully ready and levels are accessible.
    private void onServerStarted(ServerStartedEvent event) {
        if (!FrameShiftConfig.autoResumeJobs.get()) {
            return;
        }
        resumePersistedJobs(event.getServer());
    }

    // Shuts down background metadata work when the server stops.
    private void onServerStopping(ServerStoppingEvent event) {
        persistActiveJobs(event.getServer());
        loader.shutdown();
    }

    // Registers commands against the active loader instance.
    private void onRegisterCommands(RegisterCommandsEvent event) {
        SchemCommand.register(event, loader);
    }

    // Saves the currently active jobs so a clean restart can resume them later.
    private void persistActiveJobs(MinecraftServer server) {
        Path serverRoot = server.getServerDirectory();
        int saved = 0;
        for (SchematicPasteJob job : JobManager.all()) {
            if ((job.state == SchematicPasteJob.State.RUNNING
                || job.state == SchematicPasteJob.State.PAUSED
                || job.state == SchematicPasteJob.State.ROLLING_BACK)
                && job.schematicPath != null) {
                JobPersistence.save(job, serverRoot);
                saved++;
            }
        }
        if (saved > 0) {
            LOGGER.info("Persisted {} FrameShift job(s) for restart resume", saved);
        }
    }

    // Reconstructs saved jobs and restarts their async streaming on server startup.
    private void resumePersistedJobs(MinecraftServer server) {
        Path serverRoot = server.getServerDirectory();
        int resumed = 0;
        for (JobPersistence.SavedJob saved : JobPersistence.loadAll(serverRoot)) {
            ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(saved.dimension()));
            ServerLevel level = server.getLevel(dimensionKey);
            if (level == null) {
                LOGGER.warn("Skipping persisted job {} because dimension {} is unavailable", saved.jobId(), saved.dimension());
                continue;
            }
            if (!java.nio.file.Files.isRegularFile(saved.schematicPath())) {
                LOGGER.warn("Skipping persisted job {} because schematic file is missing: {}", saved.jobId(), saved.schematicPath());
                continue;
            }

            SchematicPasteJob job = new SchematicPasteJob(
                saved.jobId(),
                saved.schematicName(),
                level,
                saved.origin(),
                saved.executorUuid()
            );
            job.schematicPath = saved.schematicPath();
            job.skipClear = saved.skipClear();
            job.freezeGravity = saved.freezeGravity();
            job.persistenceDirectory = JobPersistence.jobDir(serverRoot, saved.jobId());
            job.rollbackLogPath = job.persistenceDirectory.resolve("rollback.log");
            job.rollbackMode = saved.rollbackMode();
            job.state = saved.state();
            job.clearOperationsTotal = saved.clearOperationsTotal();
            job.expectedTotalBlocks = saved.expectedTotalBlocks();
            job.displayTotalBlocks = saved.displayTotalBlocks();
            job.blocksAttempted = saved.blocksAttempted();
            job.blocksPlaced = saved.blocksPlaced();
            job.blocksUnchanged = saved.blocksUnchanged();
            job.blocksVerified = saved.blocksVerified();
            job.blocksRetried = saved.blocksRetried();
            job.blocksFailed = saved.blocksFailed();
            job.blockEntitiesApplied = saved.blockEntitiesApplied();
            job.rollbackQueued = saved.rollbackQueued();
            job.rollbackApplied = saved.rollbackApplied();
            job.rollbackSkippedConflicts = saved.rollbackSkippedConflicts();
            job.rollbackFailed = saved.rollbackFailed();
            job.rollbackSequence = saved.rollbackSequence();

            try {
                if ((saved.rollbackMode() || saved.state() == SchematicPasteJob.State.ROLLING_BACK || saved.rollbackQueued() > 0)
                    && !Files.isRegularFile(job.rollbackLogPath)) {
                    throw new IllegalStateException("rollback.log is missing");
                }
                RollbackStore.loadRollbackState(job);
                if ((saved.rollbackMode() || saved.state() == SchematicPasteJob.State.ROLLING_BACK || saved.rollbackQueued() > 0)
                    && job.rollbackIndex.isEmpty()) {
                    throw new IllegalStateException("no rollback snapshots could be reconstructed");
                }
            } catch (Exception e) {
                job.state = SchematicPasteJob.State.FAILED;
                job.failureReason = "Rollback data is missing or corrupt: " + e.getMessage();
                LOGGER.error("Failed to load rollback state for job {}: {}", saved.jobId(), e.getMessage());
            }
            if (!JobManager.submit(job)) {
                LOGGER.warn("Skipping persisted job {} because the active job limit is already reached", saved.jobId());
                continue;
            }

            if (job.rollbackMode || job.state == SchematicPasteJob.State.ROLLING_BACK) {
                RollbackStore.rebuildRollbackQueue(job);
                job.rollbackMode = true;
                job.state = saved.state() == SchematicPasteJob.State.PAUSED
                    ? SchematicPasteJob.State.PAUSED
                    : SchematicPasteJob.State.ROLLING_BACK;
                LOGGER.info("Resumed rollback state for FrameShift job {} ({})", job.jobId, job.schematicName);
            } else {
                job.state = saved.state() == SchematicPasteJob.State.PAUSED
                    ? SchematicPasteJob.State.PAUSED
                    : SchematicPasteJob.State.RUNNING;
                loader.streamPasteIntoJobAsync(level, saved.schematicPath(), new SchematicReadOptions(true, true, false), job)
                    .thenAcceptAsync(summary -> LOGGER.info(
                        "Resumed FrameShift job {} for {} ({} placeable blocks)",
                        job.jobId,
                        job.schematicName,
                        job.displayTotalBlocks
                    ), server::execute)
                    .exceptionally(error -> {
                        server.execute(() -> {
                            job.state = SchematicPasteJob.State.FAILED;
                            job.failureReason = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
                            LOGGER.error("Failed to resume FrameShift job {} ({}): {}", job.jobId, job.schematicName, job.failureReason);
                        });
                        return null;
                    });
            }
            resumed++;
        }
        if (resumed > 0) {
            LOGGER.info("Queued {} persisted FrameShift job(s) for resume", resumed);
        }
    }
}
