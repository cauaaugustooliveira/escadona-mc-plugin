package me.oredev.escadona.luckyblock;

import me.oredev.escadona.Escadona;
import me.oredev.escadona.config.EscadonaConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;
import me.oredev.escadona.game.SectionProgress;

public final class LuckyBlockTracker {
    private final Map<TrackedBlockKey, TrackedLuckyBlock> tracked = new LinkedHashMap<>();
    private final Map<TrackedBlockKey, Long> scheduledRestores = new HashMap<>();
    private long lastScanTick = Long.MIN_VALUE;

    public synchronized void track(TrackedLuckyBlock block) {
        if (tracked.putIfAbsent(block.location(), block) != null) {
            throw new IllegalArgumentException("A Lucky Block is already tracked at " + block.location());
        }
        Escadona.LOGGER.info(
                "Tracking Lucky Block {} at {} for owner {}, lane {}, section {}",
                block.id(), block.location(), block.ownerId(), block.laneId(), block.sectionNumber());
    }

    public synchronized Optional<TrackedLuckyBlock> find(TrackedBlockKey key) {
        return Optional.ofNullable(tracked.get(key));
    }

    public synchronized BreakDecision inspectBreak(TrackedBlockKey key, UUID playerId) {
        TrackedLuckyBlock block = tracked.get(key);
        if (block == null) return BreakDecision.UNTRACKED;
        if (block.state() == LuckyBlockState.BROKEN) return BreakDecision.ALLOW_ALREADY_BROKEN;
        if (!block.isOwner(playerId)) {
            Escadona.LOGGER.info("Denied player {} breaking tracked Lucky Block {} owned by {}", playerId, block.id(), block.ownerId());
            return BreakDecision.DENY_NOT_OWNER;
        }
        return BreakDecision.ALLOW_AND_COMPLETE;
    }

    public synchronized BreakDecision completeBreak(TrackedBlockKey key, UUID playerId) {
        BreakDecision decision = inspectBreak(key, playerId);
        if (decision != BreakDecision.ALLOW_AND_COMPLETE) return decision;
        TrackedLuckyBlock block = tracked.get(key);
        block.markBroken();
        scheduledRestores.remove(key);
        Escadona.LOGGER.info("Lucky Block {} completed by owner {}", block.id(), playerId);
        return BreakDecision.ALLOW_AND_COMPLETE;
    }

