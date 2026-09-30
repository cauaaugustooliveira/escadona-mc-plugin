package me.oredev.escadona.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import me.oredev.escadona.luckyblock.LuckyBlockTracker;
import me.oredev.escadona.luckyblock.TrackedBlockKey;
import me.oredev.escadona.luckyblock.TrackedLuckyBlock;
import me.oredev.escadona.ui.ChatMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.UUID;

final class DebugCommand {
    private DebugCommand() { }

    static LiteralArgumentBuilder<CommandSourceStack> node(ServerRuntimeRegistry runtimes) {
        return Commands.literal("debug")
                .then(Commands.literal("track").executes(context -> track(context.getSource(), runtimes)))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), runtimes)))
                .then(Commands.literal("reset").executes(context -> reset(context.getSource(), runtimes)))
                .then(Commands.literal("status").executes(context -> status(context.getSource(), runtimes)))
                .then(Commands.literal("sessions").executes(context -> sessions(context.getSource(), runtimes)));
    }

    private static int track(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        try {
            var player = CommandSupport.player(source);
            var runtime = runtimes.getOrCreate(source.getServer());
            var hit = CommandSupport.lookedAt(player);
            var state = player.level().getBlockState(hit.getBlockPos());
            if (!runtime.luckyBlockResolver().matches(state)) throw new IllegalStateException("O bloco mirado não é a Lucky Block configurada.");
            TrackedBlockKey key = new TrackedBlockKey(player.level().dimension(), hit.getBlockPos());
            if (runtime.luckyBlocks().find(key).isPresent()) throw new IllegalStateException("Esta posição já está registrada.");
            TrackedLuckyBlock tracked = new TrackedLuckyBlock(UUID.randomUUID(), "debug-" + player.getUUID(), 1,
                    key, player.getUUID(), runtime.luckyBlockResolver().configuredId(), state);
            runtime.luckyBlocks().track(tracked);
            source.sendSuccess(() -> ChatMessages.success("Lucky Block debug registrada: " + tracked.id()), false);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int list(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        var blocks = runtimes.getOrCreate(source.getServer()).luckyBlocks().all();
        source.sendSuccess(() -> ChatMessages.info("Lucky Blocks rastreadas: " + blocks.size()), false);
        for (TrackedLuckyBlock block : blocks) {
            source.sendSuccess(() -> ChatMessages.detail("• " + block.id() + " match=" + block.matchId() + " state="
                    + block.state() + " owner=" + block.ownerId() + " lane=" + block.laneId()
                    + " section=" + block.sectionNumber() + " pos=" + block.location().position().toShortString()), false);
        }
        return blocks.size();
    }

    private static int reset(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        int restored = runtimes.getOrCreate(source.getServer()).luckyBlocks().resetAndRestore(source.getServer());
        source.sendSuccess(() -> ChatMessages.success("Tracker resetado; " + restored + " posições restauradas."), true);
        return restored;
    }

    private static int status(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        LuckyBlockTracker.TrackerStatus status = runtimes.getOrCreate(source.getServer()).luckyBlocks().status();
        source.sendSuccess(() -> ChatMessages.info("Tracker: total=" + status.total() + ", pending=" + status.pending()
                + ", broken=" + status.broken() + ", restores=" + status.scheduledRestores()), false);
        return status.total();
    }

    private static int sessions(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        var games = runtimes.getOrCreate(source.getServer()).games();
        int persisted = games.persistedSessionCount();
        source.sendSuccess(() -> ChatMessages.info("Sessões persistidas=" + persisted + ", carregadas=" + games.matches().size()), false);
        games.matches().forEach(match -> source.sendSuccess(() -> ChatMessages.detail("• " + match.id() + " arena="
                + match.arena().id() + " state=" + match.state() + " persisted=" + games.isSessionPersisted(match.id())), false));
        return persisted;
    }
}
