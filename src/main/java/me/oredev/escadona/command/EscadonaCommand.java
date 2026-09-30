package me.oredev.escadona.command;

import com.mojang.brigadier.CommandDispatcher;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class EscadonaCommand {
    private EscadonaCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ServerRuntimeRegistry runtimes) {
        dispatcher.register(Commands.literal("escadona")
                .then(ArenaCommand.node(runtimes).requires(source -> source.hasPermission(2)))
                .then(MatchCommand.joinNode(runtimes))
                .then(MatchCommand.leaveNode(runtimes))
                .then(MatchCommand.startNode(runtimes).requires(source -> source.hasPermission(2)))
                .then(MatchCommand.stopNode(runtimes).requires(source -> source.hasPermission(2)))
                .then(MatchCommand.statusNode(runtimes))
                .then(BuildCommand.node(runtimes).requires(source -> source.hasPermission(2)))
                .then(WorldCommand.node(runtimes).requires(source -> source.hasPermission(2)))
                .then(MainLobbyCommand.node(runtimes, "lobby").requires(source -> source.hasPermission(2)))
                .then(MainLobbyCommand.node(runtimes, "mainlobby").requires(source -> source.hasPermission(2)))
                .then(DebugCommand.node(runtimes).requires(source -> source.hasPermission(2))));
    }
}
