package com.benzoogataga.frameshift.schematic;

import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

// Defines the minimum contract every schematic format reader must implement.
public interface SchematicReader {

    boolean supports(Path file);

    SchematicMetadata readMetadata(Path file) throws IOException;

    BlockStream openBlockStream(Path file, SchematicReadOptions options) throws IOException;

    default List<CompoundTag> readEntities(Path file, SchematicReadOptions options) throws IOException {
        return List.of();
    }
}
