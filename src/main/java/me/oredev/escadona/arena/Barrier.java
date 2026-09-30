package me.oredev.escadona.arena;

public record Barrier(int afterSection, BlockRegion region) {
    public Barrier {
        if (afterSection < 1) throw new IllegalArgumentException("Barrier section must be positive");
    }
}
