package me.oredev.escadona.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.time.Instant;

public record ImportedWorld(String name, ResourceLocation worldId, String origin, String storage,
                            WorldImportState state, Instant importedAt, int dataVersion, long seed,
                            BlockPos spawn, float spawnAngle, long gameTime, long dayTime, String error) {
    public ResourceKey<Level> dimensionKey() { return ResourceKey.create(Registries.DIMENSION, worldId); }

    public ImportedWorld withState(WorldImportState next, String nextError) {
        return new ImportedWorld(name, worldId, origin, storage, next, importedAt, dataVersion,
                seed, spawn, spawnAngle, gameTime, dayTime, nextError);
    }
}
