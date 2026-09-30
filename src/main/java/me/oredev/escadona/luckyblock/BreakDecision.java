package me.oredev.escadona.luckyblock;

public enum BreakDecision {
    UNTRACKED,
    ALLOW_ALREADY_BROKEN,
    DENY_NOT_OWNER,
    ALLOW_AND_COMPLETE
}
