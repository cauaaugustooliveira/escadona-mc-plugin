package me.oredev.escadona.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Atomic JSON persistence for lifetime statistics and idempotent victory credits. */
public final class JsonPlayerStatisticsRepository {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public JsonPlayerStatisticsRepository(Path file) { this.file = file; }

    public Data load() throws IOException {
        if (!Files.isRegularFile(file)) return new Data(Map.of(), Set.of());
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported player statistics schema");
            }
            Map<UUID, PlayerStatistics> statistics = new LinkedHashMap<>();
            JsonObject players = root.getAsJsonObject("players");
            if (players == null) throw new IllegalArgumentException("Player statistics has no players object");
            for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
                UUID playerId = UUID.fromString(entry.getKey());
                JsonObject value = entry.getValue().getAsJsonObject();
                statistics.put(playerId, new PlayerStatistics(value.get("kills").getAsInt(), value.get("wins").getAsInt()));
            }
            Set<UUID> creditedVictories = new LinkedHashSet<>();
            JsonArray credits = root.getAsJsonArray("creditedVictoryMatches");
            if (credits != null) credits.forEach(value -> creditedVictories.add(UUID.fromString(value.getAsString())));
            return new Data(statistics, creditedVictories);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Escadona player statistics: " + file, exception);
        }
    }

    public void save(Map<UUID, PlayerStatistics> statistics, Set<UUID> creditedVictories) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonObject players = new JsonObject();
        statistics.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> {
                    JsonObject value = new JsonObject();
                    value.addProperty("kills", entry.getValue().kills());
                    value.addProperty("wins", entry.getValue().wins());
                    players.add(entry.getKey().toString(), value);
                });
        root.add("players", players);
        JsonArray credits = new JsonArray();
        creditedVictories.stream().sorted(Comparator.comparing(UUID::toString))
                .forEach(matchId -> credits.add(matchId.toString()));
        root.add("creditedVictoryMatches", credits);

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Data(Map<UUID, PlayerStatistics> statistics, Set<UUID> creditedVictories) {
        public Data {
            statistics = Map.copyOf(statistics);
            creditedVictories = Set.copyOf(creditedVictories);
        }
    }
}
