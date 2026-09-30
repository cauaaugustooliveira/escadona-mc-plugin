package me.oredev.escadona.scoreboard;

import me.oredev.escadona.config.EscadonaConfig;
import me.oredev.escadona.game.GameManager;
import me.oredev.escadona.game.GameState;
import me.oredev.escadona.game.Match;
import me.oredev.escadona.game.RacePlayer;
import me.oredev.escadona.luckyblock.LuckyBlockTracker;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Sends per-player vanilla sidebar packets while keeping domain state in Match and its managers. */
public final class ScoreboardManager {
    private final ScoreboardRenderer renderer = new ScoreboardRenderer();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long lastRefreshTick = Long.MIN_VALUE;

    public void tick(MinecraftServer server, GameManager games, LuckyBlockTracker tracker,
                     boolean mainLobbyConfigured) {
        Set<UUID> audience = new HashSet<>();
        games.matches().stream().filter(match -> match.state() != GameState.RESETTING)
                .forEach(match -> audience.addAll(match.players().keySet()));
        if (mainLobbyConfigured) {
            server.getPlayerList().getPlayers().stream()
                    .filter(player -> games.findByPlayer(player.getUUID()).isEmpty())
                    .forEach(player -> audience.add(player.getUUID()));
        }
        for (UUID playerId : Set.copyOf(sessions.keySet())) {
            ServerPlayer online = server.getPlayerList().getPlayer(playerId);
            if (online == null) sessions.remove(playerId);
            else if (!audience.contains(playerId) || !EscadonaConfig.scoreboardEnabled()) clear(online);
        }
        if (!EscadonaConfig.scoreboardEnabled()) return;
        long tick = server.getTickCount();
        int interval = EscadonaConfig.scoreboardUpdateIntervalTicks();
        if (lastRefreshTick != Long.MIN_VALUE && tick - lastRefreshTick < interval) return;
        lastRefreshTick = tick;
        for (Match match : games.matches()) refreshMatch(server, games, tracker, match);
        if (mainLobbyConfigured) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (games.findByPlayer(player.getUUID()).isEmpty()) refreshLobby(server, games, player);
            }
        }
    }

    public void refreshMatch(MinecraftServer server, GameManager games, LuckyBlockTracker tracker, Match match) {
        if (!EscadonaConfig.scoreboardEnabled()) return;
        if (match.state() == GameState.RESETTING) return;
        for (RacePlayer racePlayer : match.players().values()) {
            ServerPlayer online = server.getPlayerList().getPlayer(racePlayer.playerId());
            if (online != null) refresh(server, games, tracker, match, racePlayer, online);
        }
    }

    public void refreshPlayer(MinecraftServer server, GameManager games, LuckyBlockTracker tracker, UUID playerId) {
        if (!EscadonaConfig.scoreboardEnabled()) return;
        Match match = games.findByPlayer(playerId).orElseGet(() -> games.matches().stream()
                .filter(candidate -> candidate.player(playerId).isPresent()).findFirst().orElse(null));
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (match == null || online == null || match.state() == GameState.RESETTING) {
            if (online != null) clear(online);
            else sessions.remove(playerId);
            return;
        }
        refresh(server, games, tracker, match, match.player(playerId).orElseThrow(), online);
    }

    public void clear(ServerPlayer player) {
        Session session = sessions.remove(player.getUUID());
        if (session != null) player.connection.send(new ClientboundSetObjectivePacket(
                session.objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
    }

    public void forget(UUID playerId) { sessions.remove(playerId); }

    public void refreshLobby(MinecraftServer server, GameManager games, ServerPlayer player) {
        if (!EscadonaConfig.scoreboardEnabled()) {
            clear(player);
            return;
        }
        ScoreboardView view = renderer.renderLobby(server.getPlayerCount(), EscadonaConfig.scoreboardFooter(),
                EscadonaConfig.scoreboardLayout(), games.statistics().get(player.getUUID()));
        Session session = sessions.computeIfAbsent(player.getUUID(), ignored -> create(player, view));
        if (session.view.equals(view)) return;
        update(player, session, view);
    }

    private void refresh(MinecraftServer server, GameManager games, LuckyBlockTracker tracker,
                         Match match, RacePlayer racePlayer, ServerPlayer online) {
        ScoreboardView view = renderer.render(match, racePlayer, tracker,
                games.pendingRespawn(match.id(), racePlayer.playerId()),
                server.getTickCount(), Instant.now(), playerId -> name(server, playerId),
                EscadonaConfig.scoreboardFooter(), EscadonaConfig.scoreboardLayout(),
                games.statistics().get(racePlayer.playerId()));
        Session session = sessions.computeIfAbsent(racePlayer.playerId(), ignored -> create(online, view));
        if (session.view.equals(view)) return;
        update(online, session, view);
    }

    private static Session create(ServerPlayer player, ScoreboardView view) {
        String objectiveName = "esc_" + player.getUUID().toString().replace("-", "").substring(0, 12);
        Scoreboard local = new Scoreboard();
        Objective objective = local.addObjective(objectiveName, ObjectiveCriteria.DUMMY, view.title(),
                ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
        sendLines(player, objectiveName, null, view);
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        return new Session(objective, view);
    }

    private static void update(ServerPlayer player, Session session, ScoreboardView next) {
        if (!session.view.title().equals(next.title())) {
            session.objective.setDisplayName(next.title());
            player.connection.send(new ClientboundSetObjectivePacket(session.objective,
                    ClientboundSetObjectivePacket.METHOD_CHANGE));
        }
        sendLines(player, session.objective.getName(), session.view, next);
        session.view = next;
    }

    private static void sendLines(ServerPlayer player, String objectiveName, ScoreboardView previous, ScoreboardView next) {
        int previousSize = previous == null ? 0 : previous.lines().size();
        int max = Math.max(previousSize, next.lines().size());
        for (int index = 0; index < max; index++) {
            String owner = lineOwner(index);
            if (index >= next.lines().size()) {
                player.connection.send(new ClientboundResetScorePacket(owner, objectiveName));
                continue;
            }
            boolean scoreChanged = previous != null && previousSize != next.lines().size();
            boolean lineChanged = previous == null || index >= previousSize
                    || !previous.lines().get(index).equals(next.lines().get(index));
            if (scoreChanged || lineChanged) {
                player.connection.send(new ClientboundSetScorePacket(owner, objectiveName,
                        next.lines().size() - index, Optional.of(next.lines().get(index)), Optional.of(BlankFormat.INSTANCE)));
            }
        }
    }

    /** Score owners beginning with '#' are deliberately hidden by the vanilla sidebar GUI. */
    static String lineOwner(int index) {
        if (index < 0 || index >= ScoreboardView.MAX_LINES) {
            throw new IllegalArgumentException("Invalid sidebar line index: " + index);
        }
        return "esc_ln_" + index;
    }

    private static String name(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) return online.getGameProfile().getName();
        return server.getProfileCache().get(playerId).map(profile -> profile.getName())
                .orElse(playerId.toString().substring(0, 8));
    }

    private static final class Session {
        private final Objective objective;
        private ScoreboardView view;

        private Session(Objective objective, ScoreboardView view) {
            this.objective = objective;
            this.view = view;
        }
    }
}
