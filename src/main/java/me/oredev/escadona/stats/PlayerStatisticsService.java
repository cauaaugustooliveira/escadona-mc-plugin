package me.oredev.escadona.stats;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** In-memory statistics index with atomic persistence on every awarded kill or victory. */
public final class PlayerStatisticsService {
    private final JsonPlayerStatisticsRepository repository;
    private final Map<UUID, PlayerStatistics> statistics = new LinkedHashMap<>();
    private final Set<UUID> creditedVictories = new LinkedHashSet<>();

    public PlayerStatisticsService(JsonPlayerStatisticsRepository repository) { this.repository = repository; }

    public int load() throws IOException {
        JsonPlayerStatisticsRepository.Data data = repository.load();
        statistics.clear();
        statistics.putAll(data.statistics());
        creditedVictories.clear();
        creditedVictories.addAll(data.creditedVictories());
        return statistics.size();
    }

    public PlayerStatistics get(UUID playerId) {
        return statistics.getOrDefault(playerId, PlayerStatistics.EMPTY);
    }

    public void recordKill(UUID playerId) throws IOException {
        PlayerStatistics previous = get(playerId);
        statistics.put(playerId, previous.withKill());
        try {
            persist();
        } catch (IOException exception) {
            restore(playerId, previous);
            throw exception;
        }
    }

    /** Returns false when this Match victory was already credited. */
    public boolean recordVictory(UUID matchId, UUID playerId) throws IOException {
        if (!creditedVictories.add(matchId)) return false;
        PlayerStatistics previous = get(playerId);
        statistics.put(playerId, previous.withWin());
        try {
            persist();
            return true;
        } catch (IOException exception) {
            creditedVictories.remove(matchId);
            restore(playerId, previous);
            throw exception;
        }
    }

    private void persist() throws IOException { repository.save(statistics, creditedVictories); }

    private void restore(UUID playerId, PlayerStatistics previous) {
        if (previous.equals(PlayerStatistics.EMPTY)) statistics.remove(playerId);
        else statistics.put(playerId, previous);
    }
}
