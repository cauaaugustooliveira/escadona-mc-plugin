package me.oredev.escadona.game;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlacementManager {
    private final int requiredFinishers;
    private final List<Placement> placements = new ArrayList<>();

    public PlacementManager(int initialPlayerCount) {
        if (initialPlayerCount < 2) {
            throw new IllegalArgumentException("A match requires at least two players");
        }
        this.requiredFinishers = Math.min(3, initialPlayerCount - 1);
    }

    public Placement record(UUID playerId, Duration finishTime, Instant finishedAt) {
        Placement existing = find(playerId);
        if (existing != null) return existing;
        Placement placement = new Placement(placements.size() + 1, playerId, finishTime, finishedAt);
        placements.add(placement);
        return placement;
    }

    public void restore(Placement placement) {
        if (find(placement.playerId()) != null) return;
        if (placement.position() != placements.size() + 1) throw new IllegalArgumentException("Placements must be consecutive");
        placements.add(placement);
    }

    public boolean contains(UUID playerId) { return find(playerId) != null; }

    private Placement find(UUID playerId) {
        return placements.stream().filter(placement -> placement.playerId().equals(playerId)).findFirst().orElse(null);
    }

    public int requiredFinishers() { return requiredFinishers; }
    public boolean podiumComplete() { return placements.size() >= requiredFinishers; }
    public List<Placement> placements() { return List.copyOf(placements); }

    public record Placement(int position, UUID playerId, Duration finishTime, Instant finishedAt) { }
}
