package me.oredev.escadona.game;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlacementManagerTest {
    @Test
    void requiredFinishersFollowsOfficialRule() {
        assertEquals(1, new PlacementManager(2).requiredFinishers());
        assertEquals(2, new PlacementManager(3).requiredFinishers());
        assertEquals(3, new PlacementManager(4).requiredFinishers());
        assertEquals(3, new PlacementManager(10).requiredFinishers());
    }

    @Test
    void placementIsIdempotent() {
        PlacementManager manager = new PlacementManager(4);
        UUID player = UUID.randomUUID();
        Instant finishedAt = Instant.parse("2026-01-01T00:00:10Z");

        var first = manager.record(player, Duration.ofSeconds(10), finishedAt);
        var repeated = manager.record(player, Duration.ofSeconds(20), finishedAt.plusSeconds(10));

        assertSame(first, repeated);
        assertEquals(1, manager.placements().size());
    }
}
