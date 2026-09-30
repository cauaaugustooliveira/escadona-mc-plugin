package me.oredev.escadona.scoreboard;

import net.minecraft.world.scores.Scoreboard;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScoreboardManagerTest {
    @Test
    void lineOwnersAreUniqueAndNotHiddenByVanilla() {
        var owners = new HashSet<String>();

        for (int index = 0; index < ScoreboardView.MAX_LINES; index++) {
            String owner = ScoreboardManager.lineOwner(index);
            assertFalse(owner.startsWith(Scoreboard.HIDDEN_SCORE_PREFIX));
            owners.add(owner);
        }

        assertEquals(ScoreboardView.MAX_LINES, owners.size());
    }

    @Test
    void rejectsOwnersOutsideSidebarCapacity() {
        assertThrows(IllegalArgumentException.class, () -> ScoreboardManager.lineOwner(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ScoreboardManager.lineOwner(ScoreboardView.MAX_LINES));
    }
}
