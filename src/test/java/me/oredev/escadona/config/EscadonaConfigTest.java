package me.oredev.escadona.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscadonaConfigTest {
    @Test
    void scoreboardUsesSafeDefaultsWhenNeoForgeConfigIsNotAttached() {
        assertTrue(EscadonaConfig.scoreboardEnabled());
        assertEquals(20, EscadonaConfig.scoreboardUpdateIntervalTicks());
        assertEquals("Escadona", EscadonaConfig.scoreboardFooter());
        assertEquals(4, EscadonaConfig.lobbySelectorSlot());
        assertFalse(EscadonaConfig.scoreboardLayout().lobby().isEmpty());
    }
}
