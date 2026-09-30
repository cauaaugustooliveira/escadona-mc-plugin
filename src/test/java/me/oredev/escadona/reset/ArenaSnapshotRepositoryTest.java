package me.oredev.escadona.reset;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArenaSnapshotRepositoryTest {
    @BeforeAll
    static void bootstrap() {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void compressedRoundTripPreservesStateAndBlockEntityAndRefusesSilentOverwrite(@TempDir Path temp) throws Exception {
        ArenaSnapshotRepository repository = new ArenaSnapshotRepository(temp);
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 0); chest.putInt("y", 0); chest.putInt("z", 0);
        var region = new me.oredev.escadona.arena.BlockRegion(BlockPos.ZERO, BlockPos.ZERO);
        var metadata = new ArenaSnapshot.Metadata(ArenaSnapshot.SCHEMA_VERSION, ArenaSnapshot.FORMAT, "classic", Level.OVERWORLD,
                region, 1, Instant.parse("2026-01-01T00:00:00Z"));
        ArenaSnapshot expected = new ArenaSnapshot(metadata, List.of(Blocks.CHEST.defaultBlockState()),
                new int[]{0}, Map.of(0, chest));
        RegistryAccess access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        repository.save(expected, access, false);
        ArenaSnapshot.Metadata loadedMetadata = repository.metadata("classic", access);
        ArenaSnapshot actual = repository.load("classic", access);

        assertEquals(expected.metadata(), loadedMetadata);
        assertEquals(expected.metadata(), actual.metadata());
        assertEquals(expected.block(0).state(), actual.block(0).state());
        assertEquals(chest, actual.block(0).blockEntity());
        assertTrue(Files.isRegularFile(temp.resolve("classic").resolve("metadata.nbt")));
        assertThrows(IllegalStateException.class, () -> repository.save(expected, access, false));
    }

    @Test
    void truncatedSnapshotIsRejected(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("classic").resolve("snapshot.nbt");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3, 4});
        RegistryAccess access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        assertThrows(Exception.class, () -> new ArenaSnapshotRepository(temp).load("classic", access));
    }

    @Test
    void metadataDetectsPayloadCorruptionWithoutDecodingBlocks(@TempDir Path temp) throws Exception {
        ArenaSnapshotRepository repository = new ArenaSnapshotRepository(temp);
        var region = new me.oredev.escadona.arena.BlockRegion(BlockPos.ZERO, BlockPos.ZERO);
        var metadata = new ArenaSnapshot.Metadata(ArenaSnapshot.SCHEMA_VERSION, ArenaSnapshot.FORMAT,
                "classic", Level.OVERWORLD, region, 1, Instant.EPOCH);
        ArenaSnapshot snapshot = new ArenaSnapshot(metadata, List.of(Blocks.STONE.defaultBlockState()),
                new int[]{0}, Map.of());
        RegistryAccess access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        repository.save(snapshot, access, false);
        Files.write(temp.resolve("classic").resolve("snapshot.nbt"), new byte[]{42},
                java.nio.file.StandardOpenOption.APPEND);

        assertThrows(IllegalArgumentException.class, () -> repository.metadata("classic", access));
    }
}
