package me.oredev.escadona.stats;

/** Lifetime Escadona statistics persisted by player UUID. */
public record PlayerStatistics(int kills, int wins) {
    public static final PlayerStatistics EMPTY = new PlayerStatistics(0, 0);

    public PlayerStatistics {
        if (kills < 0 || wins < 0) throw new IllegalArgumentException("Statistics cannot be negative");
    }

    PlayerStatistics withKill() { return new PlayerStatistics(Math.addExact(kills, 1), wins); }
    PlayerStatistics withWin() { return new PlayerStatistics(kills, Math.addExact(wins, 1)); }
}
