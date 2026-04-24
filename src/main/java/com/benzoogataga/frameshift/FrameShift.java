package com.benzoogataga.frameshift;

import com.benzoogataga.frameshift.command.SchemCommand;
import com.benzoogataga.frameshift.config.FrameShiftConfig;
import com.benzoogataga.frameshift.tick.TickHandler;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

// @Mod tells NeoForge this is the main class of our mod.
// NeoForge will call the constructor below when the game loads.
@Mod(FrameShift.MOD_ID)
public class FrameShift {

    public static final String MOD_ID = "frameshift";
    public static final Logger LOGGER = LogUtils.getLogger();

    // NeoForge injects these two objects automatically via the constructor parameters.
    // modEventBus = events fired during mod loading (config, registry, etc.)
    // modContainer = our mod's handle, used to register config files
    public FrameShift(IEventBus modEventBus, ModContainer modContainer) {
        // Register our config file (creates frameshift-server.toml in the config folder)
        FrameShiftConfig.register(modContainer);

        // Register the tick handler so it receives a callback every server tick
        NeoForge.EVENT_BUS.register(new TickHandler());

        // Register the /schem command when the server finishes loading commands
        NeoForge.EVENT_BUS.addListener(SchemCommand::register);

        LOGGER.info("FrameShift loaded — Yet Another Schematics Manager");
    }
}
