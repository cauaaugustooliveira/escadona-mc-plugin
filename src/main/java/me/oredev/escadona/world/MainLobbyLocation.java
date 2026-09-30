package me.oredev.escadona.world;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record MainLobbyLocation(ResourceKey<Level> dimension, BlockPos position, float yaw, float pitch) {
    public MainLobbyLocation {
        position = position.immutable();
    }
}
