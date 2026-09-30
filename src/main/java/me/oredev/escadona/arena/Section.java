package me.oredev.escadona.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;

import java.util.Set;

public record Section(int number, Set<BlockPos> requiredLuckyBlocks, GlobalPos checkpoint) {
    public Section(int number, Set<BlockPos> requiredLuckyBlocks) {
        this(number, requiredLuckyBlocks, null);
    }

    public Section {
        if (number < 1) throw new IllegalArgumentException("Section number must be positive");
        requiredLuckyBlocks = Set.copyOf(requiredLuckyBlocks);
    }
}
