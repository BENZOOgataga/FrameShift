# Design: Schematic Parsing + /schem list + /schem info

**Date:** 2026-04-24
**Milestone:** 1 (Parsing layer) + 2 (Read-only commands)
**Status:** Approved - ready for implementation

---

## Context

FrameShift needs to read schematic files from disk and expose them to server operators. This milestone implements:
1. A parsing abstraction layer (reader interface + Sponge implementation)
2. Metadata-only reads (dimensions, format, file size - no block data)
3. `/schem list` and `/schem info` commands backed by async I/O

Full block streaming (for paste) is out of scope here. The interfaces are defined now so paste can slot in without redesign.

---

## New Types

### `SchematicFormat` (enum)
```java
public enum SchematicFormat {
    SPONGE_V2,
    SPONGE_V3,
    LEGACY_SCHEMATIC,
    UNKNOWN
}
```

### `SchematicMetadata`
```java
public final class SchematicMetadata {
    public final String name;          // filename stem, no extension
    public final Path file;            // absolute path
    public final SchematicFormat format;
    public final int sizeX, sizeY, sizeZ;
    public final long fileSize;        // compressed bytes on disk
    public final int dataVersion;      // Minecraft data version, -1 if unknown
    public final long nonAirBlocks;    // -1 if unknown (requires full parse)
    public final int blockEntityCount; // -1 if unknown
    public final int entityCount;      // -1 if unknown

    public long volume() {
        return (long) sizeX * sizeY * sizeZ;
    }
}
```

### `SchematicReadOptions`
```java
public final class SchematicReadOptions {
    public final boolean ignoreAir;
    public final boolean includeBlockEntities;
    public final boolean includeEntities;
    // constructed via builder or all-args constructor
}
```

### `SchematicBlockEntry`
```java
public final class SchematicBlockEntry {
    public final int x, y, z;
    public final int paletteId;
    @Nullable public final CompoundTag blockEntityNbt;
}
```
Not used in this milestone. Defined now to lock the contract for `BlockStream`.

### `BlockStream` (interface)
```java
public interface BlockStream extends AutoCloseable {
    boolean hasNext() throws IOException;
    SchematicBlockEntry next() throws IOException;
}
```
Entry point for future paste implementation. All readers return `UnsupportedOperationException` from `openBlockStream` until paste is implemented.

### `SchematicReader` (interface)
```java
public interface SchematicReader {
    boolean supports(Path file);
    SchematicMetadata readMetadata(Path file) throws IOException;
    BlockStream openBlockStream(Path file, SchematicReadOptions options) throws IOException;
}
```

### `SchematicListResult`
```java
public final class SchematicListResult {
    public final List<SchematicMetadata> entries;
    @Nullable public final String nextCursor; // null = no more results
    public final int skipped;  // files with no matching reader
    public final int failed;   // files that threw during readMetadata
}
```

---

## `SpongeSchematicReader`

Implements `SchematicReader`. Reads `.schem` files in Sponge Schematic Format v2 and v3.

### `supports(Path file)`
Returns `true` if the filename ends with `.schem`. No file I/O.

### `readMetadata(Path file)`

**Temporary implementation - marked with `// TODO: replace with bounded streaming`**

Steps:
1. Assert file exists and is readable → `IOException` if not
2. **Guard:** read `Files.size(file)`. Reject if `> config.maxMetadataReadCompressedBytes` (default `256_000_000`) with a descriptive `IOException`
3. `NbtIo.readCompressed(file.toFile())` → root `CompoundTag`
4. Extract `Version` (int) → determine `SchematicFormat`:
   - `2` → `SPONGE_V2`
   - `3` → `SPONGE_V3`
   - anything else → throw `IOException("Unsupported Sponge version: " + version)`
5. Extract `Width`, `Height`, `Length` (short at root for both v2 and v3)
6. **Validate dimensions:**
   - Missing → `IOException`
   - `<= 0` → `IOException`
   - `> 32767` → `IOException`
   - `volume() > config.maxSchematicVolume` → `IOException`
7. Extract `DataVersion` (int, -1 if absent)
8. `tag.getList("BlockEntities", ...).size()` → `blockEntityCount`
   - For v3: key is inside `Blocks` compound tag
9. `tag.getList("Entities", ...).size()` → `entityCount`
   - For both v2 and v3: `Entities` is at root (not inside `Blocks`)
   - `BlockEntities` is also at root in both versions
10. `nonAirBlocks` = `-1` (requires full block data parse - not done here)
11. Drop reference to root `CompoundTag` - must not be retained
12. Return `SchematicMetadata`

**What is never touched:** `Palette`, `BlockData`, `Blocks.Data`, individual `BlockEntity` NBT contents.

**Note on `getList` calls:** In this milestone, `NbtIo.readCompressed()` already allocates the full NBT tree. Calling `getList(...).size()` is cheap. In a future streaming implementation, these counts must come from a preprocessed index or bounded scan, not a full load.

### `openBlockStream(Path file, SchematicReadOptions options)`
```java
throw new UnsupportedOperationException("Block streaming not yet implemented");
```

