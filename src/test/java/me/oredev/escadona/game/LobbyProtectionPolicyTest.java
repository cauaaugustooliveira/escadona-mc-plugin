package me.oredev.escadona.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyProtectionPolicyTest {
    @Test
    void mainLobbyProtectsPlayersWithoutMatch() {
        assertTrue(LobbyProtectionPolicy.shouldProtect(true, null, null));
        assertFalse(LobbyProtectionPolicy.shouldProtect(false, null, null));
    }

    @Test
    void waitingAndStartingPlayersAreProtected() {
        assertTrue(LobbyProtectionPolicy.shouldProtect(false, GameState.WAITING, PlayerState.ALIVE));
        assertTrue(LobbyProtectionPolicy.shouldProtect(false, GameState.STARTING, PlayerState.ALIVE));
    }

    @Test
    void onlyAlivePlayingPlayersLoseProtection() {
        assertFalse(LobbyProtectionPolicy.shouldProtect(true, GameState.PLAYING, PlayerState.ALIVE));
        assertTrue(LobbyProtectionPolicy.shouldProtect(true, GameState.PLAYING, PlayerState.DEAD_RESPAWNING));
        assertTrue(LobbyProtectionPolicy.shouldProtect(true, GameState.PLAYING, PlayerState.FINISHED));
    }

    @Test
    void blockBreakingIsPreventedOnlyInMainLobbyAndWaiting() {
        assertTrue(LobbyProtectionPolicy.shouldPreventBlockBreak(true, null, false));
        assertFalse(LobbyProtectionPolicy.shouldPreventBlockBreak(false, null, false));
        assertTrue(LobbyProtectionPolicy.shouldPreventBlockBreak(false, GameState.WAITING, false));
        assertFalse(LobbyProtectionPolicy.shouldPreventBlockBreak(true, GameState.STARTING, false));
        assertFalse(LobbyProtectionPolicy.shouldPreventBlockBreak(true, GameState.PLAYING, false));
        assertFalse(LobbyProtectionPolicy.shouldPreventBlockBreak(true, GameState.FINISHING, false));
        assertFalse(LobbyProtectionPolicy.shouldPreventBlockBreak(true, GameState.RESETTING, false));
    }

    @Test
    void personalBuildModeBypassesLobbyAndWaitingBreakProtection() {
        assertFalse(LobbyProtectionPolicy.shouldPreventBlockBreak(true, null, true));
        assertFalse(LobbyProtectionPolicy.shouldPreventBlockBreak(false, GameState.WAITING, true));
    }
}
