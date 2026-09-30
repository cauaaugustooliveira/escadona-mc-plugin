package me.oredev.escadona.reset;

import me.oredev.escadona.arena.Arena;
import me.oredev.escadona.arena.BlockRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact immutable physical template: implicit positions + palette-indexed BlockStates. */
public final class ArenaSnapshot {
    public static final int SCHEMA_VERSION = 2;
    public static final String FORMAT = "escadona-palette-nbt-gzip-v2";

    private final Metadata metadata;
    private final List<BlockState> palette;
    private final int[] stateIndices;
    private final Map<Integer, CompoundTag> blockEntities;

    public ArenaSnapshot(Metadata metadata, List<BlockState> palette, int[] stateIndices,
                         Map<Integer, CompoundTag> blockEntities) {
        this.metadata = metadata;
        this.palette = List.copyOf(palette);
        this.stateIndices = stateIndices.clone();
        LinkedHashMap<Integer, CompoundTag> copiedEntities = new LinkedHashMap<>();
        blockEntities.forEach((index, tag) -> copiedEntities.put(index, tag.copy()));
        this.blockEntities = Map.copyOf(copiedEntities);
        validate();
    }

    public Metadata metadata() { return metadata; }
    public int size() { return stateIndices.length; }
    public List<BlockState> palette() { return palette; }
    public int[] stateIndices() { return stateIndices.clone(); }
    public Map<Integer, CompoundTag> blockEntities() {
        LinkedHashMap<Integer, CompoundTag> result = new LinkedHashMap<>();
        blockEntities.forEach((index, tag) -> result.put(index, tag.copy()));
        return Map.copyOf(result);
    }

    public int[] blockEntityIndices() {
        return blockEntities.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    public BlockPos positionAt(int index) {
        requireIndex(index);
        return position(index);
    }

    public BlockState stateAt(int index) {
        requireIndex(index);
        return palette.get(stateIndices[index]);
    }

    public CompoundTag blockEntityAt(int index) {
        requireIndex(index);
        CompoundTag blockEntity = blockEntities.get(index);
        return blockEntity == null ? null : blockEntity.copy();
    }

    public boolean hasBlockEntityAt(int index) {
        requireIndex(index);
        return blockEntities.containsKey(index);
    }

    public BlockData block(int index) {
        return new BlockData(positionAt(index), stateAt(index), blockEntityAt(index));
    }

    public boolean isCompatible(Arena arena) {
        return metadata.arenaId().equals(arena.id())
                && metadata.dimension().equals(arena.dimension())
                && metadata.region().equals(arena.region());
    }

    private BlockPos position(int index) {
        int width = metadata.width();
        int length = metadata.length();
        int xOffset = index % width;
        int planeIndex = index / width;
        int zOffset = planeIndex % length;
        int yOffset = planeIndex / length;
        BlockPos min = metadata.region().min();
        return new BlockPos(min.getX() + xOffset, min.getY() + yOffset, min.getZ() + zOffset);
    }

    private void requireIndex(int index) {
        if (index < 0 || index >= stateIndices.length) throw new IndexOutOfBoundsException(index);
    }

    private void validate() {
        if (metadata.blockCount() > Integer.MAX_VALUE || metadata.blockCount() != stateIndices.length) {
            throw new IllegalArgumentException("Snapshot block count does not match compact payload");
        }
        if (metadata.region().volume() != stateIndices.length) {
            throw new IllegalArgumentException("Snapshot payload does not cover the complete region");
        }
        if (palette.isEmpty()) throw new IllegalArgumentException("Snapshot palette is empty");
        for (int paletteIndex : stateIndices) {
            if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                throw new IllegalArgumentException("Snapshot contains an invalid palette index");
            }
        }
        for (Integer index : blockEntities.keySet()) {
            if (index == null || index < 0 || index >= stateIndices.length) {
                throw new IllegalArgumentException("Block Entity index is outside snapshot bounds");
            }
        }
    }

    public static Builder builder(Metadata metadata) { return new Builder(metadata); }

    public static final class Builder {
        private final Metadata metadata;
        private final List<BlockState> palette = new ArrayList<>();
        private final Map<BlockState, Integer> paletteLookup = new LinkedHashMap<>();
        private final int[] stateIndices;
        private final Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
        private int cursor;

        private Builder(Metadata metadata) {
            this.metadata = metadata;
            if (metadata.blockCount() > Integer.MAX_VALUE) throw new IllegalArgumentException("Snapshot is too large");
            this.stateIndices = new int[(int) metadata.blockCount()];
        }

        public void add(BlockState state, CompoundTag blockEntity) {
            if (cursor >= stateIndices.length) throw new IllegalStateException("Too many snapshot blocks");
            int paletteIndex = paletteLookup.computeIfAbsent(state, ignored -> {
                palette.add(state);
                return palette.size() - 1;
            });
            stateIndices[cursor] = paletteIndex;
            if (blockEntity != null) blockEntities.put(cursor, blockEntity.copy());
            cursor++;
        }

        public ArenaSnapshot build() {
            if (cursor != stateIndices.length) throw new IllegalStateException("Snapshot capture is incomplete");
            return new ArenaSnapshot(metadata, palette, stateIndices, blockEntities);
        }
    }

    public record Metadata(int schemaVersion, String format, String arenaId,
                           ResourceKey<Level> dimension, BlockRegion region,
                           long blockCount, Instant createdAt) {
        public Metadata {
            if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported snapshot schema: " + schemaVersion);
            if (!FORMAT.equals(format)) throw new IllegalArgumentException("Unsupported snapshot format: " + format);
            if (blockCount < 1) throw new IllegalArgumentException("Snapshot must contain blocks");
        }

        public int width() { return region.max().getX() - region.min().getX() + 1; }
        public int height() { return region.max().getY() - region.min().getY() + 1; }
        public int length() { return region.max().getZ() - region.min().getZ() + 1; }
    }

    public record BlockData(BlockPos position, BlockState state, CompoundTag blockEntity) { }
}
