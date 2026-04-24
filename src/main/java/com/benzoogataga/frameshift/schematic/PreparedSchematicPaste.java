package com.benzoogataga.frameshift.schematic;

import java.util.List;

// Groups resolved placements and counts for one prepared schematic paste.
public final class PreparedSchematicPaste {

    public final SchematicMetadata metadata;
    public final List<PreparedBlockPlacement> blocks;
    public final int skippedInvalidBlocks;
    public final List<String> invalidPaletteStates;

    public PreparedSchematicPaste(
        SchematicMetadata metadata,
        List<PreparedBlockPlacement> blocks,
        int skippedInvalidBlocks,
        List<String> invalidPaletteStates
    ) {
        this.metadata = metadata;
        this.blocks = blocks;
        this.skippedInvalidBlocks = skippedInvalidBlocks;
        this.invalidPaletteStates = invalidPaletteStates;
    }
}
