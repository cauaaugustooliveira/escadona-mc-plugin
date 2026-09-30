package me.oredev.escadona.luckyblock;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record TrackedBlockKey(ResourceKey<Level> dimension, BlockPos position) {
    public TrackedBlockKey {
        position = position.immutable();
    }
}
