# Schematic Parsing + /schem list + /schem info Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a schematic metadata-reading layer and two read-only commands (`/schem list`, `/schem info`) that let server operators inspect available schematics without pasting anything.

**Architecture:** New types in `schematic/` define the parsing contract (`SchematicReader`, `BlockStream`, `SchematicMetadata`, etc.). `SpongeSchematicReader` implements the contract for `.schem` files by loading the full NBT tree with a size guard (marked temporary). `SchematicLoader` (now an instance class created at server start) owns a bounded IO executor and orchestrates async metadata reads and directory listing.

**Tech Stack:** NeoForge 1.21.1, Minecraft `NbtIo`/`CompoundTag`, Brigadier command framework, `java.util.concurrent` (`CompletableFuture`, `ExecutorService`), JUnit 5 for pure-Java unit tests.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/.../schematic/SchematicFormat.java` | Enum: SPONGE_V2, SPONGE_V3, LEGACY_SCHEMATIC, UNKNOWN |
| Create | `src/main/java/.../schematic/SchematicMetadata.java` | Immutable metadata record (dimensions, file size, counts) |
| Create | `src/main/java/.../schematic/SchematicReadOptions.java` | Options for future block stream (ignoreAir, etc.) |
| Create | `src/main/java/.../schematic/SchematicListResult.java` | Paginated list result with cursor + error counts |
| Create | `src/main/java/.../schematic/SchematicBlockEntry.java` | Single block position + palette ID + optional NBT |
| Create | `src/main/java/.../schematic/BlockStream.java` | Interface for iterating block entries (future paste) |
| Create | `src/main/java/.../schematic/SchematicReader.java` | Interface all format readers must implement |
| Create | `src/main/java/.../schematic/SpongeSchematicReader.java` | Reads .schem v2/v3 metadata via NbtIo |
| Replace | `src/main/java/.../schematic/SchematicLoader.java` | Instance class: IO executor, listAsync, readMetadataAsync |
| Modify | `src/main/java/.../config/FrameShiftConfig.java` | Add 3 fields under `[metadata]` section |
| Modify | `src/main/java/.../FrameShift.java` | Create/expose/shutdown loader; wire commands with loader |
| Replace | `src/main/java/.../command/SchemCommand.java` | Implement /schem list and /schem info |
| Create | `src/test/java/.../schematic/SchematicFormatTest.java` | Unit test: enum values exist |
| Create | `src/test/java/.../schematic/SchematicMetadataTest.java` | Unit test: volume() calculation |
| Create | `src/test/java/.../schematic/SchematicListResultTest.java` | Unit test: cursor and count fields |
| Modify | `build.gradle` | Add JUnit 5 test dependency + configure test task |

---

## Task 1: Add JUnit 5 to the build

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add test dependencies to `build.gradle`**

Open `build.gradle` and add inside the `dependencies { }` block (after the neoforge line):

```groovy
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

Also add a `test` task block anywhere after the `dependencies` block:

