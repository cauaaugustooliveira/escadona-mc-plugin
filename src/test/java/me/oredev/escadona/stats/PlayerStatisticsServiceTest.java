package me.oredev.escadona.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStatisticsServiceTest {
    @Test
    void killsAndVictoriesSurviveReloadAndVictoryIsIdempotent(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("player-stats.json");
        UUID playerId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        PlayerStatisticsService service = new PlayerStatisticsService(new JsonPlayerStatisticsRepository(file));

        assertEquals(0, service.load());
        service.recordKill(playerId);
        service.recordKill(playerId);
        assertTrue(service.recordVictory(matchId, playerId));
        assertFalse(service.recordVictory(matchId, playerId));

        PlayerStatisticsService restored = new PlayerStatisticsService(new JsonPlayerStatisticsRepository(file));
        assertEquals(1, restored.load());
        assertEquals(new PlayerStatistics(2, 1), restored.get(playerId));
        assertFalse(restored.recordVictory(matchId, playerId));
    }

    @Test
    void unknownPlayerStartsWithZeroes(@TempDir Path temp) throws Exception {
        PlayerStatisticsService service = new PlayerStatisticsService(
                new JsonPlayerStatisticsRepository(temp.resolve("player-stats.json")));
        service.load();

        assertEquals(PlayerStatistics.EMPTY, service.get(UUID.randomUUID()));
    }
}
