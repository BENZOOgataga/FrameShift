package com.benzoogataga.frameshift.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

// Holds everything read from a .schem or .schematic file after parsing.
// SchematicLoader produces one of these; SchematicPasteJob consumes it.
public class SchematicData {

    // Dimensions of the schematic in blocks
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;

    // All block states in the schematic, ordered by position (X * sizeY * sizeZ + Y * sizeZ + Z)
    public final List<BlockState> blocks;

    // NBT data for block entities (chests, signs, spawners, etc.) — may be empty
    public final List<CompoundTag> blockEntities;

    // NBT data for entities (mobs, item frames, etc.) — may be empty
    public final List<CompoundTag> entities;

    public SchematicData(int sizeX, int sizeY, int sizeZ,
                         List<BlockState> blocks,
                         List<CompoundTag> blockEntities,
                         List<CompoundTag> entities) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = blocks;
        this.blockEntities = blockEntities;
        this.entities = entities;
    }

    // Total number of positions in the schematic (including air)
    public long volume() {
        return (long) sizeX * sizeY * sizeZ;
    }

    // Number of non-air blocks
    public long nonAirCount() {
        return blocks.stream().filter(b -> !b.isAir()).count();
    }
}
