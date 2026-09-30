package me.oredev.escadona.arena;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.oredev.escadona.Escadona;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class JsonArenaRepository implements ArenaRepository {
    private static final int SCHEMA_VERSION = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;

    public JsonArenaRepository(Path directory) {
        this.directory = directory;
    }

    @Override
    public void save(Arena arena) throws IOException {
        Files.createDirectories(directory);
        Path target = fileFor(arena.id());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(toJson(arena), writer);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public List<Arena> loadAll() throws IOException {
        Files.createDirectories(directory);
        List<Arena> arenas = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    arenas.add(fromJson(JsonParser.parseReader(reader).getAsJsonObject()));
                } catch (Exception exception) {
                    Escadona.LOGGER.error("Could not load arena file {}", file, exception);
                }
            }
        }
        return List.copyOf(arenas);
    }

    private Path fileFor(String id) {
        if (!id.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid arena id: " + id);
        return directory.resolve(id + ".json");
    }

    private JsonObject toJson(Arena arena) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("id", arena.id());
        root.addProperty("dimension", arena.dimension().location().toString());
        root.add("deathWait", arena.deathWait() == null ? null : position(arena.deathWait().pos()));
        root.add("region", region(arena.region()));

        JsonArray lanes = new JsonArray();
        arena.lanes().values().stream().sorted(Comparator.comparing(Lane::id)).forEach(lane -> {
            JsonObject laneJson = new JsonObject();
            laneJson.addProperty("id", lane.id());
            laneJson.add("spawn", position(lane.spawn().pos()));
            JsonArray sections = new JsonArray();
            lane.sections().values().stream().sorted(Comparator.comparingInt(Section::number)).forEach(section -> {
                JsonObject sectionJson = new JsonObject();
                sectionJson.addProperty("number", section.number());
                if (section.checkpoint() != null) {
                    sectionJson.add("checkpoint", position(section.checkpoint().pos()));
                }
                JsonArray blocks = new JsonArray();
                section.requiredLuckyBlocks().stream()
                        .sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                                .thenComparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getZ()))
                        .forEach(pos -> blocks.add(position(pos)));
                sectionJson.add("luckyBlocks", blocks);
                sections.add(sectionJson);
            });
            laneJson.add("sections", sections);
            lanes.add(laneJson);
        });
        root.add("lanes", lanes);

        JsonArray barriers = new JsonArray();
        arena.barriers().stream().sorted(Comparator.comparingInt(Barrier::afterSection)).forEach(barrier -> {
            JsonObject barrierJson = new JsonObject();
            barrierJson.addProperty("afterSection", barrier.afterSection());
            barrierJson.add("region", region(barrier.region()));
            barriers.add(barrierJson);
        });
        root.add("barriers", barriers);
        root.add("finishLine", arena.finishLine() == null ? null : region(arena.finishLine().region()));
        JsonObject settings = new JsonObject();
        arena.settings().forEach(settings::addProperty);
        root.add("settings", settings);
        return root;
    }

    private Arena fromJson(JsonObject root) {
        int schemaVersion = root.get("schemaVersion").getAsInt();
        if (schemaVersion < 1 || schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported arena schema: " + schemaVersion);
        }
        ArenaDraft draft = new ArenaDraft(root.get("id").getAsString());
        ResourceLocation dimensionId = ResourceLocation.parse(root.get("dimension").getAsString());
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        draft.setDimension(dimension);
        if (root.has("deathWait") && !root.get("deathWait").isJsonNull()) {
            draft.setDeathWait(position(root.getAsJsonObject("deathWait")));
        }
        JsonObject arenaRegion = root.getAsJsonObject("region");
        draft.setRegionPos1(position(arenaRegion.getAsJsonObject("min")));
        draft.setRegionPos2(position(arenaRegion.getAsJsonObject("max")));

        for (JsonElement laneElement : root.getAsJsonArray("lanes")) {
            JsonObject laneJson = laneElement.getAsJsonObject();
            String laneId = laneJson.get("id").getAsString();
            draft.addLane(laneId);
            draft.setLaneSpawn(laneId, position(laneJson.getAsJsonObject("spawn")));
            for (JsonElement sectionElement : laneJson.getAsJsonArray("sections")) {
                JsonObject sectionJson = sectionElement.getAsJsonObject();
                draft.addSection(sectionJson.get("number").getAsInt());
            }
        }
        for (JsonElement laneElement : root.getAsJsonArray("lanes")) {
            JsonObject laneJson = laneElement.getAsJsonObject();
            String laneId = laneJson.get("id").getAsString();
            for (JsonElement sectionElement : laneJson.getAsJsonArray("sections")) {
                JsonObject sectionJson = sectionElement.getAsJsonObject();
                int section = sectionJson.get("number").getAsInt();
                if (sectionJson.has("checkpoint") && !sectionJson.get("checkpoint").isJsonNull()) {
                    draft.setLaneCheckpoint(laneId, section, position(sectionJson.getAsJsonObject("checkpoint")));
                }
                for (JsonElement block : sectionJson.getAsJsonArray("luckyBlocks")) {
                    draft.addLuckyBlock(laneId, section, position(block.getAsJsonObject()));
                }
            }
        }
        for (JsonElement barrierElement : root.getAsJsonArray("barriers")) {
            JsonObject barrierJson = barrierElement.getAsJsonObject();
            int section = barrierJson.get("afterSection").getAsInt();
            JsonObject barrierRegion = barrierJson.getAsJsonObject("region");
            draft.setBarrierPos1(section, position(barrierRegion.getAsJsonObject("min")));
            draft.setBarrierPos2(section, position(barrierRegion.getAsJsonObject("max")));
        }
        if (root.has("finishLine") && !root.get("finishLine").isJsonNull()) {
            JsonObject finish = root.getAsJsonObject("finishLine");
            draft.setFinishPos1(position(finish.getAsJsonObject("min")));
            draft.setFinishPos2(position(finish.getAsJsonObject("max")));
        }
        if (root.has("settings")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("settings").entrySet()) {
                draft.putSetting(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return draft.toArena();
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static BlockPos position(JsonObject json) {
        return new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt());
    }

    private static JsonObject region(BlockRegion region) {
        JsonObject json = new JsonObject();
        json.add("min", position(region.min()));
        json.add("max", position(region.max()));
        return json;
    }
}
