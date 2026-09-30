package me.oredev.escadona.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonMainLobbyRepositoryTest {
    @Test
    void roundTripPreservesDimensionPositionAndRotation(@TempDir Path temp) throws Exception {
        JsonMainLobbyRepository repository = new JsonMainLobbyRepository(temp.resolve("main-lobby.json"));
        MainLobbyLocation expected = new MainLobbyLocation(Level.NETHER, new BlockPos(12, 70, -9), 90.0F, 15.0F);

        repository.save(expected);

        assertEquals(expected, repository.load().orElseThrow());
        assertTrue(Files.isRegularFile(temp.resolve("main-lobby.json")));
    }

    @Test
    void missingConfigurationIsNotAnError(@TempDir Path temp) throws Exception {
        assertTrue(new JsonMainLobbyRepository(temp.resolve("main-lobby.json")).load().isEmpty());
    }
}
