package com.benzoogataga.frameshift.schematic;

import com.benzoogataga.frameshift.FrameShift;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

// Reads schematic files from disk and converts them into SchematicData objects.
// All file I/O runs off the server thread (async) to avoid freezing the game.
public class SchematicLoader {

    // Starts loading a schematic file in the background.
    // Returns a CompletableFuture — the caller can attach .thenAccept(...) to act on the result
    // without blocking the server thread.
    public static CompletableFuture<SchematicData> loadAsync(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: detect file format (.schem vs .schematic) and delegate to the right parser
            FrameShift.LOGGER.info("Loading schematic: {}", file.getFileName());
            throw new UnsupportedOperationException("SchematicLoader not yet implemented");
        });
    }
}
