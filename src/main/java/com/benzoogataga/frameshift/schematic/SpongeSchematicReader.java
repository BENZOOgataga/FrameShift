package com.benzoogataga.frameshift.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
            if (version == 2) {
                format = SchematicFormat.SPONGE_V2;
            } else {
                throw new IOException("Unsupported Sponge version: " + version);
            }
        }

        int sizeX = requireUnsignedShort(schematic, "Width");
        int sizeY = requireUnsignedShort(schematic, "Height");
        int sizeZ = requireUnsignedShort(schematic, "Length");
        validateDimensions(sizeX, sizeY, sizeZ);

        int dataVersion = schematic.contains("DataVersion", Tag.TAG_INT) ? schematic.getInt("DataVersion") : -1;
        int entityCount = listSize(schematic, "Entities");
        int blockEntityCount = format == SchematicFormat.SPONGE_V3
            ? listSize(schematic.getCompound("Blocks"), "BlockEntities")
            : listSize(schematic, "BlockEntities");

        return new SchematicMetadata(
            stemOf(file.getFileName().toString()),
            file.toAbsolutePath().normalize(),
            format,
            sizeX,
            sizeY,
            sizeZ,
            fileSize,
            dataVersion,
            -1L,
            blockEntityCount,
            entityCount
        );
    }

    @Override
    public BlockStream openBlockStream(Path file, SchematicReadOptions options) {
        throw new UnsupportedOperationException("Block streaming is not implemented yet");
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
}
