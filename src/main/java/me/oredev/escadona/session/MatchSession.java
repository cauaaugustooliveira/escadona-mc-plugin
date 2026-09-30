package me.oredev.escadona.session;

import me.oredev.escadona.barrier.BarrierState;
import me.oredev.escadona.game.GameState;
import me.oredev.escadona.game.PlayerState;
import me.oredev.escadona.respawn.InventorySnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record MatchSession(
        UUID matchId,
        String arenaId,
        GameState state,
        Instant startedAt,
        Instant startingEndsAt,
        Instant finishingEndsAt,
        int maxDurationSeconds,
        int initialPlayerCount,
        int requiredFinishers,
        List<PlayerData> players,
        List<PlacementData> placements,
        List<BarrierData> barriers,
        List<RespawnData> respawns
) {
    public MatchSession {
        players = List.copyOf(players);
        placements = List.copyOf(placements);
        barriers = List.copyOf(barriers);
        respawns = List.copyOf(respawns);
    }

    public record PositionData(String dimension, int x, int y, int z) { }

    public record PlayerData(UUID playerId, String laneId, PlayerState state, PositionData checkpoint,
                             Set<UUID> brokenBlocks, Set<Integer> completedSections,
                             Integer placement, Duration finishTime, List<Instant> recentPvpDeaths,
                             boolean invulnerabilityPending, Instant invulnerableUntil) {
        public PlayerData(UUID playerId, String laneId, PlayerState state, PositionData checkpoint,
                          Set<UUID> brokenBlocks, Set<Integer> completedSections,
                          Integer placement, Duration finishTime) {
            this(playerId, laneId, state, checkpoint, brokenBlocks, completedSections, placement, finishTime,
                    List.of(), false, null);
        }

        public PlayerData {
            brokenBlocks = Set.copyOf(brokenBlocks);
            completedSections = Set.copyOf(completedSections);
            recentPvpDeaths = List.copyOf(recentPvpDeaths);
        }
    }

    public record PlacementData(int position, UUID playerId, Duration elapsed, Instant finishedAt) { }

    public record BarrierData(int section, BarrierState state, Long opensAtEpochMs) { }

    public record RespawnData(UUID playerId, boolean keepInventory, InventorySnapshot snapshot,
                              long dueAtEpochMs, PositionData respawnTarget, PositionData exitTarget) { }
}
