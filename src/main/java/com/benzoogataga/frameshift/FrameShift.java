package com.benzoogataga.frameshift;

import com.benzoogataga.frameshift.command.SchemCommand;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.schematic.SchematicLoader;
import com.benzoogataga.frameshift.tick.TickHandler;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

// Main mod entry point. Wires config, lifecycle hooks, and server-side systems.
@Mod(FrameShift.MOD_ID)
public class FrameShift {

    public static final String MOD_ID = "frameshift";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final SchematicLoader loader;

    public FrameShift(IEventBus modEventBus, ModContainer modContainer) {
        FrameShiftConfig.register(modContainer);
        this.loader = new SchematicLoader();

        NeoForge.EVENT_BUS.register(new TickHandler());
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        LOGGER.info("FrameShift loaded: Yet Another Schematics Manager");
        LOGGER.info("Schematic metadata loader is ready");
    }

    // Shuts down background metadata work when the server stops.
    private void onServerStopping(ServerStoppingEvent event) {
        loader.shutdown();
    }

    // Registers commands against the active loader instance.
    private void onRegisterCommands(RegisterCommandsEvent event) {
        SchemCommand.register(event, loader);
    }
}
