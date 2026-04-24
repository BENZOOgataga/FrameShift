package com.benzoogataga.frameshift.schematic;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

// Represents one block entry that a future streaming paste path can consume.
public final class SchematicBlockEntry {

    public final int x;
    public final int y;
    public final int z;
    public final int paletteId;
    @Nullable
    public final CompoundTag blockEntityNbt;

    public SchematicBlockEntry(int x, int y, int z, int paletteId, @Nullable CompoundTag blockEntityNbt) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.paletteId = paletteId;
        this.blockEntityNbt = blockEntityNbt;
    }
}
