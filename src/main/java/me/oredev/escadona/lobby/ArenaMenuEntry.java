package me.oredev.escadona.lobby;

import me.oredev.escadona.arena.Arena;

import java.util.Locale;

public record ArenaMenuEntry(
        String arenaId,
        String displayName,
        int players,
        int capacity,
        ArenaDisplayState state
) {
    public ArenaMenuEntry {
        if (players < 0 || capacity < 0 || players > capacity) {
            throw new IllegalArgumentException("Invalid arena occupancy");
        }
    }

    public static ArenaMenuEntry from(Arena arena, int players, ArenaDisplayState state) {
        return new ArenaMenuEntry(arena.id(), displayName(arena), players, arena.lanes().size(), state);
    }

    public static String displayName(Arena arena) {
        String configured = arena.settings().get("displayName");
        if (configured != null && !configured.isBlank()) return configured;
        String[] words = arena.id().replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return result.isEmpty() ? arena.id() : result.toString();
    }
}
