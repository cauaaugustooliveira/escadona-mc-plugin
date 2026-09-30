package me.oredev.escadona.reset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalCursorTest {
    @Test
    void advancesInBoundedBatchesAndCanRestartAfterCrash() {
        IncrementalCursor cursor = new IncrementalCursor(5);
        assertEquals(2, cursor.take(2));
        assertEquals(2, cursor.current());
        assertFalse(cursor.complete());
        assertEquals(3, cursor.take(10));
        assertTrue(cursor.complete());

        cursor.restart();
        assertEquals(0, cursor.current());
        assertEquals(5, cursor.take(5));
        assertTrue(cursor.complete());
    }
}
