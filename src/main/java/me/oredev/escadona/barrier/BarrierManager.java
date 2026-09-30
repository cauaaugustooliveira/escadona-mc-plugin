package me.oredev.escadona.barrier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class BarrierManager {
    private final Map<Integer, BarrierCountdown> countdowns = new LinkedHashMap<>();
    private final Map<Integer, BarrierState> states = new LinkedHashMap<>();

    public BarrierManager(java.util.Collection<Integer> sections) {
        sections.forEach(section -> states.put(section, BarrierState.CLOSED));
    }

    public BarrierManager() {
        this(java.util.List.of());
    }

    public void register(int sectionNumber) {
        states.putIfAbsent(sectionNumber, BarrierState.CLOSED);
    }

    public Optional<BarrierCountdown> startOnce(int sectionNumber, long tick, int durationTicks) {
        if (states.get(sectionNumber) != BarrierState.CLOSED || countdowns.containsKey(sectionNumber)) return Optional.empty();
        BarrierCountdown countdown = new BarrierCountdown(sectionNumber, tick, durationTicks);
        countdowns.put(sectionNumber, countdown);
        states.put(sectionNumber, BarrierState.COUNTDOWN);
        return Optional.of(countdown);
    }

    public Map<Integer, BarrierCountdown> countdowns() {
        return Map.copyOf(countdowns);
    }

    public BarrierTickResult tick(long currentTick) {
        java.util.List<BarrierWarning> warnings = new java.util.ArrayList<>();
        java.util.List<Integer> dueToOpen = new java.util.ArrayList<>();
        for (BarrierCountdown countdown : countdowns.values()) {
            countdown.pollWarnings(currentTick).forEach(seconds -> warnings.add(new BarrierWarning(countdown.sectionNumber(), seconds)));
            if (countdown.isDue(currentTick)) dueToOpen.add(countdown.sectionNumber());
        }
        return new BarrierTickResult(warnings, dueToOpen);
    }

    public void markOpen(int sectionNumber) {
        if (states.get(sectionNumber) != BarrierState.COUNTDOWN) return;
        states.put(sectionNumber, BarrierState.OPEN);
        countdowns.remove(sectionNumber);
    }

    public void restoreOpen(int sectionNumber) {
        if (!states.containsKey(sectionNumber)) throw new IllegalArgumentException("Unknown barrier section: " + sectionNumber);
        states.put(sectionNumber, BarrierState.OPEN);
        countdowns.remove(sectionNumber);
    }

    public void restoreCountdown(int sectionNumber, long currentTick, int remainingTicks) {
        if (!states.containsKey(sectionNumber)) throw new IllegalArgumentException("Unknown barrier section: " + sectionNumber);
        BarrierCountdown countdown = new BarrierCountdown(sectionNumber, currentTick, Math.max(1, remainingTicks));
        countdowns.put(sectionNumber, countdown);
        states.put(sectionNumber, BarrierState.COUNTDOWN);
    }

    public void cancelAll() {
        countdowns.clear();
        states.replaceAll((section, state) -> state == BarrierState.COUNTDOWN ? BarrierState.CLOSED : state);
    }

    public BarrierState state(int sectionNumber) {
        return states.getOrDefault(sectionNumber, BarrierState.CLOSED);
    }

    public Map<Integer, BarrierState> states() { return Map.copyOf(states); }

    public record BarrierWarning(int sectionNumber, int secondsRemaining) { }
    public record BarrierTickResult(java.util.List<BarrierWarning> warnings, java.util.List<Integer> dueToOpen) {
        public BarrierTickResult {
            warnings = java.util.List.copyOf(warnings);
            dueToOpen = java.util.List.copyOf(dueToOpen);
        }
    }
}
