package me.oredev.escadona.game;

import me.oredev.escadona.arena.Arena;
import me.oredev.escadona.arena.Lane;
import me.oredev.escadona.barrier.BarrierManager;

import java.time.Instant;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Match {
    private final UUID id;
    private final Arena arena;
    private final Map<UUID, RacePlayer> players = new LinkedHashMap<>();
    private final BarrierManager barriers;
    private GameState state = GameState.WAITING;
    private Instant startedAt;
    private Instant startingEndsAt;
    private Instant finishingEndsAt;
    private long startedAtTick = -1;
    private long startingEndsAtTick = -1;
    private int lastStartingCue = Integer.MIN_VALUE;
    private int initialPlayerCount;
    private int maxDurationSeconds;
    private PlacementManager placements;

    public Match(UUID id, Arena arena) {
        this.id = id;
        this.arena = arena;
        this.barriers = new BarrierManager(arena.barriers().stream().map(barrier -> barrier.afterSection()).toList());
    }

    public UUID id() { return id; }
    public Arena arena() { return arena; }
    public GameState state() { return state; }
    public Instant startedAt() { return startedAt; }
    public long startedAtTick() { return startedAtTick; }
    public Instant startingEndsAt() { return startingEndsAt; }
    public Instant finishingEndsAt() { return finishingEndsAt; }
    public int startingRemainingSeconds(long currentTick) {
        if (state != GameState.STARTING || startingEndsAtTick < 0) return 0;
        return (int) Math.ceil(Math.max(0, startingEndsAtTick - currentTick) / 20.0D);
    }
    public int initialPlayerCount() { return initialPlayerCount; }
    public int maxDurationSeconds() { return maxDurationSeconds; }
    public int requiredFinishers() { return placements == null ? 0 : placements.requiredFinishers(); }
    public java.util.List<PlacementManager.Placement> placements() {
        return placements == null ? java.util.List.of() : placements.placements();
    }
    public Map<UUID, RacePlayer> players() { return Map.copyOf(players); }
    public BarrierManager barriers() { return barriers; }
    public boolean isPvpEnabled() { return state == GameState.PLAYING; }

    public RacePlayer join(UUID playerId) {
        if (state != GameState.WAITING) throw new IllegalStateException("Players may only join a waiting match");
        RacePlayer existing = players.get(playerId);
        if (existing != null) return existing;
        Set<String> occupied = players.values().stream().map(RacePlayer::laneId).collect(java.util.stream.Collectors.toSet());
        Lane lane = arena.lanes().values().stream().sorted(java.util.Comparator.comparing(Lane::id))
                .filter(candidate -> !occupied.contains(candidate.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No free lanes are available"));
        RacePlayer player = new RacePlayer(playerId, lane.id(), lane.spawn());
        players.put(playerId, player);
        return player;
    }

    public void addPlayer(RacePlayer player) {
        if (state != GameState.WAITING) throw new IllegalStateException("Players may only join a waiting match");
        if (players.values().stream().anyMatch(current -> current.laneId().equals(player.laneId()))) {
            throw new IllegalStateException("Lane is already occupied: " + player.laneId());
        }
        players.put(player.playerId(), player);
    }

    public RacePlayer removePlayer(UUID playerId) { return players.remove(playerId); }
    public java.util.Optional<RacePlayer> player(UUID playerId) { return java.util.Optional.ofNullable(players.get(playerId)); }

    public void beginStarting(long currentTick, int countdownTicks) {
        beginStarting(currentTick, countdownTicks, 30 * 60, Instant.now());
    }

    public void beginStarting(long currentTick, int countdownTicks, int maxDurationSeconds, Instant now) {
        if (players.size() < 2) throw new IllegalStateException("At least two players are required");
        transitionTo(GameState.STARTING);
        startingEndsAtTick = currentTick + countdownTicks;
        startingEndsAt = now.plusMillis(countdownTicks * 50L);
        lastStartingCue = Integer.MIN_VALUE;
        initialPlayerCount = players.size();
        this.maxDurationSeconds = maxDurationSeconds;
        placements = new PlacementManager(initialPlayerCount);
    }

    /** Returns 3/2/1 once each, then 0 exactly once while transitioning to PLAYING. */
    public java.util.OptionalInt pollStartingCue(long currentTick) {
        if (state != GameState.STARTING) return java.util.OptionalInt.empty();
        int cue = currentTick >= startingEndsAtTick
                ? 0
                : (int) Math.ceil((startingEndsAtTick - currentTick) / 20.0D);
        cue = Math.min(3, cue);
        if (cue == lastStartingCue) return java.util.OptionalInt.empty();
        lastStartingCue = cue;
        if (cue == 0) {
            transitionTo(GameState.PLAYING);
            startedAtTick = currentTick;
            startingEndsAt = null;
        }
        return java.util.OptionalInt.of(cue);
    }

    public boolean hasTimedOut(long currentTick, long maxDurationTicks) {
        return state == GameState.PLAYING && startedAtTick >= 0 && currentTick - startedAtTick >= maxDurationTicks;
    }

    public boolean hasTimedOut(Instant now) {
        return state == GameState.PLAYING && startedAt != null
                && !now.isBefore(startedAt.plusSeconds(maxDurationSeconds));
    }

    public Duration elapsed(Instant now) {
        return startedAt == null ? Duration.ZERO : Duration.between(startedAt, now).isNegative()
                ? Duration.ZERO : Duration.between(startedAt, now);
    }

    public PlacementManager.Placement recordPlacement(UUID playerId, Instant finishedAt) {
        if (state != GameState.PLAYING) throw new IllegalStateException("Placements are only accepted while playing");
        RacePlayer player = players.get(playerId);
        if (player == null) throw new IllegalArgumentException("Unknown player: " + playerId);
        PlacementManager.Placement existing = placements.placements().stream()
                .filter(placement -> placement.playerId().equals(playerId)).findFirst().orElse(null);
        if (existing != null) return existing;
        Duration elapsed = elapsed(finishedAt);
        PlacementManager.Placement placement = placements.record(playerId, elapsed, finishedAt);
        player.finish(placement.position(), elapsed);
        return placement;
    }

    public boolean hasPlacement(UUID playerId) {
        return placements != null && placements.contains(playerId);
    }

    public boolean requiredFinishersReached() {
        return placements != null && placements.podiumComplete();
    }

    public void beginFinishing(Instant now, int durationSeconds) {
        transitionTo(GameState.FINISHING);
        finishingEndsAt = now.plusSeconds(durationSeconds);
    }

    public boolean finishingIsDue(Instant now) {
        return state == GameState.FINISHING && finishingEndsAt != null && !now.isBefore(finishingEndsAt);
    }

    public void beginResetting() {
        if (state != GameState.FINISHING && state != GameState.PLAYING
                && state != GameState.STARTING && state != GameState.WAITING) {
            throw new IllegalStateException("Match cannot begin resetting from " + state);
        }
        barriers.cancelAll();
        transitionTo(GameState.RESETTING);
    }

    public void completeReset() {
        if (state != GameState.RESETTING) throw new IllegalStateException("Match is not resetting");
        transitionTo(GameState.WAITING);
        players.clear();
    }

    public static Match restore(UUID id, Arena arena, GameState restoredState, Instant startedAt,
                                Instant startingEndsAt, Instant finishingEndsAt, int maxDurationSeconds,
                                int initialPlayerCount, Collection<RacePlayer> restoredPlayers,
                                Collection<PlacementManager.Placement> restoredPlacements,
                                long currentTick, Instant now) {
        Match match = new Match(id, arena);
        for (RacePlayer player : restoredPlayers) {
            if (!arena.lanes().containsKey(player.laneId())) throw new IllegalArgumentException("Unknown lane: " + player.laneId());
            if (match.players.values().stream().anyMatch(existing -> existing.laneId().equals(player.laneId()))) {
                throw new IllegalArgumentException("Duplicate lane: " + player.laneId());
            }
            match.players.put(player.playerId(), player);
        }
        if (restoredState != GameState.WAITING && restoredState != GameState.RESETTING
                && (initialPlayerCount < 2 || initialPlayerCount != match.players.size())) {
            throw new IllegalArgumentException("Invalid initial player count");
        }
        match.state = restoredState;
        match.startedAt = restoredState == GameState.WAITING || restoredState == GameState.STARTING
                ? null : (startedAt == null ? now : startedAt);
        match.startingEndsAt = startingEndsAt;
        match.finishingEndsAt = finishingEndsAt;
        match.maxDurationSeconds = maxDurationSeconds;
        match.initialPlayerCount = initialPlayerCount;
        if (restoredState == GameState.STARTING) {
            if (startingEndsAt == null) throw new IllegalArgumentException("Starting match has no deadline");
            long remainingMillis = Math.max(0, startingEndsAt.toEpochMilli() - now.toEpochMilli());
            match.startingEndsAtTick = currentTick + Math.max(1, (long) Math.ceil(remainingMillis / 50.0D));
            match.lastStartingCue = Integer.MIN_VALUE;
        }
        if (restoredState != GameState.WAITING && initialPlayerCount >= 2) {
            match.placements = new PlacementManager(initialPlayerCount);
            restoredPlacements.forEach(match.placements::restore);
        }
        return match;
    }

    public boolean recordSectionCompleted(UUID playerId, int sectionNumber) {
        RacePlayer player = players.get(playerId);
        return player != null && player.completeSection(sectionNumber);
    }

    public void transitionTo(GameState next) {
        boolean valid = switch (state) {
            case WAITING -> next == GameState.STARTING || next == GameState.RESETTING;
            case STARTING -> next == GameState.PLAYING || next == GameState.RESETTING;
            case PLAYING -> next == GameState.FINISHING || next == GameState.RESETTING;
            case FINISHING -> next == GameState.RESETTING;
            case RESETTING -> next == GameState.WAITING;
        };
        if (!valid) throw new IllegalStateException("Invalid game state transition: " + state + " -> " + next);
        state = next;
        if (next == GameState.PLAYING) startedAt = Instant.now();
    }
}
