package me.oredev.escadona.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import me.oredev.escadona.ui.ChatMessages;

final class CommandSupport {
    private CommandSupport() { }

    static ServerPlayer player(CommandSourceStack source) throws Exception {
        return source.getPlayerOrException();
    }

    static BlockHitResult lookedAt(ServerPlayer player) {
        HitResult hit = player.pick(20.0D, 0.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) return blockHit;
        throw new IllegalStateException("Nenhum bloco no alcance.");
    }

    static int fail(CommandSourceStack source, Exception exception) {
        source.sendFailure(ChatMessages.error(
                exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
        return 0;
    }
}