---

## `SchematicLoader`

Instance class. Owns readers, executor, and config reference.

```java
public final class SchematicLoader {
    private final List<SchematicReader> readers;
    private final ExecutorService ioExecutor;   // bounded, not commonPool
    private final FrameShiftConfig config;
}
```

**Constructor:** takes `FrameShiftConfig`. Initialises:
```java
this.readers = List.of(new SpongeSchematicReader(
    config.maxMetadataReadCompressedBytes.get(),
    config.maxSchematicVolume.get()
));
this.ioExecutor = Executors.newFixedThreadPool(config.metadataIoThreads.get());
```
`SpongeSchematicReader` receives only the two numeric limits it needs - it does not depend on the full config class.

### `shutdown()`
Called on `ServerStoppingEvent`. Calls `ioExecutor.shutdown()`.

### `findReader(Path file)`
Returns `Optional<SchematicReader>` - first reader where `supports(file)` is true.

### `readMetadataAsync(Path file)`
```java
public CompletableFuture<SchematicMetadata> readMetadataAsync(Path file)
```
Submits `reader.readMetadata(file)` to `ioExecutor`. Propagates `IOException` as `CompletionException`.

### `listAsync(Path serverRoot, int limit, @Nullable String cursor)`
```java
public CompletableFuture<SchematicListResult> listAsync(Path serverRoot, int limit, @Nullable String cursor)
```

- Resolves each entry in `config.schematicDirectories` relative to `serverRoot`
- **Shallow traversal only** - `Files.list(dir)` (one level), no recursion
- Skips directories that do not exist (no error)
- Skips files where `findReader` returns empty (`skipped++`)
- Calls `readMetadata` for each candidate; on failure logs warning and increments `failed`
- **Rate-limited logging:** logs first 5 failures verbosely, then summarises the rest as `"... N more files failed, see debug log"`
- Files within each directory are sorted alphabetically by filename stem before pagination
- Cursor is the filename stem of the last returned entry - `listAsync` resumes from the first entry strictly after that name (alphabetical, case-insensitive)
- Returns at most `limit` entries (bounded by `config.maxListResults`)
- Runs entirely on `ioExecutor`

---

## `FrameShiftConfig` additions

Three new fields must be added to `FrameShiftConfig.java` under a new `[metadata]` builder section:

```toml
[metadata]
maxMetadataReadCompressedBytes = 256000000  # 256 MB compressed-size guard on NbtIo reads
metadataIoThreads = 2                       # threads in the dedicated IO executor
maxListResults = 100                        # max entries returned per listAsync call
```

These are read at `SchematicLoader` construction time. Changes require a server restart (SERVER type config).

---

## `SchematicLoader` Lifecycle

- **Created:** in `FrameShift` constructor, stored as a private field
- **Exposed:** via `FrameShift.getLoader()` instance method (no static accessor)
- **Commands** receive the loader instance during registration:
  ```java
  NeoForge.EVENT_BUS.addListener(e -> SchemCommand.register(e, this.loader));
  ```
- **Shutdown:** `FrameShift` registers a `ServerStoppingEvent` listener that calls `loader.shutdown()`

---

## Command Structure

### `/schem list [cursor]`

- Permission: level 2 (OP)
- `cursor`: optional `StringArgumentType.word()` - filename stem of last seen entry
- Dispatches `loader.listAsync(serverRoot, maxListResults, cursor)` on IO executor
- Result returned to server thread via `.thenAcceptAsync(result -> { ... }, server::execute)`
- Errors returned to server thread via:
  ```java
  .exceptionally(error -> {
      server.execute(() -> sendError(source, unwrap(error)));
      return null;
  });
  ```
- Return values: `1` on success, `0` on failure
- Output format per entry: `name  WxHxL  filesize`
- Footer: if `nextCursor != null`, shows `"More results - use /schem list <cursor>"` with the cursor value
- Shows skipped/failed counts if non-zero

### `/schem info <name>`

- Permission: level 2 (OP)
- `<name>`: `StringArgumentType.greedyString()` - filename stem (no extension), searched across configured directories in order
- If name contains path separators, reject with a clear error
- Resolves to first matching file found across directories
- Dispatches `loader.readMetadataAsync(path)` on IO executor
- Result returned to server thread before sending output
- Errors returned to server thread (same pattern as list)
- Return values: `1` on success, `0` on failure
- Output fields: name, format, dimensions (`W x H x L`), volume, file size, data version, block entities, entities
- Fields with value `-1` display as `unknown`

---

## What Is Not In This Milestone

- Block data parsing (palette, block array)
- Entity or block entity restoration
- `/schem paste`, `/schem status`, `/schem cancel/pause/resume`
- Recursive directory scanning
- Tab-completion for schematic names (future)
- Undo, rotation, mirror

---

## Progress Tracking

This file lives in `docs/superpowers/specs/`. The implementation plan will be written to `docs/superpowers/plans/` and progress tracked in `docs/progress.md`.
