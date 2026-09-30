package me.oredev.escadona.barrier;

public final class BarrierCountdown {
    private static final int[] WARNING_SECONDS = {30, 10, 5, 4, 3, 2, 1};
    private final int sectionNumber;
    private final int durationTicks;
    private final long opensAtTick;
    private final java.util.Set<Integer> announcedWarnings = new java.util.HashSet<>();

    public BarrierCountdown(int sectionNumber, long startedAtTick, int durationTicks) {
        if (durationTicks < 1) throw new IllegalArgumentException("Duration must be positive");
        this.sectionNumber = sectionNumber;
        this.durationTicks = durationTicks;
        this.opensAtTick = startedAtTick + durationTicks;
    }

    public int sectionNumber() { return sectionNumber; }
    public long opensAtTick() { return opensAtTick; }
    public boolean isDue(long currentTick) { return currentTick >= opensAtTick; }

    public java.util.List<Integer> pollWarnings(long currentTick) {
        long remainingTicks = Math.max(0, opensAtTick - currentTick);
        java.util.List<Integer> due = new java.util.ArrayList<>();
        for (int seconds : WARNING_SECONDS) {
            if (seconds * 20 >= durationTicks) continue;
            if (remainingTicks <= seconds * 20L && announcedWarnings.add(seconds)) due.add(seconds);
        }
        return java.util.List.copyOf(due);
    }
}
