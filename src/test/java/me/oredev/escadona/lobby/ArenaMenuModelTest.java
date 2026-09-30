package me.oredev.escadona.lobby;

import me.oredev.escadona.arena.Arena;
import me.oredev.escadona.arena.BlockRegion;
import me.oredev.escadona.arena.Lane;
import me.oredev.escadona.game.GameState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMenuModelTest {
    @Test
    void translatesEveryGameStateForPlayers() {
        assertEquals("Aguardando", ArenaDisplayState.from(GameState.WAITING).label());
        assertEquals("Iniciando", ArenaDisplayState.from(GameState.STARTING).label());
        assertEquals("Em partida", ArenaDisplayState.from(GameState.PLAYING).label());
        assertEquals("Finalizando", ArenaDisplayState.from(GameState.FINISHING).label());
        assertEquals("Restaurando", ArenaDisplayState.from(GameState.RESETTING).label());
        assertTrue(ArenaDisplayState.WAITING.joinable());
        assertFalse(ArenaDisplayState.PLAYING.joinable());
        assertFalse(ArenaDisplayState.RESETTING.joinable());
    }

    @Test
    void sortsByAvailabilityThenName() {
        List<ArenaMenuEntry> sorted = ArenaMenuModel.sort(List.of(
                entry("zeta", ArenaDisplayState.PLAYING),
                entry("beta", ArenaDisplayState.WAITING),
                entry("alpha", ArenaDisplayState.WAITING),
                entry("omega", ArenaDisplayState.RESETTING)));

        assertEquals(List.of("alpha", "beta", "zeta", "omega"),
                sorted.stream().map(ArenaMenuEntry::arenaId).toList());
    }

    @Test
    void usesDynamicRowsAndPaginatesAboveFiftyFour() {
        assertEquals(2, ArenaMenuModel.page(entries(10), 0).rows());
        assertFalse(ArenaMenuModel.page(entries(54), 0).paged());

        ArenaMenuModel.Page first = ArenaMenuModel.page(entries(100), 0);
        ArenaMenuModel.Page last = ArenaMenuModel.page(entries(100), 99);
        assertEquals(6, first.rows());
        assertEquals(45, first.entries().size());
        assertTrue(first.hasNext());
        assertEquals(2, last.index());
        assertEquals(10, last.entries().size());
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
    }

    @Test
    void derivesDisplayNameAndCapacityFromArenaLanes() {
        Map<String, Lane> lanes = Map.of(
                "a", new Lane("a", GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), Map.of()),
                "b", new Lane("b", GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), Map.of()),
                "c", new Lane("c", GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), Map.of()));
        Arena arena = new Arena("mega_race", Level.OVERWORLD, null, lanes, List.of(), null,
                new BlockRegion(BlockPos.ZERO, BlockPos.ZERO), Map.of());

        ArenaMenuEntry entry = ArenaMenuEntry.from(arena, 2, ArenaDisplayState.WAITING);

        assertEquals("Mega Race", entry.displayName());
        assertEquals(2, entry.players());
        assertEquals(3, entry.capacity());
    }

    private static List<ArenaMenuEntry> entries(int count) {
        List<ArenaMenuEntry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) entries.add(entry("arena_" + index, ArenaDisplayState.WAITING));
        return entries;
    }

    private static ArenaMenuEntry entry(String id, ArenaDisplayState state) {
        return new ArenaMenuEntry(id, id, 0, 8, state);
    }
}
