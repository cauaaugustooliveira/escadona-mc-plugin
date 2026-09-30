package me.oredev.escadona.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** ServerLevel whose generation seed remains the imported save's seed. */
final class ImportedServerLevel extends ServerLevel {
    private static final Map<ResourceKey<Level>, Long> CONSTRUCTING_SEEDS = new ConcurrentHashMap<>();
    private long importedSeed;

    static ImportedServerLevel create(MinecraftServer server,
                                      LevelStorageSource.LevelStorageAccess storage,
                                      ServerLevelData data, ResourceKey<Level> key, LevelStem stem,
                                      ChunkProgressListener listener, long seed) {
        CONSTRUCTING_SEEDS.put(key, seed);
        try {
            ImportedServerLevel level = new ImportedServerLevel(server, storage, data, key, stem, listener, seed);
            level.importedSeed = seed;
            return level;
        } finally {
            CONSTRUCTING_SEEDS.remove(key);
        }
    }

    private ImportedServerLevel(MinecraftServer server, LevelStorageSource.LevelStorageAccess storage,
                                ServerLevelData data, ResourceKey<Level> key, LevelStem stem,
                                ChunkProgressListener listener, long seed) {
        super(server, server.executor, storage, data, key, stem, listener, false,
                net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed), List.of(), false, null);
        this.importedSeed = seed;
    }

    @Override
    public long getSeed() {
        Long pending = CONSTRUCTING_SEEDS.get(dimension());
        return pending == null ? importedSeed : pending;
    }
}
