package me.oredev.escadona.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import me.oredev.escadona.ui.ChatMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Operator-only, per-player bypass for lobby and WAITING block-break protection. */
final class BuildCommand {
    private BuildCommand() { }

    static LiteralArgumentBuilder<CommandSourceStack> node(ServerRuntimeRegistry runtimes) {
        return Commands.literal("build")
                .executes(context -> status(context.getSource(), runtimes))
                .then(Commands.literal("enable")
                        .executes(context -> set(context.getSource(), runtimes, true)))
                .then(Commands.literal("disable")
                        .executes(context -> set(context.getSource(), runtimes, false)))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource(), runtimes)));
    }

    private static int set(CommandSourceStack source, ServerRuntimeRegistry runtimes, boolean enabled) {
        try {
            var player = CommandSupport.player(source);
            runtimes.getOrCreate(source.getServer()).games().setPersonalBuildMode(player.getUUID(), enabled);
            source.sendSuccess(() -> enabled
                    ? ChatMessages.success("Modo build pessoal ativado. Você pode quebrar blocos no lobby e em WAITING.")
                    : ChatMessages.warning("Modo build pessoal desativado. As proteções voltaram a valer."), false);
            return 1;
        } catch (Exception exception) {
            return CommandSupport.fail(source, exception);
        }
    }

    private static int status(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        try {
            var player = CommandSupport.player(source);
            boolean enabled = runtimes.getOrCreate(source.getServer()).games().personalBuildMode(player.getUUID());
            source.sendSuccess(() -> ChatMessages.info(
                    "Modo build pessoal: " + (enabled ? "ATIVADO" : "DESATIVADO") + "."), false);
            return 1;
        } catch (Exception exception) {
            return CommandSupport.fail(source, exception);
        }
    }
}
