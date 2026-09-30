package me.oredev.escadona.barrier;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BarrierManagerTest {
    @Test
    void countdownCanOnlyStartOnceAndEndsOpen() {
        BarrierManager manager = new BarrierManager(List.of(1));

        assertTrue(manager.startOnce(1, 0, 1_200).isPresent());
        assertTrue(manager.startOnce(1, 20, 1_200).isEmpty());
        assertEquals(BarrierState.COUNTDOWN, manager.state(1));

        var halfway = manager.tick(600);
        assertEquals(List.of(new BarrierManager.BarrierWarning(1, 30)), halfway.warnings());
        assertTrue(manager.tick(1_200).dueToOpen().contains(1));

        manager.markOpen(1);
        assertEquals(BarrierState.OPEN, manager.state(1));
        assertTrue(manager.startOnce(1, 1_300, 1_200).isEmpty());
    }

    @Test
    void restoredCountdownKeepsItsRemainingDeadline() {
        BarrierManager manager = new BarrierManager(List.of(1));
        manager.restoreCountdown(1, 100, 240);

        assertTrue(manager.tick(339).dueToOpen().isEmpty());
        assertEquals(List.of(1), manager.tick(340).dueToOpen());
        manager.markOpen(1);
        assertEquals(BarrierState.OPEN, manager.state(1));
    }
}
