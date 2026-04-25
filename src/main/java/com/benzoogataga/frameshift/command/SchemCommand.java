package com.benzoogataga.frameshift.command;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.chunk.ChunkHelper;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.job.JobManager;
import com.benzoogataga.frameshift.job.JobPersistence;
import com.benzoogataga.frameshift.job.RollbackStore;
import com.benzoogataga.frameshift.job.SchematicPasteJob;
import com.benzoogataga.frameshift.schematic.SchematicListResult;
import com.benzoogataga.frameshift.schematic.SchematicLoader;
import com.benzoogataga.frameshift.schematic.SchematicMetadata;
import com.benzoogataga.frameshift.schematic.SchematicReadOptions;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
                .then(Commands.literal("plan")
                    .executes(context -> executePlanLoaded(context.getSource(), null, false))
                    .then(planModeLiteral("exact", false))
                    .then(planModeLiteral("fast", true))
                    .then(Commands.literal("no-clear")
                        .executes(context -> executePlanLoaded(context.getSource(), null, true)))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> executePlanLoaded(
                            context.getSource(),
                            BlockPosArgument.getLoadedBlockPos(context, "pos"),
                            false
                        ))
                        .then(Commands.literal("no-clear")
                            .executes(context -> executePlanLoaded(
                                context.getSource(),
                                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                true
                            )))))
                .then(Commands.literal("paste")
                    .executes(context -> executePasteLoaded(context.getSource(), loader, null, false, false))
                    .then(pasteModeLiteral(loader, "exact", false))
                    .then(pasteModeLiteral(loader, "fast", true))
                    .then(Commands.literal("freeze-gravity")
                        .executes(context -> executePasteLoaded(context.getSource(), loader, null, false, false, true))
                        .then(Commands.literal("debug")
                            .executes(context -> executePasteLoaded(context.getSource(), loader, null, true, false, true)))
                        .then(Commands.literal("no-clear")
                            .executes(context -> executePasteLoaded(context.getSource(), loader, null, false, true, true))
                            .then(Commands.literal("debug")
                                .executes(context -> executePasteLoaded(context.getSource(), loader, null, true, true, true)))))
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
                        .then(Commands.literal("freeze-gravity")
                            .executes(context -> executePasteLoaded(
                                context.getSource(),
                                loader,
                                BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                false,
                                false,
                                true
                            ))
                            .then(Commands.literal("debug")
                                .executes(context -> executePasteLoaded(
                                    context.getSource(),
                                    loader,
                                    BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                    true,
                                    false,
                                    true
                                )))
                            .then(Commands.literal("no-clear")
                                .executes(context -> executePasteLoaded(
                                    context.getSource(),
                                    loader,
                                    BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                    false,
                                    true,
                                    true
                                ))
                                .then(Commands.literal("debug")
                                    .executes(context -> executePasteLoaded(
                                        context.getSource(),
                                        loader,
                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                        true,
                                        true,
                                        true
                                    )))))
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
                    .executes(context -> executeStatus(context.getSource()))
                    .then(Commands.argument("jobId", StringArgumentType.word())
                        .suggests((context, builder) -> suggestJobIds(builder))
                        .executes(context -> executeStatusJob(
                            context.getSource(),
                            StringArgumentType.getString(context, "jobId")
                        ))))
                .then(Commands.literal("cancel")
                    .then(Commands.argument("jobId", StringArgumentType.word())
                        .suggests((context, builder) -> suggestJobIds(builder))
                        .executes(context -> executeCancel(
                            context.getSource(),
                            StringArgumentType.getString(context, "jobId")
                        ))))
                .then(Commands.literal("pause")
                    .then(Commands.argument("jobId", StringArgumentType.word())
                        .suggests((context, builder) -> suggestRunningJobIds(builder))
                        .executes(context -> executePause(
                            context.getSource(),
                            StringArgumentType.getString(context, "jobId")
                        ))))
                .then(Commands.literal("resume")
                    .then(Commands.argument("jobId", StringArgumentType.word())
                        .suggests((context, builder) -> suggestPausedJobIds(builder))
                        .executes(context -> executeResume(
                            context.getSource(),
                            StringArgumentType.getString(context, "jobId")
                        ))))
                .then(Commands.literal("cleanup")
                    .executes(context -> executeCleanup(context.getSource(), false))
                    .then(Commands.literal("apply")
                        .executes(context -> executeCleanup(context.getSource(), true))))
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
        return executePasteLoaded(source, loader, explicitPos, debugWarnings, skipClear, false);
    }

    private static int executePasteLoaded(
        CommandSourceStack source,
        SchematicLoader loader,
        @Nullable net.minecraft.core.BlockPos explicitPos,
        boolean debugWarnings,
        boolean skipClear,
        boolean freezeGravity
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
        job.freezeGravity = freezeGravity;
        job.schematicPath = loaded.file;
        seedJobTotalsFromMetadata(job, loaded.metadata);
        if (!RollbackStore.initializeStorage(job, server.getServerDirectory())) {
            source.sendFailure(SchemMessages.error("Could not prepare rollback storage. ", job.failureReason != null ? job.failureReason : "Unknown error."));
            return 0;
        }
        if (!JobManager.submit(job)) {
            source.sendFailure(SchemMessages.error("Could not queue paste. ", "The max concurrent job limit has been reached."));
            return 0;
        }

        source.sendSuccess(() -> SchemMessages.info("Streaming " + loaded.name + "..."), false);
        if (skipClear) {
            source.sendSuccess(() -> SchemMessages.warning(
                "Fast mode is faster, but not safe: old world blocks, air gaps, and unsupported/falling blocks may remain inside the pasted area."
            ), false);
        }
        if (freezeGravity) {
            source.sendSuccess(() -> SchemMessages.warning(
                "Freeze-gravity mode adds hidden support under unsupported falling blocks to preserve the build shape."
            ), false);
        }

        loader.streamPasteIntoJobAsync(source.getLevel(), loaded.file, new SchematicReadOptions(true, true, true), job)
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

    private static int executePlanLoaded(
        CommandSourceStack source,
        @Nullable BlockPos explicitPos,
        boolean skipClear
    ) {
        LoadedSchematicSession loaded = LOADED_SCHEMATICS.get(sessionKey(source));
        if (loaded == null) {
            source.sendFailure(SchemMessages.error("No schematic is loaded. ", "Run /schem load <name> first."));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (explicitPos == null && player == null) {
            source.sendFailure(SchemMessages.error("Missing plan position. ", "Console use requires explicit coordinates."));
            return 0;
        }

        BlockPos origin = explicitPos != null ? explicitPos : player.blockPosition();
        sendPlanResult(source, buildPlanPreview(loaded.metadata, origin, skipClear));
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> planModeLiteral(String name, boolean skipClear) {
        return Commands.literal(name)
            .executes(context -> executePlanLoaded(context.getSource(), null, skipClear))
            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> executePlanLoaded(
                    context.getSource(),
                    BlockPosArgument.getLoadedBlockPos(context, "pos"),
                    skipClear
                )));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pasteModeLiteral(SchematicLoader loader, String name, boolean skipClear) {
        return Commands.literal(name)
            .executes(context -> executePasteLoaded(context.getSource(), loader, null, false, skipClear))
            .then(Commands.literal("freeze-gravity")
                .executes(context -> executePasteLoaded(context.getSource(), loader, null, false, skipClear, true))
                .then(Commands.literal("debug")
                    .executes(context -> executePasteLoaded(context.getSource(), loader, null, true, skipClear, true))))
            .then(Commands.literal("debug")
                .executes(context -> executePasteLoaded(context.getSource(), loader, null, true, skipClear)))
            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> executePasteLoaded(
                    context.getSource(),
                    loader,
                    BlockPosArgument.getLoadedBlockPos(context, "pos"),
                    false,
                    skipClear
                ))
                .then(Commands.literal("freeze-gravity")
                    .executes(context -> executePasteLoaded(
                        context.getSource(),
                        loader,
                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                        false,
                        skipClear,
                        true
                    ))
                    .then(Commands.literal("debug")
                        .executes(context -> executePasteLoaded(
                            context.getSource(),
                            loader,
                            BlockPosArgument.getLoadedBlockPos(context, "pos"),
                            true,
                            skipClear,
                            true
                        ))))
                .then(Commands.literal("debug")
                    .executes(context -> executePasteLoaded(
                        context.getSource(),
                        loader,
                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                        true,
                        skipClear
                    ))));
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

    private static void sendPlanResult(CommandSourceStack source, PlanPreview plan) {
        source.sendSuccess(() -> SchemMessages.field("Plan", plan.metadata.name, ChatFormatting.AQUA), false);
        source.sendSuccess(() -> SchemMessages.field(
            "Origin",
            plan.origin.getX() + ", " + plan.origin.getY() + ", " + plan.origin.getZ(),
            ChatFormatting.WHITE
        ), false);
        source.sendSuccess(() -> SchemMessages.field(
            "Bounds",
            plan.min.getX() + ", " + plan.min.getY() + ", " + plan.min.getZ()
                + " -> "
                + plan.max.getX() + ", " + plan.max.getY() + ", " + plan.max.getZ(),
            ChatFormatting.WHITE
        ), false);
        source.sendSuccess(() -> SchemMessages.field(
            "Chunks",
            plan.chunkSpanX + " x " + plan.chunkSpanZ + " (" + plan.chunkCount + " total)  radius " + plan.chunkRadius,
            plan.exceedsChunkRadiusLimit ? ChatFormatting.YELLOW : ChatFormatting.WHITE
        ), false);
        source.sendSuccess(() -> SchemMessages.field(
            "Paste mode",
            pasteModeName(plan.skipClear),
            plan.skipClear ? ChatFormatting.YELLOW : ChatFormatting.WHITE
        ), false);
        source.sendSuccess(() -> SchemMessages.field("Volume", formatCountLong(plan.volume), ChatFormatting.WHITE), false);
        source.sendSuccess(() -> SchemMessages.field(
            "Place ops",
            plan.placeOperations >= 0L ? formatCountLong(plan.placeOperations) : "unknown",
            plan.placeOperations >= 0L ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY
        ), false);
        source.sendSuccess(() -> SchemMessages.field(
            "Clear ops",
            formatCountLong(plan.clearOperations),
            plan.skipClear ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE
        ), false);
        source.sendSuccess(() -> SchemMessages.field("Estimated work", formatCountLong(plan.totalOperations), ChatFormatting.WHITE), false);
        source.sendSuccess(() -> SchemMessages.field(
            "ETA",
            formatEta(plan.etaSecondsFullSpeed) + " at " + formatCountLong(plan.fullSpeedBlocksPerSecond) + "/s",
            ChatFormatting.WHITE
        ), false);

        if (plan.metadata.blockEntityCount > 0 || plan.metadata.entityCount > 0) {
            source.sendSuccess(() -> SchemMessages.field(
                "Extra data",
                plan.metadata.blockEntityCount + " block entities  " + plan.metadata.entityCount + " entities",
                ChatFormatting.WHITE
            ), false);
        }
        if (plan.exceedsChunkRadiusLimit) {
            source.sendSuccess(() -> SchemMessages.warning(
                "Plan exceeds configured chunkRadiusLimit of " + FrameShiftConfig.chunkRadiusLimit.get() + " chunks."
            ), false);
        }
    }

    private static int executeStatus(CommandSourceStack source) {
        if (JobManager.all().isEmpty()) {
            source.sendSuccess(() -> SchemMessages.info("No active schematic jobs."), false);
            return Command.SINGLE_SUCCESS;
        }
        for (SchematicPasteJob job : JobManager.all()) {
            Component line = buildCompactStatusLine(job);
            source.sendSuccess(() -> line, false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeStatusJob(CommandSourceStack source, String jobToken) {
        SchematicPasteJob job = findJobByToken(jobToken);
        if (job == null) {
            source.sendFailure(SchemMessages.error("Job not found: ", jobToken));
            return 0;
        }
        sendVerboseStatus(source, job);
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
        if (job.state == SchematicPasteJob.State.ROLLING_BACK || (job.state == SchematicPasteJob.State.PAUSED && job.rollbackMode)) {
            source.sendFailure(SchemMessages.error(
                "Job is already rolling back: ",
                shortJobId(job) + " (" + job.schematicName + ")."
            ));
            return 0;
        }

        JobManager.cancel(job.jobId);
        if (job.state == SchematicPasteJob.State.CANCELLED) {
            JobPersistence.delete(job.jobId, source.getServer().getServerDirectory());
            source.sendSuccess(() -> SchemMessages.warning(
                "Cancelled job " + shortJobId(job) + " (" + job.schematicName + "). No world changes needed rollback."
            ), true);
        } else {
            source.sendSuccess(() -> SchemMessages.warning(
                "Rollback started for job " + shortJobId(job) + " (" + job.schematicName + ")."
            ), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executePause(CommandSourceStack source, String jobToken) {
        SchematicPasteJob job = findJobByToken(jobToken);
        if (job == null) {
            source.sendFailure(SchemMessages.error("Job not found: ", jobToken));
            return 0;
        }
        if (job.state != SchematicPasteJob.State.RUNNING && job.state != SchematicPasteJob.State.ROLLING_BACK) {
            source.sendFailure(SchemMessages.error(
                "Job is not active: ",
                shortJobId(job) + " is " + job.state.toString().toLowerCase(Locale.ROOT) + "."
            ));
            return 0;
        }
        JobManager.pause(job.jobId);
        source.sendSuccess(() -> SchemMessages.warning(
            "Paused " + (job.rollbackMode ? "rollback" : "job") + " " + shortJobId(job) + " (" + job.schematicName + ")."
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeResume(CommandSourceStack source, String jobToken) {
        SchematicPasteJob job = findJobByToken(jobToken);
        if (job == null) {
            source.sendFailure(SchemMessages.error("Job not found: ", jobToken));
            return 0;
        }
        if (job.state != SchematicPasteJob.State.PAUSED) {
            source.sendFailure(SchemMessages.error(
                "Job is not paused: ",
                shortJobId(job) + " is " + job.state.toString().toLowerCase(Locale.ROOT) + "."
            ));
            return 0;
        }
        JobManager.resume(job.jobId);
        source.sendSuccess(() -> SchemMessages.success(
            "Resumed " + (job.rollbackMode ? "rollback" : "job") + " " + shortJobId(job) + " (" + job.schematicName + ")."
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeCleanup(CommandSourceStack source, boolean apply) {
        java.util.Set<UUID> activeJobIds = JobManager.all().stream().map(job -> job.jobId).collect(java.util.stream.Collectors.toSet());
        java.util.List<JobPersistence.CleanupCandidate> candidates =
            JobPersistence.cleanupCandidates(source.getServer().getServerDirectory(), activeJobIds);

        if (candidates.isEmpty()) {
            source.sendSuccess(() -> SchemMessages.info("No persisted job directories need cleanup."), false);
            return Command.SINGLE_SUCCESS;
        }

        if (!apply) {
            source.sendSuccess(() -> SchemMessages.warning(
                "Cleanup preview: " + candidates.size() + " persisted job director" + (candidates.size() == 1 ? "y" : "ies") + " can be removed."
            ), false);
            for (JobPersistence.CleanupCandidate candidate : candidates) {
                source.sendSuccess(() -> SchemMessages.prefix()
                    .append(Component.literal(candidate.displayId() + "  ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(candidate.reason()).withStyle(ChatFormatting.WHITE)), false);
            }
            source.sendSuccess(() -> SchemMessages.info("Run /schem cleanup apply to delete them."), false);
            return Command.SINGLE_SUCCESS;
        }

        for (JobPersistence.CleanupCandidate candidate : candidates) {
            JobPersistence.deleteDirectory(candidate.directory());
        }
        source.sendSuccess(() -> SchemMessages.success(
            "Removed " + candidates.size() + " persisted job director" + (candidates.size() == 1 ? "y" : "ies") + "."
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static Component buildCompactStatusLine(SchematicPasteJob job) {
        boolean rollback = job.rollbackMode;
        boolean clearing = !rollback && job.isClearingPhase();
        int done = rollback ? job.rollbackCompletedOperations() : (clearing ? job.clearCompletedOperations() : job.displayCompletedBlocks());
        int total = rollback
            ? Math.max(job.rollbackQueued, job.rollbackCompletedOperations())
            : (clearing
                ? job.clearOperationsTotal
                : job.knownPlaceTotal());
        boolean totalKnown = rollback || clearing || job.hasKnownPlaceTotal();
        double pct = totalKnown && total > 0 ? Math.min(100.0, done * 100.0 / total) : 0.0;
        double mspt = job.level.getServer().getAverageTickTimeNanos() / 1_000_000.0;
        double bps = FrameShiftConfig.maxBlocksPerTick.get() * FrameShiftConfig.throttleFactor(mspt) * 20.0;
        long eta = totalKnown ? job.etaSeconds(System.nanoTime(), bps) : -1L;
        String bar = progressBar(totalKnown ? (int) pct : 0, 8);
        ChatFormatting stateColor = stateColor(job.state);
        ChatFormatting progressColor = job.state == SchematicPasteJob.State.PAUSED
            ? ChatFormatting.YELLOW
            : (rollback ? ChatFormatting.GOLD : ChatFormatting.GREEN);
        return SchemMessages.prefix()
            .append(Component.literal(shortJobId(job) + "  ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(job.schematicName + "  ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(job.state.toString().toLowerCase(Locale.ROOT)).withStyle(stateColor))
            .append(Component.literal("  " + (rollback ? "rollback" : (clearing ? "clearing" : "placing")) + " ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(totalKnown ? String.format(Locale.ROOT, "%.1f", pct) + "% " : "streaming ").withStyle(progressColor))
            .append(Component.literal("[" + bar + "]  ").withStyle(progressColor))
            .append(Component.literal(formatCount(done) + " / " + (totalKnown ? formatCount(total) : "streaming")).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  ETA " + formatEta(eta)).withStyle(ChatFormatting.GRAY));
    }

    private static void sendVerboseStatus(CommandSourceStack source, SchematicPasteJob job) {
        boolean rollback = job.rollbackMode;
        boolean clearing = !rollback && job.isClearingPhase();
        int done = rollback ? job.rollbackCompletedOperations() : (clearing ? job.clearCompletedOperations() : job.displayCompletedBlocks());
        int total = rollback
            ? Math.max(job.rollbackQueued, job.rollbackCompletedOperations())
            : (clearing
                ? job.clearOperationsTotal
                : job.knownPlaceTotal());
        boolean totalKnown = rollback || clearing || job.hasKnownPlaceTotal();
        double pct = totalKnown && total > 0 ? Math.min(100.0, done * 100.0 / total) : 0.0;
        double mspt = job.level.getServer().getAverageTickTimeNanos() / 1_000_000.0;
        double bps = FrameShiftConfig.maxBlocksPerTick.get() * FrameShiftConfig.throttleFactor(mspt) * 20.0;
        long eta = totalKnown ? job.etaSeconds(System.nanoTime(), bps) : -1L;
        String bar = progressBar(totalKnown ? (int) pct : 0, 10);
        String pctStr = totalKnown ? String.format(Locale.ROOT, "%.2f", pct) : "streaming";
        String doneStr = formatCount(done);
        String totalStr = totalKnown ? formatCount(total) : "streaming";
        String etaStr = formatEta(eta);
        ChatFormatting stateColor = stateColor(job.state);
        ChatFormatting progressColor = job.state == SchematicPasteJob.State.PAUSED
            ? ChatFormatting.YELLOW
            : (rollback ? ChatFormatting.GOLD : ChatFormatting.GREEN);
        net.minecraft.core.BlockPos origin = job.origin;
        boolean clearDone = !job.isClearingPhase();

        source.sendSuccess(() -> SchemMessages.prefix()
            .append(Component.literal("Job ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(shortJobId(job) + "  ").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(job.schematicName + "  ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(job.state.toString().toLowerCase(Locale.ROOT)).withStyle(stateColor)),
            false);

        source.sendSuccess(() -> SchemMessages.prefix()
            .append(Component.literal("Phase: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal((rollback ? "rollback" : (clearing ? "clearing" : "placing")) + "  ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(pctStr + "% ").withStyle(progressColor))
            .append(Component.literal("[" + bar + "]  ").withStyle(progressColor))
            .append(Component.literal(doneStr + " / " + totalStr).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  ETA: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(etaStr).withStyle(ChatFormatting.WHITE)),
            false);

        source.sendSuccess(() -> SchemMessages.prefix()
            .append(Component.literal("Origin: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(origin.getX() + ", " + origin.getY() + ", " + origin.getZ() + "  ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("Remaining: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(job.hasKnownPlaceTotal() ? formatCount(job.displayRemainingBlocks()) : "streaming").withStyle(ChatFormatting.WHITE)),
            false);

        if (rollback) {
            source.sendSuccess(() -> SchemMessages.prefix()
                .append(Component.literal("Rollback: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCount(job.rollbackApplied) + " restored  ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("Skipped: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCount(job.rollbackSkippedConflicts) + "  ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("Failed: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCount(job.rollbackFailed)).withStyle(job.rollbackFailed > 0 ? ChatFormatting.RED : ChatFormatting.WHITE)),
                false);
        } else {
            source.sendSuccess(() -> SchemMessages.prefix()
                .append(Component.literal("Placed: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCount(job.blocksPlaced) + "  ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("Unchanged: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCount(job.blocksUnchanged) + "  ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("Failed: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCount(job.blocksFailed) + "  ").withStyle(job.blocksFailed > 0 ? ChatFormatting.RED : ChatFormatting.WHITE))
                .append(Component.literal("Block entities: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(job.blockEntitiesApplied) + "  ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("Entities: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(job.entitiesApplied)).withStyle(ChatFormatting.WHITE)),
                false);

            if (job.clearOperationsTotal > 0) {
                source.sendSuccess(() -> SchemMessages.prefix()
                    .append(Component.literal("Clear ops: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(formatCount(job.clearCompletedOperations()) + " / " + formatCount(job.clearOperationsTotal)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(clearDone ? "  (complete)" : "").withStyle(ChatFormatting.GREEN)),
                    false);
            }
        }

        source.sendSuccess(() -> SchemMessages.prefix()
            .append(Component.literal("Queue: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(
                rollback
                    ? formatCount(job.rollbackQueue.size()) + " rollback entries"
                    : formatCount(job.placementQueue.size()) + " pending  "
                        + job.blockEntityQueue.size() + " block entities  "
                        + job.connectionFinalizeQueue.size() + " finalize  "
                        + job.entityQueue.size() + " entities"
            ).withStyle(ChatFormatting.WHITE)),
            false);

        if (job.failureReason != null && !job.failureReason.isBlank()) {
            source.sendSuccess(() -> SchemMessages.warning("Failure: " + job.failureReason), false);
        }
    }

    private static ChatFormatting stateColor(SchematicPasteJob.State state) {
        return switch (state) {
            case RUNNING -> ChatFormatting.GREEN;
            case ROLLING_BACK -> ChatFormatting.GOLD;
            case PAUSED -> ChatFormatting.YELLOW;
            case CANCELLED, FAILED -> ChatFormatting.RED;
            case DONE -> ChatFormatting.DARK_GRAY;
        };
    }

    private static String progressBar(int pct, int width) {
        int filled = pct * width / 100;
        return "#".repeat(filled) + "-".repeat(width - filled);
    }

    private static String formatCount(int count) {
        if (count < 1_000) return String.valueOf(count);
        if (count < 1_000_000) return String.format(Locale.ROOT, "%.1fK", count / 1_000.0);
        return String.format(Locale.ROOT, "%.2fM", count / 1_000_000.0);
    }

    private static String formatCountLong(long count) {
        if (count < 1_000L) return Long.toString(count);
        if (count < 1_000_000L) return String.format(Locale.ROOT, "%.1fK", count / 1_000.0);
        if (count < 1_000_000_000L) return String.format(Locale.ROOT, "%.2fM", count / 1_000_000.0);
        return String.format(Locale.ROOT, "%.2fB", count / 1_000_000_000.0);
    }

    private static String pasteModeName(boolean skipClear) {
        return skipClear ? "fast" : "exact";
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

    private static CompletableFuture<Suggestions> suggestRunningJobIds(SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
            JobManager.all().stream()
                .filter(j -> j.state == SchematicPasteJob.State.RUNNING || j.state == SchematicPasteJob.State.ROLLING_BACK)
                .map(SchemCommand::shortJobId)
                .toList(),
            builder
        );
    }

    private static CompletableFuture<Suggestions> suggestPausedJobIds(SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
            JobManager.all().stream()
                .filter(j -> j.state == SchematicPasteJob.State.PAUSED)
                .map(SchemCommand::shortJobId)
                .toList(),
            builder
        );
    }

    // Reattaches a reconnecting player to any active jobs they started.
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        for (SchematicPasteJob job : JobManager.all()) {
            if (job.executorUuid == null || !job.executorUuid.equals(player.getUUID())) {
                continue;
            }
            if (job.state != SchematicPasteJob.State.RUNNING
                && job.state != SchematicPasteJob.State.PAUSED
                && job.state != SchematicPasteJob.State.ROLLING_BACK) {
                continue;
            }

            player.sendSystemMessage(SchemMessages.prefix()
                .append(Component.literal("Reattached to job ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(shortJobId(job)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" (" + job.schematicName + ") ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(job.rollbackMode ? "rollback" : (job.isClearingPhase() ? "clearing" : "placing"))
                    .withStyle(ChatFormatting.YELLOW)));
            player.sendSystemMessage(buildCompactStatusLine(job));
        }
    }

    // Clears the loaded schematic session when a player disconnects to avoid a memory leak.
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LOADED_SCHEMATICS.remove(player.getUUID());
        }
    }

    private static UUID sessionKey(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? player.getUUID() : CONSOLE_SESSION_KEY;
    }

    private static void seedJobTotalsFromMetadata(SchematicPasteJob job, SchematicMetadata metadata) {
        long clearOps = job.skipClear ? 0L : metadata.volume();
        job.clearOperationsTotal = (int) Math.min(clearOps, Integer.MAX_VALUE);
        if (metadata.nonAirBlocks >= 0L) {
            job.displayTotalBlocks = (int) Math.min(metadata.nonAirBlocks, Integer.MAX_VALUE);
            job.expectedTotalBlocks = (int) Math.min(clearOps + metadata.nonAirBlocks, Integer.MAX_VALUE);
        } else {
            job.displayTotalBlocks = -1;
            job.expectedTotalBlocks = -1;
        }
    }

    private static PlanPreview buildPlanPreview(SchematicMetadata metadata, BlockPos origin, boolean skipClear) {
        BlockPos min = origin.offset(metadata.offsetX, metadata.offsetY, metadata.offsetZ);
        BlockPos max = min.offset(metadata.sizeX - 1, metadata.sizeY - 1, metadata.sizeZ - 1);
        int minChunkX = ChunkHelper.chunkX(min);
        int maxChunkX = ChunkHelper.chunkX(max);
        int minChunkZ = ChunkHelper.chunkZ(min);
        int maxChunkZ = ChunkHelper.chunkZ(max);
        int chunkSpanX = maxChunkX - minChunkX + 1;
        int chunkSpanZ = maxChunkZ - minChunkZ + 1;
        int centerChunkX = ChunkHelper.chunkX(origin);
        int centerChunkZ = ChunkHelper.chunkZ(origin);
        int chunkRadius = Math.max(
            Math.max(Math.abs(minChunkX - centerChunkX), Math.abs(maxChunkX - centerChunkX)),
            Math.max(Math.abs(minChunkZ - centerChunkZ), Math.abs(maxChunkZ - centerChunkZ))
        );
        long clearOperations = skipClear ? 0L : metadata.volume();
        long placeOperations = metadata.nonAirBlocks;
        long totalOperations = clearOperations + Math.max(0L, placeOperations);
        long fullSpeedBlocksPerSecond = (long) FrameShiftConfig.maxBlocksPerTick.get() * 20L;
        long etaSeconds = fullSpeedBlocksPerSecond > 0L ? (long) Math.ceil(totalOperations / (double) fullSpeedBlocksPerSecond) : -1L;
        return new PlanPreview(
            metadata,
            origin,
            min,
            max,
            skipClear,
            metadata.volume(),
            placeOperations,
            clearOperations,
            totalOperations,
            chunkSpanX,
            chunkSpanZ,
            (long) chunkSpanX * chunkSpanZ,
            chunkRadius,
            chunkRadius > FrameShiftConfig.chunkRadiusLimit.get(),
            fullSpeedBlocksPerSecond,
            etaSeconds
        );
    }

    private record LoadedSchematicSession(String name, Path file, SchematicMetadata metadata) {
    }

    private record PlanPreview(
        SchematicMetadata metadata,
        BlockPos origin,
        BlockPos min,
        BlockPos max,
        boolean skipClear,
        long volume,
        long placeOperations,
        long clearOperations,
        long totalOperations,
        int chunkSpanX,
        int chunkSpanZ,
        long chunkCount,
        int chunkRadius,
        boolean exceedsChunkRadiusLimit,
        long fullSpeedBlocksPerSecond,
        long etaSecondsFullSpeed
    ) {
    }
}
