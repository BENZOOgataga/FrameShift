package com.benzoogataga.frameshift.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

// Holds one resolved block placement ready for the server thread to apply.
public final class PreparedBlockPlacement {

    public final BlockPos relativePos;
    public final BlockState state;
    @Nullable
    public final CompoundTag blockEntityTag;

    public PreparedBlockPlacement(BlockPos relativePos, BlockState state, @Nullable CompoundTag blockEntityTag) {
        this.relativePos = relativePos;
        this.state = state;
        this.blockEntityTag = blockEntityTag;
    }
}
