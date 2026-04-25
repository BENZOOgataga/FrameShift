package com.benzoogataga.frameshift.job;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RollbackStoreTest {

    @Test
    void rollbackOrderIsTopToBottomAcrossLayers() {
        List<SchematicPasteJob.RollbackTask> ordered = RollbackStore.orderRollbackTasks(List.of(
            createTask(4, 64, 4, 10L),
            createTask(8, 66, 8, 30L),
            createTask(6, 65, 6, 20L)
        ));

        assertEquals(66, ordered.get(0).worldPos.getY());
        assertEquals(65, ordered.get(1).worldPos.getY());
        assertEquals(64, ordered.get(2).worldPos.getY());
    }

    // Creates one rollback snapshot task for order testing.
    private static SchematicPasteJob.RollbackTask createTask(int x, int y, int z, long sequence) {
        return new SchematicPasteJob.RollbackTask(
            new BlockPos(x, y, z),
            null,
            null,
            false,
            null,
            null,
            false,
            sequence
        );
    }
}
