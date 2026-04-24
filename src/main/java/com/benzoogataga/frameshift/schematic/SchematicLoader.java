package com.benzoogataga.frameshift.schematic;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.job.SchematicPasteJob;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Owns schematic readers and the IO executor used for metadata reads and listing.
public final class SchematicLoader {

    private static final int MAX_VERBOSE_FAILURES = 5;

    private volatile ExecutorService ioExecutor;

    public SchematicLoader() {
    }

    // Stops the background executor during server shutdown.
    public void shutdown() {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
    }

    // Drops runtime state that depends on config values so it can be rebuilt after reload.
    public synchronized void reload() {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
            ioExecutor = null;
        }
    }

    // Finds the first reader that claims the given file path.
    public Optional<SchematicReader> findReader(Path file) {
        SchematicReader spongeReader = createSpongeReader();
        if (spongeReader.supports(file)) {
            return Optional.of(spongeReader);
        }
        return Optional.empty();
    }

    // Reads metadata asynchronously on the dedicated IO executor.
    public CompletableFuture<SchematicMetadata> readMetadataAsync(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            SchematicReader reader = findReader(file)
                .orElseThrow(() -> new CompletionException(new IOException("Unsupported schematic format: " + file.getFileName())));
            try {
                return reader.readMetadata(file);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, executor());
    }

    // Lists schematic metadata from configured directories using an opaque cursor token.
    public CompletableFuture<SchematicListResult> listAsync(Path serverRoot, int limit, @Nullable String cursor) {
        int effectiveLimit = Math.min(limit, FrameShiftConfig.maxListResults.get());
        return CompletableFuture.supplyAsync(() -> listNow(serverRoot, effectiveLimit, cursor), executor());
    }

    // Resolves the first matching schematic name across configured directories.
    public Optional<Path> findByName(Path serverRoot, String name) {
        List<String> schematicDirectories = configuredDirectories();
        for (String directoryName : schematicDirectories) {
            Path directory = serverRoot.resolve(directoryName).normalize();
            if (!Files.isDirectory(directory)) {
                continue;
            }

            try (Stream<Path> stream = Files.list(directory)) {
                Optional<Path> match = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> stemOf(path.getFileName().toString()).equals(name))
                    .filter(path -> findReader(path).isPresent())
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize());
                if (match.isPresent()) {
                    return match;
                }
            } catch (IOException exception) {
                FrameShift.LOGGER.warn("Could not scan schematic directory {}: {}", directory, exception.getMessage());
            }
        }
        return Optional.empty();
    }

    // Returns distinct schematic name suggestions from supported files in configured directories.
    public List<String> suggestNames(Path serverRoot) {
        List<String> suggestions = new ArrayList<>();
        List<String> schematicDirectories = configuredDirectories();

        for (String directoryName : schematicDirectories) {
            Path directory = serverRoot.resolve(directoryName).normalize();
            if (!Files.isDirectory(directory)) {
                continue;
            }

            try (Stream<Path> stream = Files.list(directory)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(path -> findReader(path).isPresent())
                    .map(path -> stemOf(path.getFileName().toString()))
                    .filter(name -> !suggestions.contains(name))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(suggestions::add);
            } catch (IOException exception) {
                FrameShift.LOGGER.warn("Could not gather schematic suggestions from {}: {}", directory, exception.getMessage());
            }
        }

        return suggestions;
    }

    // Parses and resolves a schematic into concrete block placements off the server thread.
    public CompletableFuture<PreparedSchematicPaste> preparePasteAsync(
        ServerLevel level,
        Path file,
        SchematicReadOptions options
    ) {
        return CompletableFuture.supplyAsync(() -> {
            SchematicReader reader = findReader(file)
                .orElseThrow(() -> new CompletionException(new IOException("Unsupported schematic format: " + file.getFileName())));
            try {
                SchematicMetadata metadata = reader.readMetadata(file);
                List<PreparedBlockPlacement> blocks = new ArrayList<>();
                Map<String, BlockState> parsedStates = new HashMap<>();
                Set<String> invalidStateIds = new HashSet<>();
                HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
                int skippedInvalidBlocks = 0;

                try (BlockStream stream = reader.openBlockStream(file, options)) {
                    while (stream.hasNext()) {
                        SchematicBlockEntry entry = stream.next();
                        BlockState state = parsedStates.computeIfAbsent(entry.blockStateId, key -> parseBlockState(blockLookup, key, invalidStateIds));
                        if (state == null) {
                            skippedInvalidBlocks++;
                            continue;
                        }
                        blocks.add(new PreparedBlockPlacement(
                            new BlockPos(entry.x, entry.y, entry.z),
                            state,
                            normalizeBlockEntityTag(entry.blockEntityNbt, entry.x, entry.y, entry.z)
                        ));
                    }
                }

                if (!invalidStateIds.isEmpty()) {
                    FrameShift.LOGGER.warn(
                        "Schematic {} has {} invalid palette states ({} block placements skipped)",
                        file.getFileName(),
                        invalidStateIds.size(),
                        skippedInvalidBlocks
                    );
                }

                // Group work by chunk to reduce repeated chunk switches during placement.
                blocks.sort(
                    Comparator
                        .comparingInt((PreparedBlockPlacement block) -> block.relativePos.getX() >> 4)
                        .thenComparingInt(block -> block.relativePos.getZ() >> 4)
                        .thenComparingInt(block -> block.relativePos.getY())
                        .thenComparingInt(block -> block.relativePos.getX() & 15)
                        .thenComparingInt(block -> block.relativePos.getZ() & 15)
                );

                return new PreparedSchematicPaste(metadata, blocks, skippedInvalidBlocks, List.copyOf(invalidStateIds));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, executor());
    }

    // Streams parsed placements directly into a bounded job queue to keep memory usage stable.
    public CompletableFuture<StreamPasteSummary> streamPasteIntoJobAsync(
        ServerLevel level,
        Path file,
        SchematicReadOptions options,
        SchematicPasteJob job
    ) {
        return CompletableFuture.supplyAsync(() -> {
            SchematicReader reader = findReader(file)
                .orElseThrow(() -> new CompletionException(new IOException("Unsupported schematic format: " + file.getFileName())));

            Set<String> invalidStateIds = new HashSet<>();
            int skippedInvalidBlocks = 0;

            try {
                SchematicMetadata metadata = reader.readMetadata(file);
                Map<String, BlockState> parsedStates = new HashMap<>();
                HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
                int placeableBlocks = 0;

                try (BlockStream countStream = reader.openBlockStream(file, options)) {
                    while (countStream.hasNext()) {
                        if (job.state == SchematicPasteJob.State.CANCELLED || job.state == SchematicPasteJob.State.FAILED) {
                            break;
                        }
                        countStream.next();
                        placeableBlocks++;
                    }
                }

                job.expectedTotalBlocks = Math.toIntExact((job.skipClear ? 0L : metadata.volume()) + placeableBlocks);
                job.displayTotalBlocks = placeableBlocks;
                job.clearOperationsTotal = job.skipClear ? 0 : Math.toIntExact(metadata.volume());

                if (!job.skipClear) {
                    // Clear the full schematic bounds from metadata so every position inside the volume is reset,
                    // including schematic-air space that would otherwise keep old world blocks.
                    enqueueClearVolume(job, metadata);
                } else {
                    job.placePhaseObserved = true;
                    job.placePhaseStartedAtNanos = System.nanoTime();
                }

                try (BlockStream stream = reader.openBlockStream(file, options)) {
                    while (stream.hasNext()) {
                        if (job.state == SchematicPasteJob.State.CANCELLED || job.state == SchematicPasteJob.State.FAILED) {
                            break;
                        }

                        SchematicBlockEntry entry = stream.next();
                        BlockState state = parsedStates.computeIfAbsent(entry.blockStateId, key -> parseBlockState(blockLookup, key, invalidStateIds));
                        if (state == null) {
                            skippedInvalidBlocks++;
                            continue;
                        }

                        BlockPos worldPos = job.origin.offset(entry.x, entry.y, entry.z);
                        SchematicPasteJob.PlacementTask task = new SchematicPasteJob.PlacementTask(
                            worldPos,
                            state,
                            // Block entity x/y/z must be world-space coordinates, not schematic-relative ones.
                            normalizeBlockEntityTag(entry.blockEntityNbt, worldPos.getX(), worldPos.getY(), worldPos.getZ()),
                            0
                        );
                        if (state.getBlock() instanceof FallingBlock) {
                            job.enqueueGravityPlacement(task);
                        } else {
                            job.enqueuePlacement(task);
                        }
                    }
                }

                if (!invalidStateIds.isEmpty()) {
                    FrameShift.LOGGER.warn(
                        "Schematic {} has {} invalid palette states ({} block placements skipped)",
                        file.getFileName(),
                        invalidStateIds.size(),
                        skippedInvalidBlocks
                    );
                }

                return new StreamPasteSummary(metadata, skippedInvalidBlocks, List.copyOf(invalidStateIds));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new IOException("Schematic stream preparation was interrupted", interrupted));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            } finally {
                job.markLoadingComplete();
            }
        }, executor());
    }

    private SchematicListResult listNow(Path serverRoot, int effectiveLimit, @Nullable String cursorToken) {
        Cursor decodedCursor = decodeCursor(cursorToken);
        List<ListEntryCandidate> candidates = new ArrayList<>();
        List<String> schematicDirectories = configuredDirectories();
        int skipped = 0;
        int failed = 0;
        int verboseFailures = 0;

        for (int directoryIndex = 0; directoryIndex < schematicDirectories.size(); directoryIndex++) {
            String directoryName = schematicDirectories.get(directoryIndex);
            Path directory = serverRoot.resolve(directoryName).normalize();
            if (!Files.isDirectory(directory)) {
                continue;
            }

            List<Path> files;
            try (Stream<Path> stream = Files.list(directory)) {
                files = stream
                    .filter(Files::isRegularFile)
                    .sorted(FILE_NAME_COMPARATOR)
                    .collect(Collectors.toList());
            } catch (IOException exception) {
                FrameShift.LOGGER.warn("Could not list schematic directory {}: {}", directory, exception.getMessage());
                continue;
            }

            for (Path file : files) {
                Optional<SchematicReader> reader = findReader(file);
                if (reader.isEmpty()) {
                    skipped++;
                    continue;
                }

                Path normalized = file.toAbsolutePath().normalize();
                ListEntryCandidate candidate = new ListEntryCandidate(directoryIndex, normalized.getFileName().toString(), hashPath(normalized), normalized);
                if (decodedCursor != null && compareCursor(candidate, decodedCursor) <= 0) {
                    continue;
                }

                try {
                    candidates.add(new ListEntryCandidate(candidate, reader.get().readMetadata(normalized)));
                } catch (IOException exception) {
                    failed++;
                    if (verboseFailures < MAX_VERBOSE_FAILURES) {
                        FrameShift.LOGGER.warn("Failed to read schematic metadata from {}: {}", normalized.getFileName(), exception.getMessage());
                        verboseFailures++;
                    }
                }
            }
        }

        if (failed > MAX_VERBOSE_FAILURES) {
            FrameShift.LOGGER.warn("{} more schematic metadata reads failed; check debug logs for details", failed - MAX_VERBOSE_FAILURES);
        }

        candidates.sort(LIST_ENTRY_COMPARATOR);
        boolean hasMore = candidates.size() > effectiveLimit;
        List<ListEntryCandidate> page = hasMore ? candidates.subList(0, effectiveLimit) : candidates;
        List<SchematicMetadata> entries = page.stream().map(candidate -> candidate.metadata).toList();
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ListEntryCandidate lastEntry = page.get(page.size() - 1);
            nextCursor = encodeCursor(lastEntry.directoryIndex, lastEntry.fileName, lastEntry.pathHash);
        }

        return new SchematicListResult(entries, nextCursor, skipped, failed);
    }

    private static final Comparator<Path> FILE_NAME_COMPARATOR = Comparator
        .comparing((Path path) -> path.getFileName().toString().toLowerCase(Locale.ROOT))
        .thenComparing(path -> path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT));

    private static final Comparator<ListEntryCandidate> LIST_ENTRY_COMPARATOR = Comparator
        .comparingInt((ListEntryCandidate entry) -> entry.directoryIndex)
        .thenComparing(entry -> entry.fileName.toLowerCase(Locale.ROOT))
        .thenComparing(entry -> entry.pathHash)
        .thenComparing(entry -> entry.file.toString().toLowerCase(Locale.ROOT));

    private static int compareCursor(ListEntryCandidate candidate, Cursor cursor) {
        int directoryCompare = Integer.compare(candidate.directoryIndex, cursor.directoryIndex);
        if (directoryCompare != 0) {
            return directoryCompare;
        }

        int fileCompare = candidate.fileName.compareToIgnoreCase(cursor.fileName);
        if (fileCompare != 0) {
            return fileCompare;
        }

        return candidate.pathHash.compareTo(cursor.pathHash);
    }

    private static String encodeCursor(int directoryIndex, String fileName, String pathHash) {
        String raw = directoryIndex + "\n" + fileName + "\n" + pathHash;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static Cursor decodeCursor(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\n", -1);
            if (parts.length < 2 || parts.length > 3) {
                return null;
            }

            int directoryIndex = Integer.parseInt(parts[0]);
            String fileName = parts[1];
            String pathHash = parts.length == 3 ? parts[2] : "";
            return new Cursor(directoryIndex, fileName, pathHash);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String hashPath(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(path.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                builder.append(String.format("%02x", bytes[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // Reads the configured directory list only after NeoForge has loaded config values.
    private static List<String> configuredDirectories() {
        return FrameShiftConfig.schematicDirectories.get().stream().map(String::valueOf).toList();
    }

    @Nullable
    private static BlockState parseBlockState(
        HolderLookup<net.minecraft.world.level.block.Block> blockLookup,
        String blockStateId,
        Set<String> invalidStateIds
    ) {
        try {
            return BlockStateParser.parseForBlock(blockLookup, blockStateId, false).blockState();
        } catch (CommandSyntaxException exception) {
            if (invalidStateIds.add(blockStateId)) {
                FrameShift.LOGGER.warn("Invalid block state in schematic palette: {}", blockStateId);
            }
            return null;
        }
    }

    private static CompoundTag normalizeBlockEntityTag(@Nullable CompoundTag schematicTag, int x, int y, int z) {
        if (schematicTag == null) {
            return null;
        }

        CompoundTag normalized = new CompoundTag();
        if (schematicTag.contains("Data", 10)) {
            normalized.merge(schematicTag.getCompound("Data").copy());
        } else {
            normalized.merge(schematicTag.copy());
        }

        String id = schematicTag.contains("Id", 8) ? schematicTag.getString("Id") : normalized.getString("id");
        if (!id.isBlank()) {
            normalized.putString("id", id);
        }
        normalized.putInt("x", x);
        normalized.putInt("y", y);
        normalized.putInt("z", z);
        normalized.remove("Pos");
        normalized.remove("Id");
        normalized.remove("Data");
        return normalized;
    }

    private static void enqueueClearVolume(SchematicPasteJob job, SchematicMetadata metadata) throws InterruptedException {
        // Clear each vertical column from top to bottom so falling blocks are removed before their support disappears.
        for (int y = metadata.sizeY - 1; y >= 0; y--) {
            for (int z = 0; z < metadata.sizeZ; z++) {
                for (int x = 0; x < metadata.sizeX; x++) {
                    if (job.state == SchematicPasteJob.State.CANCELLED || job.state == SchematicPasteJob.State.FAILED) {
                        return;
                    }

                    BlockPos worldPos = job.origin.offset(
                        metadata.offsetX + x,
                        metadata.offsetY + y,
                        metadata.offsetZ + z
                    );
                    job.enqueuePlacement(new SchematicPasteJob.PlacementTask(
                        worldPos,
                        Blocks.AIR.defaultBlockState(),
                        null,
                        0
                    ));
                }
            }
        }
    }

    // Builds a reader from the current config values when a command actually needs one.
    private static SchematicReader createSpongeReader() {
        return new SpongeSchematicReader(
            FrameShiftConfig.maxMetadataReadCompressedBytes.get(),
            FrameShiftConfig.maxSchematicVolume.get()
        );
    }

    // Creates the executor lazily so config-backed thread counts are only read after config load.
    private ExecutorService executor() {
        ExecutorService executor = ioExecutor;
        if (executor != null) {
            return executor;
        }

        synchronized (this) {
            if (ioExecutor == null) {
                ioExecutor = Executors.newFixedThreadPool(FrameShiftConfig.metadataIoThreads.get());
            }
            return ioExecutor;
        }
    }

    // Stores the opaque cursor fields after decoding.
    private record Cursor(int directoryIndex, String fileName, String pathHash) {
    }

    // Carries stream preparation results for messaging without holding all block placements in memory.
    public record StreamPasteSummary(
        SchematicMetadata metadata,
        int skippedInvalidBlocks,
        List<String> invalidPaletteStates
    ) {
    }

    // Holds sorting and cursor fields for one list entry.
    private static final class ListEntryCandidate {
        private final int directoryIndex;
        private final String fileName;
        private final String pathHash;
        private final Path file;
        private final SchematicMetadata metadata;

        private ListEntryCandidate(int directoryIndex, String fileName, String pathHash, Path file) {
            this(directoryIndex, fileName, pathHash, file, null);
        }

        private ListEntryCandidate(ListEntryCandidate base, SchematicMetadata metadata) {
            this(base.directoryIndex, base.fileName, base.pathHash, base.file, metadata);
        }

        private ListEntryCandidate(int directoryIndex, String fileName, String pathHash, Path file, @Nullable SchematicMetadata metadata) {
            this.directoryIndex = directoryIndex;
            this.fileName = fileName;
            this.pathHash = pathHash;
            this.file = file;
            this.metadata = metadata;
        }
    }
}
