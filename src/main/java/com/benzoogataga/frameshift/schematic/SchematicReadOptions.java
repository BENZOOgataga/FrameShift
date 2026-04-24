package com.benzoogataga.frameshift.schematic;

// Carries future block-stream flags without tying the reader API to paste logic yet.
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
