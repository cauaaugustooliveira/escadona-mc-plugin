package me.oredev.escadona.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RacePlayerPvpProtectionTest {
    private static final Duration WINDOW = Duration.ofSeconds(30);

    @Test
    void thirdPvpDeathInsideRollingWindowQueuesProtection() {
        RacePlayer player = player();
        Instant first = Instant.parse("2026-09-29T12:00:00Z");

        assertFalse(player.recordPvpDeath(first, WINDOW, 3));
        assertFalse(player.recordPvpDeath(first.plusSeconds(15), WINDOW, 3));
        assertTrue(player.recordPvpDeath(first.plusSeconds(30), WINDOW, 3));
        assertTrue(player.invulnerabilityPending());
    }

    @Test
    void deathsOlderThanWindowDoNotCount() {
        RacePlayer player = player();
        Instant first = Instant.parse("2026-09-29T12:00:00Z");

        assertFalse(player.recordPvpDeath(first, WINDOW, 3));
        assertFalse(player.recordPvpDeath(first.plusSeconds(20), WINDOW, 3));
        assertFalse(player.recordPvpDeath(first.plusSeconds(31), WINDOW, 3));
    }

    @Test
    void pendingProtectionStartsAtRespawnAndExpiresAfterThirtySeconds() {
        RacePlayer player = player();
        Instant death = Instant.parse("2026-09-29T12:00:00Z");
        player.recordPvpDeath(death, WINDOW, 1);
        Instant respawn = death.plusSeconds(5);

        assertFalse(player.isCombatInvulnerable(respawn));
        assertTrue(player.activatePendingInvulnerability(respawn, Duration.ofSeconds(30)));
        assertTrue(player.isCombatInvulnerable(respawn.plusSeconds(29)));
        assertFalse(player.isCombatInvulnerable(respawn.plusSeconds(30)));
    }

    private static RacePlayer player() {
        return new RacePlayer(UUID.randomUUID(), "a",
                GlobalPos.of(Level.OVERWORLD, new BlockPos(0, 64, 0)));
    }
}
