package me.oredev.escadona.reset;

import me.oredev.escadona.Escadona;
import me.oredev.escadona.arena.Arena;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Coordinates deterministic, bounded physical restores for all arenas on one server. */
public final class ArenaResetManager {
    private static final int RESTORE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
    private final ArenaSnapshotRepository snapshots;
    private final Map<String, ResetJob> active = new LinkedHashMap<>();
    private final Map<String, FailedReset> failed = new LinkedHashMap<>();
    private final List<CompletedReset> completed = new ArrayList<>();

    public ArenaResetManager(ArenaSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    public ArenaSnapshotRepository snapshots() { return snapshots; }
    public boolean isBlocked(String arenaId) { return active.containsKey(arenaId) || failed.containsKey(arenaId); }
    public Optional<Progress> progress(String arenaId) {
        ResetJob job = active.get(arenaId);
        if (job != null) return Optional.of(job.progress());
        FailedReset failure = failed.get(arenaId);
        return failure == null ? Optional.empty() : Optional.of(failure.progress());
    }
    public Optional<FailedReset> failure(String arenaId) { return Optional.ofNullable(failed.get(arenaId)); }

    public void begin(Arena arena, ArenaSnapshot snapshot, UUID matchId) {
        if (!snapshot.isCompatible(arena)) throw new IllegalArgumentException("Snapshot incompatível com dimensão ou bounds atuais da Arena");
        if (isBlocked(arena.id())) throw new IllegalStateException("A Arena já está restaurando ou possui reset falho");
        active.put(arena.id(), new ResetJob(arena, snapshot, matchId, Instant.now()));
    }

    public void markFailed(Arena arena, UUID matchId, Exception exception) {
        Progress progress = new Progress(arena.id(), "FAILED_TO_START", arena.region().volume(), 0,
                0, 0, 0, Instant.now(), true, exception.toString());
        failed.put(arena.id(), new FailedReset(arena.id(), matchId, progress, exception.toString()));
    }

    public void retry(Arena arena, MinecraftServer server) throws Exception {
        FailedReset previous = failed.remove(arena.id());
        if (previous == null) throw new IllegalStateException("A Arena não possui reset falho");
        try {
            begin(arena, snapshots.load(arena.id(), server.registryAccess()), previous.matchId());
        } catch (Exception exception) {
            failed.put(arena.id(), previous);
            throw exception;
        }
    }

    public void tick(MinecraftServer server, int blocksPerTick) {
        for (ResetJob job : List.copyOf(active.values())) {
            try {
                ServerLevel level = server.getLevel(job.snapshot.metadata().dimension());
                if (level == null) throw new IllegalStateException("Dimensão do snapshot não está carregada");
                job.tick(level, Math.max(1, blocksPerTick));
                if (job.phase == Phase.COMPLETE) {
                    active.remove(job.arena.id());
                    completed.add(new CompletedReset(job.arena.id(), job.matchId, job.startedAt, Instant.now()));
                    Escadona.LOGGER.info("Arena {} reset complete: {} blocks in {} ms (configured batch {})",
                            job.arena.id(), job.snapshot.metadata().blockCount(),
                            Duration.between(job.startedAt, Instant.now()).toMillis(), blocksPerTick);
                }
            } catch (Exception exception) {
                active.remove(job.arena.id());
                FailedReset failure = new FailedReset(job.arena.id(), job.matchId, job.progress(), exception.toString());
                failed.put(job.arena.id(), failure);
                Escadona.LOGGER.error("Physical reset failed for arena {} at phase {} cursor {}",
                        job.arena.id(), job.phase, job.cursor.current(), exception);
            }
        }
    }

    public List<CompletedReset> drainCompleted() {
        List<CompletedReset> result = List.copyOf(completed);
        completed.clear();
        return result;
    }

    public ArenaSnapshot capture(Arena arena, ServerLevel level, int maxBlocks, int maxChunks) {
        long volume = arena.region().volume();
        if (volume > maxBlocks) {
            throw new IllegalStateException("Região possui " + volume + " blocos; limite configurado: " + maxBlocks);
        }
        long chunkCount = regionChunkCount(arena.region());
        if (chunkCount > maxChunks) {
            throw new IllegalStateException("Região ocupa " + chunkCount + " chunks; limite configurado: "
                    + maxChunks + ". Reduza os bounds ou aumente maxSnapshotChunks conscientemente.");
        }
        loadRegionChunks(level, arena);
        AABB bounds = bounds(arena);
        List<Entity> entities = level.getEntities((Entity) null, bounds,
                entity -> !(entity instanceof ServerPlayer));
        if (!entities.isEmpty()) {
            throw new IllegalStateException("Remova as " + entities.size() + " entidades não-player da região antes de criar o snapshot");
        }
        var metadata = new ArenaSnapshot.Metadata(ArenaSnapshot.SCHEMA_VERSION, ArenaSnapshot.FORMAT,
                arena.id(), arena.dimension(), arena.region(), volume, Instant.now());
        ArenaSnapshot.Builder builder = ArenaSnapshot.builder(metadata);
        BlockPos min = arena.region().min();
        BlockPos max = arena.region().max();
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    level.getChunkAt(pos);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    builder.add(level.getBlockState(pos), blockEntity == null ? null
                            : blockEntity.saveWithFullMetadata(level.registryAccess()));
                }
            }
        }
        return builder.build();
    }

    public Verification verify(ArenaSnapshot snapshot, ServerLevel level) {
        long mismatched = 0;
        long mismatchedBlockEntities = 0;
        for (int index = 0; index < snapshot.size(); index++) {
            ArenaSnapshot.BlockData expected = snapshot.block(index);
            level.getChunkAt(expected.position());
            if (!level.getBlockState(expected.position()).equals(expected.state())) mismatched++;
            BlockEntity current = level.getBlockEntity(expected.position());
            if ((expected.blockEntity() == null) != (current == null)) mismatchedBlockEntities++;
            else if (current != null && !current.saveWithFullMetadata(level.registryAccess()).equals(expected.blockEntity())) {
                mismatchedBlockEntities++;
            }
        }
        return new Verification(snapshot.metadata().blockCount(), mismatched, mismatchedBlockEntities);
    }

    private static AABB bounds(Arena arena) {
        BlockPos min = arena.region().min();
        BlockPos max = arena.region().max();
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D);
    }

    private static void loadRegionChunks(ServerLevel level, Arena arena) {
        int minChunkX = arena.region().min().getX() >> 4;
        int maxChunkX = arena.region().max().getX() >> 4;
        int minChunkZ = arena.region().min().getZ() >> 4;
        int maxChunkZ = arena.region().max().getZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) level.getChunk(chunkX, chunkZ);
        }
    }

    static long regionChunkCount(me.oredev.escadona.arena.BlockRegion region) {
        long width = (long) (region.max().getX() >> 4) - (region.min().getX() >> 4) + 1L;
        long length = (long) (region.max().getZ() >> 4) - (region.min().getZ() >> 4) + 1L;
        return Math.multiplyExact(width, length);
    }

    private enum Phase { CLEAN_INITIAL, BLOCKS, BLOCK_ENTITIES, NEIGHBOR_UPDATES, CLEAN_FINAL, COMPLETE }

    private static final class ResetJob {
        private final Arena arena;
        private final ArenaSnapshot snapshot;
        private final UUID matchId;
        private final Instant startedAt;
        private final int[] blockEntityIndices;
        private final BitSet dirtyBlocks;
        private Phase phase = Phase.CLEAN_INITIAL;
        private IncrementalCursor cursor;
        private int[] dirtyIndices = new int[0];

        private ResetJob(Arena arena, ArenaSnapshot snapshot, UUID matchId, Instant startedAt) {
            this.arena = arena; this.snapshot = snapshot; this.matchId = matchId; this.startedAt = startedAt;
            this.cursor = new IncrementalCursor(snapshot.size());
            this.blockEntityIndices = snapshot.blockEntityIndices();
            this.dirtyBlocks = new BitSet(snapshot.size());
        }

        private void tick(ServerLevel level, int limit) {
            if (phase == Phase.CLEAN_INITIAL) {
                clean(level);
                BoundingBox tickBounds = BoundingBox.fromCorners(arena.region().min(), arena.region().max());
                level.getBlockTicks().clearArea(tickBounds);
                level.getFluidTicks().clearArea(tickBounds);
                transition(Phase.BLOCKS, snapshot.size());
                return;
            }
            int processed = phase == Phase.BLOCKS ? cursor.take(limit) : 0;
            int start = cursor.current() - processed;
            for (int index = start; index < start + processed; index++) {
                BlockPos position = snapshot.positionAt(index);
                var expectedState = snapshot.stateAt(index);
                level.getChunkAt(position);
                var currentState = level.getBlockState(position);
                BlockEntity currentBlockEntity = level.getBlockEntity(position);
                boolean stateChanged = !currentState.equals(expectedState);
                boolean blockEntityChanged = currentBlockEntity != null || snapshot.hasBlockEntityAt(index);
                if (stateChanged || blockEntityChanged) dirtyBlocks.set(index);
                if (currentBlockEntity != null) level.removeBlockEntity(position);
                if (stateChanged) level.setBlock(position, expectedState, RESTORE_FLAGS, Block.UPDATE_LIMIT);
            }
            if (phase == Phase.BLOCKS) {
                if (cursor.complete()) transition(Phase.BLOCK_ENTITIES, blockEntityIndices.length);
                return;
            }
            processed = phase == Phase.BLOCK_ENTITIES ? cursor.take(limit) : 0;
            start = cursor.current() - processed;
            for (int cursorIndex = start; cursorIndex < start + processed; cursorIndex++) {
                int blockIndex = blockEntityIndices[cursorIndex];
                BlockPos position = snapshot.positionAt(blockIndex);
                var state = snapshot.stateAt(blockIndex);
                var data = snapshot.blockEntityAt(blockIndex);
                level.getChunkAt(position);
                if (data != null) {
                    BlockEntity restored = BlockEntity.loadStatic(position, state, data, level.registryAccess());
                    if (restored == null) throw new IllegalStateException("Falha ao restaurar Block Entity em " + position.toShortString());
                    level.setBlockEntity(restored);
                    restored.setChanged();
                    level.sendBlockUpdated(position, state, state, Block.UPDATE_CLIENTS);
                }
            }
            if (phase == Phase.BLOCK_ENTITIES) {
                if (cursor.complete()) {
                    dirtyIndices = dirtyBlocks.stream().toArray();
                    transition(Phase.NEIGHBOR_UPDATES, dirtyIndices.length);
                }
                return;
            }
            processed = phase == Phase.NEIGHBOR_UPDATES ? cursor.take(limit) : 0;
            start = cursor.current() - processed;
            for (int cursorIndex = start; cursorIndex < start + processed; cursorIndex++) {
                int blockIndex = dirtyIndices[cursorIndex];
                BlockPos position = snapshot.positionAt(blockIndex);
                var state = snapshot.stateAt(blockIndex);
                level.getChunkAt(position);
                level.updateNeighborsAt(position, state.getBlock());
                if (!state.getFluidState().isEmpty()) {
                    level.scheduleTick(position, state.getFluidState().getType(), 1);
                }
            }
            if (phase == Phase.NEIGHBOR_UPDATES) {
                if (cursor.complete()) transition(Phase.CLEAN_FINAL, 1);
                return;
            }
            if (phase == Phase.CLEAN_FINAL) {
                clean(level);
                cursor.take(1);
                transition(Phase.COMPLETE, 0);
            }
        }

        private void transition(Phase next, int workItems) {
            phase = next;
            cursor = new IncrementalCursor(workItems);
            Escadona.LOGGER.info("Arena {} reset phase {} started ({} work items, {} changed blocks)",
                    arena.id(), next, workItems, dirtyBlocks.cardinality());
        }

        private void clean(ServerLevel level) {
            loadRegionChunks(level, arena);
            level.getEntities((Entity) null, bounds(arena), entity -> !(entity instanceof ServerPlayer))
                    .forEach(Entity::discard);
        }

        private Progress progress() {
            long restored = phase.ordinal() > Phase.BLOCKS.ordinal() ? snapshot.metadata().blockCount()
                    : phase == Phase.BLOCKS ? cursor.current() : 0;
            return new Progress(arena.id(), phase.name(), snapshot.metadata().blockCount(), restored,
                    cursor.current(), cursor.total(), dirtyBlocks.cardinality(), startedAt, false, null);
        }
    }

    public record Progress(String arenaId, String phase, long totalBlocks, long restoredBlocks,
                           long phaseProcessed, long phaseTotal, long changedBlocks,
                           Instant startedAt, boolean failed, String error) {
        public double percentage() { return totalBlocks == 0 ? 100.0D : restoredBlocks * 100.0D / totalBlocks; }
        public double phasePercentage() { return phaseTotal == 0 ? 100.0D : phaseProcessed * 100.0D / phaseTotal; }
    }
    public record FailedReset(String arenaId, UUID matchId, Progress lastProgress, String error) {
        public Progress progress() { return new Progress(arenaId, "FAILED", lastProgress.totalBlocks(),
                lastProgress.restoredBlocks(), lastProgress.phaseProcessed(), lastProgress.phaseTotal(),
                lastProgress.changedBlocks(), lastProgress.startedAt(), true, error); }
    }
    public record CompletedReset(String arenaId, UUID matchId, Instant startedAt, Instant completedAt) { }
    public record Verification(long totalBlocks, long mismatchedBlocks, long mismatchedBlockEntities) {
        public boolean valid() { return mismatchedBlocks == 0 && mismatchedBlockEntities == 0; }
    }
}
