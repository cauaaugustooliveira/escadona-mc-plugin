package me.oredev.escadona.lobby;

import java.util.Comparator;
import java.util.List;

public final class ArenaMenuModel {
    public static final int MAX_SLOTS = 54;
    public static final int PAGED_ARENAS = 45;

    private ArenaMenuModel() { }

    public static List<ArenaMenuEntry> sort(List<ArenaMenuEntry> entries) {
        return entries.stream().sorted(Comparator.comparingInt((ArenaMenuEntry entry) -> entry.state().priority())
                .thenComparing(ArenaMenuEntry::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ArenaMenuEntry::arenaId)).toList();
    }

    public static Page page(List<ArenaMenuEntry> sortedEntries, int requestedPage) {
        boolean paged = sortedEntries.size() > MAX_SLOTS;
        int pageSize = paged ? PAGED_ARENAS : MAX_SLOTS;
        int pageCount = Math.max(1, (int) Math.ceil(sortedEntries.size() / (double) pageSize));
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        int from = page * pageSize;
        int to = Math.min(sortedEntries.size(), from + pageSize);
        int rows = paged ? 6 : Math.max(1, (int) Math.ceil(Math.max(1, sortedEntries.size()) / 9.0D));
        return new Page(page, pageCount, rows, sortedEntries.subList(from, to), paged);
    }

    public record Page(int index, int count, int rows, List<ArenaMenuEntry> entries, boolean paged) {
        public boolean hasPrevious() { return index > 0; }
        public boolean hasNext() { return index + 1 < count; }
    }
}
