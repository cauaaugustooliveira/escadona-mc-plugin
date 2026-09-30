package me.oredev.escadona.reset;

/** Pure bounded-work cursor used by reset phases and restart tests. */
public final class IncrementalCursor {
    private final int total;
    private int current;

    public IncrementalCursor(int total) {
        if (total < 0) throw new IllegalArgumentException("total cannot be negative");
        this.total = total;
    }

    public int current() { return current; }
    public int total() { return total; }
    public boolean complete() { return current >= total; }
    public int take(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        int start = current;
        current = Math.min(total, current + limit);
        return current - start;
    }
    public void restart() { current = 0; }
}
