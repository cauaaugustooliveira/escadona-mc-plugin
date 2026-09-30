package me.oredev.escadona.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.oredev.escadona.game.Match;
import me.oredev.escadona.game.RacePlayer;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import me.oredev.escadona.luckyblock.LuckyBlockState;
import me.oredev.escadona.ui.ChatMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.Locale;
import java.time.Instant;

final class MatchCommand {
    private MatchCommand() { }

    static LiteralArgumentBuilder<CommandSourceStack> joinNode(ServerRuntimeRegistry runtimes) {
        return Commands.literal("join").then(Commands.argument("arena", StringArgumentType.word())
                .executes(context -> join(context.getSource(), runtimes, arena(context))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> leaveNode(ServerRuntimeRegistry runtimes) {
        return Commands.literal("leave").executes(context -> leave(context.getSource(), runtimes));
    }

    static LiteralArgumentBuilder<CommandSourceStack> startNode(ServerRuntimeRegistry runtimes) {
        return Commands.literal("start").then(Commands.argument("arena", StringArgumentType.word())
                .executes(context -> start(context.getSource(), runtimes, arena(context))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> stopNode(ServerRuntimeRegistry runtimes) {
        return Commands.literal("stop").then(Commands.argument("arena", StringArgumentType.word())
                .executes(context -> stop(context.getSource(), runtimes, arena(context))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> statusNode(ServerRuntimeRegistry runtimes) {
        return Commands.literal("status")
                .executes(context -> status(context.getSource(), runtimes, null))
                .then(Commands.argument("arena", StringArgumentType.word())
                        .executes(context -> status(context.getSource(), runtimes, arena(context))));
    }

    private static int join(CommandSourceStack source, ServerRuntimeRegistry runtimes, String arena) {
        try {
            var player = CommandSupport.player(source);
            var runtime = runtimes.getOrCreate(source.getServer());
            var joined = runtime.games().join(source.getServer(), arena, player.getUUID());
            runtime.scoreboards().refreshMatch(source.getServer(), runtime.games(), runtime.luckyBlocks(), joined.match());
            source.sendSuccess(() -> ChatMessages.success("Você entrou na arena " + arena
                    + " pela lane " + joined.player().laneId() + "."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int leave(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        try {
            var player = CommandSupport.player(source);
            var runtime = runtimes.getOrCreate(source.getServer());
            var left = runtime.games().leave(source.getServer(), player.getUUID(), runtime.luckyBlocks());
            runtime.scoreboards().refreshLobby(source.getServer(), runtime.games(), player);
            runtime.games().findByArena(left.arenaId()).ifPresent(match ->
                    runtime.scoreboards().refreshMatch(source.getServer(), runtime.games(), runtime.luckyBlocks(), match));
            source.sendSuccess(() -> ChatMessages.info("Você saiu da arena " + left.arenaId() + "."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int start(CommandSourceStack source, ServerRuntimeRegistry runtimes, String arena) {
        try {
            var runtime = runtimes.getOrCreate(source.getServer());
            Match match = runtime.games().start(source.getServer(), arena, runtime.luckyBlocks(), runtime.luckyBlockResolver());
            runtime.scoreboards().refreshMatch(source.getServer(), runtime.games(), runtime.luckyBlocks(), match);
            source.sendSuccess(() -> ChatMessages.success("Partida iniciando na arena " + arena + ". Prepare-se!"), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int stop(CommandSourceStack source, ServerRuntimeRegistry runtimes, String arena) {
        try {
            var runtime = runtimes.getOrCreate(source.getServer());
            Match match = runtime.games().stop(source.getServer(), arena, runtime.luckyBlocks());
            runtime.scoreboards().refreshMatch(source.getServer(), runtime.games(), runtime.luckyBlocks(), match);
            source.sendSuccess(() -> ChatMessages.warning("Partida da arena " + arena
                    + " interrompida. O reset físico foi iniciado."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int status(CommandSourceStack source, ServerRuntimeRegistry runtimes, String requestedArena) {
        try {
            var runtime = runtimes.getOrCreate(source.getServer());
            Match match;
            if (requestedArena != null) {
                match = runtime.games().findByArena(requestedArena).orElse(null);
                if (match == null) {
                    var reset = runtime.games().resets().progress(requestedArena)
                            .orElseThrow(() -> new IllegalStateException("Nenhuma partida ou reset para a arena " + requestedArena));
                    sendResetStatus(source, reset);
                    return reset.failed() ? 0 : 1;
                }
            } else if (source.getEntity() != null) {
                match = runtime.games().findByPlayer(source.getEntity().getUUID())
                        .orElseThrow(() -> new IllegalStateException("Você não está em uma partida; informe a arena."));
            } else {
                throw new IllegalStateException("Informe a arena.");
            }
            var blocks = runtime.luckyBlocks().forMatch(match.id());
            long pending = blocks.stream().filter(block -> block.state() == LuckyBlockState.PENDING).count();
            long broken = blocks.size() - pending;
            long elapsed = match.elapsed(Instant.now()).toSeconds();
            long remaining = match.startedAt() == null ? match.maxDurationSeconds()
                    : Math.max(0, match.maxDurationSeconds() - elapsed);
            String phaseTimer = match.finishingEndsAt() == null ? "" : " finishingRemaining="
                    + Math.max(0, java.time.Duration.between(Instant.now(), match.finishingEndsAt()).toSeconds()) + "s";
            source.sendSuccess(() -> ChatMessages.info("matchId=" + match.id() + " Arena=" + match.arena().id() + " state=" + match.state()
                    + " players=" + match.players().size()
                    + " duration=" + formatSeconds(elapsed) + " remaining=" + formatSeconds(remaining)
                    + " initialPlayerCount=" + match.initialPlayerCount()
                    + " requiredFinishers=" + match.requiredFinishers()
                    + " keepInventoryOnDeath=" + match.arena().keepInventoryOnDeath()
                    + " pending=" + pending + " broken=" + broken
                    + " persisted=" + runtime.games().isSessionPersisted(match.id()) + phaseTimer), false);
            runtime.games().resets().progress(match.arena().id()).ifPresent(reset -> sendResetStatus(source, reset));
            if (match.placements().isEmpty()) {
                source.sendSuccess(() -> ChatMessages.detail("Placements: nenhum"), false);
            } else match.placements().forEach(placement -> source.sendSuccess(() -> ChatMessages.detail("• placement="
                    + placement.position() + " uuid=" + placement.playerId() + " time="
                    + formatSeconds(placement.finishTime().toSeconds())), false));
            match.players().values().stream().sorted(Comparator.comparing(RacePlayer::laneId)).forEach(player ->
                    source.sendSuccess(() -> {
                        var onlinePlayer = source.getServer().getPlayerList().getPlayer(player.playerId());
                        long playerTotal = blocks.stream().filter(block -> block.ownerId().equals(player.playerId())).count();
                        long playerBroken = blocks.stream().filter(block -> block.ownerId().equals(player.playerId())
                                && block.state() == LuckyBlockState.BROKEN).count();
                        String identity = onlinePlayer == null ? player.playerId().toString()
                                : onlinePlayer.getGameProfile().getName() + "/" + player.playerId();
                        return ChatMessages.detail("• " + identity + " lane=" + player.laneId()
                            + " state=" + player.state() + " currentSection=" + player.currentSection()
                            + " online=" + (onlinePlayer != null) + " blocks=" + playerBroken + "/" + playerTotal
                            + " completed=" + player.completedSections()
                            + " respawnPending=" + runtime.games().isRespawning(player.playerId()));
                    }, false));
            match.barriers().states().forEach((section, state) -> {
                var countdown = match.barriers().countdowns().get(section);
                String barrierRemaining = countdown == null ? "" : " remaining="
                        + Math.max(0, (int) Math.ceil((countdown.opensAtTick() - source.getServer().getTickCount()) / 20.0D)) + "s";
                source.sendSuccess(() -> ChatMessages.detail("• barrier section=" + section + " state=" + state + barrierRemaining), false);
            });
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static String arena(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "arena").toLowerCase(Locale.ROOT);
    }

    private static void sendResetStatus(CommandSourceStack source, me.oredev.escadona.reset.ArenaResetManager.Progress reset) {
        String text = String.format(Locale.ROOT,
                "Reset: phase=%s %.1f%% (%,d / %,d) varredura=%,d/%,d blocos alterados=%,d startedAt=%s",
                reset.phase(), reset.phasePercentage(), reset.phaseProcessed(), reset.phaseTotal(),
                reset.restoredBlocks(), reset.totalBlocks(), reset.changedBlocks(), reset.startedAt());
        if (reset.failed()) text += " error=" + reset.error();
        String message = text;
        source.sendSuccess(() -> reset.failed() ? ChatMessages.error(message) : ChatMessages.info(message), false);
    }

    private static String formatSeconds(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60);
    }
}
