package com.benzoogataga.frameshift.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static CompoundTag spongeV2(int width, int height, int length, int dataVersion) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", dataVersion);
        root.putShort("Width", (short) width);
        root.putShort("Height", (short) height);
        root.putShort("Length", (short) length);
        root.put("BlockEntities", new ListTag());
        root.put("Entities", new ListTag());
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

    private static void writeCompressed(Path file, CompoundTag tag) throws IOException {
        NbtIo.writeCompressed(tag, file);
    }
}
