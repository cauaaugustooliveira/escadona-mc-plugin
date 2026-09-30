package me.oredev.escadona.arena;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ArenaManager {
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, ArenaDraft> drafts = new LinkedHashMap<>();
    private final ArenaRepository repository;

    public ArenaManager(ArenaRepository repository) {
        this.repository = repository;
    }

    public void register(Arena arena) {
        arenas.put(arena.id(), arena);
    }

    public Optional<Arena> find(String id) { return Optional.ofNullable(arenas.get(id)); }
    public Collection<Arena> all() { return List.copyOf(arenas.values()); }

    public ArenaDraft createDraft(String id) {
        if (arenas.containsKey(id) || drafts.containsKey(id)) {
            throw new IllegalArgumentException("Arena already exists: " + id);
        }
        ArenaDraft draft = new ArenaDraft(id);
        drafts.put(id, draft);
        return draft;
    }

    public Optional<ArenaDraft> draft(String id) {
        ArenaDraft draft = drafts.get(id);
        if (draft != null) return Optional.of(draft);
        Arena arena = arenas.get(id);
        if (arena == null) return Optional.empty();
        draft = ArenaDraft.fromArena(arena);
        drafts.put(id, draft);
        return Optional.of(draft);
    }

    public Collection<ArenaDraft> drafts() { return List.copyOf(drafts.values()); }

    public Arena save(String id) throws java.io.IOException {
        ArenaDraft draft = drafts.get(id);
        if (draft == null) throw new IllegalArgumentException("Unknown arena draft: " + id);
        ArenaValidation validation = draft.validate();
        if (!validation.isValid()) {
            throw new IllegalStateException(String.join("; ", validation.errors()));
        }
        Arena arena = draft.toArena();
        repository.save(arena);
        arenas.put(id, arena);
        return arena;
    }

    public int loadAll() throws java.io.IOException {
        arenas.clear();
        drafts.clear();
        for (Arena arena : repository.loadAll()) register(arena);
        return arenas.size();
    }
}
