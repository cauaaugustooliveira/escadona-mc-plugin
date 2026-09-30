package me.oredev.escadona.scoreboard;

import net.minecraft.network.chat.Component;

import java.util.List;

/** Immutable client-facing sidebar contents, independent from packet transport. */
public record ScoreboardView(Component title, List<Component> lines) {
    public static final int MAX_LINES = 15;

    public ScoreboardView {
        lines = List.copyOf(lines);
        if (lines.size() > MAX_LINES) throw new IllegalArgumentException("Sidebar supports at most 15 lines");
    }
}
