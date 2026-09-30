package me.oredev.escadona.game;

/** Pure routing policy for players that crossed below their dimension's build floor. */
public final class VoidRescuePolicy {
    private VoidRescuePolicy() { }

    public static Destination destination(boolean belowWorld, boolean hasMatch, GameState matchState,
                                          boolean mainLobbyConfigured) {
        if (!belowWorld) return Destination.NONE;
        if (hasMatch) return matchState == GameState.WAITING ? Destination.LANE_SPAWN : Destination.NONE;
        return mainLobbyConfigured ? Destination.MAIN_LOBBY : Destination.NONE;
    }

    public enum Destination { NONE, MAIN_LOBBY, LANE_SPAWN }
}
