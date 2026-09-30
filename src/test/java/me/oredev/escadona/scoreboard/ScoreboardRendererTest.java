package me.oredev.escadona.scoreboard;

import me.oredev.escadona.arena.Arena;
import me.oredev.escadona.arena.Barrier;
import me.oredev.escadona.arena.BlockRegion;
import me.oredev.escadona.arena.FinishLine;
import me.oredev.escadona.arena.Lane;
import me.oredev.escadona.arena.Section;
import me.oredev.escadona.game.GameState;
import me.oredev.escadona.game.Match;
import me.oredev.escadona.game.PlacementManager;
import me.oredev.escadona.game.PlayerState;
import me.oredev.escadona.game.RacePlayer;
import me.oredev.escadona.luckyblock.LuckyBlockTracker;
import me.oredev.escadona.luckyblock.TrackedBlockKey;
import me.oredev.escadona.luckyblock.TrackedLuckyBlock;
import me.oredev.escadona.respawn.RespawnManager;
import me.oredev.escadona.stats.PlayerStatistics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardRendererTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:20:00Z");
    private final ScoreboardRenderer renderer = new ScoreboardRenderer();

    @Test
    void waitingShowsPlayerCountAndConfiguredFooter() {
        Match match = waitingMatch(4, 3);

        List<String> lines = render(match, match.players().values().iterator().next(), new LuckyBlockTracker()).lines();

        assertTrue(lines.contains("Jogadores: 3/4"));
        assertTrue(lines.contains("Aguardando jogadores..."));
        assertTrue(lines.contains("play.teste.net"));
    }

    @Test
    void startingUsesAuthoritativeMatchCountdown() {
        Match match = waitingMatch(4, 3);
        match.beginStarting(100, 60, 1800, NOW);

        List<String> lines = strings(renderer.render(match, first(match), new LuckyBlockTracker(), Optional.empty(),
                120, NOW, ScoreboardRendererTest::name, "play.teste.net"));

        assertTrue(lines.contains("Iniciando em: 2s"));
        assertTrue(lines.contains("Prepare-se!"));
    }

    @Test
    void playingDerivesSectionLuckyBlocksAndBarrierFromExistingState() {
        Match match = playingMatch(4, List.of());
        RacePlayer player = first(match);
        player.completeSection(1);
        LuckyBlockTracker tracker = tracker(match, player, 2, 2, 1);
        match.barriers().restoreCountdown(1, 100, 680);

        List<String> lines = strings(renderer.render(match, player, tracker, Optional.empty(),
                100, NOW, ScoreboardRendererTest::name, "play.teste.net"));

        assertTrue(lines.contains("Tempo: 12:43"));
        assertTrue(lines.contains("Parte: 2/2"));
        assertTrue(lines.contains("Lucky Blocks: 1/2"));
        assertTrue(lines.contains("Barreira: 34s"));
    }

    @Test
    void placementsRespectRequiredFinishers() {
        Match match = playingMatch(2, List.of());

        List<String> lines = render(match, first(match), new LuckyBlockTracker()).lines();

        assertTrue(lines.contains("1º ---"));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("2º")));
    }

    @Test
    void finishedPlayerShowsPlacementAndFinishTime() {
        Match match = playingMatch(3, List.of());
        RacePlayer player = first(match);
        player.finish(1, Duration.ofSeconds(751));

        List<String> lines = render(match, player, new LuckyBlockTracker()).lines();

        assertTrue(lines.contains("Posição: 1º"));
        assertTrue(lines.contains("Tempo: 12:31"));
    }

    @Test
    void deadPlayerUsesPendingRespawnDeadline() {
        Match match = playingMatch(3, List.of());
        RacePlayer player = first(match);
        player.setState(PlayerState.DEAD_RESPAWNING);
        RespawnManager respawns = new RespawnManager();
        var pending = respawns.restore(match.id(), player.playerId(), false, null, 160,
                player.checkpoint(), player.checkpoint());

        List<String> lines = strings(renderer.render(match, player, new LuckyBlockTracker(), Optional.of(pending),
                100, NOW, ScoreboardRendererTest::name, "play.teste.net"));

        assertTrue(lines.contains("Respawn: 3s"));
    }

    @Test
    void finishingShowsOnlyExistingResults() {
        UUID winner = UUID.nameUUIDFromBytes("winner".getBytes());
        var placement = new PlacementManager.Placement(1, winner, Duration.ofSeconds(751), NOW.minusSeconds(10));
        Match match = playingMatch(4, List.of(placement));
        match.beginFinishing(NOW, 10);

        List<String> lines = render(match, first(match), new LuckyBlockTracker()).lines();

        assertTrue(lines.contains("RESULTADO"));
        assertTrue(lines.contains("1º winner - 12:31"));
        assertTrue(lines.stream().noneMatch(line -> line.contains("---")));
        assertTrue(lines.contains("Reiniciando arena..."));
    }

    @Test
    void reconnectRebuildProducesCurrentAuthoritativeView() {
        Match match = playingMatch(3, List.of());
        RacePlayer player = first(match);
        LuckyBlockTracker tracker = tracker(match, player, 1, 2, 1);

        ScoreboardView firstConnection = renderer.render(match, player, tracker, Optional.empty(),
                100, NOW, ScoreboardRendererTest::name, "play.teste.net");
        ScoreboardView reconnected = renderer.render(match, player, tracker, Optional.empty(),
                100, NOW, ScoreboardRendererTest::name, "play.teste.net");

        assertEquals(firstConnection, reconnected);
    }

    @Test
    void customLayoutChangesTitleLinesAndPlaceholders() {
        Match match = waitingMatch(4, 2);
        ScoreboardLayout defaults = ScoreboardLayout.defaults();
        ScoreboardLayout custom = new ScoreboardLayout("&d&lCORRIDA", defaults.placementLine(),
                defaults.resultLine(), defaults.lobby(), List.of("&bArena: {arena}", "&fVagas: {players}/{max_players}",
                "&7{footer}"), defaults.starting(), defaults.playing(), defaults.finished(),
                defaults.deadRespawning(), defaults.finishing());

        ScoreboardView view = renderer.render(match, first(match), new LuckyBlockTracker(), Optional.empty(),
                100, NOW, ScoreboardRendererTest::name, "meu.servidor", custom);

        assertEquals("CORRIDA", view.title().getString());
        assertEquals(List.of("Arena: test", "Vagas: 2/4", "meu.servidor"), strings(view));
    }

    @Test
    void customLayoutPreservesEveryConfiguredBlankLine() {
        Match match = waitingMatch(4, 2);
        ScoreboardLayout defaults = ScoreboardLayout.defaults();
        ScoreboardLayout custom = new ScoreboardLayout(defaults.title(), defaults.placementLine(),
                defaults.resultLine(), defaults.lobby(), List.of("&eTopo", "", "", "&7Rodapé"), defaults.starting(),
                defaults.playing(), defaults.finished(), defaults.deadRespawning(), defaults.finishing());

        ScoreboardView view = renderer.render(match, first(match), new LuckyBlockTracker(), Optional.empty(),
                100, NOW, ScoreboardRendererTest::name, "meu.servidor", custom);

        assertEquals(List.of("Topo", "", "", "Rodapé"), strings(view));
    }

    @Test
    void statisticsPlaceholdersUseCurrentPlayerTotals() {
        Match match = waitingMatch(4, 2);
        ScoreboardLayout defaults = ScoreboardLayout.defaults();
        ScoreboardLayout custom = new ScoreboardLayout(defaults.title(), defaults.placementLine(),
                defaults.resultLine(), defaults.lobby(), List.of("Kills: {kills}", "Vitórias: {wins}"), defaults.starting(),
                defaults.playing(), defaults.finished(), defaults.deadRespawning(), defaults.finishing());

        ScoreboardView view = renderer.render(match, first(match), new LuckyBlockTracker(), Optional.empty(),
                100, NOW, ScoreboardRendererTest::name, "meu.servidor", custom, new PlayerStatistics(7, 3));

        assertEquals(List.of("Kills: 7", "Vitórias: 3"), strings(view));
    }

    @Test
    void lobbyLayoutShowsOnlinePlayersAndPersistentStatistics() {
        ScoreboardView view = renderer.renderLobby(12, "play.teste.net", ScoreboardLayout.defaults(),
                new PlayerStatistics(7, 3));

        assertEquals(List.of("Jogadores online: 12", "", "Kills: 7", "Vitórias: 3", "", "play.teste.net"),
                strings(view));
    }

    private Rendered render(Match match, RacePlayer player, LuckyBlockTracker tracker) {
        return new Rendered(strings(renderer.render(match, player, tracker, Optional.empty(),
                100, NOW, ScoreboardRendererTest::name, "play.teste.net")));
    }

    private static List<String> strings(ScoreboardView view) {
        return view.lines().stream().map(component -> component.getString()).toList();
    }

    private static String name(UUID playerId) {
        return playerId.equals(UUID.nameUUIDFromBytes("winner".getBytes())) ? "winner" : "player";
    }

    private static RacePlayer first(Match match) { return match.players().values().iterator().next(); }

    private static Match waitingMatch(int lanes, int players) {
        Match match = new Match(UUID.randomUUID(), arena(lanes));
        for (int index = 0; index < players; index++) match.join(UUID.randomUUID());
        return match;
    }

    private static Match playingMatch(int players, List<PlacementManager.Placement> placements) {
        Arena arena = arena(players);
        List<RacePlayer> racePlayers = new ArrayList<>();
        int index = 0;
        for (Lane lane : arena.lanes().values()) {
            UUID id = index++ == 0 && !placements.isEmpty() ? placements.getFirst().playerId() : UUID.randomUUID();
            racePlayers.add(new RacePlayer(id, lane.id(), lane.spawn()));
        }
        return Match.restore(UUID.randomUUID(), arena, GameState.PLAYING, NOW.minusSeconds(763), null, null,
                1800, players, racePlayers, placements, 100, NOW);
    }

    private static LuckyBlockTracker tracker(Match match, RacePlayer player, int section, int total, int broken) {
        LuckyBlockTracker tracker = new LuckyBlockTracker();
        for (int index = 0; index < total; index++) {
            BlockPos pos = new BlockPos(index, 64, section);
            var tracked = new TrackedLuckyBlock(UUID.randomUUID(), match.id(), player.laneId(), section,
                    new TrackedBlockKey(Level.OVERWORLD, pos), player.playerId(),
                    ResourceLocation.parse("minecraft:stone"), Blocks.STONE.defaultBlockState());
            if (index < broken) tracked.markBroken();
            tracker.track(tracked);
        }
        return tracker;
    }

    private static Arena arena(int laneCount) {
        Map<String, Lane> lanes = new LinkedHashMap<>();
        for (int index = 0; index < laneCount; index++) {
            String id = Character.toString('a' + index);
            int x = index * 10;
            lanes.put(id, new Lane(id, GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, 0)), Map.of(
                    1, new Section(1, Set.of(new BlockPos(x, 64, 1))),
                    2, new Section(2, Set.of(new BlockPos(x, 64, 2))))));
        }
        return new Arena("test", Level.OVERWORLD, null,
                lanes, List.of(new Barrier(1, new BlockRegion(new BlockPos(0, 64, 3), new BlockPos(40, 67, 3)))),
                new FinishLine(new BlockRegion(new BlockPos(0, 64, 10), new BlockPos(50, 70, 12))),
                new BlockRegion(new BlockPos(-5, 60, -5), new BlockPos(60, 80, 20)), Map.of());
    }

    private record Rendered(List<String> lines) { }
}
