package me.oredev.escadona.world;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldImportManagerTest {
    @Test
    void acceptsOnlySimpleSafeNamesAndRejectsTraversal() {
        assertTrue(WorldImportManager.isSafeName("mapa_escadona"));
        assertTrue(WorldImportManager.isSafeName("arena-2"));
        assertFalse(WorldImportManager.isSafeName("../world"));
        assertFalse(WorldImportManager.isSafeName("ArenA"));
        assertFalse(WorldImportManager.isSafeName("namespace:world"));
        assertEquals("escadona:mapa_escadona", WorldImportManager.worldId("mapa_escadona").toString());
    }

    @Test
    void findsDedicatedImportBeforeServerRoot(@TempDir Path temp) throws Exception {
        Path rootSource = createSave(temp.resolve("classic"));
        Path dedicatedSource = createSave(temp.resolve("escadona/world-imports/classic"));
        try (WorldImportManager manager = new WorldImportManager(
                new JsonWorldRegistry(temp.resolve("registry.json")), temp)) {
            assertEquals(dedicatedSource.toRealPath(), manager.resolveSource("classic"));
            assertNull(manager.resolveSource("missing"));
        }
        assertTrue(Files.exists(rootSource));
    }

    @Test
    void readsLevelDatSeedSpawnAndVersion(@TempDir Path temp) throws Exception {
        Path save = createSave(temp.resolve("fixture"));
        var metadata = WorldImportManager.readSaveMetadata(save);
        assertEquals(987654321L, metadata.seed());
        assertEquals(12, metadata.spawn().getX());
        assertEquals(72, metadata.spawn().getY());
        assertEquals(-4, metadata.spawn().getZ());
        assertEquals(45.0F, metadata.spawnAngle());
        assertEquals(1000L, metadata.gameTime());
        assertEquals(6000L, metadata.dayTime());
    }

    @Test
    void rejectsFolderWithoutLevelDat(@TempDir Path temp) throws Exception {
        Path invalid = Files.createDirectories(temp.resolve("invalid/region"));
        assertThrows(WorldImportManager.InvalidSaveException.class,
                () -> WorldImportManager.readSaveMetadata(invalid.getParent()));
    }

    @Test
    void rejectsSaveFromNewerMinecraft(@TempDir Path temp) throws Exception {
        Path save = Files.createDirectories(temp.resolve("future"));
        Files.createDirectories(save.resolve("region"));
        writeLevelDat(save, SharedConstants.getCurrentVersion().dataVersion().version() + 1);
        assertThrows(WorldImportManager.InvalidSaveException.class,
                () -> WorldImportManager.readSaveMetadata(save));
    }

    private static Path createSave(Path path) throws Exception {
        Files.createDirectories(path.resolve("region"));
        Files.write(path.resolve("region/r.0.0.mca"), new byte[] {0});
        writeLevelDat(path, SharedConstants.getCurrentVersion().dataVersion().version());
        return path;
    }

    private static void writeLevelDat(Path path, int version) throws Exception {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", version);
        data.putInt("SpawnX", 12);
        data.putInt("SpawnY", 72);
        data.putInt("SpawnZ", -4);
        data.putFloat("SpawnAngle", 45.0F);
        data.putLong("Time", 1000L);
        data.putLong("DayTime", 6000L);
        CompoundTag worldGen = new CompoundTag();
        worldGen.putLong("seed", 987654321L);
        data.put("WorldGenSettings", worldGen);
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, path.resolve("level.dat"));
    }
}
