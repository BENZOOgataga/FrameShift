package com.benzoogataga.frameshift.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

// Defines every setting the user can tweak in frameshift-server.toml.
// NeoForge reads this file from the server's /config folder and hot-reloads it on /schem reload.
public class FrameShiftConfig {

    // ── Tick budget ───────────────────────────────────────────────────────────

    // How many blocks to place per tick at 100% throughput (server MSPT < 35)
    public static ModConfigSpec.IntValue maxBlocksPerTick;

    // How many block entities (chests, signs, etc.) to restore per tick
    public static ModConfigSpec.IntValue maxBlockEntitiesPerTick;

    // How many entities (mobs, item frames, etc.) to spawn per tick
    public static ModConfigSpec.IntValue maxEntitiesPerTick;

    // Hard wall-clock limit per tick in milliseconds — stops the batch early if exceeded
    public static ModConfigSpec.IntValue maxMillisPerTick;

    // Scale down placement speed automatically when the server is lagging
    public static ModConfigSpec.BooleanValue adaptiveThrottling;

    // ── Chunk management ─────────────────────────────────────────────────────

    // Load required chunks before starting a paste
    public static ModConfigSpec.BooleanValue preloadChunks;

    // Keep chunks force-loaded for the duration of the paste (may impact performance)
    public static ModConfigSpec.BooleanValue forceLoadChunks;

    // Maximum radius (in chunks) around the paste origin that the mod will touch
    public static ModConfigSpec.IntValue chunkRadiusLimit;

    // ── Safety limits ─────────────────────────────────────────────────────────

    // Reject schematics whose total volume (X*Y*Z) exceeds this number of blocks
    public static ModConfigSpec.LongValue maxSchematicVolume;

    // Reject schematics with more non-air blocks than this
    public static ModConfigSpec.LongValue maxBlocksTotal;

    // Maximum number of paste jobs allowed to run (or be paused) at the same time
    public static ModConfigSpec.IntValue maxConcurrentJobs;

    // ── File system ───────────────────────────────────────────────────────────

    // Folders (relative to the server root) where the mod looks for schematic files
    public static ModConfigSpec.ConfigValue<List<? extends String>> schematicDirectories;

    // ─────────────────────────────────────────────────────────────────────────

    // Called from FrameShift constructor — builds the config spec and registers the file
    public static void register(ModContainer container) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("tick");
        maxBlocksPerTick        = builder.comment("Blocks placed per tick at full speed").defineInRange("maxBlocksPerTick", 2000, 1, 100000);
        maxBlockEntitiesPerTick = builder.comment("Block entities restored per tick").defineInRange("maxBlockEntitiesPerTick", 100, 1, 10000);
        maxEntitiesPerTick      = builder.comment("Entities spawned per tick").defineInRange("maxEntitiesPerTick", 20, 1, 1000);
        maxMillisPerTick        = builder.comment("Max milliseconds to spend on placement per tick").defineInRange("maxMillisPerTick", 8, 1, 50);
        adaptiveThrottling      = builder.comment("Reduce speed automatically when server is lagging").define("adaptiveThrottling", true);
        builder.pop();

        builder.push("chunks");
        preloadChunks    = builder.comment("Load chunks before starting a paste").define("preloadChunks", true);
        forceLoadChunks  = builder.comment("Force-load chunks for the duration of the paste").define("forceLoadChunks", false);
        chunkRadiusLimit = builder.comment("Max chunk radius the mod will touch around the origin").defineInRange("chunkRadiusLimit", 8, 1, 64);
        builder.pop();

        builder.push("limits");
        maxSchematicVolume = builder.comment("Max schematic volume (X*Y*Z) in blocks").defineInRange("maxSchematicVolume", 50_000_000L, 1L, Long.MAX_VALUE);
        maxBlocksTotal     = builder.comment("Max non-air blocks in a schematic").defineInRange("maxBlocksTotal", 20_000_000L, 1L, Long.MAX_VALUE);
        maxConcurrentJobs  = builder.comment("Max simultaneously active paste jobs").defineInRange("maxConcurrentJobs", 2, 1, 10);
        builder.pop();

        builder.push("filesystem");
        schematicDirectories = builder.comment("Folders to search for schematic files (relative to server root)")
            .defineListAllowEmpty("schematicDirectories",
                List.of("worldedit/schematics", "config/worldedit/schematics", "schematics"),
                e -> e instanceof String);
        builder.pop();

        ModConfigSpec spec = builder.build();
        container.registerConfig(ModConfig.Type.SERVER, spec);
    }
}
