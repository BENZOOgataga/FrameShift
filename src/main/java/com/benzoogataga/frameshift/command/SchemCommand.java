package com.benzoogataga.frameshift.command;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.job.JobManager;
import com.benzoogataga.frameshift.job.SchematicPasteJob;
import com.benzoogataga.frameshift.schematic.SchematicListResult;
import com.benzoogataga.frameshift.schematic.SchematicLoader;
import com.benzoogataga.frameshift.schematic.SchematicMetadata;
import com.benzoogataga.frameshift.schematic.SchematicReadOptions;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

// Registers read-only schematic inspection commands.
public class SchemCommand {

    private static final UUID CONSOLE_SESSION_KEY = new UUID(0L, 0L);
    private static final int MAX_DEBUG_INVALID_STATES = 5;
    private static final float DEFAULT_FLY_SPEED = 0.05F;
    private static final float MIN_FLY_SPEED = 0.01F;
    private static final float MAX_FLY_SPEED = 1.00F;
    private static final Map<UUID, LoadedSchematicSession> LOADED_SCHEMATICS = new ConcurrentHashMap<>();

    public static void register(RegisterCommandsEvent event, SchematicLoader loader) {
        event.getDispatcher().register(
            Commands.literal("schem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                    .executes(context -> executeList(context.getSource(), loader, null))
                    .then(Commands.argument("cursor", StringArgumentType.word())
                        .executes(context -> executeList(
                            context.getSource(),
                            loader,
                            StringArgumentType.getString(context, "cursor")
                        ))))
                .then(Commands.literal("info")
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggestSchematicNames(context.getSource(), loader, builder))
                        .executes(context -> executeInfo(
                            context.getSource(),
                            loader,
                            StringArgumentType.getString(context, "name")
                        ))))
                .then(Commands.literal("load")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> suggestSchematicNames(context.getSource(), loader, builder))
                        .executes(context -> executeLoad(
                            context.getSource(),
                            loader,
                            StringArgumentType.getString(context, "name")
                        ))))
                .then(Commands.literal("paste")
                    .executes(context -> executePasteLoaded(context.getSource(), loader, null, false, false))
                    .then(Commands.literal("no-clear")
                        .executes(context -> executePasteLoaded(context.getSource(), loader, null, false, true))
                        .then(Commands.literal("debug")
                            .executes(context -> executePasteLoaded(context.getSource(), loader, null, true, true))))
                    .then(Commands.literal("debug")
                        .executes(context -> executePasteLoaded(context.getSource(), loader, null, true, false)))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> executePasteLoaded(
                            context.getSource(),
                            loader,
                            BlockPosArgument.getLoadedBlockPos(context, "pos"),
                            false,
                            false
                        ))
                        .then(Commands.literal("no-clear")
                            .executes(context -> executePasteLoaded(
                                context.getSource(),
                                loader,
                                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                false,
                                true
                            ))
                            .then(Commands.literal("debug")
                                .executes(context -> executePasteLoaded(
                                    context.getSource(),
                                    loader,
                                    BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                    true,
                                    true
                                ))))
                        .then(Commands.literal("debug")
                            .executes(context -> executePasteLoaded(
                                context.getSource(),
                                loader,
                                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                true,
                                false
                            )))))
                .then(Commands.literal("status")
                    .executes(context -> executeStatus(context.getSource())))
                .then(Commands.literal("cancel")
                    .then(Commands.argument("jobId", StringArgumentType.word())
                        .suggests((context, builder) -> suggestJobIds(builder))
                        .executes(context -> executeCancel(
                            context.getSource(),
                            StringArgumentType.getString(context, "jobId")
                        ))))
                .then(Commands.literal("reload")
                    .executes(context -> executeReload(context.getSource(), loader)))
        );

        event.getDispatcher().register(
            Commands.literal("flyspeed")
                .requires(source -> source.hasPermission(2))
                .executes(context -> executeFlySpeedReset(context.getSource()))
                .then(Commands.literal("reset")
                    .executes(context -> executeFlySpeedReset(context.getSource())))
                .then(Commands.argument("value", FloatArgumentType.floatArg(MIN_FLY_SPEED, MAX_FLY_SPEED))
                    .executes(context -> executeFlySpeedSet(
                        context.getSource(),
                        FloatArgumentType.getFloat(context, "value")
                    )))
        );
    }

    private static int executeList(CommandSourceStack source, SchematicLoader loader, @Nullable String cursor) {
        MinecraftServer server = source.getServer();
        loader.listAsync(server.getServerDirectory(), FrameShiftConfig.maxListResults.get(), cursor)
            .thenAcceptAsync(result -> sendListResult(source, result), server::execute)
            .exceptionally(error -> {
                server.execute(() -> source.sendFailure(SchemMessages.error("Error listing schematics: ", unwrap(error).getMessage())));
                return null;
            });
        return Command.SINGLE_SUCCESS;
    }

    private static int executeInfo(CommandSourceStack source, SchematicLoader loader, String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            source.sendFailure(SchemMessages.error("Invalid schematic name. ", "Path separators are not allowed."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        return loader.findByName(server.getServerDirectory(), name)
            .map(path -> {
                loader.readMetadataAsync(path)
                    .thenAcceptAsync(metadata -> sendInfoResult(source, metadata), server::execute)
                    .exceptionally(error -> {
                        server.execute(() -> source.sendFailure(SchemMessages.error("Error reading schematic: ", unwrap(error).getMessage())));
                        return null;
                    });
                return Command.SINGLE_SUCCESS;
            })
            .orElseGet(() -> {
                source.sendFailure(SchemMessages.error("Schematic not found: ", name));
                return 0;
            });
    }

    private static int executeReload(CommandSourceStack source, SchematicLoader loader) {
        try {
            ModConfig config = ModConfigs.getModConfigs(FrameShift.MOD_ID).stream()
                .filter(modConfig -> modConfig.getType() == ModConfig.Type.SERVER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("FrameShift server config is not registered"));

            reloadConfig(config);
            loader.reload();

            source.sendSuccess(() -> SchemMessages.success("Config reloaded."), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            Throwable cause = unwrap(exception);
            source.sendFailure(SchemMessages.error("Error reloading FrameShift config: ", cause.getMessage()));
            return 0;
        }
    }

    private static int executeFlySpeedSet(CommandSourceStack source, float speed) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(SchemMessages.error("Player only command. ", "Use this command in-game."));
            return 0;
        }

        float clamped = Math.max(MIN_FLY_SPEED, Math.min(MAX_FLY_SPEED, speed));
        player.getAbilities().setFlyingSpeed(clamped);
        player.onUpdateAbilities();
        source.sendSuccess(() -> SchemMessages.success(
            "Flight speed set to " + String.format(Locale.ROOT, "%.2f", clamped) + "."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeFlySpeedReset(CommandSourceStack source) {
        return executeFlySpeedSet(source, DEFAULT_FLY_SPEED);
    }

    private static int executeLoad(CommandSourceStack source, SchematicLoader loader, String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            source.sendFailure(SchemMessages.error("Invalid schematic name. ", "Path separators are not allowed."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> SchemMessages.info("Loading schematic " + name + "..."), false);
        return loader.findByName(server.getServerDirectory(), name)
            .map(path -> {
                loader.readMetadataAsync(path)
                    .thenAcceptAsync(metadata -> {
                        LOADED_SCHEMATICS.put(sessionKey(source), new LoadedSchematicSession(name, path, metadata));
                        source.sendSuccess(() -> SchemMessages.success(
                            "Loaded " + name + " (" + metadata.sizeX + "x" + metadata.sizeY + "x" + metadata.sizeZ + ")."
                        ), false);
                        source.sendSuccess(() -> SchemMessages.field(
                            "Offset",
                            metadata.offsetX + ", " + metadata.offsetY + ", " + metadata.offsetZ,
                            ChatFormatting.WHITE
                        ), false);
                    }, server::execute)
                    .exceptionally(error -> {
                        server.execute(() -> source.sendFailure(SchemMessages.error("Error loading schematic: ", unwrap(error).getMessage())));
                        return null;
                    });
                return Command.SINGLE_SUCCESS;
            })
            .orElseGet(() -> {
                source.sendFailure(SchemMessages.error("Schematic not found: ", name));
                return 0;
            });
    }

    private static int executePasteLoaded(
        CommandSourceStack source,
        SchematicLoader loader,
        @Nullable net.minecraft.core.BlockPos explicitPos,
        boolean debugWarnings,
        boolean skipClear
    ) {
        LoadedSchematicSession loaded = LOADED_SCHEMATICS.get(sessionKey(source));
        if (loaded == null) {
            source.sendFailure(SchemMessages.error("No schematic is loaded. ", "Run /schem load <name> first."));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (explicitPos == null && player == null) {
            source.sendFailure(SchemMessages.error("Missing paste position. ", "Console use requires explicit coordinates."));
            return 0;
        }

        net.minecraft.core.BlockPos origin = explicitPos != null ? explicitPos : player.blockPosition();
        MinecraftServer server = source.getServer();
        SchematicPasteJob job = new SchematicPasteJob(loaded.name, source.getLevel(), origin, player != null ? player.getUUID() : null);
        job.skipClear = skipClear;
        if (!JobManager.submit(job)) {
            source.sendFailure(SchemMessages.error("Could not queue paste. ", "The max concurrent job limit has been reached."));
            return 0;
        }

        source.sendSuccess(() -> SchemMessages.info("Streaming " + loaded.name + "..."), false);
        if (skipClear) {
            source.sendSuccess(() -> SchemMessages.warning(
                "No-clear mode is faster, but not safe: old world blocks, air gaps, and unsupported/falling blocks may remain inside the pasted area."
            ), false);
        }

        loader.streamPasteIntoJobAsync(source.getLevel(), loaded.file, new SchematicReadOptions(true, true, false), job)
            .thenAcceptAsync(summary -> {
                source.sendSuccess(() -> SchemMessages.info(
                    "Streamed " + loaded.name + " (" + job.displayTotalBlocks + " placeable blocks)."
                ), false);

                if (debugWarnings && summary.skippedInvalidBlocks() > 0) {
                    source.sendSuccess(() -> SchemMessages.warning(
                        "Debug: skipped " + summary.skippedInvalidBlocks()
                            + " block placements due to invalid palette states."
                    ), false);
                    int lines = Math.min(MAX_DEBUG_INVALID_STATES, summary.invalidPaletteStates().size());
                    for (int index = 0; index < lines; index++) {
                        String stateId = summary.invalidPaletteStates().get(index);
                        source.sendSuccess(() -> SchemMessages.warning("Invalid state: " + stateId), false);
                    }
                }
            }, server::execute)
            .exceptionally(error -> {
                server.execute(() -> {
                    job.state = SchematicPasteJob.State.FAILED;
                    job.failureReason = unwrap(error).getMessage();
                    source.sendFailure(SchemMessages.error("Error streaming loaded schematic: ", unwrap(error).getMessage()));
                });
                return null;
            });

        return Command.SINGLE_SUCCESS;
    }

    private static void sendListResult(CommandSourceStack source, SchematicListResult result) {
        if (result.entries.isEmpty()) {
            Component message = (result.failed > 0 || result.skipped > 0)
                ? SchemMessages.warning("No readable schematics found.")
                : SchemMessages.warning("No schematics found.");
            source.sendSuccess(() -> message, false);
            if (result.failed > 0) {
                source.sendSuccess(() -> SchemMessages.warning("Some files were rejected by safety limits or parse checks."), false);
            }
            if (result.skipped > 0 || result.failed > 0) {
                source.sendSuccess(() -> SchemMessages.counts(result.skipped, result.failed), false);
            }
            return;
        }

        for (SchematicMetadata metadata : result.entries) {
            source.sendSuccess(() -> SchemMessages.listEntry(
                metadata.name,
                metadata.sizeX + "x" + metadata.sizeY + "x" + metadata.sizeZ,
                formatFileSize(metadata.fileSize)
            ), false);
        }

        if (result.nextCursor != null) {
            source.sendSuccess(() -> SchemMessages.nextPage(result.nextCursor), false);
        }

        if (result.skipped > 0 || result.failed > 0) {
            source.sendSuccess(() -> SchemMessages.counts(result.skipped, result.failed), false);
        }
    }

    private static void sendInfoResult(CommandSourceStack source, SchematicMetadata metadata) {
        source.sendSuccess(() -> SchemMessages.field("Name", metadata.name, ChatFormatting.AQUA), false);
        source.sendSuccess(() -> SchemMessages.field("Format", metadata.format.toString(), ChatFormatting.WHITE), false);
        source.sendSuccess(() -> SchemMessages.field("Dimensions", metadata.sizeX + " x " + metadata.sizeY + " x " + metadata.sizeZ, ChatFormatting.WHITE), false);
        source.sendSuccess(() -> SchemMessages.field(
            "Offset",
            metadata.offsetX + ", " + metadata.offsetY + ", " + metadata.offsetZ,
            ChatFormatting.WHITE
        ), false);
        source.sendSuccess(() -> SchemMessages.field("Volume", Long.toString(metadata.volume()), ChatFormatting.WHITE), false);
        source.sendSuccess(() -> SchemMessages.field("File size", formatFileSize(metadata.fileSize), ChatFormatting.WHITE), false);
        source.sendSuccess(() -> SchemMessages.mutedField("Data version", metadata.dataVersion, -1, ChatFormatting.WHITE), false);
        source.sendSuccess(() -> SchemMessages.mutedField("Block entities", metadata.blockEntityCount, -1, ChatFormatting.WHITE), false);
        source.sendSuccess(() -> SchemMessages.mutedField("Entities", metadata.entityCount, -1, ChatFormatting.WHITE), false);
    }

    private static int executeStatus(CommandSourceStack source) {
        if (JobManager.all().isEmpty()) {
            source.sendSuccess(() -> SchemMessages.info("No active schematic jobs."), false);
            return Command.SINGLE_SUCCESS;
        }

        for (SchematicPasteJob job : JobManager.all()) {
            double mspt = job.level.getServer().getAverageTickTimeNanos() / 1_000_000.0D;
            double configuredBlocksPerSecond = FrameShiftConfig.maxBlocksPerTick.get() * throttleFactor(mspt) * 20.0D;
            long etaSeconds = job.etaSeconds(System.nanoTime(), configuredBlocksPerSecond);
            boolean clearing = job.isClearingPhase();
            int phaseDone = clearing ? job.clearCompletedOperations() : job.displayCompletedBlocks();
            int phaseTotal = clearing ? job.clearOperationsTotal : job.displayTotalBlocks;
            String phasePct = phaseTotal > 0
                ? String.format(Locale.ROOT, "%.2f", Math.min(100.0D, phaseDone * 100.0D / phaseTotal))
                : "0.00";
            String line = "Job " + shortJobId(job)
                + " | " + job.schematicName
                + " | state=" + job.state
                + " | phase=" + (clearing ? "clearing" : "placing")
                + " | pct=" + phasePct + "%"
                + " | queued=" + job.placementQueue.size()
                + " | blockEntities=" + job.blockEntityQueue.size()
                + " | blocks=" + job.displayCompletedBlocks() + "/" + job.displayTotalBlocks
                + " | clear=" + job.clearCompletedOperations() + "/" + job.clearOperationsTotal
                + " | ops=" + job.blocksAttempted + "/" + job.expectedTotalBlocks
                + " | changed=" + job.blocksPlaced
                + " | unchanged=" + job.blocksUnchanged
                + " | failed=" + job.blocksFailed
                + " | eta=" + formatEta(etaSeconds)
                + " | remaining=" + job.displayRemainingBlocks();
            source.sendSuccess(() -> SchemMessages.info(line), false);
            if (job.failureReason != null && !job.failureReason.isBlank()) {
                source.sendSuccess(() -> SchemMessages.warning("Failure: " + job.failureReason), false);
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int executeCancel(CommandSourceStack source, String jobToken) {
        SchematicPasteJob job = findJobByToken(jobToken);
        if (job == null) {
            source.sendFailure(SchemMessages.error("Job not found: ", jobToken));
            return 0;
        }
        if (job.state == SchematicPasteJob.State.CANCELLED
            || job.state == SchematicPasteJob.State.DONE
            || job.state == SchematicPasteJob.State.FAILED) {
            source.sendFailure(SchemMessages.error(
                "Job is not active: ",
                shortJobId(job) + " is already " + job.state.toString().toLowerCase(Locale.ROOT) + "."
            ));
            return 0;
        }

        JobManager.cancel(job.jobId);
        source.sendSuccess(() -> SchemMessages.warning(
            "Cancelled job " + shortJobId(job) + " (" + job.schematicName + "). Rollback is not implemented yet."
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    private static String formatEta(long etaSeconds) {
        if (etaSeconds < 0L) {
            return "estimating";
        }
        long hours = etaSeconds / 3600L;
        long minutes = (etaSeconds % 3600L) / 60L;
        long seconds = etaSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
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

    @Nullable
    private static SchematicPasteJob findJobByToken(String token) {
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        for (SchematicPasteJob job : JobManager.all()) {
            if (shortJobId(job).equals(normalized) || job.jobId.toString().equalsIgnoreCase(token)) {
                return job;
            }
        }
        return null;
    }

    private static String shortJobId(SchematicPasteJob job) {
        return job.jobId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static void reloadConfig(ModConfig config) throws Exception {
        Class<?> trackerClass = Class.forName("net.neoforged.fml.config.ConfigTracker");
        Method loadConfig = trackerClass.getDeclaredMethod("loadConfig", ModConfig.class, Path.class, Function.class);
        loadConfig.setAccessible(true);
        loadConfig.invoke(null, config, config.getFullPath(), (Function<ModConfig, ModConfigEvent>) ModConfigEvent.Reloading::new);
    }

    private static CompletableFuture<Suggestions> suggestSchematicNames(
        CommandSourceStack source,
        SchematicLoader loader,
        SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(loader.suggestNames(source.getServer().getServerDirectory()), builder);
    }

    private static CompletableFuture<Suggestions> suggestJobIds(SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
            JobManager.all().stream().map(SchemCommand::shortJobId).toList(),
            builder
        );
    }

    private static UUID sessionKey(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? player.getUUID() : CONSOLE_SESSION_KEY;
    }

    private record LoadedSchematicSession(String name, Path file, SchematicMetadata metadata) {
    }
}
