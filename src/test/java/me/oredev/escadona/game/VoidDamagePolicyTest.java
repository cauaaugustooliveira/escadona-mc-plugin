package me.oredev.escadona.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidDamagePolicyTest {
    @Test
    void alivePlayingParticipantReceivesInstantLethalVoidDamage() {
        assertTrue(VoidDamagePolicy.shouldBeInstantlyLethal(GameState.PLAYING, PlayerState.ALIVE));
    }

    @Test
    void lobbyWaitingAndRespawningPlayersKeepTheirExistingProtectionFlow() {
        assertFalse(VoidDamagePolicy.shouldBeInstantlyLethal(null, null));
        assertFalse(VoidDamagePolicy.shouldBeInstantlyLethal(GameState.WAITING, PlayerState.ALIVE));
        assertFalse(VoidDamagePolicy.shouldBeInstantlyLethal(GameState.STARTING, PlayerState.ALIVE));
        assertFalse(VoidDamagePolicy.shouldBeInstantlyLethal(GameState.PLAYING, PlayerState.DEAD_RESPAWNING));
        assertFalse(VoidDamagePolicy.shouldBeInstantlyLethal(GameState.FINISHING, PlayerState.ALIVE));
    }
}
