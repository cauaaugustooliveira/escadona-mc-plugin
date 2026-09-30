package me.oredev.escadona.world;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonWorldRegistryTest {
    @Test
    void persistsCompleteSchemaTwoMetadataSorted(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("worlds.json");
        JsonWorldRegistry repository = new JsonWorldRegistry(file);
        ImportedWorld second = world("second", WorldImportState.IMPORTED);
        ImportedWorld arena = world("arena", WorldImportState.LOADED);

        repository.save(List.of(second, arena));

        assertEquals(arena, repository.load().get("arena"));
        assertEquals(second, repository.load().get("second"));
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(2, json.get("schemaVersion").getAsInt());
        assertEquals("arena", json.getAsJsonArray("worlds").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("escadona:arena", json.getAsJsonArray("worlds").get(0).getAsJsonObject().get("worldId").getAsString());
    }

    @Test
    void readsLegacySchemaAsInvalidInsteadOfLosingIt(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("worlds.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"worlds\":[\"escadona:classic\"]}");
        ImportedWorld migrated = new JsonWorldRegistry(file).load().get("classic");
        assertEquals(WorldImportState.INVALID, migrated.state());
        assertEquals("escadona:classic", migrated.worldId().toString());
    }

    private static ImportedWorld world(String name, WorldImportState state) {
        return new ImportedWorld(name, WorldImportManager.worldId(name), "source/" + name,
                "dimensions/escadona/" + name, state, Instant.parse("2026-09-29T00:00:00Z"),
                4440, 123L, new BlockPos(1, 80, 2), 90.0F, 500L, 600L, null);
    }
}
