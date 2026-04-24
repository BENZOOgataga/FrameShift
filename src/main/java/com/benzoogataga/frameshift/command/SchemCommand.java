package com.benzoogataga.frameshift.command;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.schematic.SchematicListResult;
import com.benzoogataga.frameshift.schematic.SchematicLoader;
import com.benzoogataga.frameshift.schematic.SchematicMetadata;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

// Registers read-only schematic inspection commands.
public class SchemCommand {

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
                .then(Commands.literal("reload")
                    .executes(context -> executeReload(context.getSource(), loader)))
        );
    }

    // Asks the loader for one page of metadata and sends the result back on the server thread.
    private static int executeList(CommandSourceStack source, SchematicLoader loader, @Nullable String cursor) {
        MinecraftServer server = source.getServer();
        loader.listAsync(server.getServerDirectory(), FrameShiftConfig.maxListResults.get(), cursor)
            .thenAcceptAsync(result -> sendListResult(source, result), server::execute)
            .exceptionally(error -> {
                server.execute(() -> source.sendFailure(
                    Component.literal("Error listing schematics: ")
                        .withStyle(ChatFormatting.RED)
                        .append(Component.literal(unwrap(error).getMessage()).withStyle(ChatFormatting.GRAY))
                ));
                return null;
            });
        return Command.SINGLE_SUCCESS;
    }

    // Resolves a schematic by configured search order and shows its metadata.
    private static int executeInfo(CommandSourceStack source, SchematicLoader loader, String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            source.sendFailure(
                Component.literal("Invalid schematic name. ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal("Path separators are not allowed.").withStyle(ChatFormatting.GRAY))
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        return loader.findByName(server.getServerDirectory(), name)
            .map(path -> {
                loader.readMetadataAsync(path)
                    .thenAcceptAsync(metadata -> sendInfoResult(source, metadata), server::execute)
                    .exceptionally(error -> {
                        server.execute(() -> source.sendFailure(
                            Component.literal("Error reading schematic: ")
                                .withStyle(ChatFormatting.RED)
                                .append(Component.literal(unwrap(error).getMessage()).withStyle(ChatFormatting.GRAY))
                        ));
                        return null;
                    });
                return Command.SINGLE_SUCCESS;
            })
            .orElseGet(() -> {
                source.sendFailure(
                    Component.literal("Schematic not found: ")
                        .withStyle(ChatFormatting.RED)
                        .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                );
                return 0;
            });
    }

    // Reloads this mod's registered server config without invoking vanilla /reload.
    private static int executeReload(CommandSourceStack source, SchematicLoader loader) {
        try {
            ModConfig config = ModConfigs.getModConfigs(FrameShift.MOD_ID).stream()
                .filter(modConfig -> modConfig.getType() == ModConfig.Type.SERVER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("FrameShift server config is not registered"));

            reloadConfig(config);
            loader.reload();

            source.sendSuccess(() -> Component.literal("FrameShift config reloaded.").withStyle(ChatFormatting.GREEN), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            Throwable cause = unwrap(exception);
            source.sendFailure(
                Component.literal("Error reloading FrameShift config: ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal(cause.getMessage()).withStyle(ChatFormatting.GRAY))
            );
            return 0;
        }
    }

    private static void sendListResult(CommandSourceStack source, SchematicListResult result) {
        if (result.entries.isEmpty()) {
            MutableComponent message = Component.literal("No schematics found.").withStyle(ChatFormatting.YELLOW);
            if (result.failed > 0 || result.skipped > 0) {
                message.append(Component.literal("  "))
                    .append(Component.literal("Skipped: ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(Integer.toString(result.skipped)).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("Failed: ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(Integer.toString(result.failed)).withStyle(ChatFormatting.RED));
            }
            if (result.failed > 0) {
                message.append(Component.literal("  "))
                    .append(Component.literal("Some files were rejected by safety limits or parse checks.").withStyle(ChatFormatting.RED));
            }
            source.sendSuccess(() -> message, false);
            return;
        }

        for (SchematicMetadata metadata : result.entries) {
            MutableComponent line = Component.literal(metadata.name).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(metadata.sizeX + "x" + metadata.sizeY + "x" + metadata.sizeZ).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(formatFileSize(metadata.fileSize)).withStyle(ChatFormatting.GOLD));
            source.sendSuccess(() -> line, false);
        }

        if (result.nextCursor != null) {
            source.sendSuccess(() -> Component.literal("More results available. ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("Use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/schem list ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(result.nextCursor).withStyle(ChatFormatting.DARK_AQUA)), false);
        }

        if (result.skipped > 0 || result.failed > 0) {
            source.sendSuccess(() -> Component.literal("Skipped: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(Integer.toString(result.skipped)).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("Failed: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(Integer.toString(result.failed)).withStyle(ChatFormatting.RED)), false);
        }
    }

    private static void sendInfoResult(CommandSourceStack source, SchematicMetadata metadata) {
        source.sendSuccess(() -> label("Name").append(value(metadata.name, ChatFormatting.AQUA)), false);
        source.sendSuccess(() -> label("Format").append(value(metadata.format.toString(), ChatFormatting.LIGHT_PURPLE)), false);
        source.sendSuccess(() -> label("Dimensions").append(value(metadata.sizeX + " x " + metadata.sizeY + " x " + metadata.sizeZ, ChatFormatting.GREEN)), false);
        source.sendSuccess(() -> label("Volume").append(value(Long.toString(metadata.volume()), ChatFormatting.GOLD)), false);
        source.sendSuccess(() -> label("File size").append(value(formatFileSize(metadata.fileSize), ChatFormatting.GOLD)), false);
        source.sendSuccess(() -> label("Data version").append(unknownValue(metadata.dataVersion)), false);
        source.sendSuccess(() -> label("Block entities").append(unknownValue(metadata.blockEntityCount)), false);
        source.sendSuccess(() -> label("Entities").append(unknownValue(metadata.entityCount)), false);
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

    private static String unknownIfMinusOne(int value) {
        return value == -1 ? "unknown" : Integer.toString(value);
    }

    private static MutableComponent label(String label) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.YELLOW);
    }

    private static MutableComponent value(String value, ChatFormatting color) {
        return Component.literal(value).withStyle(color);
    }

    private static MutableComponent unknownValue(int value) {
        if (value == -1) {
            return Component.literal("unknown").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        }
        return Component.literal(Integer.toString(value)).withStyle(ChatFormatting.AQUA);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    // Calls NeoForge's internal config reload path so spec validation and reload events still fire.
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
}
