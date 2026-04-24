package com.benzoogataga.frameshift.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;

// Utility methods for chunk-related checks and preloading.
// TickHandler and the paste command use these before touching any block positions.
public class ChunkHelper {

    // Returns true if the chunk containing 'pos' is currently loaded in the world.
    // Always call this before placing a block — writing to an unloaded chunk can corrupt the world.
    public static boolean isLoaded(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos);
    }

    // Returns the chunk X coordinate for a block position
    public static int chunkX(BlockPos pos) {
        return SectionPos.blockToSectionCoord(pos.getX());
    }

    // Returns the chunk Z coordinate for a block position
    public static int chunkZ(BlockPos pos) {
        return SectionPos.blockToSectionCoord(pos.getZ());
    }

    // Ensures all chunks within 'radius' chunks of 'origin' are loaded before the paste starts.
    // If forceLoadChunks is false this only loads chunks that are naturally in range; it won't
    // prevent the server from unloading them during the paste.
    public static void preloadChunks(ServerLevel level, BlockPos origin, int radius) {
        // TODO: iterate chunk positions in the square around origin and ticket or force-load them
    }

    // Release any chunk tickets that were added by preloadChunks for this origin
    public static void releaseChunks(ServerLevel level, BlockPos origin, int radius) {
        // TODO: remove tickets added in preloadChunks
    }
}
