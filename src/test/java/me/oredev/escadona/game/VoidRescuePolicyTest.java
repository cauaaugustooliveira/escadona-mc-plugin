package me.oredev.escadona.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoidRescuePolicyTest {
    @Test
    void lobbyPlayerBelowWorldReturnsToMainLobby() {
        assertEquals(VoidRescuePolicy.Destination.MAIN_LOBBY,
                VoidRescuePolicy.destination(true, false, null, true));
    }

    @Test
    void waitingParticipantReturnsToLaneSpawn() {
        assertEquals(VoidRescuePolicy.Destination.LANE_SPAWN,
                VoidRescuePolicy.destination(true, true, GameState.WAITING, true));
    }

    @Test
    void playingParticipantKeepsNormalDeathFlow() {
        assertEquals(VoidRescuePolicy.Destination.NONE,
                VoidRescuePolicy.destination(true, true, GameState.PLAYING, true));
    }

    @Test
    void doesNothingAboveWorldOrWithoutConfiguredLobby() {
        assertEquals(VoidRescuePolicy.Destination.NONE,
                VoidRescuePolicy.destination(false, false, null, true));
        assertEquals(VoidRescuePolicy.Destination.NONE,
                VoidRescuePolicy.destination(true, false, null, false));
    }
}
