package me.oredev.escadona.game;

/** Pure policy for players in the main lobby or protected Match states. Null means no active Match. */
public final class LobbyProtectionPolicy {
    private LobbyProtectionPolicy() { }

    public static boolean shouldProtect(boolean mainLobbyConfigured, GameState matchState, PlayerState playerState) {
        if (matchState == null) return mainLobbyConfigured;
        return matchState != GameState.PLAYING || playerState != PlayerState.ALIVE;
    }

    public static boolean shouldPreventBlockBreak(boolean mainLobbyConfigured, GameState matchState,
                                                  boolean personalBuildMode) {
        if (personalBuildMode) return false;
        if (matchState == null) return mainLobbyConfigured;
        return matchState == GameState.WAITING;
    }
}
