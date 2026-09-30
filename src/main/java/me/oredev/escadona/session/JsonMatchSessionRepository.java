package me.oredev.escadona.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import me.oredev.escadona.Escadona;
import me.oredev.escadona.barrier.BarrierState;
import me.oredev.escadona.game.GameState;
import me.oredev.escadona.game.PlayerState;
import me.oredev.escadona.respawn.InventorySnapshot;
import net.minecraft.core.HolderLookup;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class JsonMatchSessionRepository {
    public static final int SCHEMA_VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;

    public JsonMatchSessionRepository(Path directory) { this.directory = directory; }

    public void save(MatchSession session, HolderLookup.Provider registries) throws IOException {
        Files.createDirectories(directory);
        Path target = file(session.matchId());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(toJson(session, registries), writer);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<MatchSession> loadAll(HolderLookup.Provider registries) throws IOException {
        Files.createDirectories(directory);
        List<MatchSession> sessions = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString().endsWith(".json")).toList()) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    sessions.add(fromJson(JsonParser.parseReader(reader).getAsJsonObject(), registries));
                } catch (Exception exception) {
                    Escadona.LOGGER.error("Invalid Escadona session {}; moving it aside", path, exception);
                    quarantine(path);
                }
            }
        }
        return List.copyOf(sessions);
    }

    public void delete(UUID matchId) throws IOException { Files.deleteIfExists(file(matchId)); }

    public void invalidate(UUID matchId) {
        Path path = file(matchId);
        try {
            if (Files.exists(path)) quarantine(path);
        } catch (IOException exception) {
            Escadona.LOGGER.error("Could not quarantine invalid session {}", matchId, exception);
        }
    }

    public int count() throws IOException {
        if (!Files.exists(directory)) return 0;
        try (var paths = Files.list(directory)) {
            return (int) paths.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        }
    }

    public boolean exists(UUID matchId) { return Files.exists(file(matchId)); }

    private void quarantine(Path source) throws IOException {
        Path invalid = directory.resolve("invalid");
        Files.createDirectories(invalid);
        String name = source.getFileName().toString().replace(".json", "-" + System.currentTimeMillis() + ".invalid.json");
        Files.move(source, invalid.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path file(UUID matchId) { return directory.resolve(matchId + ".json"); }

    private static JsonObject toJson(MatchSession session, HolderLookup.Provider registries) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("matchId", session.matchId().toString());
        root.addProperty("arenaId", session.arenaId());
        root.addProperty("state", session.state().name());
        instant(root, "startedAt", session.startedAt());
        instant(root, "startingEndsAt", session.startingEndsAt());
        instant(root, "finishingEndsAt", session.finishingEndsAt());
        root.addProperty("maxDurationSeconds", session.maxDurationSeconds());
        root.addProperty("initialPlayerCount", session.initialPlayerCount());
        root.addProperty("requiredFinishers", session.requiredFinishers());

        JsonArray players = new JsonArray();
        session.players().forEach(player -> {
            JsonObject json = new JsonObject();
            json.addProperty("uuid", player.playerId().toString());
            json.addProperty("lane", player.laneId());
            json.addProperty("state", player.state().name());
            json.add("checkpoint", position(player.checkpoint()));
            JsonArray broken = new JsonArray();
            player.brokenBlocks().forEach(id -> broken.add(id.toString()));
            json.add("brokenBlocks", broken);
            JsonArray sections = new JsonArray();
            player.completedSections().stream().sorted().forEach(sections::add);
            json.add("completedSections", sections);
            if (player.placement() != null) json.addProperty("placement", player.placement());
            if (player.finishTime() != null) json.addProperty("finishTimeMillis", player.finishTime().toMillis());
            JsonArray pvpDeaths = new JsonArray();
            player.recentPvpDeaths().forEach(death -> pvpDeaths.add(death.toEpochMilli()));
            json.add("recentPvpDeaths", pvpDeaths);
            if (player.invulnerabilityPending()) json.addProperty("invulnerabilityPending", true);
            if (player.invulnerableUntil() != null) {
                json.addProperty("invulnerableUntil", player.invulnerableUntil().toEpochMilli());
            }
            players.add(json);
        });
        root.add("players", players);

        JsonArray placements = new JsonArray();
        session.placements().forEach(placement -> {
            JsonObject json = new JsonObject();
            json.addProperty("position", placement.position());
            json.addProperty("uuid", placement.playerId().toString());
            json.addProperty("elapsedMillis", placement.elapsed().toMillis());
            json.addProperty("finishedAt", placement.finishedAt().toEpochMilli());
            placements.add(json);
        });
        root.add("placements", placements);

        JsonArray barriers = new JsonArray();
        session.barriers().forEach(barrier -> {
            JsonObject json = new JsonObject();
            json.addProperty("section", barrier.section());
            json.addProperty("state", barrier.state().name());
            if (barrier.opensAtEpochMs() != null) json.addProperty("opensAt", barrier.opensAtEpochMs());
            barriers.add(json);
        });
        root.add("barriers", barriers);

        JsonArray respawns = new JsonArray();
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        session.respawns().forEach(respawn -> {
            JsonObject json = new JsonObject();
            json.addProperty("uuid", respawn.playerId().toString());
            json.addProperty("keepInventory", respawn.keepInventory());
            json.addProperty("dueAt", respawn.dueAtEpochMs());
            json.add("respawnTarget", position(respawn.respawnTarget()));
            json.add("exitTarget", position(respawn.exitTarget()));
            if (respawn.snapshot() != null) {
                json.add("inventory", InventorySnapshot.CODEC.encodeStart(ops, respawn.snapshot()).getOrThrow());
            }
            respawns.add(json);
        });
        root.add("respawns", respawns);
        return root;
    }

    private static MatchSession fromJson(JsonObject root, HolderLookup.Provider registries) {
        int schema = root.get("schemaVersion").getAsInt();
        if (schema < 1 || schema > SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported session schema: " + schema);
        List<MatchSession.PlayerData> players = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("players")) {
            JsonObject json = element.getAsJsonObject();
            var broken = new HashSet<UUID>();
            json.getAsJsonArray("brokenBlocks").forEach(id -> broken.add(UUID.fromString(id.getAsString())));
            var sections = new HashSet<Integer>();
            json.getAsJsonArray("completedSections").forEach(section -> sections.add(section.getAsInt()));
            Integer placement = json.has("placement") ? json.get("placement").getAsInt() : null;
            Duration finishTime = json.has("finishTimeMillis") ? Duration.ofMillis(json.get("finishTimeMillis").getAsLong()) : null;
            List<Instant> pvpDeaths = new ArrayList<>();
            if (json.has("recentPvpDeaths")) {
                json.getAsJsonArray("recentPvpDeaths").forEach(death ->
                        pvpDeaths.add(Instant.ofEpochMilli(death.getAsLong())));
            }
            boolean protectionPending = json.has("invulnerabilityPending")
                    && json.get("invulnerabilityPending").getAsBoolean();
            Instant invulnerableUntil = json.has("invulnerableUntil")
                    ? Instant.ofEpochMilli(json.get("invulnerableUntil").getAsLong()) : null;
            players.add(new MatchSession.PlayerData(UUID.fromString(json.get("uuid").getAsString()), json.get("lane").getAsString(),
                    PlayerState.valueOf(json.get("state").getAsString()), position(json.getAsJsonObject("checkpoint")),
                    broken, sections, placement, finishTime, pvpDeaths, protectionPending, invulnerableUntil));
        }
        List<MatchSession.PlacementData> placements = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("placements")) {
            JsonObject json = element.getAsJsonObject();
            placements.add(new MatchSession.PlacementData(json.get("position").getAsInt(),
                    UUID.fromString(json.get("uuid").getAsString()), Duration.ofMillis(json.get("elapsedMillis").getAsLong()),
                    Instant.ofEpochMilli(json.get("finishedAt").getAsLong())));
        }
        List<MatchSession.BarrierData> barriers = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("barriers")) {
            JsonObject json = element.getAsJsonObject();
            barriers.add(new MatchSession.BarrierData(json.get("section").getAsInt(),
                    BarrierState.valueOf(json.get("state").getAsString()), json.has("opensAt") ? json.get("opensAt").getAsLong() : null));
        }
        List<MatchSession.RespawnData> respawns = new ArrayList<>();
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        for (JsonElement element : root.getAsJsonArray("respawns")) {
            JsonObject json = element.getAsJsonObject();
            boolean keep = json.get("keepInventory").getAsBoolean();
            InventorySnapshot snapshot = json.has("inventory")
                    ? InventorySnapshot.CODEC.parse(ops, json.get("inventory")).getOrThrow() : null;
            if (keep && snapshot == null) throw new IllegalArgumentException("Missing preserved inventory snapshot");
            respawns.add(new MatchSession.RespawnData(UUID.fromString(json.get("uuid").getAsString()), keep, snapshot,
                    json.get("dueAt").getAsLong(), position(json.getAsJsonObject("respawnTarget")),
                    position(json.getAsJsonObject("exitTarget"))));
        }
        return new MatchSession(UUID.fromString(root.get("matchId").getAsString()), root.get("arenaId").getAsString(),
                GameState.valueOf(root.get("state").getAsString()), readInstant(root, "startedAt"),
                readInstant(root, "startingEndsAt"), readInstant(root, "finishingEndsAt"),
                root.get("maxDurationSeconds").getAsInt(), root.get("initialPlayerCount").getAsInt(),
                root.get("requiredFinishers").getAsInt(), players, placements, barriers, respawns);
    }

    private static JsonObject position(MatchSession.PositionData position) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", position.dimension());
        json.addProperty("x", position.x());
        json.addProperty("y", position.y());
        json.addProperty("z", position.z());
        return json;
    }

    private static MatchSession.PositionData position(JsonObject json) {
        return new MatchSession.PositionData(json.get("dimension").getAsString(), json.get("x").getAsInt(),
                json.get("y").getAsInt(), json.get("z").getAsInt());
    }

    private static void instant(JsonObject json, String key, Instant value) {
        if (value != null) json.addProperty(key, value.toEpochMilli());
    }

    private static Instant readInstant(JsonObject json, String key) {
        return json.has(key) ? Instant.ofEpochMilli(json.get(key).getAsLong()) : null;
    }
}
