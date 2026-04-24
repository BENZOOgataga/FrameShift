package com.benzoogataga.frameshift.job;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.UUID;

// Represents one paste operation — from the moment it's submitted until it finishes or is cancelled.
// TickHandler drains the three queues a little each tick so the server never freezes.
public class SchematicPasteJob {

    // All possible states a job can be in
    public enum State {
        LOADING,    // schematic file is being read from disk (async)
        RUNNING,    // blocks are being placed tick by tick
        PAUSED,     // operator ran /schem pause, or server is under load
        CANCELLED,  // operator ran /schem cancel
        DONE        // all blocks placed successfully
    }

    // Unique ID so operators can target this specific job with /schem cancel <jobId>
    public final UUID jobId;

    // Name of the schematic file (without extension) for display purposes
    public final String schematicName;

    // The world dimension this paste is happening in
    public final ServerLevel level;

    // The block coordinate where the schematic's origin (0,0,0) will be placed
    public final BlockPos origin;

    // Current lifecycle state — volatile so the tick thread and command thread both see updates
    public volatile State state = State.LOADING;

    // Placement queues — TickHandler drains these in order each tick:
    //   1. Place all blocks first
    //   2. Then restore block entities (chest contents, sign text, etc.)
    //   3. Then spawn entities (mobs, item frames, etc.)
    public final ArrayDeque<BlockPos> blockQueue      = new ArrayDeque<>();
    public final ArrayDeque<CompoundTag> beQueue      = new ArrayDeque<>();
    public final ArrayDeque<CompoundTag> entityQueue  = new ArrayDeque<>();

    // Running totals for /schem status output
    public int blocksPlaced      = 0;
    public int blockEntitiesPlaced = 0;
    public int entitiesPlaced    = 0;

    public SchematicPasteJob(String schematicName, ServerLevel level, BlockPos origin) {
        this.jobId = UUID.randomUUID();
        this.schematicName = schematicName;
        this.level = level;
        this.origin = origin;
    }

    // Total blocks remaining across all three queues
    public int remaining() {
        return blockQueue.size() + beQueue.size() + entityQueue.size();
    }
}
