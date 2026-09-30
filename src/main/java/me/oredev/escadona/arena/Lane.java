package me.oredev.escadona.arena;

import net.minecraft.core.GlobalPos;

import java.util.Map;

public record Lane(String id, GlobalPos spawn, Map<Integer, Section> sections) {
    public Lane {
        sections = Map.copyOf(sections);
    }
}
