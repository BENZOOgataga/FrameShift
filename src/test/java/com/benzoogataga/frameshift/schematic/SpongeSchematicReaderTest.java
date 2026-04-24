package com.benzoogataga.frameshift.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpongeSchematicReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void invalidDimensionsAreRejected() throws Exception {
        Path file = tempDir.resolve("invalid.schem");
        writeCompressed(file, spongeV2(0, 1, 1, 3465));

        SpongeSchematicReader reader = new SpongeSchematicReader(1_000_000L, 50_000_000L);

        assertThrows(IOException.class, () -> reader.readMetadata(file));
    }

    @Test
    void unsupportedVersionIsRejected() throws Exception {
        Path file = tempDir.resolve("unsupported.schem");
        CompoundTag root = spongeV2(1, 1, 1, 3465);
        root.putInt("Version", 99);
        writeCompressed(file, root);

        SpongeSchematicReader reader = new SpongeSchematicReader(1_000_000L, 50_000_000L);

        assertThrows(IOException.class, () -> reader.readMetadata(file));
    }

    @Test
    void fileSizeGuardWorks() throws Exception {
        Path file = tempDir.resolve("large.schem");
        writeCompressed(file, spongeV2(1, 1, 1, 3465));
        long fileSize = Files.size(file);

        SpongeSchematicReader reader = new SpongeSchematicReader(fileSize - 1L, 50_000_000L);

        assertThrows(IOException.class, () -> reader.readMetadata(file));
    }

    @Test
    void schemSupportDetectionWorks() {
        SpongeSchematicReader reader = new SpongeSchematicReader(1_000_000L, 50_000_000L);

        assertTrue(reader.supports(Path.of("castle.schem")));
        assertFalse(reader.supports(Path.of("castle.schematic")));
    }

    @Test
    void v3MetadataUsesVerifiedSpongeKeys() throws Exception {
        Path file = tempDir.resolve("v3.schem");
        writeCompressed(file, spongeV3(4, 5, 6, 3953, 2, 1));

        SpongeSchematicReader reader = new SpongeSchematicReader(1_000_000L, 50_000_000L);
        SchematicMetadata metadata = reader.readMetadata(file);

        assertEquals(SchematicFormat.SPONGE_V3, metadata.format);
        assertEquals(4, metadata.sizeX);
        assertEquals(5, metadata.sizeY);
        assertEquals(6, metadata.sizeZ);
        assertEquals(2, metadata.blockEntityCount);
        assertEquals(1, metadata.entityCount);
    }

    @Test
    void v2BlockStreamUsesSpecifiedCoordinateOrder() throws Exception {
        Path file = tempDir.resolve("stream-v2.schem");
        writeCompressed(file, spongeV2WithBlocks(
            2,
            1,
            2,
            3465,
            List.of(1, 0, 2, 1)
        ));

        SpongeSchematicReader reader = new SpongeSchematicReader(1_000_000L, 50_000_000L);
        try (BlockStream stream = reader.openBlockStream(file, new SchematicReadOptions(false, true, false))) {
            List<SchematicBlockEntry> entries = readAll(stream);

            assertEquals(4, entries.size());
            assertEquals(0, entries.get(0).x);
            assertEquals(0, entries.get(0).y);
            assertEquals(0, entries.get(0).z);
            assertEquals(1, entries.get(0).paletteId);

            assertEquals(1, entries.get(1).x);
            assertEquals(0, entries.get(1).y);
            assertEquals(0, entries.get(1).z);
            assertEquals(0, entries.get(1).paletteId);

            assertEquals(0, entries.get(2).x);
            assertEquals(0, entries.get(2).y);
            assertEquals(1, entries.get(2).z);
            assertEquals(2, entries.get(2).paletteId);
        }
    }

    @Test
    void blockStreamCanSkipAirAndAttachBlockEntities() throws Exception {
        Path file = tempDir.resolve("stream-v3.schem");
        writeCompressed(file, spongeV3WithBlocks(
            2,
            1,
            2,
            3953,
            List.of(0, 1, 2, 0)
        ));

        SpongeSchematicReader reader = new SpongeSchematicReader(1_000_000L, 50_000_000L);
        try (BlockStream stream = reader.openBlockStream(file, new SchematicReadOptions(true, true, false))) {
            List<SchematicBlockEntry> entries = readAll(stream);

            assertEquals(2, entries.size());
            assertEquals(1, entries.get(0).x);
            assertEquals(0, entries.get(0).z);
            assertEquals(1, entries.get(0).paletteId);
            assertEquals("test:crate", entries.get(0).blockEntityNbt.getString("Id"));

            assertEquals(0, entries.get(1).x);
            assertEquals(1, entries.get(1).z);
            assertEquals(2, entries.get(1).paletteId);
            assertNull(entries.get(1).blockEntityNbt);
        }
    }

    @Test
    void blockStreamCanOmitBlockEntities() throws Exception {
        Path file = tempDir.resolve("stream-v3-no-be.schem");
        writeCompressed(file, spongeV3WithBlocks(
            2,
            1,
            2,
            3953,
            List.of(0, 1, 2, 0)
        ));

        SpongeSchematicReader reader = new SpongeSchematicReader(1_000_000L, 50_000_000L);
        try (BlockStream stream = reader.openBlockStream(file, new SchematicReadOptions(true, false, false))) {
            List<SchematicBlockEntry> entries = readAll(stream);

            assertEquals(2, entries.size());
            assertNull(entries.get(0).blockEntityNbt);
        }
    }

    @Test
    void blockStreamAppliesSchematicOffset() throws Exception {
        Path file = tempDir.resolve("stream-v3-offset.schem");
        writeCompressed(file, spongeV3WithOffsetAndBlocks(
            1,
            1,
            1,
            3953,
            List.of(1),
            new int[] { -10, 5, 20 }
        ));

        SpongeSchematicReader reader = new SpongeSchematicReader(1_000_000L, 50_000_000L);
        try (BlockStream stream = reader.openBlockStream(file, new SchematicReadOptions(false, true, false))) {
            List<SchematicBlockEntry> entries = readAll(stream);

            assertEquals(1, entries.size());
            assertEquals(-10, entries.get(0).x);
            assertEquals(5, entries.get(0).y);
            assertEquals(20, entries.get(0).z);
        }
    }

    private static CompoundTag spongeV2(int width, int height, int length, int dataVersion) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", dataVersion);
        root.putShort("Width", (short) width);
        root.putShort("Height", (short) height);
        root.putShort("Length", (short) length);
        root.putInt("PaletteMax", 3);
        root.put("Palette", palette());
        root.putByteArray("BlockData", encodeVarInts(List.of(0)));
        root.put("BlockEntities", new ListTag());
        root.put("Entities", new ListTag());
        return root;
    }

    private static CompoundTag spongeV2WithBlocks(int width, int height, int length, int dataVersion, List<Integer> paletteIds) {
        CompoundTag root = spongeV2(width, height, length, dataVersion);
        root.putByteArray("BlockData", encodeVarInts(paletteIds));
        return root;
    }

    private static CompoundTag spongeV3(int width, int height, int length, int dataVersion, int blockEntities, int entities) {
        CompoundTag root = new CompoundTag();
        CompoundTag schematic = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        ListTag blockEntityList = new ListTag();
        ListTag entityList = new ListTag();

        for (int index = 0; index < blockEntities; index++) {
            blockEntityList.add(new CompoundTag());
        }
        for (int index = 0; index < entities; index++) {
            entityList.add(new CompoundTag());
        }

        blocks.put("BlockEntities", blockEntityList);
        blocks.put("Palette", palette());
        blocks.putByteArray("Data", encodeVarInts(List.of(0)));
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", dataVersion);
        schematic.putShort("Width", (short) width);
        schematic.putShort("Height", (short) height);
        schematic.putShort("Length", (short) length);
        schematic.put("Blocks", blocks);
        schematic.put("Entities", entityList);
        root.put("Schematic", schematic);
        return root;
    }

    private static CompoundTag spongeV3WithBlocks(int width, int height, int length, int dataVersion, List<Integer> paletteIds) {
        CompoundTag root = new CompoundTag();
        CompoundTag schematic = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        ListTag blockEntities = new ListTag();
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Pos", new IntArrayTag(new int[] { 1, 0, 0 }));
        blockEntity.putString("Id", "test:crate");
        blockEntity.put("Data", new CompoundTag());
        blockEntities.add(blockEntity);

        blocks.put("Palette", palette());
        blocks.putByteArray("Data", encodeVarInts(paletteIds));
        blocks.put("BlockEntities", blockEntities);
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", dataVersion);
        schematic.putShort("Width", (short) width);
        schematic.putShort("Height", (short) height);
        schematic.putShort("Length", (short) length);
        schematic.put("Blocks", blocks);
        schematic.put("Entities", new ListTag());
        root.put("Schematic", schematic);
        return root;
    }

    private static CompoundTag spongeV3WithOffsetAndBlocks(
        int width,
        int height,
        int length,
        int dataVersion,
        List<Integer> paletteIds,
        int[] offset
    ) {
        CompoundTag root = spongeV3WithBlocks(width, height, length, dataVersion, paletteIds);
        CompoundTag schematic = root.getCompound("Schematic");
        schematic.putIntArray("Offset", offset);
        root.put("Schematic", schematic);
        return root;
    }

    private static CompoundTag palette() {
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone", 1);
        palette.putInt("minecraft:oak_planks", 2);
        return palette;
    }

    private static byte[] encodeVarInts(List<Integer> values) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (Integer value : values) {
            int current = value;
            while ((current & -128) != 0) {
                stream.write(current & 127 | 128);
                current >>>= 7;
            }
            stream.write(current);
        }
        return stream.toByteArray();
    }

    private static void writeCompressed(Path file, CompoundTag tag) throws IOException {
        NbtIo.writeCompressed(tag, file);
    }

    private static List<SchematicBlockEntry> readAll(BlockStream stream) throws IOException {
        List<SchematicBlockEntry> entries = new ArrayList<>();
        while (stream.hasNext()) {
            entries.add(stream.next());
        }
        return entries;
    }
}