    public synchronized void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        int scanInterval = EscadonaConfig.TRACKER_SCAN_INTERVAL_TICKS.get();
        if (lastScanTick == Long.MIN_VALUE || tick - lastScanTick >= scanInterval) {
            lastScanTick = tick;
            scanPending(server, tick);
        }
        restoreDue(server, tick);
    }

    private void scanPending(MinecraftServer server, long tick) {
        long restoreAt = tick + EscadonaConfig.TRACKER_RESTORE_DELAY_TICKS.get();
        for (TrackedLuckyBlock block : tracked.values()) {
            if (block.state() != LuckyBlockState.PENDING) continue;
            ServerLevel level = server.getLevel(block.location().dimension());
            if (level == null || !level.isLoaded(block.location().position())) continue;
            Block expected = BuiltInRegistries.BLOCK.getValue(block.expectedBlockId());
            boolean present = expected != null && level.getBlockState(block.location().position()).is(expected);
            if (present) {
                scheduledRestores.remove(block.location());
            } else if (!scheduledRestores.containsKey(block.location())) {
                scheduledRestores.put(block.location(), restoreAt);
                Escadona.LOGGER.debug("Scheduled restoration of pending Lucky Block {} at tick {}", block.id(), restoreAt);
            }
        }
    }

    private void restoreDue(MinecraftServer server, long tick) {
        List<TrackedBlockKey> processed = new ArrayList<>();
        for (Map.Entry<TrackedBlockKey, Long> entry : scheduledRestores.entrySet()) {
            if (entry.getValue() > tick) continue;
            TrackedLuckyBlock block = tracked.get(entry.getKey());
            if (block == null || block.state() != LuckyBlockState.PENDING) {
                processed.add(entry.getKey());
                continue;
            }
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            if (level == null || !level.isLoaded(entry.getKey().position())) continue;

            Block expected = BuiltInRegistries.BLOCK.getValue(block.expectedBlockId());
            if (expected != null && !level.getBlockState(entry.getKey().position()).is(expected)) {
                level.setBlockAndUpdate(entry.getKey().position(), block.originalState());
                Escadona.LOGGER.info("Restored pending Lucky Block {} at {}", block.id(), block.location());
            }
            processed.add(entry.getKey());
        }
        processed.forEach(scheduledRestores::remove);
    }

    public synchronized int resetAndRestore(MinecraftServer server) {
        int restored = 0;
        scheduledRestores.clear();
        for (TrackedLuckyBlock block : tracked.values()) {
            block.reset();
            ServerLevel level = server.getLevel(block.location().dimension());
            if (level != null && level.isLoaded(block.location().position())) {
                level.setBlockAndUpdate(block.location().position(), block.originalState());
                restored++;
            }
        }
        Escadona.LOGGER.info("Reset {} tracked Lucky Blocks; restored {} loaded positions", tracked.size(), restored);
        return restored;
    }

    public synchronized Collection<TrackedLuckyBlock> all() {
        return List.copyOf(tracked.values());
    }

    public synchronized Collection<TrackedLuckyBlock> forMatch(UUID matchId) {
        return tracked.values().stream().filter(block -> matchId.equals(block.matchId())).toList();
    }

    public synchronized boolean isSectionComplete(UUID matchId, UUID ownerId, String laneId, int sectionNumber) {
        Set<UUID> required = tracked.values().stream()
                .filter(block -> matchId.equals(block.matchId()))
                .filter(block -> ownerId.equals(block.ownerId()))
                .filter(block -> laneId.equals(block.laneId()))
                .filter(block -> sectionNumber == block.sectionNumber())
                .map(TrackedLuckyBlock::id)
                .collect(Collectors.toSet());
        Set<UUID> broken = tracked.values().stream()
                .filter(block -> block.state() == LuckyBlockState.BROKEN)
                .map(TrackedLuckyBlock::id)
                .collect(Collectors.toSet());
        return SectionProgress.isComplete(required, broken);
    }

    public synchronized boolean isOwnerComplete(UUID matchId, UUID ownerId) {
        List<TrackedLuckyBlock> required = tracked.values().stream()
                .filter(block -> matchId.equals(block.matchId()) && ownerId.equals(block.ownerId())).toList();
        return !required.isEmpty() && required.stream().allMatch(block -> block.state() == LuckyBlockState.BROKEN);
    }

    public synchronized SectionStatus sectionStatus(UUID matchId, UUID ownerId, String laneId, int sectionNumber) {
        List<TrackedLuckyBlock> section = tracked.values().stream()
                .filter(block -> matchId.equals(block.matchId()) && ownerId.equals(block.ownerId()))
                .filter(block -> laneId.equals(block.laneId()) && sectionNumber == block.sectionNumber()).toList();
        int broken = (int) section.stream().filter(block -> block.state() == LuckyBlockState.BROKEN).count();
        return new SectionStatus(section.size(), broken);
    }

    public synchronized int removeMatch(UUID matchId) {
        List<TrackedBlockKey> keys = tracked.entrySet().stream()
                .filter(entry -> matchId.equals(entry.getValue().matchId()))
                .map(Map.Entry::getKey).toList();
        keys.forEach(key -> {
            tracked.remove(key);
            scheduledRestores.remove(key);
        });
        return keys.size();
    }

    public synchronized int removeOwner(UUID matchId, UUID ownerId) {
        List<TrackedBlockKey> keys = tracked.entrySet().stream()
                .filter(entry -> matchId.equals(entry.getValue().matchId()) && ownerId.equals(entry.getValue().ownerId()))
                .map(Map.Entry::getKey).toList();
        keys.forEach(key -> {
            tracked.remove(key);
            scheduledRestores.remove(key);
        });
        return keys.size();
    }

    public synchronized TrackerStatus status() {
        long pending = tracked.values().stream().filter(block -> block.state() == LuckyBlockState.PENDING).count();
        long broken = tracked.size() - pending;
        return new TrackerStatus(tracked.size(), (int) pending, (int) broken, scheduledRestores.size());
    }

    public record TrackerStatus(int total, int pending, int broken, int scheduledRestores) { }
    public record SectionStatus(int total, int broken) { }
}
