package me.oredev.escadona.game;

import me.oredev.escadona.arena.Section;

/** Applies an optional lane checkpoint only on the first confirmed Lucky Block break of its section. */
public final class CheckpointProgress {
    private CheckpointProgress() { }

    public static boolean acquire(RacePlayer player, Section section, boolean newlyBroken, int brokenInSection) {
        if (!newlyBroken || brokenInSection != 1 || section == null || section.checkpoint() == null) return false;
        player.setCheckpoint(section.checkpoint());
        return true;
    }
}
