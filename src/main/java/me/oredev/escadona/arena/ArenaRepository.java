package me.oredev.escadona.arena;

import java.io.IOException;
import java.util.List;

public interface ArenaRepository {
    void save(Arena arena) throws IOException;
    List<Arena> loadAll() throws IOException;
}
