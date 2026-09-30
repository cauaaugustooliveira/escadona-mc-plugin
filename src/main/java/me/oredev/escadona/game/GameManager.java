package me.oredev.escadona.game;

import me.oredev.escadona.Escadona;
import me.oredev.escadona.arena.Arena;
import me.oredev.escadona.arena.ArenaDraft;
import me.oredev.escadona.arena.ArenaManager;
import me.oredev.escadona.arena.Barrier;
import me.oredev.escadona.arena.Lane;
import me.oredev.escadona.arena.Section;
import me.oredev.escadona.barrier.BarrierManager;
import me.oredev.escadona.barrier.BarrierState;
import me.oredev.escadona.config.EscadonaConfig;
import me.oredev.escadona.luckyblock.LuckyBlockResolver;
import me.oredev.escadona.luckyblock.LuckyBlockTracker;
import me.oredev.escadona.luckyblock.TrackedBlockKey;
import me.oredev.escadona.luckyblock.TrackedLuckyBlock;
import me.oredev.escadona.respawn.RespawnManager;
import me.oredev.escadona.reset.ArenaResetManager;
import me.oredev.escadona.reset.ArenaSnapshot;
import me.oredev.escadona.reset.ArenaSnapshotValidator;
import me.oredev.escadona.session.JsonMatchSessionRepository;
import me.oredev.escadona.session.MatchSession;
import me.oredev.escadona.stats.PlayerStatisticsService;
import me.oredev.escadona.ui.ChatMessages;
import me.oredev.escadona.lobby.LobbyManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GameManager {
    private static final int START_COUNTDOWN_TICKS = 3 * 20;
    private static final int PVP_DEATH_THRESHOLD = 3;
    private static final Duration PVP_DEATH_WINDOW = Duration.ofSeconds(30);
    private static final Duration PVP_INVULNERABILITY_DURATION = Duration.ofSeconds(30);
    private final ArenaManager arenaManager;
    private final RespawnManager respawnManager;
    private final JsonMatchSessionRepository sessions;
    private final ArenaResetManager resets;
    private final PlayerStatisticsService statistics;
    private final LobbyManager lobby;
    private final Map<UUID, Match> matches = new LinkedHashMap<>();
    private final Map<String, UUID> matchesByArena = new LinkedHashMap<>();
    private final Map<UUID, UUID> matchesByPlayer = new LinkedHashMap<>();
    private final Map<UUID, Long> finishWarningCooldowns = new LinkedHashMap<>();
    private final Set<UUID> personalBuildMode = new java.util.HashSet<>();

    public GameManager(ArenaManager arenaManager, RespawnManager respawnManager, JsonMatchSessionRepository sessions,
                       ArenaResetManager resets, PlayerStatisticsService statistics, LobbyManager lobby) {
        this.arenaManager = arenaManager;
        this.respawnManager = respawnManager;
        this.sessions = sessions;
        this.resets = resets;
        this.statistics = statistics;
        this.lobby = lobby;
    }

    public ArenaManager arenas() { return arenaManager; }
    public ArenaResetManager resets() { return resets; }
    public PlayerStatisticsService statistics() { return statistics; }
    public Optional<Match> find(UUID matchId) { return Optional.ofNullable(matches.get(matchId)); }
    public Optional<Match> findByArena(String arenaId) {
        UUID matchId = matchesByArena.get(arenaId);
        return matchId == null ? Optional.empty() : find(matchId);
    }
    public Optional<Match> findByPlayer(UUID playerId) {
        UUID matchId = matchesByPlayer.get(playerId);
        return matchId == null ? Optional.empty() : find(matchId);
    }
    public Collection<Match> matches() { return List.copyOf(matches.values()); }
    public boolean isPlaying(UUID matchId) {
        Match match = matches.get(matchId);
        return match != null && match.state() == GameState.PLAYING;
    }

    public boolean isRespawning(UUID playerId) {
        return respawnManager.findByPlayer(playerId).isPresent();
    }

    public Optional<RespawnManager.PendingRespawn> pendingRespawn(UUID matchId, UUID playerId) {
        return respawnManager.find(matchId, playerId);
    }

    public boolean rescueWaitingPlayer(ServerPlayer player) {
        Match match = findByPlayer(player.getUUID()).orElse(null);
        if (match == null || match.state() != GameState.WAITING) return false;
        RacePlayer racePlayer = match.player(player.getUUID()).orElse(null);
        if (racePlayer == null) return false;
        Lane lane = match.arena().lanes().get(racePlayer.laneId());
        if (lane == null) return false;
        teleport(player, lane.spawn(), player.getServer());
        stopFalling(player);
        return true;
    }

    public JoinResult join(MinecraftServer server, String arenaId, UUID playerId) {
        if (matchesByPlayer.containsKey(playerId)) {
            throw new JoinException(JoinFailureReason.ALREADY_JOINED, "Você já está em uma arena.");
        }
        Arena arena = arenaManager.find(arenaId).orElseThrow(() ->
                new JoinException(JoinFailureReason.UNKNOWN_ARENA, "Arena desconhecida: " + arenaId));
        if (resets.failure(arenaId).isPresent()) {
            throw new JoinException(JoinFailureReason.UNAVAILABLE, "Esta arena está indisponível no momento.");
        }
        if (resets.isBlocked(arenaId)) {
            throw new JoinException(JoinFailureReason.RESETTING, "Esta arena está sendo restaurada.");
        }
        Match existingMatch = findByArena(arenaId).orElse(null);
        if (existingMatch != null && existingMatch.state() != GameState.WAITING) {
            JoinFailureReason reason = switch (existingMatch.state()) {
                case STARTING -> JoinFailureReason.STARTING;
                case PLAYING -> JoinFailureReason.PLAYING;
                case FINISHING -> JoinFailureReason.FINISHING;
                case RESETTING -> JoinFailureReason.RESETTING;
                case WAITING -> throw new IllegalStateException("Unexpected waiting state");
            };
            String message = reason == JoinFailureReason.RESETTING
                    ? "Esta arena está sendo restaurada."
                    : "Esta arena já está em partida.";
            throw new JoinException(reason, message);
        }
        if (existingMatch != null && existingMatch.players().size() >= arena.lanes().size()) {
            throw new JoinException(JoinFailureReason.FULL, "Esta arena está cheia.");
        }
        validateArenaForJoin(server, arena);
        lobby.requireGlobalPosition(server);
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online == null) throw new JoinException(JoinFailureReason.PLAYER_OFFLINE, "O jogador precisa estar online.");
        Match match = existingMatch == null ? createMatch(arena) : existingMatch;
        RacePlayer racePlayer;
        try {
            racePlayer = match.join(playerId);
        } catch (IllegalStateException exception) {
            if (match.players().size() >= arena.lanes().size()) {
                throw new JoinException(JoinFailureReason.FULL, "Esta arena ficou cheia.", exception);
            }
            throw exception;
        }
        matchesByPlayer.put(playerId, match.id());
        teleport(online, racePlayer.checkpoint(), server);
        lobby.removeLobbyItems(online);
        persist(server, match);
        return new JoinResult(match, racePlayer);
    }

    private void validateArenaForJoin(MinecraftServer server, Arena arena) {
        try {
            var validation = ArenaDraft.fromArena(arena).validate();
            if (!validation.isValid()) throw new IllegalStateException(String.join("; ", validation.errors()));
            if (server.getLevel(arena.dimension()) == null) throw new IllegalStateException("Arena dimension is not loaded");
            ArenaSnapshot.Metadata metadata = resets.snapshots().metadata(arena.id(), server.registryAccess());
            ArenaSnapshotValidator.requireMetadataCompatible(arena, metadata);
        } catch (Exception exception) {
            throw new JoinException(JoinFailureReason.UNAVAILABLE,
                    "Esta arena está indisponível no momento.", exception);
        }
    }

    private Match createMatch(Arena arena) {
        Match match = new Match(UUID.randomUUID(), arena);
        matches.put(match.id(), match);
        matchesByArena.put(arena.id(), match.id());
        return match;
    }

    public LeaveResult leave(MinecraftServer server, UUID playerId, LuckyBlockTracker tracker) {
        Match match = findByPlayer(playerId).orElseThrow(() -> new IllegalStateException("Player is not in a match"));
        if (match.state() != GameState.WAITING) {
            throw new IllegalStateException("Não é possível abandonar uma partida iniciada; desconecte-se ou peça a um administrador para pará-la.");
        }
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        lobby.requireGlobalPosition(server);
        if (online != null && !lobby.returnToLobby(online)) {
            throw new IllegalStateException("Não foi possível teleportar o jogador ao lobby principal.");
        }
        respawnManager.find(match.id(), playerId).ifPresent(ticket -> {
            ticket.markMatchEnded();
            if (online != null) completePendingRespawn(server, ticket, online, null);
        });
        RacePlayer player = match.removePlayer(playerId);
        matchesByPlayer.remove(playerId);
        tracker.removeOwner(match.id(), playerId);
        if (match.players().isEmpty()) {
            removeMatch(match);
            deleteSession(match.id());
        } else persist(server, match);
        return new LeaveResult(match.arena().id(), player == null ? null : player.laneId());
    }

    public Match start(MinecraftServer server, String arenaId, LuckyBlockTracker tracker, LuckyBlockResolver resolver) {
        Match match = findByArena(arenaId).orElseThrow(() -> new IllegalStateException("No waiting match for arena " + arenaId));
        if (match.state() != GameState.WAITING) throw new IllegalStateException("Match is not waiting");
        if (resets.isBlocked(arenaId)) throw new IllegalStateException("A Arena está restaurando ou possui reset falho");
        lobby.requireGlobalPosition(server);
        ArenaSnapshot.Metadata snapshotMetadata;
        try {
            snapshotMetadata = resets.snapshots().metadata(arenaId, server.registryAccess());
        } catch (Exception exception) {
            throw new IllegalStateException("A Arena não possui um snapshot válido. Use /escadona arena snapshot create "
                    + arenaId + ".", exception);
        }
        ArenaSnapshotValidator.requireMetadataCompatible(match.arena(), snapshotMetadata);
        var validation = ArenaDraft.fromArena(match.arena()).validate();
        if (!validation.isValid()) throw new IllegalStateException(String.join("; ", validation.errors()));
        if (match.players().size() < 2) throw new IllegalStateException("At least two players are required");
        for (RacePlayer racePlayer : match.players().values()) {
            if (server.getPlayerList().getPlayer(racePlayer.playerId()) == null) {
                throw new IllegalStateException("All joined players must be online to start");
            }
        }
        Block luckyBlock = resolver.resolve().orElseThrow(() -> new IllegalStateException("Configured Lucky Block is unavailable"));
        ServerLevel level = server.getLevel(match.arena().dimension());
        if (level == null) throw new IllegalStateException("Arena dimension is not loaded");

        List<TrackedLuckyBlock> prepared = prepareTrackedBlocks(match, tracker, resolver, luckyBlock, level);
        match.beginStarting(server.getTickCount(), START_COUNTDOWN_TICKS,
                EscadonaConfig.MAX_MATCH_MINUTES.get() * 60, Instant.now());
        materializeBarrierStates(level, match);
        prepared.forEach(tracker::track);
        for (TrackedLuckyBlock tracked : prepared) {
            if (!level.getBlockState(tracked.location().position()).is(luckyBlock)) {
                level.setBlockAndUpdate(tracked.location().position(), tracked.originalState());
            }
        }
        for (RacePlayer racePlayer : match.players().values()) {
            preparePlayer(server.getPlayerList().getPlayer(racePlayer.playerId()), racePlayer, server);
        }
        Escadona.LOGGER.info("Match {} for arena {} entered STARTING with {} players and {} tracked blocks",
                match.id(), arenaId, match.players().size(), prepared.size());
        persist(server, match);
        return match;
    }

    private List<TrackedLuckyBlock> prepareTrackedBlocks(Match match, LuckyBlockTracker tracker,
                                                          LuckyBlockResolver resolver, Block luckyBlock, ServerLevel level) {
        List<TrackedLuckyBlock> prepared = new ArrayList<>();
        for (RacePlayer racePlayer : match.players().values()) {
            Lane lane = match.arena().lanes().get(racePlayer.laneId());
            for (Section section : lane.sections().values()) {
                for (BlockPos pos : section.requiredLuckyBlocks()) {
                    TrackedBlockKey key = new TrackedBlockKey(match.arena().dimension(), pos);
                    if (tracker.find(key).isPresent()) throw new IllegalStateException("Tracked position is already in use: " + pos.toShortString());
                    BlockState physical = level.getBlockState(pos);
                    BlockState original = physical.is(luckyBlock) ? physical : luckyBlock.defaultBlockState();
                    String stableKey = match.id() + ":" + lane.id() + ":" + section.number() + ":" + pos.asLong();
                    prepared.add(new TrackedLuckyBlock(
                            UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)), match.id(), lane.id(),
                            section.number(), key, racePlayer.playerId(), resolver.configuredId(), original));
                }
            }
        }
        return prepared;
    }

    private void preparePlayer(ServerPlayer player, RacePlayer racePlayer, MinecraftServer server) {
        player.setGameMode(GameType.SURVIVAL);
        respawnManager.prepareForMatch(player);
        teleport(player, racePlayer.checkpoint(), server);
    }

    public void onLuckyBlockBroken(MinecraftServer server, LuckyBlockTracker tracker, TrackedLuckyBlock block) {
        if (block.matchId() == null) return;
        Match match = matches.get(block.matchId());
        if (match == null || match.state() != GameState.PLAYING) return;
        RacePlayer racePlayer = match.player(block.ownerId()).orElse(null);
        if (racePlayer == null) return;
        boolean newlyBroken = racePlayer.recordBroken(block.id());
        Lane lane = match.arena().lanes().get(block.laneId());
        Section section = lane == null ? null : lane.sections().get(block.sectionNumber());
        int brokenInSection = tracker.sectionStatus(match.id(), block.ownerId(), block.laneId(), block.sectionNumber()).broken();
        if (CheckpointProgress.acquire(racePlayer, section, newlyBroken, brokenInSection)) {
            ServerPlayer online = server.getPlayerList().getPlayer(block.ownerId());
            if (online != null) {
                online.sendSystemMessage(ChatMessages.success("Checkpoint da etapa " + block.sectionNumber() + " conquistado!"));
            }
            Escadona.LOGGER.info("Player {} acquired checkpoint for arena {} lane {} section {} at {}",
                    block.ownerId(), match.arena().id(), block.laneId(), block.sectionNumber(),
                    racePlayer.checkpoint().pos().toShortString());
        }
        if (!tracker.isSectionComplete(match.id(), block.ownerId(), block.laneId(), block.sectionNumber())) {
            persist(server, match);
            return;
        }
        if (!match.recordSectionCompleted(block.ownerId(), block.sectionNumber())) {
            persist(server, match);
            return;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(block.ownerId());
        String name = player == null ? block.ownerId().toString() : player.getGameProfile().getName();
        Optional<Barrier> barrier = match.arena().barriers().stream()
                .filter(candidate -> candidate.afterSection() == block.sectionNumber()).findFirst();
        if (barrier.isEmpty()) {
            broadcast(server, ChatMessages.sectionCompleted(name, block.sectionNumber()));
            persist(server, match);
            return;
        }
        int seconds = EscadonaConfig.BARRIER_COUNTDOWN_SECONDS.get();
        if (match.barriers().startOnce(block.sectionNumber(), server.getTickCount(), seconds * 20).isPresent()) {
            String duration = seconds == 60 ? "1 minuto" : seconds + " segundos";
            broadcast(server, ChatMessages.barrierCountdown(name + " quebrou todas as Lucky Blocks da parte "
                    + block.sectionNumber() + ". A barreira cairá em " + duration + "."));
        }
        persist(server, match);
    }

    /** Cancels vanilla death and starts the server-authoritative respawn window. */
    public DeathResult onPlayerDeath(ServerPlayer player, UUID killerId) {
        Match match = findByPlayer(player.getUUID()).orElse(null);
        if (match == null || match.state() != GameState.PLAYING) return DeathResult.ignored();
        RacePlayer racePlayer = match.player(player.getUUID()).orElse(null);
        if (racePlayer == null) return DeathResult.ignored();
        if (racePlayer.state() != PlayerState.ALIVE) {
            return isRespawning(player.getUUID()) ? DeathResult.alreadyManaged() : DeathResult.ignored();
        }

        int seconds = EscadonaConfig.RESPAWN_SECONDS.get();
        UUID creditedKiller = eligibleKiller(match, player.getUUID(), killerId);
        respawnManager.begin(match.id(), player, match.arena().keepInventoryOnDeath(),
                player.getServer().getTickCount() + seconds * 20L, racePlayer.checkpoint(),
                lobby.requireGlobalPosition(player.getServer()));
        racePlayer.setState(PlayerState.DEAD_RESPAWNING);
        if (creditedKiller != null) {
            creditKill(match, creditedKiller);
            if (racePlayer.recordPvpDeath(Instant.now(), PVP_DEATH_WINDOW, PVP_DEATH_THRESHOLD)) {
                player.sendSystemMessage(ChatMessages.success(
                        "Você foi abatido 3 vezes em 30 segundos e ganhou 30 segundos de invulnerabilidade após o respawn!"));
            }
        }
        player.setGameMode(GameType.SPECTATOR);
        if (match.arena().deathWait() != null) teleport(player, match.arena().deathWait(), player.getServer());
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("VOCÊ MORREU!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("Respawnará em " + seconds + (seconds == 1 ? " segundo" : " segundos"))
                        .withStyle(ChatFormatting.YELLOW)));
        persist(player.getServer(), match);
        return DeathResult.managed(creditedKiller);
    }

    public void onPlayerLogout(MinecraftServer server, UUID playerId) {
        personalBuildMode.remove(playerId);
        findByPlayer(playerId).ifPresent(match -> match.player(playerId).ifPresent(player -> {
            player.setState(match.hasPlacement(playerId) ? PlayerState.FINISHED : PlayerState.DISCONNECTED);
            persist(server, match);
        }));
    }

    public void onPlayerLogin(MinecraftServer server, ServerPlayer player) {
        RespawnManager.PendingRespawn pending = respawnManager.findByPlayer(player.getUUID()).orElse(null);
        if (pending == null) pending = respawnManager.findExitRecovery(player.getUUID()).orElse(null);
        if (pending != null) {
            Match match = matches.get(pending.key().matchId());
            RacePlayer racePlayer = match == null ? null : match.player(player.getUUID()).orElse(null);
            if (pending.matchEnded()) {
                completePendingRespawn(server, pending, player, null);
            } else if (match != null && match.state() == GameState.PLAYING && racePlayer != null) {
                completePendingRespawn(server, pending, player, racePlayer);
                player.sendSystemMessage(ChatMessages.success("Você retornou à partida de Escadona!"));
                Escadona.LOGGER.info("Player {} reconnected to match {} through pending respawn", player.getUUID(), match.id());
            }
            if (match != null) persist(server, match);
            return;
        }

        findByPlayer(player.getUUID()).ifPresent(match -> match.player(player.getUUID()).ifPresent(racePlayer -> {
            if (racePlayer.state() == PlayerState.FINISHED || match.hasPlacement(player.getUUID())) {
                racePlayer.setState(PlayerState.FINISHED);
                player.setGameMode(GameType.SPECTATOR);
                teleport(player, racePlayer.checkpoint(), server);
            } else if (match.state() == GameState.FINISHING) {
                racePlayer.setState(PlayerState.ALIVE);
                player.setGameMode(GameType.SPECTATOR);
                teleport(player, racePlayer.checkpoint(), server);
            } else {
                racePlayer.setState(PlayerState.ALIVE);
                player.setGameMode(GameType.SURVIVAL);
                teleport(player, racePlayer.checkpoint(), server);
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
            }
            player.sendSystemMessage(ChatMessages.success("Você retornou à partida de Escadona!"));
            Escadona.LOGGER.info("Player {} reconnected to match {} on lane {}", player.getUUID(), match.id(), racePlayer.laneId());
            persist(server, match);
        }));
    }

    public void tick(MinecraftServer server, LuckyBlockTracker tracker) {
        List<String> timedOut = new ArrayList<>();
        long tick = server.getTickCount();
        Instant now = Instant.now();
        tickRespawns(server, tick);
        resets.tick(server, EscadonaConfig.RESET_BLOCKS_PER_TICK.get());
        for (ArenaResetManager.CompletedReset completed : resets.drainCompleted()) {
            completePhysicalReset(completed);
        }
        for (Match match : List.copyOf(matches.values())) {
            if (match.state() == GameState.STARTING) tickStarting(server, match, tick);
            if (match.state() == GameState.PLAYING) {
                tickBarriers(server, match, tick);
                tickFinishLine(server, match, tracker, now, tick);
                if (match.hasTimedOut(now)) timedOut.add(match.arena().id());
            }
            if (match.state() == GameState.FINISHING && match.finishingIsDue(now)) finishNormally(server, match, tracker);
        }
        for (String arenaId : timedOut) {
            findByArena(arenaId).ifPresent(match -> beginFinishing(server, match, true));
        }
    }

    private void tickRespawns(MinecraftServer server, long tick) {
        for (RespawnManager.PendingRespawn pending : respawnManager.all()) {
            ServerPlayer online = server.getPlayerList().getPlayer(pending.key().playerId());
            if (online == null) continue;
            Match match = matches.get(pending.key().matchId());
            RacePlayer racePlayer = match == null ? null : match.player(pending.key().playerId()).orElse(null);
            if (pending.matchEnded()) {
                completePendingRespawn(server, pending, online, null);
                continue;
            }
            if (match == null || match.state() != GameState.PLAYING || racePlayer == null) continue;
            pending.pollCountdownCue(tick).ifPresent(cue -> online.connection.send(
                    new ClientboundSetSubtitleTextPacket(Component.literal("Respawn em " + cue)
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))));
            if (tick >= pending.dueAtTick()) completePendingRespawn(server, pending, online, racePlayer);
        }
    }

    private boolean completePendingRespawn(MinecraftServer server, RespawnManager.PendingRespawn pending,
                                           ServerPlayer player, RacePlayer racePlayer) {
        boolean returningToLobby = pending.matchEnded();
        if (returningToLobby) {
            if (!lobby.teleportToLobby(player, false)) return false;
        } else {
            teleport(player, pending.target(), server);
        }
        player.setGameMode(GameType.SURVIVAL);
        if (!respawnManager.complete(pending.key(), player)) return false;
        if (returningToLobby) lobby.prepareLobbyInventory(player);
        if (racePlayer != null) {
            racePlayer.setState(PlayerState.ALIVE);
            if (racePlayer.activatePendingInvulnerability(Instant.now(), PVP_INVULNERABILITY_DURATION)) {
                player.sendSystemMessage(ChatMessages.success("Sua invulnerabilidade está ativa por 30 segundos!"));
            }
        }
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("VOLTOU!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
        Match match = matches.get(pending.key().matchId());
        if (match != null) persist(server, match);
        return true;
    }

    private void tickStarting(MinecraftServer server, Match match, long tick) {
        match.pollStartingCue(tick).ifPresent(cue -> {
            for (RacePlayer racePlayer : match.players().values()) {
                ServerPlayer player = server.getPlayerList().getPlayer(racePlayer.playerId());
                if (player == null) continue;
                if (cue == 0) {
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal("COMEÇOU!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
                } else {
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal(Integer.toString(cue)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(
                            Component.literal("Prepare-se").withStyle(ChatFormatting.YELLOW)));
                }
            }
            if (cue == 0) Escadona.LOGGER.info("Match {} entered PLAYING", match.id());
            if (cue == 0) persist(server, match);
        });
    }

    private void tickBarriers(MinecraftServer server, Match match, long tick) {
        BarrierManager.BarrierTickResult result = match.barriers().tick(tick);
        for (BarrierManager.BarrierWarning warning : result.warnings()) {
            broadcast(server, ChatMessages.barrierCountdown("A barreira da parte " + warning.sectionNumber()
                    + " cairá em " + warning.secondsRemaining()
                    + (warning.secondsRemaining() == 1 ? " segundo." : " segundos.")));
        }
        for (int section : result.dueToOpen()) openBarrier(server, match, section);
    }

    private void openBarrier(MinecraftServer server, Match match, int sectionNumber) {
        Barrier barrier = match.arena().barriers().stream()
                .filter(candidate -> candidate.afterSection() == sectionNumber).findFirst().orElse(null);
        if (barrier == null) return;
        ServerLevel level = server.getLevel(match.arena().dimension());
        if (level == null) return;
        for (BlockPos pos : BlockPos.betweenClosed(barrier.region().min(), barrier.region().max())) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        match.barriers().markOpen(sectionNumber);
        broadcast(server, ChatMessages.barrierOpen("A barreira da parte " + sectionNumber + " caiu! Avance!"));
        Escadona.LOGGER.info("Opened barrier after section {} for match {}", sectionNumber, match.id());
        persist(server, match);
    }

    /** Materializes collision for every logical barrier; an empty configured region must still block racers. */
    private void materializeBarrierStates(ServerLevel level, Match match) {
        for (Barrier barrier : match.arena().barriers()) {
            BarrierState state = match.barriers().state(barrier.afterSection());
            for (BlockPos pos : BlockPos.betweenClosed(barrier.region().min(), barrier.region().max())) {
                if (state == BarrierState.OPEN) {
                    if (level.getBlockState(pos).is(Blocks.BARRIER)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                } else if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                    level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private void removeMaterializedBarriers(MinecraftServer server, Match match) {
        ServerLevel level = server.getLevel(match.arena().dimension());
        if (level == null) return;
        for (Barrier barrier : match.arena().barriers()) {
            for (BlockPos pos : BlockPos.betweenClosed(barrier.region().min(), barrier.region().max())) {
                if (level.getBlockState(pos).is(Blocks.BARRIER)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private void tickFinishLine(MinecraftServer server, Match match, LuckyBlockTracker tracker, Instant now, long tick) {
        if (match.arena().finishLine() == null) return;
        for (RacePlayer racePlayer : match.players().values()) {
            if (racePlayer.state() != PlayerState.ALIVE || match.hasPlacement(racePlayer.playerId())) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(racePlayer.playerId());
            if (player == null || !player.level().dimension().equals(match.arena().dimension())) continue;
            if (!match.arena().finishLine().region().contains(player.blockPosition())) continue;
            if (!tracker.isOwnerComplete(match.id(), racePlayer.playerId())) {
                long nextMessage = finishWarningCooldowns.getOrDefault(racePlayer.playerId(), Long.MIN_VALUE);
                if (tick >= nextMessage) {
                    player.displayClientMessage(ChatMessages.warning(
                            "Você ainda precisa quebrar todos os seus Lucky Blocks!"), true);
                    finishWarningCooldowns.put(racePlayer.playerId(), tick + 40);
                }
                continue;
            }
            recordFinish(server, match, racePlayer, player, now);
            if (match.requiredFinishersReached()) {
                beginFinishing(server, match, false);
                return;
            }
        }
    }

    private void recordFinish(MinecraftServer server, Match match, RacePlayer racePlayer, ServerPlayer player, Instant now) {
        PlacementManager.Placement placement = match.recordPlacement(racePlayer.playerId(), now);
        if (placement.position() == 1) creditVictory(match);
        player.setGameMode(GameType.SPECTATOR);
        String ordinal = ordinal(placement.position());
        broadcast(server, ChatMessages.placement(placement.position(), player.getGameProfile().getName(),
                formatDuration(placement.finishTime())));
        ChatFormatting placementColor = placement.position() == 1 ? ChatFormatting.GOLD
                : placement.position() == 2 ? ChatFormatting.WHITE : ChatFormatting.DARK_RED;
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(ordinal.toUpperCase() + " LUGAR!")
                .withStyle(placementColor, ChatFormatting.BOLD)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal(formatDuration(placement.finishTime())).withStyle(ChatFormatting.AQUA)));
        Escadona.LOGGER.info("Player {} placed {} in match {} after {}", racePlayer.playerId(), placement.position(),
                match.id(), placement.finishTime());
        persist(server, match);
    }

    private void beginFinishing(MinecraftServer server, Match match, boolean timeout) {
        if (match.state() != GameState.PLAYING) return;
        if (timeout) broadcast(server, ChatMessages.warning("A partida terminou por limite de tempo."));
        match.beginFinishing(Instant.now(), EscadonaConfig.FINISHING_DURATION_SECONDS.get());
        for (RacePlayer racePlayer : match.players().values()) {
            ServerPlayer online = server.getPlayerList().getPlayer(racePlayer.playerId());
            if (online != null) online.setGameMode(GameType.SPECTATOR);
        }
        announceResults(server, match);
        Escadona.LOGGER.info("Match {} entered FINISHING{} with {} placements", match.id(),
                timeout ? " after timeout" : "", match.placements().size());
        persist(server, match);
    }

    private void announceResults(MinecraftServer server, Match match) {
        broadcast(server, ChatMessages.message("════ RESULTADO FINAL ════", ChatFormatting.GOLD));
        if (match.placements().isEmpty()) {
            broadcast(server, ChatMessages.warning("Nenhum jogador concluiu a corrida."));
            return;
        }
        for (PlacementManager.Placement placement : match.placements()) {
            ServerPlayer online = server.getPlayerList().getPlayer(placement.playerId());
            String name = online == null ? placement.playerId().toString() : online.getGameProfile().getName();
            broadcast(server, ChatMessages.placement(placement.position(), name,
                    formatDuration(placement.finishTime())));
        }
    }

    private void finishNormally(MinecraftServer server, Match match, LuckyBlockTracker tracker) {
        if (match.state() != GameState.FINISHING) return;
        beginPhysicalReset(server, match, tracker);
    }

    private void beginPhysicalReset(MinecraftServer server, Match match, LuckyBlockTracker tracker) {
        if (match.state() != GameState.RESETTING) match.beginResetting();
        List<UUID> playerIds = List.copyOf(match.players().keySet());
        tracker.removeMatch(match.id());
        respawnManager.cancelMatch(match.id());
        for (UUID playerId : playerIds) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                boolean returnedByRespawn = respawnManager.findExitRecovery(playerId)
                        .map(ticket -> completePendingRespawn(server, ticket, player, null)).orElse(false);
                player.setGameMode(GameType.SURVIVAL);
                if (!returnedByRespawn && !lobby.returnToLobby(player)) {
                    Escadona.LOGGER.error("Could not return player {} to the main lobby while resetting arena {}",
                            playerId, match.arena().id());
                }
            }
            matchesByPlayer.remove(playerId);
        }
        finishWarningCooldowns.keySet().removeAll(playerIds);
        try {
            persistRequired(server, match);
            ArenaSnapshot snapshot = resets.snapshots().load(match.arena().id(), server.registryAccess());
            resets.begin(match.arena(), snapshot, match.id());
            Escadona.LOGGER.info("Match {} entered physical RESETTING for arena {}", match.id(), match.arena().id());
        } catch (Exception exception) {
            resets.markFailed(match.arena(), match.id(), exception);
            Escadona.LOGGER.error("Could not begin physical reset for match {}", match.id(), exception);
            Escadona.LOGGER.error("Arena {} remains blocked until /escadona arena reset retry {}",
                    match.arena().id(), match.arena().id());
        }
    }

    private void completePhysicalReset(ArenaResetManager.CompletedReset completed) {
        if (completed.matchId() == null) return;
        Match match = matches.get(completed.matchId());
        if (match == null || match.state() != GameState.RESETTING) return;
        match.completeReset();
        removeMatch(match);
        deleteSession(match.id());
        Escadona.LOGGER.info("Match {} fully closed after physical reset", match.id());
    }

    public boolean shouldProtectLobbyPlayer(UUID playerId, boolean mainLobbyConfigured) {
        Match match = findByPlayer(playerId).orElse(null);
        if (match == null) return LobbyProtectionPolicy.shouldProtect(mainLobbyConfigured, null, null);
        RacePlayer player = match.player(playerId).orElse(null);
        return LobbyProtectionPolicy.shouldProtect(mainLobbyConfigured, match.state(),
                player == null ? null : player.state());
    }

    public boolean shouldPreventBlockBreak(UUID playerId, boolean mainLobbyConfigured) {
        Match match = findByPlayer(playerId).orElse(null);
        return LobbyProtectionPolicy.shouldPreventBlockBreak(mainLobbyConfigured,
                match == null ? null : match.state(), personalBuildMode.contains(playerId));
    }

    public void setPersonalBuildMode(UUID playerId, boolean enabled) {
        if (enabled) personalBuildMode.add(playerId);
        else personalBuildMode.remove(playerId);
    }

    public boolean personalBuildMode(UUID playerId) {
        return personalBuildMode.contains(playerId);
    }

    public boolean shouldBlockPvp(UUID attackerId, UUID victimId) {
        UUID attackerMatch = matchesByPlayer.get(attackerId);
        UUID victimMatch = matchesByPlayer.get(victimId);
        if (attackerMatch == null || !attackerMatch.equals(victimMatch)) return false;
        Match match = matches.get(attackerMatch);
        return match != null && !match.isPvpEnabled();
    }

    public boolean isCombatInvulnerable(UUID playerId) {
        Match match = findByPlayer(playerId).orElse(null);
        if (match == null || match.state() != GameState.PLAYING) return false;
        RacePlayer player = match.player(playerId).orElse(null);
        return player != null && player.state() == PlayerState.ALIVE && player.isCombatInvulnerable(Instant.now());
    }

    public boolean shouldInstantlyKillFromVoid(UUID playerId) {
        Match match = findByPlayer(playerId).orElse(null);
        if (match == null) return false;
        RacePlayer player = match.player(playerId).orElse(null);
        return VoidDamagePolicy.shouldBeInstantlyLethal(match.state(), player == null ? null : player.state());
    }

    private static String ordinal(int position) { return position + "º"; }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    public int persistedSessionCount() {
        try { return sessions.count(); }
        catch (IOException exception) { throw new IllegalStateException("Could not list persisted sessions", exception); }
    }

    public boolean isSessionPersisted(UUID matchId) { return sessions.exists(matchId); }

    public ArenaSnapshot.Metadata createArenaSnapshot(MinecraftServer server, String arenaId) throws IOException {
        if (findByArena(arenaId).isPresent() || resets.isBlocked(arenaId)) {
            throw new IllegalStateException("Não é possível capturar snapshot com Match ou reset ativo");
        }
        Arena arena = arenaManager.find(arenaId).orElseThrow(() -> new IllegalArgumentException("Arena desconhecida: " + arenaId));
        ServerLevel level = server.getLevel(arena.dimension());
        if (level == null) throw new IllegalStateException("Dimensão da Arena não está carregada");
        ArenaSnapshot snapshot = resets.capture(arena, level, EscadonaConfig.MAX_SNAPSHOT_BLOCKS.get(),
                EscadonaConfig.MAX_SNAPSHOT_CHUNKS.get());
        resets.snapshots().save(snapshot, server.registryAccess(), false);
        return snapshot.metadata();
    }

    public ArenaSnapshot arenaSnapshot(MinecraftServer server, String arenaId) throws IOException {
        return resets.snapshots().load(arenaId, server.registryAccess());
    }

    public ArenaSnapshot.Metadata arenaSnapshotMetadata(MinecraftServer server, String arenaId) throws IOException {
        return resets.snapshots().metadata(arenaId, server.registryAccess());
    }

    public void deleteArenaSnapshot(String arenaId) throws IOException {
        if (findByArena(arenaId).isPresent() || resets.isBlocked(arenaId)) {
            throw new IllegalStateException("Não é possível apagar snapshot com Match ou reset ativo");
        }
        resets.snapshots().delete(arenaId);
    }

    public void restoreArenaSnapshot(MinecraftServer server, String arenaId) throws IOException {
        if (findByArena(arenaId).isPresent() || resets.isBlocked(arenaId)) {
            throw new IllegalStateException("Não é possível restaurar manualmente com Match ou reset ativo");
        }
        Arena arena = arenaManager.find(arenaId).orElseThrow(() -> new IllegalArgumentException("Arena desconhecida: " + arenaId));
        ArenaSnapshot snapshot = resets.snapshots().load(arenaId, server.registryAccess());
        resets.begin(arena, snapshot, null);
    }

    public ArenaResetManager.Verification verifyArenaSnapshot(MinecraftServer server, String arenaId) throws IOException {
        Arena arena = arenaManager.find(arenaId).orElseThrow(() -> new IllegalArgumentException("Arena desconhecida: " + arenaId));
        ArenaSnapshot snapshot = resets.snapshots().load(arenaId, server.registryAccess());
        if (!snapshot.isCompatible(arena)) throw new IllegalStateException("Snapshot incompatível com dimensão ou bounds atuais");
        ServerLevel level = server.getLevel(arena.dimension());
        if (level == null) throw new IllegalStateException("Dimensão da Arena não está carregada");
        return resets.verify(snapshot, level);
    }

    public void retryArenaReset(MinecraftServer server, String arenaId) throws Exception {
        Arena arena = arenaManager.find(arenaId).orElseThrow(() -> new IllegalArgumentException("Arena desconhecida: " + arenaId));
        Match match = findByArena(arenaId).orElse(null);
        if (match != null) persistRequired(server, match);
        resets.retry(arena, server);
    }

    public int recoverSessions(MinecraftServer server, LuckyBlockTracker tracker, LuckyBlockResolver resolver) throws IOException {
        int recovered = 0;
        for (MatchSession session : sessions.loadAll(server.registryAccess())) {
            try {
                recoverSession(server, tracker, resolver, session);
                recovered++;
                Escadona.LOGGER.info("Recovered Escadona session {} for arena {} in state {}",
                        session.matchId(), session.arenaId(), session.state());
            } catch (Exception exception) {
                tracker.removeMatch(session.matchId());
                if (session.state() == GameState.RESETTING && matches.containsKey(session.matchId())) {
                    Arena arena = arenaManager.find(session.arenaId()).orElse(null);
                    if (arena != null) resets.markFailed(arena, session.matchId(), exception);
                    Escadona.LOGGER.error("Resetting session {} could not resume and remains blocked for retry",
                            session.matchId(), exception);
                } else {
                    sessions.invalidate(session.matchId());
                    Escadona.LOGGER.error("Session {} is incompatible and was quarantined", session.matchId(), exception);
                }
            }
        }
        return recovered;
    }

    private void recoverSession(MinecraftServer server, LuckyBlockTracker tracker, LuckyBlockResolver resolver,
                                MatchSession session) {
        if (matches.containsKey(session.matchId()) || matchesByArena.containsKey(session.arenaId())) {
            throw new IllegalArgumentException("Duplicate match or arena session");
        }
        Arena arena = arenaManager.find(session.arenaId())
                .orElseThrow(() -> new IllegalArgumentException("Arena does not exist: " + session.arenaId()));
        if (server.getLevel(arena.dimension()) == null) throw new IllegalArgumentException("Arena dimension is not loaded");
        int expectedFinishers = session.initialPlayerCount() < 2 ? 0 : Math.min(3, session.initialPlayerCount() - 1);
        if (session.requiredFinishers() != expectedFinishers) throw new IllegalArgumentException("Invalid requiredFinishers");
        if (session.state() != GameState.WAITING && session.maxDurationSeconds() < 1) {
            throw new IllegalArgumentException("Invalid maximum duration");
        }
        if ((session.state() == GameState.PLAYING || session.state() == GameState.FINISHING)
                && session.startedAt() == null) throw new IllegalArgumentException("Active match has no start timestamp");

        List<RacePlayer> players = new ArrayList<>();
        java.util.Set<UUID> restoredPlayerIds = new java.util.HashSet<>();
        for (MatchSession.PlayerData data : session.players()) {
            if (!restoredPlayerIds.add(data.playerId())) throw new IllegalArgumentException("Duplicate player UUID");
            if (matchesByPlayer.containsKey(data.playerId())) throw new IllegalArgumentException("Player belongs to another match");
            if (!arena.lanes().containsKey(data.laneId())) throw new IllegalArgumentException("Lane does not exist: " + data.laneId());
            GlobalPos checkpoint = globalPos(data.checkpoint());
            if (!checkpoint.dimension().equals(arena.dimension())) throw new IllegalArgumentException("Checkpoint is in another dimension");
            RacePlayer player = new RacePlayer(data.playerId(), data.laneId(), checkpoint);
            PlayerState restoredState = data.state() == PlayerState.FINISHED ? PlayerState.FINISHED : PlayerState.DISCONNECTED;
            player.restoreProgress(data.brokenBlocks(), data.completedSections(), restoredState, data.placement(), data.finishTime());
            player.restorePvpProtection(data.recentPvpDeaths(), data.invulnerabilityPending(), data.invulnerableUntil());
            players.add(player);
        }
        List<PlacementManager.Placement> placements = session.placements().stream()
                .map(data -> new PlacementManager.Placement(data.position(), data.playerId(), data.elapsed(), data.finishedAt())).toList();
        if (placements.stream().anyMatch(placement -> !restoredPlayerIds.contains(placement.playerId()))) {
            throw new IllegalArgumentException("Placement owner is not a participant");
        }
        Match match = Match.restore(session.matchId(), arena, session.state(), session.startedAt(), session.startingEndsAt(),
                session.finishingEndsAt(), session.maxDurationSeconds(), session.initialPlayerCount(), players, placements,
                server.getTickCount(), Instant.now());
        if (match.state() == GameState.FINISHING && match.finishingEndsAt() == null) {
            throw new IllegalArgumentException("Finishing match has no deadline");
        }
        if (match.state() == GameState.PLAYING && match.requiredFinishersReached()) {
            match.beginFinishing(Instant.now(), EscadonaConfig.FINISHING_DURATION_SECONDS.get());
        }

        long nowMs = System.currentTimeMillis();
        for (MatchSession.BarrierData barrier : session.barriers()) {
            if (match.barriers().states().get(barrier.section()) == null) throw new IllegalArgumentException("Unknown barrier section");
            if (barrier.state() == BarrierState.OPEN) match.barriers().restoreOpen(barrier.section());
            else if (barrier.state() == BarrierState.COUNTDOWN) {
                if (barrier.opensAtEpochMs() == null) throw new IllegalArgumentException("Countdown has no deadline");
                long remainingMs = Math.max(0, barrier.opensAtEpochMs() - nowMs);
                match.barriers().restoreCountdown(barrier.section(), server.getTickCount(),
                        (int) Math.max(1, Math.ceil(remainingMs / 50.0D)));
            }
        }
        if (match.state() != GameState.RESETTING) materializeBarrierStates(server.getLevel(arena.dimension()), match);

        List<TrackedLuckyBlock> restoredBlocks = List.of();
        if (match.state() != GameState.WAITING && match.state() != GameState.RESETTING) {
            Block luckyBlock = resolver.resolve().orElseThrow(() -> new IllegalStateException("Configured Lucky Block is unavailable"));
            ServerLevel level = server.getLevel(arena.dimension());
            restoredBlocks = prepareTrackedBlocks(match, tracker, resolver, luckyBlock, level);
            var knownIds = restoredBlocks.stream().map(TrackedLuckyBlock::id).collect(java.util.stream.Collectors.toSet());
            for (RacePlayer player : players) {
                if (!knownIds.containsAll(player.brokenLuckyBlocks())) throw new IllegalArgumentException("Unknown broken tracker id");
            }
            restoredBlocks.forEach(block -> {
                RacePlayer owner = match.player(block.ownerId()).orElseThrow();
                if (owner.brokenLuckyBlocks().contains(block.id())) block.restoreBroken();
            });
        }

        for (MatchSession.RespawnData data : session.respawns()) {
            if (match.player(data.playerId()).isEmpty()) throw new IllegalArgumentException("Respawn owner is not in match");
            respawnManager.restore(match.id(), data.playerId(), data.keepInventory(), data.snapshot(),
                    server.getTickCount(), globalPos(data.respawnTarget()), lobby.requireGlobalPosition(server));
        }
        restoredBlocks.forEach(tracker::track);
        matches.put(match.id(), match);
        matchesByArena.put(arena.id(), match.id());
        creditVictory(match);
        if (match.state() == GameState.RESETTING) {
            respawnManager.cancelMatch(match.id());
            ArenaSnapshot snapshot;
            try {
                snapshot = resets.snapshots().load(arena.id(), server.registryAccess());
            } catch (IOException exception) {
                throw new IllegalStateException("Resetting session has no readable snapshot", exception);
            }
            resets.begin(arena, snapshot, match.id());
        } else {
            players.forEach(player -> matchesByPlayer.put(player.playerId(), match.id()));
        }
    }

    private void persist(MinecraftServer server, Match match) {
        try {
            sessions.save(snapshot(server, match), server.registryAccess());
            Escadona.LOGGER.info("Saved Escadona session {} in state {}", match.id(), match.state());
        } catch (Exception exception) {
            Escadona.LOGGER.error("Could not save Escadona session {}", match.id(), exception);
        }
    }

    private UUID eligibleKiller(Match match, UUID victimId, UUID killerId) {
        if (killerId == null || killerId.equals(victimId)) return null;
        RacePlayer killer = match.player(killerId).orElse(null);
        return killer != null && killer.state() == PlayerState.ALIVE ? killerId : null;
    }

    private void creditKill(Match match, UUID killerId) {
        try {
            statistics.recordKill(killerId);
            Escadona.LOGGER.info("Credited Escadona kill to {} in match {}", killerId, match.id());
        } catch (IOException exception) {
            Escadona.LOGGER.error("Could not persist Escadona kill for {} in match {}", killerId, match.id(), exception);
        }
    }

    private void creditVictory(Match match) {
        PlacementManager.Placement winner = match.placements().stream()
                .filter(placement -> placement.position() == 1).findFirst().orElse(null);
        if (winner == null) return;
        try {
            if (statistics.recordVictory(match.id(), winner.playerId())) {
                Escadona.LOGGER.info("Credited Escadona victory to {} for match {}", winner.playerId(), match.id());
            }
        } catch (IOException exception) {
            Escadona.LOGGER.error("Could not persist Escadona victory for {} in match {}",
                    winner.playerId(), match.id(), exception);
        }
    }

    private void persistRequired(MinecraftServer server, Match match) throws IOException {
        sessions.save(snapshot(server, match), server.registryAccess());
        Escadona.LOGGER.info("Saved required Escadona session {} in state {}", match.id(), match.state());
    }

    private MatchSession snapshot(MinecraftServer server, Match match) {
        List<MatchSession.PlayerData> players = match.players().values().stream().map(player ->
                new MatchSession.PlayerData(player.playerId(), player.laneId(), player.state(), position(player.checkpoint()),
                        player.brokenLuckyBlocks(), player.completedSections(), player.placement().orElse(null),
                        player.finishTime().orElse(null), player.recentPvpDeaths(), player.invulnerabilityPending(),
                        player.invulnerableUntil().orElse(null))).toList();
        List<MatchSession.PlacementData> placements = match.placements().stream().map(placement ->
                new MatchSession.PlacementData(placement.position(), placement.playerId(), placement.finishTime(),
                        placement.finishedAt())).toList();
        long nowMs = System.currentTimeMillis();
        long currentTick = server.getTickCount();
        List<MatchSession.BarrierData> barriers = match.barriers().states().entrySet().stream().map(entry -> {
            var countdown = match.barriers().countdowns().get(entry.getKey());
            Long deadline = countdown == null ? null : nowMs + Math.max(0, countdown.opensAtTick() - currentTick) * 50L;
            return new MatchSession.BarrierData(entry.getKey(), entry.getValue(), deadline);
        }).toList();
        List<MatchSession.RespawnData> respawns = respawnManager.all().stream()
                .filter(ticket -> ticket.key().matchId().equals(match.id()) && !ticket.matchEnded())
                .map(ticket -> new MatchSession.RespawnData(ticket.key().playerId(), ticket.keepInventory(),
                        ticket.snapshot().orElse(null), nowMs + Math.max(0, ticket.dueAtTick() - currentTick) * 50L,
                        position(ticket.respawnTarget()), position(ticket.exitTarget()))).toList();
        return new MatchSession(match.id(), match.arena().id(), match.state(), match.startedAt(), match.startingEndsAt(),
                match.finishingEndsAt(), match.maxDurationSeconds(), match.initialPlayerCount(), match.requiredFinishers(),
                players, placements, barriers, respawns);
    }

    private void deleteSession(UUID matchId) {
        try { sessions.delete(matchId); }
        catch (IOException exception) { Escadona.LOGGER.error("Could not delete session {}", matchId, exception); }
    }

    private static MatchSession.PositionData position(GlobalPos position) {
        return new MatchSession.PositionData(position.dimension().location().toString(), position.pos().getX(),
                position.pos().getY(), position.pos().getZ());
    }

    private static GlobalPos globalPos(MatchSession.PositionData position) {
        ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(position.dimension()));
        return GlobalPos.of(dimension, new BlockPos(position.x(), position.y(), position.z()));
    }

    public Match stop(MinecraftServer server, String arenaId, LuckyBlockTracker tracker) {
        Match match = findByArena(arenaId).orElseThrow(() -> new IllegalStateException("No active match for arena " + arenaId));
        if (match.state() == GameState.RESETTING) throw new IllegalStateException("A Arena já está restaurando");
        beginPhysicalReset(server, match, tracker);
        Escadona.LOGGER.info("Stopped match {} for arena {}; physical reset started", match.id(), arenaId);
        return match;
    }

    private void removeMatch(Match match) {
        matches.remove(match.id());
        matchesByArena.remove(match.arena().id());
    }

    private static void teleport(ServerPlayer player, net.minecraft.core.GlobalPos target, MinecraftServer server) {
        ServerLevel level = server.getLevel(target.dimension());
        if (level == null) throw new IllegalStateException("Target dimension is not loaded");
        BlockPos pos = target.pos();
        player.teleportTo(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                java.util.Set.<Relative>of(), player.getYRot(), player.getXRot(), false);
    }

    private static void stopFalling(ServerPlayer player) {
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.fallDistance = 0.0F;
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    public record JoinResult(Match match, RacePlayer player) { }
    public record LeaveResult(String arenaId, String laneId) { }
    public record DeathResult(boolean handled, boolean firstOccurrence, UUID killerId) {
        private static DeathResult ignored() { return new DeathResult(false, false, null); }
        private static DeathResult alreadyManaged() { return new DeathResult(true, false, null); }
        private static DeathResult managed(UUID killerId) { return new DeathResult(true, true, killerId); }
    }
}
