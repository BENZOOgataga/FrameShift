package com.benzoogataga.frameshift.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Reads read-only metadata from Sponge .schem files without parsing block payloads.
public class SpongeSchematicReader implements SchematicReader {

    private static final String ROOT_SCHEMATIC = "Schematic";

    private final long maxCompressedBytes;
    private final long maxVolume;

    public SpongeSchematicReader(long maxCompressedBytes, long maxVolume) {
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxVolume = maxVolume;
    }

    @Override
    public boolean supports(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return fileName.endsWith(".schem");
    }

    @Override
    public SchematicMetadata readMetadata(Path file) throws IOException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("Schematic file is not readable: " + file);
        }

        long fileSize = Files.size(file);
        if (fileSize > maxCompressedBytes) {
            throw new IOException("Schematic file exceeds metadata read limit: " + file.getFileName());
        }

        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        if (root == null) {
            throw new IOException("Schematic file is empty: " + file.getFileName());
        }

        ParsedSchematic parsed = parseStructure(root, file, fileSize, true, false);
        long nonAirBlocks = countNonAirBlocks(parsed.data, parsed.airPaletteIds, parsed.volume());

        return new SchematicMetadata(
            stemOf(file.getFileName().toString()),
            file.toAbsolutePath().normalize(),
            parsed.format,
            parsed.sizeX,
            parsed.sizeY,
            parsed.sizeZ,
            parsed.offsetX,
            parsed.offsetY,
            parsed.offsetZ,
            fileSize,
            parsed.dataVersion,
            nonAirBlocks,
            parsed.blockEntityCount,
            parsed.entityCount
        );
    }

    @Override
    public BlockStream openBlockStream(Path file, SchematicReadOptions options) throws IOException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("Schematic file is not readable: " + file);
        }

        long fileSize = Files.size(file);
        if (fileSize > maxCompressedBytes) {
            throw new IOException("Schematic file exceeds metadata read limit: " + file.getFileName());
        }

        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        if (root == null) {
            throw new IOException("Schematic file is empty: " + file.getFileName());
        }

        ParsedSchematic parsed = parseStructure(root, file, fileSize, options.includeBlockEntities, false);
        return new SpongeBlockStream(parsed, options);
    }

    @Override
    public List<CompoundTag> readEntities(Path file, SchematicReadOptions options) throws IOException {
        if (!options.includeEntities) {
            return List.of();
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("Schematic file is not readable: " + file);
        }

        long fileSize = Files.size(file);
        if (fileSize > maxCompressedBytes) {
            throw new IOException("Schematic file exceeds metadata read limit: " + file.getFileName());
        }

        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        if (root == null) {
            throw new IOException("Schematic file is empty: " + file.getFileName());
        }

        ParsedSchematic parsed = parseStructure(root, file, fileSize, false, true);
        return parsed.entities;
    }

    // Reads a required int field and fails with a clear message when missing.
    private static int requireInt(CompoundTag tag, String key) throws IOException {
        if (!tag.contains(key, Tag.TAG_INT)) {
            throw new IOException("Missing required int tag: " + key);
        }
        return tag.getInt(key);
    }

    // Sponge stores dimensions as unsigned short values in NBT short tags.
    private static int requireUnsignedShort(CompoundTag tag, String key) throws IOException {
        if (!tag.contains(key, Tag.TAG_SHORT)) {
            throw new IOException("Missing required short tag: " + key);
        }
        return tag.getShort(key) & 0xFFFF;
    }

    // Applies the volume and positivity rules before any caller uses the dimensions.
    private void validateDimensions(int sizeX, int sizeY, int sizeZ) throws IOException {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IOException("Invalid schematic dimensions: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }

        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > maxVolume) {
            throw new IOException("Schematic volume exceeds configured limit: " + volume);
        }
    }

    // Treats missing lists as empty because metadata inspection should stay permissive here.
    private static int listSize(CompoundTag tag, String key) {
        if (tag == null || !tag.contains(key, Tag.TAG_LIST)) {
            return 0;
        }
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        return list.size();
    }

    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private ParsedSchematic parseStructure(
        CompoundTag root,
        Path file,
        long fileSize,
        boolean includeBlockEntities,
        boolean includeEntities
    ) throws IOException {
        CompoundTag schematic = root;
        SchematicFormat format;
        int version;

        if (root.contains(ROOT_SCHEMATIC, Tag.TAG_COMPOUND)) {
            schematic = root.getCompound(ROOT_SCHEMATIC);
            version = requireInt(schematic, "Version");
            if (version != 3) {
                throw new IOException("Unsupported Sponge version: " + version);
            }
            format = SchematicFormat.SPONGE_V3;
        } else {
            version = requireInt(root, "Version");
            if (version != 2) {
                throw new IOException("Unsupported Sponge version: " + version);
            }
            format = SchematicFormat.SPONGE_V2;
        }

        int sizeX = requireUnsignedShort(schematic, "Width");
        int sizeY = requireUnsignedShort(schematic, "Height");
        int sizeZ = requireUnsignedShort(schematic, "Length");
        validateDimensions(sizeX, sizeY, sizeZ);
        int dataVersion = schematic.contains("DataVersion", Tag.TAG_INT) ? schematic.getInt("DataVersion") : -1;
        int entityCount = listSize(schematic, "Entities");
        int[] offset = resolveOffset(schematic);

        CompoundTag blockContainer = format == SchematicFormat.SPONGE_V3 ? requireCompound(schematic, "Blocks") : schematic;
        CompoundTag palette = requireCompound(blockContainer, "Palette");
        byte[] data = requireByteArray(blockContainer, format == SchematicFormat.SPONGE_V3 ? "Data" : "BlockData");
        ListTag blockEntityList = format == SchematicFormat.SPONGE_V3
            ? blockContainer.getList("BlockEntities", Tag.TAG_COMPOUND)
            : schematic.getList("BlockEntities", Tag.TAG_COMPOUND);
        ListTag entityList = schematic.getList("Entities", Tag.TAG_COMPOUND);
        Map<Integer, String> paletteById = invertPalette(palette);
        Map<Long, CompoundTag> blockEntities = readBlockEntities(
            blockEntityList,
            includeBlockEntities
        );
        List<CompoundTag> entities = readEntities(entityList, includeEntities);
        int[] airPaletteIds = resolveAirPaletteIds(palette);

        return new ParsedSchematic(
            file.toAbsolutePath().normalize(),
            format,
            fileSize,
            sizeX,
            sizeY,
            sizeZ,
            dataVersion,
            entityCount,
            blockEntityList.size(),
            offset[0],
            offset[1],
            offset[2],
            data,
            paletteById,
            blockEntities,
            entities,
            airPaletteIds
        );
    }

    private static CompoundTag requireCompound(CompoundTag tag, String key) throws IOException {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            throw new IOException("Missing required compound tag: " + key);
        }
        return tag.getCompound(key);
    }

    private static byte[] requireByteArray(CompoundTag tag, String key) throws IOException {
        if (!tag.contains(key, Tag.TAG_BYTE_ARRAY)) {
            throw new IOException("Missing required byte array tag: " + key);
        }
        return tag.getByteArray(key);
    }

    // Resolves schematic origin offset from Sponge Offset or WorldEdit Metadata fields.
    private static int[] resolveOffset(CompoundTag schematic) {
        if (schematic.contains("Offset", Tag.TAG_INT_ARRAY)) {
            int[] values = schematic.getIntArray("Offset");
            if (values.length >= 3) {
                return new int[] { values[0], values[1], values[2] };
            }
        }

        if (schematic.contains("Metadata", Tag.TAG_COMPOUND)) {
            CompoundTag metadata = schematic.getCompound("Metadata");
            int x = metadata.contains("WEOffsetX", Tag.TAG_INT) ? metadata.getInt("WEOffsetX") : 0;
            int y = metadata.contains("WEOffsetY", Tag.TAG_INT) ? metadata.getInt("WEOffsetY") : 0;
            int z = metadata.contains("WEOffsetZ", Tag.TAG_INT) ? metadata.getInt("WEOffsetZ") : 0;
            return new int[] { x, y, z };
        }

        return new int[] { 0, 0, 0 };
    }

    private static Map<Long, CompoundTag> readBlockEntities(ListTag list, boolean includeBlockEntities) {
        if (!includeBlockEntities) {
            return Map.of();
        }

        Map<Long, CompoundTag> byIndex = new HashMap<>();
        for (Tag element : list) {
            if (!(element instanceof CompoundTag blockEntity)) {
                continue;
            }
            long index = relativeIndex(blockEntity);
            byIndex.put(index, blockEntity.copy());
        }
        return byIndex;
    }

    private static List<CompoundTag> readEntities(ListTag list, boolean includeEntities) {
        if (!includeEntities) {
            return List.of();
        }

        List<CompoundTag> entities = new ArrayList<>();
        for (Tag element : list) {
            if (element instanceof CompoundTag entity) {
                entities.add(entity.copy());
            }
        }
        return entities;
    }

    private static long relativeIndex(CompoundTag blockEntity) {
        int[] pos = blockEntity.getIntArray("Pos");
        if (pos.length == 3) {
            return packPosition(pos[0], pos[1], pos[2]);
        }
        return Long.MIN_VALUE;
    }

    // Packs x/y/z into a long using 16 bits per axis, supporting Sponge's full unsigned-short range (0-65534).
    private static long packPosition(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (y & 0xFFFF) << 16) | (z & 0xFFFFL);
    }

    private static int[] resolveAirPaletteIds(CompoundTag palette) {
        ArrayDeque<Integer> ids = new ArrayDeque<>();
        for (String key : palette.getAllKeys()) {
            String blockId = key.contains("[") ? key.substring(0, key.indexOf('[')) : key;
            if (blockId.equals("minecraft:air") || blockId.equals("air") || blockId.equals("minecraft:cave_air") || blockId.equals("minecraft:void_air")) {
                ids.add(palette.getInt(key));
            }
        }
        int[] result = new int[ids.size()];
        int index = 0;
        for (Integer id : ids) {
            result[index++] = id;
        }
        return result;
    }

    private static Map<Integer, String> invertPalette(CompoundTag palette) {
        Map<Integer, String> byId = new HashMap<>();
        for (String key : palette.getAllKeys()) {
            byId.put(palette.getInt(key), key);
        }
        return byId;
    }

    private static long countNonAirBlocks(byte[] data, int[] airPaletteIds, long volume) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(data)) {
            long nonAirBlocks = 0L;
            for (long cursor = 0; cursor < volume; cursor++) {
                int paletteId = SpongeBlockStream.readVarInt(stream);
                if (!SpongeBlockStream.isAirPaletteId(paletteId, airPaletteIds)) {
                    nonAirBlocks++;
                }
            }
            return nonAirBlocks;
        }
    }

    private static final class SpongeBlockStream implements BlockStream {
        private final ParsedSchematic parsed;
        private final SchematicReadOptions options;
        private final InputStream data;
        private long cursor;
        @Nullable
        private SchematicBlockEntry nextEntry;

        private SpongeBlockStream(ParsedSchematic parsed, SchematicReadOptions options) {
            this.parsed = parsed;
            this.options = options;
            this.data = new ByteArrayInputStream(parsed.data);
        }

        @Override
        public boolean hasNext() throws IOException {
            if (nextEntry != null) {
                return true;
            }

            while (cursor < parsed.volume()) {
                int paletteId = readVarInt(data);
                long plane = (long) parsed.sizeX * parsed.sizeZ;
                int localX = (int) (cursor % parsed.sizeX);
                int localZ = (int) ((cursor / parsed.sizeX) % parsed.sizeZ);
                int localY = (int) (cursor / plane);
                cursor++;

                if (options.ignoreAir && isAirPaletteId(paletteId, parsed.airPaletteIds)) {
                    continue;
                }

                CompoundTag blockEntity = options.includeBlockEntities
                    ? parsed.blockEntities.get(packPosition(localX, localY, localZ))
                    : null;
                nextEntry = new SchematicBlockEntry(
                    localX + parsed.offsetX,
                    localY + parsed.offsetY,
                    localZ + parsed.offsetZ,
                    paletteId,
                    parsed.paletteById.getOrDefault(paletteId, "minecraft:air"),
                    blockEntity == null ? null : blockEntity.copy()
                );
                return true;
            }

            return false;
        }

        @Override
        public SchematicBlockEntry next() throws IOException {
            if (!hasNext()) {
                throw new IOException("No more block entries are available");
            }
            SchematicBlockEntry current = nextEntry;
            nextEntry = null;
            return current;
        }

        @Override
        public void close() {
            try {
                data.close();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static boolean isAirPaletteId(int paletteId, int[] airPaletteIds) {
            for (int airPaletteId : airPaletteIds) {
                if (airPaletteId == paletteId) {
                    return true;
                }
            }
            return false;
        }

        private static int readVarInt(InputStream stream) throws IOException {
            int value = 0;
            int position = 0;

            while (true) {
                int current = stream.read();
                if (current == -1) {
                    throw new IOException("Unexpected end of schematic block data");
                }

                value |= (current & 0x7F) << position;
                if ((current & 0x80) == 0) {
                    return value;
                }

                position += 7;
                if (position >= 35) {
                    throw new IOException("Block data varint is too large");
                }
            }
        }
    }

    private record ParsedSchematic(
        Path file,
        SchematicFormat format,
        long fileSize,
        int sizeX,
        int sizeY,
        int sizeZ,
        int dataVersion,
        int entityCount,
        int blockEntityCount,
        int offsetX,
        int offsetY,
        int offsetZ,
        byte[] data,
        Map<Integer, String> paletteById,
        Map<Long, CompoundTag> blockEntities,
        List<CompoundTag> entities,
        int[] airPaletteIds
    ) {
        private long volume() {
            return (long) sizeX * sizeY * sizeZ;
        }
    }
}
