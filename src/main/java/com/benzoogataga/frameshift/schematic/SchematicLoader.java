package com.benzoogataga.frameshift.schematic;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
