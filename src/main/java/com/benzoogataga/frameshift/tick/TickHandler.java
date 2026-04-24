package com.benzoogataga.frameshift.tick;

import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.job.JobManager;
import com.benzoogataga.frameshift.job.SchematicPasteJob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// Called by NeoForge at the end of every server tick.
// Responsible for placing a small batch of blocks per tick so the server never freezes.
public class TickHandler {

    // NeoForge calls this method automatically after every server tick completes.
    // "Post" means the rest of the tick has already finished, so it is safe to modify the world.
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        long tickStart = System.currentTimeMillis();

        for (SchematicPasteJob job : JobManager.all()) {
            if (job.state != SchematicPasteJob.State.RUNNING) {
                continue;
            }

            // Check if we have already used up our time budget for this tick.
            long elapsed = System.currentTimeMillis() - tickStart;
            if (elapsed >= FrameShiftConfig.maxMillisPerTick.get()) {
                break;
            }

            // TODO: implement adaptive throttling based on current server MSPT
            // TODO: drain blockQueue, beQueue, entityQueue up to their per-tick limits
            // TODO: pause job automatically if a required chunk is unloaded
        }

        // Periodically remove finished jobs so they do not accumulate in memory.
        JobManager.cleanup();
    }
}
