package me.oredev.escadona.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Atomic metadata store for imported worlds. Runtime objects are never persisted. */
public final class JsonWorldRegistry {
    private static final int SCHEMA_VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public JsonWorldRegistry(Path file) { this.file = file; }

    public synchronized Map<String, ImportedWorld> load() throws IOException {
        if (!Files.isRegularFile(file)) return Map.of();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int schema = root.get("schemaVersion").getAsInt();
            JsonArray worlds = root.getAsJsonArray("worlds");
            if (worlds == null) throw new IllegalArgumentException("World registry has no worlds array");
            if (schema == 1) return migrateSchemaOne(worlds);
            if (schema != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported world registry schema: " + schema);
            LinkedHashMap<String, ImportedWorld> result = new LinkedHashMap<>();
            worlds.forEach(element -> {
                JsonObject value = element.getAsJsonObject();
                String name = value.get("name").getAsString();
                ResourceLocation id = ResourceLocation.tryParse(value.get("worldId").getAsString());
                if (!WorldImportManager.isSafeName(name) || id == null || !id.equals(WorldImportManager.worldId(name))) {
                    throw new IllegalArgumentException("Invalid imported world identity: " + value);
                }
                JsonObject spawn = value.getAsJsonObject("spawn");
                ImportedWorld world = new ImportedWorld(name, id, value.get("origin").getAsString(),
                        value.get("storage").getAsString(), WorldImportState.valueOf(value.get("state").getAsString()),
                        Instant.parse(value.get("importedAt").getAsString()), value.get("dataVersion").getAsInt(),
                        value.get("seed").getAsLong(), new BlockPos(spawn.get("x").getAsInt(),
                        spawn.get("y").getAsInt(), spawn.get("z").getAsInt()),
                        value.get("spawnAngle").getAsFloat(), value.has("gameTime") ? value.get("gameTime").getAsLong() : 0L,
                        value.has("dayTime") ? value.get("dayTime").getAsLong() : 0L,
                        value.has("error") ? value.get("error").getAsString() : null);
                if (result.put(name, world) != null) throw new IllegalArgumentException("Duplicate imported world: " + name);
            });
            return Map.copyOf(result);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Escadona world registry: " + file, exception);
        }
    }

    private static Map<String, ImportedWorld> migrateSchemaOne(JsonArray values) {
        LinkedHashMap<String, ImportedWorld> result = new LinkedHashMap<>();
        values.forEach(element -> {
            ResourceLocation id = ResourceLocation.tryParse(element.getAsString());
            if (id == null || !id.getNamespace().equals("escadona") || !WorldImportManager.isSafeName(id.getPath())) return;
            String name = id.getPath();
            result.put(name, new ImportedWorld(name, id, "legacy-registry", "", WorldImportState.INVALID,
                    Instant.EPOCH, 0, 0L, BlockPos.ZERO, 0.0F, 0L, 0L,
                    "Registro legado 0.6.x: reimporte a pasta original com /escadona world load."));
        });
        return Map.copyOf(result);
    }

    public synchronized void save(Collection<ImportedWorld> worlds) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray values = new JsonArray();
        worlds.stream().sorted(Comparator.comparing(ImportedWorld::name)).forEach(world -> {
            JsonObject value = new JsonObject();
            value.addProperty("name", world.name()); value.addProperty("worldId", world.worldId().toString());
            value.addProperty("origin", world.origin()); value.addProperty("storage", world.storage());
            value.addProperty("state", world.state().name()); value.addProperty("importedAt", world.importedAt().toString());
            value.addProperty("dataVersion", world.dataVersion()); value.addProperty("seed", world.seed());
            JsonObject spawn = new JsonObject();
            spawn.addProperty("x", world.spawn().getX()); spawn.addProperty("y", world.spawn().getY()); spawn.addProperty("z", world.spawn().getZ());
            value.add("spawn", spawn); value.addProperty("spawnAngle", world.spawnAngle());
            value.addProperty("gameTime", world.gameTime()); value.addProperty("dayTime", world.dayTime());
            if (world.error() != null) value.addProperty("error", world.error());
            values.add(value);
        });
        root.add("worlds", values);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { GSON.toJson(root, writer); }
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
