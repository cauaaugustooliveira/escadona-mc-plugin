package me.oredev.escadona.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class JsonMainLobbyRepository {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public JsonMainLobbyRepository(Path file) { this.file = file; }

    public void save(MainLobbyLocation lobby) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("dimension", lobby.dimension().location().toString());
        json.addProperty("x", lobby.position().getX());
        json.addProperty("y", lobby.position().getY());
        json.addProperty("z", lobby.position().getZ());
        json.addProperty("yaw", lobby.yaw());
        json.addProperty("pitch", lobby.pitch());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Optional<MainLobbyLocation> load() throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            int schema = json.get("schemaVersion").getAsInt();
            if (schema != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported main lobby schema: " + schema);
            ResourceLocation id = ResourceLocation.tryParse(json.get("dimension").getAsString());
            if (id == null) throw new IllegalArgumentException("Invalid main lobby dimension");
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
            BlockPos position = new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt());
            return Optional.of(new MainLobbyLocation(dimension, position,
                    json.get("yaw").getAsFloat(), json.get("pitch").getAsFloat()));
        }
    }
}
