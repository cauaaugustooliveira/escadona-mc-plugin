package me.oredev.escadona.session;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import me.oredev.escadona.barrier.BarrierState;
import me.oredev.escadona.game.GameState;
import me.oredev.escadona.game.PlayerState;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonMatchSessionRepositoryTest {
    @Test
    void activeSessionRoundTripsWithDeadlinesAndProgress(@TempDir Path directory) throws Exception {
        UUID matchId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID brokenId = UUID.randomUUID();
        long barrierDeadline = 1_800_000_000_000L;
        Instant pvpDeath = Instant.ofEpochMilli(1_700_000_010_000L);
        Instant invulnerableUntil = Instant.ofEpochMilli(1_700_000_040_000L);
        MatchSession session = new MatchSession(matchId, "classic", GameState.PLAYING,
                Instant.ofEpochMilli(1_700_000_000_000L), null, null, 1800, 2, 1,
                List.of(new MatchSession.PlayerData(playerId, "a", PlayerState.DISCONNECTED,
                        new MatchSession.PositionData("minecraft:overworld", 1, 64, 2), Set.of(brokenId), Set.of(1),
                        null, null, List.of(pvpDeath), false, invulnerableUntil)),
                List.of(), List.of(new MatchSession.BarrierData(1, BarrierState.COUNTDOWN, barrierDeadline)), List.of());
        JsonMatchSessionRepository repository = new JsonMatchSessionRepository(directory);

        repository.save(session, RegistryAccess.EMPTY);
        MatchSession loaded = repository.loadAll(RegistryAccess.EMPTY).getFirst();

        assertEquals(matchId, loaded.matchId());
        assertEquals(Set.of(brokenId), loaded.players().getFirst().brokenBlocks());
        assertEquals(barrierDeadline, loaded.barriers().getFirst().opensAtEpochMs());
        assertEquals(1, loaded.requiredFinishers());
        assertEquals(List.of(pvpDeath), loaded.players().getFirst().recentPvpDeaths());
        assertEquals(invulnerableUntil, loaded.players().getFirst().invulnerableUntil());
    }

    @Test
    void schemaOneDefaultsPvpProtectionState(@TempDir Path directory) throws Exception {
        UUID matchId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        MatchSession session = new MatchSession(matchId, "legacy", GameState.WAITING,
                null, null, null, 0, 1, 0,
                List.of(new MatchSession.PlayerData(playerId, "a", PlayerState.ALIVE,
                        new MatchSession.PositionData("minecraft:overworld", 0, 64, 0),
                        Set.of(), Set.of(), null, null)), List.of(), List.of(), List.of());
        JsonMatchSessionRepository repository = new JsonMatchSessionRepository(directory);
        repository.save(session, RegistryAccess.EMPTY);
        Path file = directory.resolve(matchId + ".json");
        var json = JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
        json.addProperty("schemaVersion", 1);
        var player = json.getAsJsonArray("players").get(0).getAsJsonObject();
        player.remove("recentPvpDeaths");
        player.remove("invulnerabilityPending");
        player.remove("invulnerableUntil");
        java.nio.file.Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json));

        MatchSession.PlayerData loaded = repository.loadAll(RegistryAccess.EMPTY).getFirst().players().getFirst();
        assertEquals(List.of(), loaded.recentPvpDeaths());
        assertEquals(false, loaded.invulnerabilityPending());
        assertEquals(null, loaded.invulnerableUntil());
    }
}
