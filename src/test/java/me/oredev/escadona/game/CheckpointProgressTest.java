package me.oredev.escadona.game;

import me.oredev.escadona.arena.Section;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointProgressTest {
    private static final GlobalPos SPAWN = GlobalPos.of(Level.OVERWORLD, new BlockPos(0, 64, 0));
    private static final GlobalPos CHECKPOINT = GlobalPos.of(Level.OVERWORLD, new BlockPos(10, 70, 10));

    @Test
    void acquiresConfiguredCheckpointOnFirstConfirmedBreak() {
        RacePlayer player = new RacePlayer(UUID.randomUUID(), "a", SPAWN);
        Section section = new Section(2, Set.of(new BlockPos(5, 65, 5)), CHECKPOINT);

        assertTrue(CheckpointProgress.acquire(player, section, true, 1));
        assertEquals(CHECKPOINT, player.checkpoint());
    }

    @Test
    void ignoresRepeatedOrLaterBreakAndMissingCheckpoint() {
        RacePlayer player = new RacePlayer(UUID.randomUUID(), "a", SPAWN);
        Section configured = new Section(2, Set.of(new BlockPos(5, 65, 5)), CHECKPOINT);
        Section optional = new Section(1, Set.of(new BlockPos(1, 64, 1)));

        assertFalse(CheckpointProgress.acquire(player, configured, false, 1));
        assertFalse(CheckpointProgress.acquire(player, configured, true, 2));
        assertFalse(CheckpointProgress.acquire(player, optional, true, 1));
        assertEquals(SPAWN, player.checkpoint());
    }
}
