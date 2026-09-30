package me.oredev.escadona.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class JsonArenaRepositoryTest {
    @Test
    void savesAndLoadsWithoutSerializingMinecraftClasses(@TempDir Path directory) throws Exception {
        ArenaDraft draft = new ArenaDraft("roundtrip");
        draft.setDimension(Level.OVERWORLD);
        draft.setDeathWait(new BlockPos(3, 90, 3));
        draft.setRegionPos1(new BlockPos(-5, 50, -5));
        draft.setRegionPos2(new BlockPos(20, 100, 20));
        draft.addLane("a");
        draft.addLane("b");
        draft.setLaneSpawn("a", new BlockPos(0, 64, 0));
        draft.setLaneSpawn("b", new BlockPos(10, 64, 0));
        draft.addSection(1);
        draft.addLuckyBlock("a", 1, new BlockPos(1, 64, 1));
        draft.addLuckyBlock("b", 1, new BlockPos(11, 64, 1));
        draft.setLaneCheckpoint("a", 1, new BlockPos(2, 65, 2));
        draft.setFinishPos1(new BlockPos(5, 64, 10));
        draft.setFinishPos2(new BlockPos(15, 70, 12));
        draft.setKeepInventoryOnDeath(true);

        JsonArenaRepository repository = new JsonArenaRepository(directory);
        repository.save(draft.toArena());
        String persisted = Files.readString(directory.resolve("roundtrip.json"));
        Arena loaded = repository.loadAll().getFirst();

        assertTrue(persisted.contains("\"schemaVersion\": 3"));
        assertFalse(persisted.contains("\"lobby\""));
        assertEquals("roundtrip", loaded.id());
        assertEquals(Level.OVERWORLD, loaded.dimension());
        assertEquals(2, loaded.lanes().size());
        assertTrue(loaded.keepInventoryOnDeath());
        assertEquals(new BlockPos(3, 90, 3), loaded.deathWait().pos());
        assertEquals("true", loaded.settings().get(Arena.KEEP_INVENTORY_ON_DEATH));
        assertTrue(loaded.lanes().get("a").sections().get(1).requiredLuckyBlocks().contains(new BlockPos(1, 64, 1)));
        assertEquals(new BlockPos(2, 65, 2), loaded.lanes().get("a").sections().get(1).checkpoint().pos());
        assertNull(loaded.lanes().get("b").sections().get(1).checkpoint());
    }

    @Test
    void keepInventoryDefaultsToFalse() {
        assertFalse(new ArenaDraft("default").keepInventoryOnDeath());
    }

    @Test
    void acceptsLegacySchemaAndIgnoresPerArenaLobby(@TempDir Path directory) throws Exception {
        ArenaDraft draft = configuredDraft("legacy");
        JsonArenaRepository repository = new JsonArenaRepository(directory);
        repository.save(draft.toArena());
        Path file = directory.resolve("legacy.json");
        String legacy = Files.readString(file)
                .replace("\"schemaVersion\": 3", "\"schemaVersion\": 1")
                .replace("\"deathWait\"", "\"lobby\": {\"x\": 999, \"y\": 70, \"z\": 999},\n  \"deathWait\"");
        Files.writeString(file, legacy);

        Arena loaded = repository.loadAll().getFirst();

        assertEquals("legacy", loaded.id());
        repository.save(loaded);
        assertFalse(Files.readString(file).contains("\"lobby\""));
    }

    @Test
    void acceptsSchemaTwoWithoutCheckpoints(@TempDir Path directory) throws Exception {
        JsonArenaRepository repository = new JsonArenaRepository(directory);
        repository.save(configuredDraft("schema_two").toArena());
        Path file = directory.resolve("schema_two.json");
        Files.writeString(file, Files.readString(file).replace("\"schemaVersion\": 3", "\"schemaVersion\": 2"));

        Arena loaded = repository.loadAll().getFirst();

        assertNull(loaded.lanes().get("a").sections().get(1).checkpoint());
    }

    private static ArenaDraft configuredDraft(String id) {
        ArenaDraft draft = new ArenaDraft(id);
        draft.setDimension(Level.OVERWORLD);
        draft.setRegionPos1(new BlockPos(-5, 50, -5));
        draft.setRegionPos2(new BlockPos(20, 100, 20));
        draft.addLane("a");
        draft.addLane("b");
        draft.setLaneSpawn("a", new BlockPos(0, 64, 0));
        draft.setLaneSpawn("b", new BlockPos(10, 64, 0));
        draft.addSection(1);
        draft.addLuckyBlock("a", 1, new BlockPos(1, 64, 1));
        draft.addLuckyBlock("b", 1, new BlockPos(11, 64, 1));
        draft.setFinishPos1(new BlockPos(5, 64, 10));
        draft.setFinishPos2(new BlockPos(15, 70, 12));
        return draft;
    }
}
