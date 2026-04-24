package com.benzoogataga.frameshift.schematic;

import java.nio.file.Path;

// Holds read-only information about a schematic file without loading block data.
public final class SchematicMetadata {

    public final String name;
    public final Path file;
    public final SchematicFormat format;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    // Paste-origin offset within the bounding box (from Sponge Offset or WEOffset fields).
    public final int offsetX;
    public final int offsetY;
    public final int offsetZ;
    public final long fileSize;
    public final int dataVersion;
    public final long nonAirBlocks;
    public final int blockEntityCount;
    public final int entityCount;

    public SchematicMetadata(
        String name,
        Path file,
        SchematicFormat format,
        int sizeX,
        int sizeY,
        int sizeZ,
        int offsetX,
        int offsetY,
        int offsetZ,
        long fileSize,
        int dataVersion,
        long nonAirBlocks,
        int blockEntityCount,
        int entityCount
    ) {
        this.name = name;
        this.file = file;
        this.format = format;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.fileSize = fileSize;
        this.dataVersion = dataVersion;
        this.nonAirBlocks = nonAirBlocks;
        this.blockEntityCount = blockEntityCount;
        this.entityCount = entityCount;
    }

    // Uses long math so large schematics do not overflow during multiplication.
    public long volume() {
        return (long) sizeX * sizeY * sizeZ;
    }
}
