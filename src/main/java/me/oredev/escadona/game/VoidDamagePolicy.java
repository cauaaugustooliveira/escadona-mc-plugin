package me.oredev.escadona.game;

/** Pure state policy for turning the first vanilla void damage pulse into lethal damage. */
public final class VoidDamagePolicy {
    private VoidDamagePolicy() { }

    public static boolean shouldBeInstantlyLethal(GameState matchState, PlayerState playerState) {
        return matchState == GameState.PLAYING && playerState == PlayerState.ALIVE;
    }
}
