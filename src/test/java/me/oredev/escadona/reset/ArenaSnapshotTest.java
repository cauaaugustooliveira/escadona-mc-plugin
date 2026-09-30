package me.oredev.escadona.reset;

import me.oredev.escadona.arena.Arena;
import me.oredev.escadona.arena.BlockRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArenaSnapshotTest {
    @Test
    void metadataNormalizesBoundsAndReportsDimensions() {
        BlockRegion region = new BlockRegion(new BlockPos(2, 5, 9), new BlockPos(0, 4, 7));
        var metadata = new ArenaSnapshot.Metadata(ArenaSnapshot.SCHEMA_VERSION, ArenaSnapshot.FORMAT,
                "classic", Level.OVERWORLD,
                region, 12, Instant.EPOCH);

        assertEquals(new BlockPos(0, 4, 7), region.min());
        assertEquals(3, metadata.width());
        assertEquals(2, metadata.height());
        assertEquals(3, metadata.length());
        assertEquals(18, region.volume());
    }

    @Test
    void compatibilityRejectsChangedBoundsAndDimension() {
        BlockRegion region = new BlockRegion(BlockPos.ZERO, BlockPos.ZERO);
        ArenaSnapshot snapshot = snapshot("classic", Level.OVERWORLD, region);

        assertTrue(snapshot.isCompatible(arena("classic", Level.OVERWORLD, region)));
        assertFalse(snapshot.isCompatible(arena("classic", Level.NETHER, region)));
        assertFalse(snapshot.isCompatible(arena("classic", Level.OVERWORLD,
                new BlockRegion(BlockPos.ZERO, new BlockPos(1, 0, 0)))));
    }

    @Test
    void payloadMustCoverTheWholeRegion() {
        BlockRegion region = new BlockRegion(BlockPos.ZERO, new BlockPos(1, 0, 0));
        var metadata = new ArenaSnapshot.Metadata(ArenaSnapshot.SCHEMA_VERSION, ArenaSnapshot.FORMAT, "classic", Level.OVERWORLD,
                region, 1, Instant.EPOCH);
        assertThrows(IllegalArgumentException.class, () -> new ArenaSnapshot(metadata,
                List.of(Blocks.STONE.defaultBlockState()), new int[]{0}, Map.of()));
    }

    @Test
    void matchPreflightRejectsMissingAndIncompatibleSnapshots() {
        BlockRegion region = new BlockRegion(BlockPos.ZERO, BlockPos.ZERO);
        Arena arena = arena("classic", Level.OVERWORLD, region);
        assertThrows(IllegalStateException.class, () -> ArenaSnapshotValidator.requireCompatible(arena, null));
        assertThrows(IllegalStateException.class, () -> ArenaSnapshotValidator.requireCompatible(arena,
                snapshot("classic", Level.NETHER, region)));
        assertDoesNotThrow(() -> ArenaSnapshotValidator.requireCompatible(arena,
                snapshot("classic", Level.OVERWORLD, region)));
    }

    @Test
    void largeUniformSnapshotUsesOnePaletteEntryAndImplicitPositions() {
        BlockRegion region = new BlockRegion(BlockPos.ZERO, new BlockPos(99, 9, 99));
        var metadata = new ArenaSnapshot.Metadata(ArenaSnapshot.SCHEMA_VERSION, ArenaSnapshot.FORMAT,
                "large", Level.OVERWORLD, region, region.volume(), Instant.EPOCH);
        ArenaSnapshot.Builder builder = ArenaSnapshot.builder(metadata);
        for (int index = 0; index < region.volume(); index++) builder.add(Blocks.STONE.defaultBlockState(), null);

        ArenaSnapshot snapshot = builder.build();

        assertEquals(100_000, snapshot.size());
        assertEquals(1, snapshot.palette().size());
        assertEquals(new BlockPos(99, 9, 99), snapshot.block(snapshot.size() - 1).position());
    }

    @Test
    void chunkCountIncludesBothBoundsAndNegativeCoordinates() {
        assertEquals(1, ArenaResetManager.regionChunkCount(
                new BlockRegion(BlockPos.ZERO, new BlockPos(15, 10, 15))));
        assertEquals(2, ArenaResetManager.regionChunkCount(
                new BlockRegion(BlockPos.ZERO, new BlockPos(16, 10, 15))));
        assertEquals(2, ArenaResetManager.regionChunkCount(
                new BlockRegion(new BlockPos(-1, 0, 0), new BlockPos(0, 10, 15))));
    }

    @Test
    void blockEntityWorkListContainsOnlySparseSortedIndices() {
        BlockRegion region = new BlockRegion(BlockPos.ZERO, new BlockPos(4, 0, 0));
        var metadata = new ArenaSnapshot.Metadata(ArenaSnapshot.SCHEMA_VERSION, ArenaSnapshot.FORMAT,
                "sparse", Level.OVERWORLD, region, region.volume(), Instant.EPOCH);
        CompoundTag first = new CompoundTag();
        first.putString("id", "minecraft:chest");
        CompoundTag second = new CompoundTag();
        second.putString("id", "minecraft:barrel");
        ArenaSnapshot snapshot = new ArenaSnapshot(metadata, List.of(Blocks.STONE.defaultBlockState()),
                new int[]{0, 0, 0, 0, 0}, Map.of(4, second, 1, first));

        assertArrayEquals(new int[]{1, 4}, snapshot.blockEntityIndices());
        assertFalse(snapshot.hasBlockEntityAt(0));
        assertTrue(snapshot.hasBlockEntityAt(1));
    }

    private static ArenaSnapshot snapshot(String id, net.minecraft.resources.ResourceKey<Level> dimension, BlockRegion region) {
        var metadata = new ArenaSnapshot.Metadata(ArenaSnapshot.SCHEMA_VERSION, ArenaSnapshot.FORMAT,
                id, dimension, region, 1, Instant.EPOCH);
        return new ArenaSnapshot(metadata, List.of(Blocks.STONE.defaultBlockState()), new int[]{0}, Map.of());
    }

    private static Arena arena(String id, net.minecraft.resources.ResourceKey<Level> dimension, BlockRegion region) {
        return new Arena(id, dimension, null,
                Map.of(), List.of(), null, region, Map.of());
    }
}
