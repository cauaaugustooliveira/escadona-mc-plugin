package me.oredev.escadona.game;

import me.oredev.escadona.arena.ArenaManager;
import me.oredev.escadona.arena.JsonArenaRepository;
import me.oredev.escadona.luckyblock.LuckyBlockResolver;
import me.oredev.escadona.luckyblock.LuckyBlockTracker;
import me.oredev.escadona.lobby.LobbyManager;
import me.oredev.escadona.respawn.RespawnManager;
import me.oredev.escadona.reset.ArenaResetManager;
import me.oredev.escadona.reset.ArenaSnapshotRepository;
import me.oredev.escadona.session.JsonMatchSessionRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import me.oredev.escadona.world.WorldTeleportService;
import me.oredev.escadona.world.JsonMainLobbyRepository;
import me.oredev.escadona.world.MainLobbyService;
import me.oredev.escadona.world.WorldImportManager;
import me.oredev.escadona.world.JsonWorldRegistry;
import me.oredev.escadona.scoreboard.ScoreboardManager;
import me.oredev.escadona.stats.JsonPlayerStatisticsRepository;
import me.oredev.escadona.stats.PlayerStatisticsService;

import java.io.IOException;

public final class EscadonaRuntime {
    private final GameManager games;
    private final LuckyBlockTracker luckyBlocks;
    private final LuckyBlockResolver luckyBlockResolver;
    private final WorldTeleportService worldTeleportService;
    private final MainLobbyService mainLobby;
    private final LobbyManager lobbyManager;
    private final WorldImportManager worldImportManager;
    private final ScoreboardManager scoreboards;
    private final PlayerStatisticsService statistics;

    private EscadonaRuntime(GameManager games, LuckyBlockTracker luckyBlocks, LuckyBlockResolver luckyBlockResolver,
                            WorldTeleportService worldTeleportService, MainLobbyService mainLobby,
                            LobbyManager lobbyManager, WorldImportManager worldImportManager, ScoreboardManager scoreboards,
                            PlayerStatisticsService statistics) {
        this.games = games;
        this.luckyBlocks = luckyBlocks;
        this.luckyBlockResolver = luckyBlockResolver;
        this.worldTeleportService = worldTeleportService;
        this.mainLobby = mainLobby;
        this.lobbyManager = lobbyManager;
        this.worldImportManager = worldImportManager;
        this.scoreboards = scoreboards;
        this.statistics = statistics;
    }

    public static EscadonaRuntime create(MinecraftServer server) {
        var root = server.getWorldPath(LevelResource.ROOT).resolve("escadona");
        var repository = new JsonArenaRepository(root.resolve("arenas"));
        var arenas = new ArenaManager(repository);
        var sessions = new JsonMatchSessionRepository(root.resolve("sessions"));
        var resets = new ArenaResetManager(new ArenaSnapshotRepository(root.resolve("snapshots")));
        var teleports = new WorldTeleportService();
        var mainLobby = new MainLobbyService(new JsonMainLobbyRepository(root.resolve("main-lobby.json")), teleports);
        var lobbyManager = new LobbyManager(mainLobby, arenas, resets);
        var statistics = new PlayerStatisticsService(new JsonPlayerStatisticsRepository(root.resolve("player-stats.json")));
        return new EscadonaRuntime(new GameManager(arenas, new RespawnManager(), sessions, resets, statistics, lobbyManager),
                new LuckyBlockTracker(), new LuckyBlockResolver(), teleports, mainLobby, lobbyManager,
                new WorldImportManager(new JsonWorldRegistry(root.resolve("worlds.json")),
                        server.getServerDirectory()),
                new ScoreboardManager(), statistics);
    }

    public int loadArenas() throws IOException { return games.arenas().loadAll(); }
    public int loadPlayerStatistics() throws IOException { return statistics.load(); }
    public int recoverSessions(MinecraftServer server) throws IOException {
        return games.recoverSessions(server, luckyBlocks, luckyBlockResolver);
    }
    public GameManager games() { return games; }
    public LuckyBlockTracker luckyBlocks() { return luckyBlocks; }
    public LuckyBlockResolver luckyBlockResolver() { return luckyBlockResolver; }
    public WorldTeleportService worldTeleportService() { return worldTeleportService; }
    public MainLobbyService mainLobby() { return mainLobby; }
    public LobbyManager lobbyManager() { return lobbyManager; }
    public boolean loadMainLobby() { return mainLobby.load(); }
    public WorldImportManager worldImportManager() { return worldImportManager; }
    public WorldImportManager.StartupResult loadRegisteredWorlds(MinecraftServer server) throws IOException {
        return worldImportManager.loadRegisteredAtStartup(server);
    }
    public ScoreboardManager scoreboards() { return scoreboards; }
    public PlayerStatisticsService statistics() { return statistics; }
    public void close() { worldImportManager.close(); }
}
