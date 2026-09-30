package me.oredev.escadona.game;

import java.util.Set;
import java.util.UUID;

public final class SectionProgress {
    private SectionProgress() { }

    public static boolean isComplete(Set<UUID> requiredBlockIds, Set<UUID> brokenBlockIds) {
        return !requiredBlockIds.isEmpty() && brokenBlockIds.containsAll(requiredBlockIds);
    }
}
