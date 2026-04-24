package com.benzoogataga.frameshift.command;

import net.neoforged.neoforge.event.RegisterCommandsEvent;

// This class registers the /schem command tree with the server.
// NeoForge calls register() automatically once the server is ready to accept commands.
public class SchemCommand {

    // Entry point — NeoForge fires RegisterCommandsEvent when the server builds its command list.
    // We add /schem and all its subcommands here.
    public static void register(RegisterCommandsEvent event) {
        // TODO: build the /schem command tree using Brigadier
        // Subcommands to implement: list, info, paste, status, cancel, pause, resume, reload
    }
}