```groovy
test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Verify the test task resolves**

```bash
./gradlew test --tests "com.nonexistent.*"
```

Expected: task runs and exits with "No tests found" (or 0 failures) — not a build error.

---

## Task 2: SchematicFormat enum

**Files:**
- Create: `src/main/java/com/benzoogataga/frameshift/schematic/SchematicFormat.java`
- Create: `src/test/java/com/benzoogataga/frameshift/schematic/SchematicFormatTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/benzoogataga/frameshift/schematic/SchematicFormatTest.java`:

```java
package com.benzoogataga.frameshift.schematic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicFormatTest {

    @Test
    void allExpectedValuesExist() {
        assertNotNull(SchematicFormat.SPONGE_V2);
        assertNotNull(SchematicFormat.SPONGE_V3);
        assertNotNull(SchematicFormat.LEGACY_SCHEMATIC);
        assertNotNull(SchematicFormat.UNKNOWN);
    }

    @Test
    void exactlyFourValues() {
        assertEquals(4, SchematicFormat.values().length);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails (class missing)**

```bash
./gradlew test --tests "com.benzoogataga.frameshift.schematic.SchematicFormatTest"
```

Expected: compilation failure — `SchematicFormat` doesn't exist yet.

- [ ] **Step 3: Create `SchematicFormat.java`**

Create `src/main/java/com/benzoogataga/frameshift/schematic/SchematicFormat.java`:

```java
package com.benzoogataga.frameshift.schematic;

public enum SchematicFormat {
    SPONGE_V2,
    SPONGE_V3,
    LEGACY_SCHEMATIC,
    UNKNOWN
}
```

- [ ] **Step 4: Run test to confirm it passes**

```bash
./gradlew test --tests "com.benzoogataga.frameshift.schematic.SchematicFormatTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/schematic/SchematicFormat.java \
        src/test/java/com/benzoogataga/frameshift/schematic/SchematicFormatTest.java \
        build.gradle
git commit -m "feat: add SchematicFormat enum + JUnit 5 test infrastructure"
```

---

## Task 3: SchematicMetadata

**Files:**
- Create: `src/main/java/com/benzoogataga/frameshift/schematic/SchematicMetadata.java`
- Create: `src/test/java/com/benzoogataga/frameshift/schematic/SchematicMetadataTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/benzoogataga/frameshift/schematic/SchematicMetadataTest.java`:

```java
package com.benzoogataga.frameshift.schematic;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SchematicMetadataTest {

    private SchematicMetadata make(int x, int y, int z) {
        return new SchematicMetadata(
            "test", Path.of("test.schem"), SchematicFormat.SPONGE_V2,
            x, y, z,
            1024L, 3953, -1L, -1, -1
        );
    }

    @Test
    void volumeIsProduct() {
        assertEquals(6L, make(1, 2, 3).volume());
    }

    @Test
    void volumeHandlesLargeNumbers() {
        // Ensure multiplication doesn't overflow int
        assertEquals(50_000_000L, make(500, 500, 200).volume());
    }

    @Test
    void fieldsAreStoredCorrectly() {
        SchematicMetadata m = make(10, 20, 30);
        assertEquals("test", m.name);
        assertEquals(SchematicFormat.SPONGE_V2, m.format);
        assertEquals(10, m.sizeX);
        assertEquals(20, m.sizeY);
        assertEquals(30, m.sizeZ);
        assertEquals(-1L, m.nonAirBlocks);
        assertEquals(-1, m.blockEntityCount);
        assertEquals(-1, m.entityCount);
    }
}
```

- [ ] **Step 2: Run test to confirm compilation failure**

```bash
./gradlew test --tests "com.benzoogataga.frameshift.schematic.SchematicMetadataTest"
```

Expected: compilation error — `SchematicMetadata` doesn't exist.

- [ ] **Step 3: Create `SchematicMetadata.java`**

Create `src/main/java/com/benzoogataga/frameshift/schematic/SchematicMetadata.java`:

```java
package com.benzoogataga.frameshift.schematic;

import java.nio.file.Path;

public final class SchematicMetadata {
    public final String name;           // filename stem, no extension
    public final Path file;             // absolute path to the file on disk
    public final SchematicFormat format;
    public final int sizeX, sizeY, sizeZ;
    public final long fileSize;         // compressed bytes on disk
    public final int dataVersion;       // Minecraft data version, -1 if unknown
    public final long nonAirBlocks;     // -1 until full block data is parsed
    public final int blockEntityCount;  // -1 if unknown
    public final int entityCount;       // -1 if unknown

    public SchematicMetadata(String name, Path file, SchematicFormat format,
                              int sizeX, int sizeY, int sizeZ,
                              long fileSize, int dataVersion,
                              long nonAirBlocks, int blockEntityCount, int entityCount) {
        this.name = name;
        this.file = file;
        this.format = format;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.fileSize = fileSize;
        this.dataVersion = dataVersion;
        this.nonAirBlocks = nonAirBlocks;
        this.blockEntityCount = blockEntityCount;
        this.entityCount = entityCount;
    }

    // Total block positions including air
    public long volume() {
        return (long) sizeX * sizeY * sizeZ;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "com.benzoogataga.frameshift.schematic.SchematicMetadataTest"
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/schematic/SchematicMetadata.java \
        src/test/java/com/benzoogataga/frameshift/schematic/SchematicMetadataTest.java
git commit -m "feat: add SchematicMetadata with volume() helper"
```

---

## Task 4: SchematicReadOptions, SchematicListResult

**Files:**
- Create: `src/main/java/com/benzoogataga/frameshift/schematic/SchematicReadOptions.java`
- Create: `src/main/java/com/benzoogataga/frameshift/schematic/SchematicListResult.java`
- Create: `src/test/java/com/benzoogataga/frameshift/schematic/SchematicListResultTest.java`

- [ ] **Step 1: Write the failing test for SchematicListResult**

Create `src/test/java/com/benzoogataga/frameshift/schematic/SchematicListResultTest.java`:

```java
package com.benzoogataga.frameshift.schematic;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SchematicListResultTest {

    @Test
    void nullCursorMeansNoMorePages() {
        SchematicListResult result = new SchematicListResult(List.of(), null, 0, 0);
        assertNull(result.nextCursor);
    }

    @Test
    void nonNullCursorMeansMoreResults() {
        SchematicListResult result = new SchematicListResult(List.of(), "castle", 2, 1);
        assertEquals("castle", result.nextCursor);
        assertEquals(2, result.skipped);
        assertEquals(1, result.failed);
    }
}
```

- [ ] **Step 2: Run test to confirm compilation failure**

```bash
./gradlew test --tests "com.benzoogataga.frameshift.schematic.SchematicListResultTest"
```

Expected: compilation error.

- [ ] **Step 3: Create `SchematicReadOptions.java`**

Create `src/main/java/com/benzoogataga/frameshift/schematic/SchematicReadOptions.java`:

```java
package com.benzoogataga.frameshift.schematic;

public final class SchematicReadOptions {
    public final boolean ignoreAir;
    public final boolean includeBlockEntities;
    public final boolean includeEntities;

    public SchematicReadOptions(boolean ignoreAir, boolean includeBlockEntities, boolean includeEntities) {
        this.ignoreAir = ignoreAir;
        this.includeBlockEntities = includeBlockEntities;
        this.includeEntities = includeEntities;
    }
}
```

- [ ] **Step 4: Create `SchematicListResult.java`**

Create `src/main/java/com/benzoogataga/frameshift/schematic/SchematicListResult.java`:

```java
package com.benzoogataga.frameshift.schematic;

import org.jetbrains.annotations.Nullable;
import java.util.List;

public final class SchematicListResult {
    public final List<SchematicMetadata> entries;
    @Nullable public final String nextCursor; // null = no more pages
    public final int skipped;  // files with no matching reader
    public final int failed;   // files that threw during readMetadata

    public SchematicListResult(List<SchematicMetadata> entries, @Nullable String nextCursor,
                                int skipped, int failed) {
        this.entries = entries;
        this.nextCursor = nextCursor;
        this.skipped = skipped;
        this.failed = failed;
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests "com.benzoogataga.frameshift.schematic.SchematicListResultTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/schematic/SchematicReadOptions.java \
        src/main/java/com/benzoogataga/frameshift/schematic/SchematicListResult.java \
        src/test/java/com/benzoogataga/frameshift/schematic/SchematicListResultTest.java
git commit -m "feat: add SchematicReadOptions and SchematicListResult"
```

---

## Task 5: SchematicBlockEntry, BlockStream, SchematicReader interfaces

These types depend on Minecraft's `CompoundTag` and cannot be unit-tested without the game. Verification is by successful compilation.

**Files:**
- Create: `src/main/java/com/benzoogataga/frameshift/schematic/SchematicBlockEntry.java`
- Create: `src/main/java/com/benzoogataga/frameshift/schematic/BlockStream.java`
- Create: `src/main/java/com/benzoogataga/frameshift/schematic/SchematicReader.java`

- [ ] **Step 1: Create `SchematicBlockEntry.java`**

```java
package com.benzoogataga.frameshift.schematic;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

// Represents one block position in a schematic during streaming (future paste use)
public final class SchematicBlockEntry {
    public final int x, y, z;
    public final int paletteId;
    @Nullable public final CompoundTag blockEntityNbt;

    public SchematicBlockEntry(int x, int y, int z, int paletteId, @Nullable CompoundTag blockEntityNbt) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.paletteId = paletteId;
        this.blockEntityNbt = blockEntityNbt;
    }
}
```

- [ ] **Step 2: Create `BlockStream.java`**

```java
package com.benzoogataga.frameshift.schematic;

import java.io.IOException;

// Streaming iterator over block entries in a schematic — used by future paste implementation
public interface BlockStream extends AutoCloseable {
    boolean hasNext() throws IOException;
    SchematicBlockEntry next() throws IOException;
    void close() throws IOException;
}
```

- [ ] **Step 3: Create `SchematicReader.java`**

```java
package com.benzoogataga.frameshift.schematic;

import java.io.IOException;
import java.nio.file.Path;

// Contract that every schematic format parser must implement
public interface SchematicReader {
    // Returns true if this reader can handle the given file (based on extension — no I/O)
    boolean supports(Path file);

    // Reads only the metadata (dimensions, counts) — no block data
    SchematicMetadata readMetadata(Path file) throws IOException;

    // Opens a block-by-block stream for paste — not implemented until Milestone 3
    BlockStream openBlockStream(Path file, SchematicReadOptions options) throws IOException;
}
```

- [ ] **Step 4: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/schematic/SchematicBlockEntry.java \
        src/main/java/com/benzoogataga/frameshift/schematic/BlockStream.java \
        src/main/java/com/benzoogataga/frameshift/schematic/SchematicReader.java
git commit -m "feat: add SchematicBlockEntry, BlockStream, and SchematicReader interfaces"
```

---

## Task 6: Update FrameShiftConfig with three new metadata fields

**Files:**
- Modify: `src/main/java/com/benzoogataga/frameshift/config/FrameShiftConfig.java`

- [ ] **Step 1: Add three new static fields to `FrameShiftConfig`**

Open `FrameShiftConfig.java`. After the `schematicDirectories` field declaration (around line 55), add:

```java
    // ── Metadata I/O ─────────────────────────────────────────────────────────

    // Reject schematic files larger than this many compressed bytes before reading NBT (256 MB default)
    public static ModConfigSpec.LongValue maxMetadataReadCompressedBytes;

    // Number of threads in the dedicated IO pool used for metadata reads and directory listing
    public static ModConfigSpec.IntValue metadataIoThreads;

    // Maximum number of entries returned by /schem list per page
    public static ModConfigSpec.IntValue maxListResults;
```

- [ ] **Step 2: Register the three fields in the `register()` method**

In the `register()` method, after `builder.pop()` for the `filesystem` section, add a new section:

```java
        builder.push("metadata");
        maxMetadataReadCompressedBytes = builder
            .comment("Max compressed file size in bytes before rejecting an NBT read (default: 256 MB)")
            .defineInRange("maxMetadataReadCompressedBytes", 256_000_000L, 1L, Long.MAX_VALUE);
        metadataIoThreads = builder
            .comment("Threads in the dedicated IO executor for metadata reads")
            .defineInRange("metadataIoThreads", 2, 1, 16);
        maxListResults = builder
            .comment("Max entries returned per /schem list page")
            .defineInRange("maxListResults", 100, 1, 1000);
        builder.pop();
```

- [ ] **Step 3: Compile to verify no errors**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/config/FrameShiftConfig.java
git commit -m "feat: add maxMetadataReadCompressedBytes, metadataIoThreads, maxListResults config fields"
```

---

## Task 7: Implement SpongeSchematicReader

**Files:**
- Create: `src/main/java/com/benzoogataga/frameshift/schematic/SpongeSchematicReader.java`

Note: `NbtIo.readCompressed` loads the entire NBT tree into memory. This is marked `// TODO: replace with bounded streaming` because a future implementation should parse only the header without reading block arrays. The size guard (`maxCompressedBytes`) prevents catastrophically large files.

- [ ] **Step 1: Create `SpongeSchematicReader.java`**

```java
package com.benzoogataga.frameshift.schematic;

import com.benzoogataga.frameshift.FrameShift;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SpongeSchematicReader implements SchematicReader {

    private final long maxCompressedBytes;
    private final long maxVolume;

    public SpongeSchematicReader(long maxCompressedBytes, long maxVolume) {
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxVolume = maxVolume;
    }

    @Override
    public boolean supports(Path file) {
        // No file I/O — just check the extension
        return file.getFileName().toString().endsWith(".schem");
    }

    @Override
    public SchematicMetadata readMetadata(Path file) throws IOException {
        if (!Files.exists(file) || !Files.isReadable(file)) {
            throw new IOException("File is not accessible: " + file);
        }

        long compressedSize = Files.size(file);
        if (compressedSize > maxCompressedBytes) {
            throw new IOException("File too large to read: " + compressedSize
                + " bytes (limit: " + maxCompressedBytes + ")");
        }

        // TODO: replace with bounded streaming — NbtIo.readCompressed loads the full NBT tree
        CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());

        if (!tag.contains("Version", Tag.TAG_INT)) {
            throw new IOException("Missing Version field in: " + file.getFileName());
        }
        int version = tag.getInt("Version");

        SchematicFormat format;
        if (version == 2) {
            format = SchematicFormat.SPONGE_V2;
        } else if (version == 3) {
            format = SchematicFormat.SPONGE_V3;
        } else {
            throw new IOException("Unsupported Sponge version " + version + " in: " + file.getFileName());
        }

        if (!tag.contains("Width", Tag.TAG_SHORT)
                || !tag.contains("Height", Tag.TAG_SHORT)
                || !tag.contains("Length", Tag.TAG_SHORT)) {
            throw new IOException("Missing dimension fields (Width/Height/Length) in: " + file.getFileName());
        }

        int sizeX = tag.getShort("Width");
        int sizeY = tag.getShort("Height");
        int sizeZ = tag.getShort("Length");

        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IOException("Invalid dimensions (must be > 0): "
                + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        if (sizeX > 32767 || sizeY > 32767 || sizeZ > 32767) {
            throw new IOException("Dimensions exceed 32767: "
                + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > maxVolume) {
            throw new IOException("Schematic volume " + volume
                + " exceeds configured limit " + maxVolume);
        }

        int dataVersion = tag.contains("DataVersion", Tag.TAG_INT)
            ? tag.getInt("DataVersion") : -1;

        // BlockEntities location differs between Sponge v2 and v3
        int blockEntityCount;
        if (format == SchematicFormat.SPONGE_V3 && tag.contains("Blocks", Tag.TAG_COMPOUND)) {
            blockEntityCount = tag.getCompound("Blocks")
                .getList("BlockEntities", Tag.TAG_COMPOUND).size();
        } else {
            blockEntityCount = tag.getList("BlockEntities", Tag.TAG_COMPOUND).size();
        }

        // Entities are at root in both v2 and v3
        int entityCount = tag.getList("Entities", Tag.TAG_COMPOUND).size();

        // Release the entire NBT tree — we only needed the header
        tag = null;

        return new SchematicMetadata(
            stemOf(file.getFileName().toString()), file, format,
            sizeX, sizeY, sizeZ,
            compressedSize, dataVersion,
            -1L, blockEntityCount, entityCount
        );
    }

    @Override
    public BlockStream openBlockStream(Path file, SchematicReadOptions options) throws IOException {
        throw new UnsupportedOperationException("Block streaming not yet implemented");
    }

    private static String stemOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/schematic/SpongeSchematicReader.java
git commit -m "feat: implement SpongeSchematicReader for .schem v2/v3 metadata"
```

---

## Task 8: Rewrite SchematicLoader as an instance class

The existing `SchematicLoader.java` is a static utility stub. Replace it entirely with an instance class that owns a bounded IO executor.

**Files:**
- Replace: `src/main/java/com/benzoogataga/frameshift/schematic/SchematicLoader.java`

- [ ] **Step 1: Replace `SchematicLoader.java` with the new instance class**

Overwrite the entire file:

```java
package com.benzoogataga.frameshift.schematic;

import com.benzoogataga.frameshift.FrameShift;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SchematicLoader {

    private final List<SchematicReader> readers;
    private final ExecutorService ioExecutor;
    private final List<String> schematicDirectories;
    private final int maxListResults;

    // Called at server start (after SERVER config is loaded from disk)
    public SchematicLoader() {
        long maxMetadataBytes = FrameShiftConfig.maxMetadataReadCompressedBytes.get();
        long maxVolume = FrameShiftConfig.maxSchematicVolume.get();
        int ioThreads = FrameShiftConfig.metadataIoThreads.get();
        this.maxListResults = FrameShiftConfig.maxListResults.get();
        this.schematicDirectories = List.copyOf(FrameShiftConfig.schematicDirectories.get());

        this.readers = List.of(new SpongeSchematicReader(maxMetadataBytes, maxVolume));
        // Daemon threads so the JVM doesn't hang on shutdown if we miss a shutdown() call
        this.ioExecutor = Executors.newFixedThreadPool(ioThreads, r -> {
            Thread t = new Thread(r, "frameshift-io");
            t.setDaemon(true);
            return t;
        });
    }

    // Called on ServerStoppingEvent to release the thread pool
    public void shutdown() {
        ioExecutor.shutdown();
    }

    // Returns the first reader that claims to support this file (by extension)
    public Optional<SchematicReader> findReader(Path file) {
        return readers.stream().filter(r -> r.supports(file)).findFirst();
    }

    // Reads metadata for a single file on the IO executor
    public CompletableFuture<SchematicMetadata> readMetadataAsync(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<SchematicReader> readerOpt = findReader(file);
            if (readerOpt.isEmpty()) {
                throw new CompletionException(
                    new IOException("No reader supports file: " + file.getFileName()));
            }
            try {
                return readerOpt.get().readMetadata(file);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, ioExecutor);
    }

    // Lists schematics across all configured directories with cursor-based pagination.
    // limit is capped at maxListResults. cursor is the stem of the last returned entry.
    public CompletableFuture<SchematicListResult> listAsync(
            Path serverRoot, int limit, @Nullable String cursor) {
        int effectiveLimit = Math.min(limit, maxListResults);

        return CompletableFuture.supplyAsync(() -> {
            // Collect up to effectiveLimit+1 entries so we can detect whether a next page exists
            List<SchematicMetadata> collected = new ArrayList<>();
            int skipped = 0;
            int failed = 0;
            int failedLogged = 0;
            boolean hasMore = false;

            outer:
            for (String dirStr : schematicDirectories) {
                Path dir = serverRoot.resolve(dirStr);
                if (!Files.isDirectory(dir)) continue;

                List<Path> files;
                try (Stream<Path> stream = Files.list(dir)) {
                    files = stream
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(
                            p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .collect(Collectors.toList());
                } catch (IOException e) {
                    FrameShift.LOGGER.warn("Could not list directory {}: {}", dir, e.getMessage());
                    continue;
                }

                for (Path file : files) {
                    if (findReader(file).isEmpty()) {
                        skipped++;
                        continue;
                    }

                    String stem = stemOf(file.getFileName().toString());

                    // Skip entries up to and including the cursor (case-insensitive)
                    if (cursor != null && stem.compareToIgnoreCase(cursor) <= 0) {
                        continue;
                    }

                    try {
                        SchematicMetadata meta = findReader(file).get().readMetadata(file);
                        collected.add(meta);
                    } catch (IOException e) {
                        failed++;
                        if (failedLogged < 5) {
                            FrameShift.LOGGER.warn("Failed to read metadata for {}: {}",
                                file.getFileName(), e.getMessage());
                            failedLogged++;
                        }
                        continue;
                    }

                    if (collected.size() > effectiveLimit) {
                        // We have one extra entry — that's proof there's a next page
                        hasMore = true;
                        break outer;
                    }
                }
            }

            // Summarize failures beyond the first 5
            if (failed > 5) {
                FrameShift.LOGGER.warn("... {} more files failed, see debug log", failed - 5);
            }

            String nextCursor = null;
            if (hasMore) {
                // Trim the extra entry and set cursor to the last kept entry's name
                collected = new ArrayList<>(collected.subList(0, effectiveLimit));
                nextCursor = collected.get(effectiveLimit - 1).name;
            }

            return new SchematicListResult(List.copyOf(collected), nextCursor, skipped, failed);
        }, ioExecutor);
    }

    private static String stemOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/schematic/SchematicLoader.java
git commit -m "feat: rewrite SchematicLoader as instance class with IO executor and pagination"
```

---

## Task 9: Wire SchematicLoader into FrameShift

The `@Mod` constructor currently registers `SchemCommand` without a loader. We need to:
1. Add a `SchematicLoader loader` field
2. Create the loader when the server starts (after SERVER config is loaded)
3. Shut down the executor when the server stops
4. Pass the loader to `SchemCommand.register`

**Files:**
- Modify: `src/main/java/com/benzoogataga/frameshift/FrameShift.java`
- Modify: `src/main/java/com/benzoogataga/frameshift/command/SchemCommand.java` (signature change only)

- [ ] **Step 1: Update `SchemCommand.java` to accept a loader parameter**

Open `SchemCommand.java` and change the `register` method signature:

```java
package com.benzoogataga.frameshift.command;

import com.benzoogataga.frameshift.schematic.SchematicLoader;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class SchemCommand {

    // entry point — called once per server start with the active loader instance
    public static void register(RegisterCommandsEvent event, SchematicLoader loader) {
        // TODO in Task 10: build the /schem command tree using Brigadier
    }
}
```

- [ ] **Step 2: Rewrite `FrameShift.java`**

Replace the entire file:

```java
package com.benzoogataga.frameshift;

import com.benzoogataga.frameshift.command.SchemCommand;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.schematic.SchematicLoader;
import com.benzoogataga.frameshift.tick.TickHandler;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(FrameShift.MOD_ID)
public class FrameShift {

    public static final String MOD_ID = "frameshift";
    public static final Logger LOGGER = LogUtils.getLogger();

    private SchematicLoader loader;

    public FrameShift(IEventBus modEventBus, ModContainer modContainer) {
        FrameShiftConfig.register(modContainer);

        NeoForge.EVENT_BUS.register(new TickHandler());

        // ServerAboutToStartEvent fires after SERVER configs are loaded from disk,
        // so it's the right time to snapshot config values into SchematicLoader.
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        LOGGER.info("FrameShift loaded — Yet Another Schematics Manager");
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        this.loader = new SchematicLoader();
        LOGGER.info("SchematicLoader initialised ({} IO thread(s))",
            FrameShiftConfig.metadataIoThreads.get());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (loader != null) {
            loader.shutdown();
            loader = null;
        }
    }

    // RegisterCommandsEvent fires after ServerAboutToStartEvent, so loader is non-null here
    private void onRegisterCommands(RegisterCommandsEvent event) {
        if (loader != null) {
            SchemCommand.register(event, loader);
        }
    }

    public SchematicLoader getLoader() {
        return loader;
    }
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/FrameShift.java \
        src/main/java/com/benzoogataga/frameshift/command/SchemCommand.java
git commit -m "feat: wire SchematicLoader lifecycle into FrameShift (create on start, shutdown on stop)"
```

---

## Task 10: Implement /schem list

**Files:**
- Modify: `src/main/java/com/benzoogataga/frameshift/command/SchemCommand.java`

- [ ] **Step 1: Replace `SchemCommand.java` with the full list+info implementation skeleton**

```java
package com.benzoogataga.frameshift.command;

import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.schematic.SchematicLoader;
import com.benzoogataga.frameshift.schematic.SchematicListResult;
import com.benzoogataga.frameshift.schematic.SchematicMetadata;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

public class SchemCommand {

    public static void register(RegisterCommandsEvent event, SchematicLoader loader) {
        event.getDispatcher().register(
            Commands.literal("schem")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                    .executes(ctx -> executeList(ctx.getSource(), loader, null))
                    .then(Commands.argument("cursor", StringArgumentType.word())
                        .executes(ctx -> executeList(
                            ctx.getSource(), loader,
                            StringArgumentType.getString(ctx, "cursor")))))
                .then(Commands.literal("info")
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> executeInfo(
                            ctx.getSource(), loader,
                            StringArgumentType.getString(ctx, "name")))))
        );
    }

    // ── /schem list [cursor] ──────────────────────────────────────────────────

    private static int executeList(CommandSourceStack source, SchematicLoader loader,
                                   @Nullable String cursor) {
        MinecraftServer server = source.getServer();
        Path serverRoot = server.getServerDirectory();
        int limit = FrameShiftConfig.maxListResults.get();

        loader.listAsync(serverRoot, limit, cursor)
            .thenAcceptAsync(result -> sendListResult(source, result), server::execute)
            .exceptionally(error -> {
                server.execute(() -> source.sendFailure(
                    Component.literal("Error listing schematics: " + unwrap(error).getMessage())));
                return null;
            });

        return Command.SINGLE_SUCCESS;
    }

    private static void sendListResult(CommandSourceStack source, SchematicListResult result) {
        if (result.entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No schematics found."), false);
            return;
        }

        for (SchematicMetadata meta : result.entries) {
            String line = meta.name
                + "  " + meta.sizeX + "x" + meta.sizeY + "x" + meta.sizeZ
                + "  " + formatFileSize(meta.fileSize);
            source.sendSuccess(() -> Component.literal(line), false);
        }

        if (result.nextCursor != null) {
            String hint = "More results — use /schem list " + result.nextCursor;
            source.sendSuccess(() -> Component.literal(hint), false);
        }

        if (result.skipped > 0 || result.failed > 0) {
            String counts = "(" + result.skipped + " skipped, " + result.failed + " failed)";
            source.sendSuccess(() -> Component.literal(counts), false);
        }
    }

    // ── /schem info <name> ────────────────────────────────────────────────────

    private static int executeInfo(CommandSourceStack source, SchematicLoader loader, String name) {
        // Reject names containing path separators to prevent directory traversal
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            source.sendFailure(Component.literal(
                "Invalid schematic name — path separators are not allowed."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        Path serverRoot = server.getServerDirectory();

        // Locate the first matching file across configured directories
        Path found = findSchematic(loader, serverRoot, name);
        if (found == null) {
            source.sendFailure(Component.literal("Schematic not found: " + name));
            return 0;
        }

        loader.readMetadataAsync(found)
            .thenAcceptAsync(meta -> sendInfoResult(source, meta), server::execute)
            .exceptionally(error -> {
                server.execute(() -> source.sendFailure(
                    Component.literal("Error reading schematic: " + unwrap(error).getMessage())));
                return null;
            });

        return Command.SINGLE_SUCCESS;
    }

    private static void sendInfoResult(CommandSourceStack source, SchematicMetadata meta) {
        source.sendSuccess(() -> Component.literal("Name:          " + meta.name), false);
        source.sendSuccess(() -> Component.literal("Format:        " + meta.format), false);
        source.sendSuccess(() -> Component.literal(
            "Dimensions:    " + meta.sizeX + " x " + meta.sizeY + " x " + meta.sizeZ), false);
        source.sendSuccess(() -> Component.literal("Volume:        " + meta.volume()), false);
        source.sendSuccess(() -> Component.literal("File size:     " + formatFileSize(meta.fileSize)), false);
        source.sendSuccess(() -> Component.literal(
            "Data version:  " + (meta.dataVersion == -1 ? "unknown" : meta.dataVersion)), false);
        source.sendSuccess(() -> Component.literal(
            "Block entities:" + (meta.blockEntityCount == -1 ? " unknown" : " " + meta.blockEntityCount)), false);
        source.sendSuccess(() -> Component.literal(
            "Entities:      " + (meta.entityCount == -1 ? "unknown" : meta.entityCount)), false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Searches configured directories for a file whose stem matches `name` and has a known reader
    @Nullable
    private static Path findSchematic(SchematicLoader loader, Path serverRoot, String name) {
        for (String dirStr : FrameShiftConfig.schematicDirectories.get()) {
            Path dir = serverRoot.resolve(dirStr);
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                var match = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> stemOf(f.getFileName().toString()).equals(name))
                    .filter(f -> loader.findReader(f).isPresent())
                    .findFirst();
                if (match.isPresent()) return match.get();
            } catch (IOException e) {
                // Skip unreadable directories
            }
        }
        return null;
    }

    private static String stemOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // Unwrap CompletionException (thrown by CompletableFuture) to get the root cause
    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 3: Build the jar**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`. Jar appears in `build/libs/`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/benzoogataga/frameshift/command/SchemCommand.java
git commit -m "feat: implement /schem list and /schem info commands"
```

---

## Task 11: End-to-end server test

This task verifies both commands work against real schematic files on a running dev server.

- [ ] **Step 1: Start the dev server**

```bash
./gradlew runServer
```

Wait for the log line: `SchematicLoader initialised (2 IO thread(s))`

Also check that `config/frameshift-server.toml` now contains a `[metadata]` section with the three new fields.

- [ ] **Step 2: Test /schem list with no schematics**

In the server console:
```
/schem list
```

Expected output:
```
No schematics found.
```

- [ ] **Step 3: Drop a real .schem file into the schematics folder**

Copy any `.schem` file (Sponge v2 or v3) to `runs/server/schematics/` (create the folder if it doesn't exist).

If you don't have a `.schem` file, download a sample from a WorldEdit or FAWE schematic pack and place it there.

- [ ] **Step 4: Test /schem list again**

```
/schem list
```

Expected: one line per file in the format `name  WxHxL  X.X KB` (or MB).

- [ ] **Step 5: Test /schem info**

```
/schem info <name>
```

(Replace `<name>` with the filename stem from step 4, no `.schem` extension.)

Expected output (values will differ):
```
Name:          my_schematic
Format:        SPONGE_V2
Dimensions:    100 x 50 x 80
Volume:        400000
File size:     128.4 KB
Data version:  3953
Block entities: 12
Entities:      0
```

- [ ] **Step 6: Test path traversal rejection**

```
/schem info ../config/frameshift-server
```

Expected: error message `Invalid schematic name — path separators are not allowed.`

- [ ] **Step 7: Test missing schematic**

```
/schem info does_not_exist
```

Expected: error message `Schematic not found: does_not_exist`

- [ ] **Step 8: Stop the server and commit any fixes**

Type `stop` in the console. If any issues were found and fixed, commit them:

```bash
git add -p
git commit -m "fix: <describe what was wrong>"
```

---

## All tests passing check

After Task 11:

```bash
./gradlew test
```

Expected: all unit tests (SchematicFormatTest, SchematicMetadataTest, SchematicListResultTest) pass.

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`, jar built without errors.
