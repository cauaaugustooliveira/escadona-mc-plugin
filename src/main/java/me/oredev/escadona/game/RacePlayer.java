package me.oredev.escadona.game;

import net.minecraft.core.GlobalPos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RacePlayer {
    private final UUID playerId;
    private final String laneId;
    private final Set<UUID> brokenLuckyBlocks = new HashSet<>();
    private final Set<Integer> completedSections = new HashSet<>();
    private final Deque<Instant> recentPvpDeaths = new ArrayDeque<>();
    private PlayerState state = PlayerState.ALIVE;
    private GlobalPos checkpoint;
    private Integer placement;
    private Duration finishTime;
    private boolean invulnerabilityPending;
    private Instant invulnerableUntil;

    public RacePlayer(UUID playerId, String laneId, GlobalPos checkpoint) {
        this.playerId = playerId;
        this.laneId = laneId;
        this.checkpoint = checkpoint;
    }

    public UUID playerId() { return playerId; }
    public String laneId() { return laneId; }
    public PlayerState state() { return state; }
    public void setState(PlayerState state) { this.state = state; }
    public GlobalPos checkpoint() { return checkpoint; }
    public void setCheckpoint(GlobalPos checkpoint) { this.checkpoint = checkpoint; }
    public Set<UUID> brokenLuckyBlocks() { return Set.copyOf(brokenLuckyBlocks); }
    public Set<Integer> completedSections() { return Set.copyOf(completedSections); }
    public Optional<Integer> placement() { return Optional.ofNullable(placement); }
    public Optional<Duration> finishTime() { return Optional.ofNullable(finishTime); }
    public List<Instant> recentPvpDeaths() { return List.copyOf(recentPvpDeaths); }
    public boolean invulnerabilityPending() { return invulnerabilityPending; }
    public Optional<Instant> invulnerableUntil() { return Optional.ofNullable(invulnerableUntil); }

    public boolean recordBroken(UUID blockId) { return brokenLuckyBlocks.add(blockId); }
    public boolean completeSection(int section) { return completedSections.add(section); }

    public boolean recordPvpDeath(Instant now, Duration window, int threshold) {
        if (threshold < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("PvP death protection settings must be positive");
        }
        Instant cutoff = now.minus(window);
        while (!recentPvpDeaths.isEmpty() && recentPvpDeaths.peekFirst().isBefore(cutoff)) {
            recentPvpDeaths.removeFirst();
        }
        recentPvpDeaths.addLast(now);
        if (recentPvpDeaths.size() < threshold || invulnerabilityPending || isCombatInvulnerable(now)) return false;
        recentPvpDeaths.clear();
        invulnerabilityPending = true;
        return true;
    }

    public boolean activatePendingInvulnerability(Instant now, Duration duration) {
        if (!invulnerabilityPending) return false;
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Duration must be positive");
        invulnerabilityPending = false;
        invulnerableUntil = now.plus(duration);
        return true;
    }

    public boolean isCombatInvulnerable(Instant now) {
        return invulnerableUntil != null && now.isBefore(invulnerableUntil);
    }

    public void restorePvpProtection(List<Instant> deaths, boolean pending, Instant until) {
        recentPvpDeaths.clear();
        recentPvpDeaths.addAll(deaths);
        invulnerabilityPending = pending;
        invulnerableUntil = until;
    }

    public void restoreProgress(Set<UUID> brokenBlocks, Set<Integer> sections, PlayerState restoredState,
                                Integer restoredPlacement, Duration restoredFinishTime) {
        brokenLuckyBlocks.clear();
        brokenLuckyBlocks.addAll(brokenBlocks);
        completedSections.clear();
        completedSections.addAll(sections);
        state = restoredState;
        placement = restoredPlacement;
        finishTime = restoredFinishTime;
    }

    public int currentSection() {
        int section = 1;
        while (completedSections.contains(section)) section++;
        return section;
    }

    public void finish(int placement, Duration finishTime) {
        this.placement = placement;
        this.finishTime = finishTime;
        this.state = PlayerState.FINISHED;
    }
}
