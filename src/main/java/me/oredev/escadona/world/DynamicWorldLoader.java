package me.oredev.escadona.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

import javax.annotation.Nullable;

/** The only class allowed to touch NeoForge's internal runtime world map. */
final class DynamicWorldLoader {
    private static final ChunkProgressListener SILENT_PROGRESS = new ChunkProgressListener() {
        @Override public void updateSpawnPos(ChunkPos center) { }
        @Override public void onStatusChange(ChunkPos position, @Nullable ChunkStatus status) { }
        @Override public void start() { }
        @Override public void stop() { }
    };

    ServerLevel load(MinecraftServer server, ImportedWorld imported) {
        ServerLevel existing = server.getLevel(imported.dimensionKey());
        if (existing != null) return existing;

        var dimensionTypes = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        var overworldType = dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        var generator = server.overworld().getChunkSource().getGenerator();
        var stem = new LevelStem(overworldType, generator);
        var data = new ImportedLevelData(server.getWorldData(), server.getWorldData().overworldData(),
                imported.name(), imported.spawn(), imported.spawnAngle(), imported.gameTime(), imported.dayTime());
        ServerLevel level = ImportedServerLevel.create(server, server.storageSource, data,
                imported.dimensionKey(), stem, SILENT_PROGRESS, imported.seed());

        server.overworld().getWorldBorder().addListener(
                new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder()));
        server.forgeGetWorldMap().put(imported.dimensionKey(), level);
        server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        return level;
    }

    /**
     * Must be initiated outside the server thread. ServerChunkCache then schedules its own
     * main-thread bookkeeping without making the current tick wait for generation/datafixing.
     */
    CompletableFuture<Void> prepareSpawnAsync(ServerLevel level, ImportedWorld imported) {
        var futures = new ArrayList<CompletableFuture<Void>>();
        forEachTeleportChunk(imported, chunk -> futures.add(
                level.getChunkSource().getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true)
                        .thenAccept(result -> {
                            if (result.orElse(null) == null) {
                                throw new IllegalStateException("Chunk de teleporte indisponível: " + chunk);
                            }
                        })));
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** Keeps the prepared chunk resident long enough for safe-position lookup and teleport. */
    void retainSpawnForTeleport(ServerLevel level, ImportedWorld imported) {
        forEachTeleportChunk(imported, chunk -> level.getChunkSource().addTicket(
                new Ticket(TicketType.PORTAL, ChunkLevel.byStatus(ChunkStatus.FULL)), chunk));
    }

    private static void forEachTeleportChunk(ImportedWorld imported, java.util.function.Consumer<ChunkPos> consumer) {
        int radius = WorldTeleportService.SEARCH_RADIUS;
        int minX = (imported.spawn().getX() - radius) >> 4;
        int maxX = (imported.spawn().getX() + radius) >> 4;
        int minZ = (imported.spawn().getZ() - radius) >> 4;
        int maxZ = (imported.spawn().getZ() + radius) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) consumer.accept(new ChunkPos(x, z));
        }
    }

    Path storagePath(MinecraftServer server, ImportedWorld imported) {
        return server.storageSource.getDimensionPath(imported.dimensionKey()).toAbsolutePath().normalize();
    }
}
