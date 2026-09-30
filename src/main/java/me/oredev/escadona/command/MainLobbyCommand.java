package me.oredev.escadona.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import me.oredev.escadona.ui.ChatMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

final class MainLobbyCommand {
    private MainLobbyCommand() { }

    static LiteralArgumentBuilder<CommandSourceStack> node(ServerRuntimeRegistry runtimes, String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("set").executes(context -> set(context.getSource(), runtimes)))
                .then(Commands.literal("tp").executes(context -> teleport(context.getSource(), runtimes)))
                .then(Commands.literal("info").executes(context -> info(context.getSource(), runtimes)));
    }

    private static int teleport(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        try {
            var player = CommandSupport.player(source);
            var runtime = runtimes.getOrCreate(source.getServer());
            if (runtime.games().findByPlayer(player.getUUID()).isPresent()) {
                throw new IllegalStateException("Saia da partida antes de teleportar ao lobby principal.");
            }
            if (!runtime.lobbyManager().returnToLobby(player)) {
                throw new IllegalStateException("Não foi possível teleportar ao lobby principal.");
            }
            source.sendSuccess(() -> ChatMessages.success("Você foi teleportado ao lobby principal."), false);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int set(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        try {
            var player = CommandSupport.player(source);
            var runtime = runtimes.getOrCreate(source.getServer());
            if (runtime.games().findByPlayer(player.getUUID()).isPresent()) {
                throw new IllegalStateException("Saia da partida antes de definir o lobby principal.");
            }
            var lobby = runtime.mainLobby().setFrom(player);
            runtime.lobbyManager().prepareLobbyInventory(player);
            source.sendSuccess(() -> ChatMessages.success("Lobby principal definido em " + lobby.dimension().location()
                    + " @ " + lobby.position().toShortString() + "."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int info(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        try {
            var lobby = runtimes.getOrCreate(source.getServer()).mainLobby().location()
                    .orElseThrow(() -> new IllegalStateException("O lobby principal ainda não foi definido."));
            source.sendSuccess(() -> ChatMessages.info("Lobby principal: " + lobby.dimension().location()
                    + " @ " + lobby.position().toShortString()), false);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }
}
