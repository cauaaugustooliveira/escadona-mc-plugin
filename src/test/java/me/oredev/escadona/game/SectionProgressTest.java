package me.oredev.escadona.game;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SectionProgressTest {
    @Test
    void requiresEveryConfiguredBlock() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertFalse(SectionProgress.isComplete(Set.of(), Set.of()));
        assertFalse(SectionProgress.isComplete(Set.of(first, second), Set.of(first)));
        assertTrue(SectionProgress.isComplete(Set.of(first, second), Set.of(first, second)));
    }
}
