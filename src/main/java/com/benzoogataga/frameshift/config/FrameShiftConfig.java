package com.benzoogataga.frameshift.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

// Defines every setting the user can tweak in frameshift-server.toml.
// NeoForge reads this file from the server's /config folder and hot-reloads it on /schem reload.
public class FrameShiftConfig {

    // How many blocks to place per tick at 100% throughput (server MSPT < 35)
    public static ModConfigSpec.IntValue maxBlocksPerTick;

    // How many block entities (chests, signs, etc.) to restore per tick
    public static ModConfigSpec.IntValue maxBlockEntitiesPerTick;

    // How many entities (mobs, item frames, etc.) to spawn per tick
    public static ModConfigSpec.IntValue maxEntitiesPerTick;

    // Hard wall-clock limit per tick in milliseconds; stops the batch early if exceeded.
    public static ModConfigSpec.IntValue maxMillisPerTick;

    // Max number of block placements buffered in memory per job before async parsing back-pressures.
    public static ModConfigSpec.IntValue maxQueuedPlacements;

    // Scale down placement speed automatically when the server is lagging
    public static ModConfigSpec.BooleanValue adaptiveThrottling;

    // Load required chunks before starting a paste
    public static ModConfigSpec.BooleanValue preloadChunks;

    // Keep chunks force-loaded for the duration of the paste (may impact performance)
    public static ModConfigSpec.BooleanValue forceLoadChunks;

    // Maximum radius (in chunks) around the paste origin that the mod will touch
    public static ModConfigSpec.IntValue chunkRadiusLimit;

    // Reject schematics whose total volume (X*Y*Z) exceeds this number of blocks
    public static ModConfigSpec.LongValue maxSchematicVolume;

    // Reject schematics with more non-air blocks than this
    public static ModConfigSpec.LongValue maxBlocksTotal;

    // Maximum number of paste jobs allowed to run (or be paused) at the same time
    public static ModConfigSpec.IntValue maxConcurrentJobs;

    // Folders (relative to the server root) where the mod looks for schematic files
    public static ModConfigSpec.ConfigValue<List<? extends String>> schematicDirectories;

    // Reject metadata reads larger than this compressed byte count.
    public static ModConfigSpec.LongValue maxMetadataReadCompressedBytes;

    // Controls the thread count for asynchronous metadata reads and listing.
    public static ModConfigSpec.IntValue metadataIoThreads;

    // Caps the number of entries returned by one /schem list page.
    public static ModConfigSpec.IntValue maxListResults;

    // Automatically resume persisted jobs when the server starts
    public static ModConfigSpec.BooleanValue autoResumeJobs;

    // Max bytes of rollback snapshot data allowed for one job directory.
    public static ModConfigSpec.LongValue maxRollbackSnapshotBytesPerJob;

    // Max bytes of rollback snapshot data allowed across all persisted jobs.
    public static ModConfigSpec.LongValue maxTotalRollbackStorageBytes;

    // Max number of persisted job directories allowed at once.
    public static ModConfigSpec.IntValue maxPersistedRollbackJobs;

    // Called from FrameShift constructor; builds the config spec and registers the file.
    public static void register(ModContainer container) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("tick");
        maxBlocksPerTick = builder.comment("Blocks placed per tick at full speed").defineInRange("maxBlocksPerTick", 2000, 1, 100000);
        maxBlockEntitiesPerTick = builder.comment("Block entities restored per tick").defineInRange("maxBlockEntitiesPerTick", 100, 1, 10000);
        maxEntitiesPerTick = builder.comment("Entities spawned per tick").defineInRange("maxEntitiesPerTick", 20, 1, 1000);
        maxMillisPerTick = builder.comment("Max milliseconds to spend on placement per tick").defineInRange("maxMillisPerTick", 8, 1, 50);
        maxQueuedPlacements = builder.comment("Max buffered block placements in memory per job before parser waits").defineInRange("maxQueuedPlacements", 20000, 1000, 2_000_000);
        adaptiveThrottling = builder.comment("Reduce speed automatically when server is lagging").define("adaptiveThrottling", true);
        builder.pop();

        builder.push("chunks");
        preloadChunks = builder.comment("Load chunks before starting a paste").define("preloadChunks", true);
        forceLoadChunks = builder.comment("Force-load chunks for the duration of the paste").define("forceLoadChunks", false);
        chunkRadiusLimit = builder.comment("Max chunk radius the mod will touch around the origin").defineInRange("chunkRadiusLimit", 8, 1, 64);
        builder.pop();

        builder.push("limits");
        maxSchematicVolume = builder.comment("Max schematic volume (X*Y*Z) in blocks").defineInRange("maxSchematicVolume", 50_000_000L, 1L, Long.MAX_VALUE);
        maxBlocksTotal = builder.comment("Max non-air blocks in a schematic").defineInRange("maxBlocksTotal", 20_000_000L, 1L, Long.MAX_VALUE);
        maxConcurrentJobs = builder.comment("Max simultaneously active paste jobs").defineInRange("maxConcurrentJobs", 2, 1, 10);
        builder.pop();

        builder.push("filesystem");
        schematicDirectories = builder.comment("Folders to search for schematic files (relative to server root)")
            .defineListAllowEmpty(
                "schematicDirectories",
                List.of("worldedit/schematics", "config/worldedit/schematics", "schematics"),
                entry -> entry instanceof String
            );
        builder.pop();

        builder.push("metadata");
        maxMetadataReadCompressedBytes = builder
            .comment("Max compressed file size in bytes before rejecting an NBT metadata read")
            .defineInRange("maxMetadataReadCompressedBytes", 256_000_000L, 1L, Long.MAX_VALUE);
        metadataIoThreads = builder
            .comment("Threads in the dedicated IO executor for metadata reads")
            .defineInRange("metadataIoThreads", 2, 1, 16);
        maxListResults = builder
            .comment("Max entries returned per /schem list page")
            .defineInRange("maxListResults", 100, 1, 1000);
        builder.pop();

        builder.push("jobs");
        autoResumeJobs = builder
            .comment("Automatically resume paused jobs when the server restarts")
            .define("autoResumeJobs", true);
        maxRollbackSnapshotBytesPerJob = builder
            .comment("Max rollback snapshot bytes allowed for one job")
            .defineInRange("maxRollbackSnapshotBytesPerJob", 256_000_000L, 1L, Long.MAX_VALUE);
        maxTotalRollbackStorageBytes = builder
            .comment("Max rollback snapshot bytes allowed across all jobs")
            .defineInRange("maxTotalRollbackStorageBytes", 1_000_000_000L, 1L, Long.MAX_VALUE);
        maxPersistedRollbackJobs = builder
            .comment("Max persisted job directories allowed for rollback and restart resume")
            .defineInRange("maxPersistedRollbackJobs", 50, 1, 10_000);
        builder.pop();

        ModConfigSpec spec = builder.build();
        container.registerConfig(ModConfig.Type.SERVER, spec);
    }

    // Returns the fraction of max throughput to use given current server MSPT.
    // 0.0 means fully paused; used identically by TickHandler and status/ETA displays.
    public static double throttleFactor(double mspt) {
        if (!adaptiveThrottling.get()) {
            return 1.0D;
        }
        if (mspt < 35.0D) return 1.0D;
        if (mspt < 45.0D) return 0.5D;
        if (mspt < 50.0D) return 0.25D;
        return 0.0D;
    }
}
