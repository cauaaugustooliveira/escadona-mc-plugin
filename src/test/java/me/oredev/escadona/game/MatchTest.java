package me.oredev.escadona.game;

import me.oredev.escadona.arena.Arena;
import me.oredev.escadona.arena.BlockRegion;
import me.oredev.escadona.arena.FinishLine;
import me.oredev.escadona.arena.Lane;
import me.oredev.escadona.arena.Section;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchTest {
    @Test
    void assignsEachPlayerToADifferentLane() {
        Match match = new Match(UUID.randomUUID(), arena());
        RacePlayer first = match.join(UUID.randomUUID());
        RacePlayer second = match.join(UUID.randomUUID());

        assertNotEquals(first.laneId(), second.laneId());
        assertThrows(IllegalStateException.class, () -> match.join(UUID.randomUUID()));
    }

    @Test
    void transitionsFromWaitingThroughCountdownToPlaying() {
        Match match = new Match(UUID.randomUUID(), arena());
        match.join(UUID.randomUUID());
        match.join(UUID.randomUUID());
        assertFalse(match.isPvpEnabled());
        match.beginStarting(100, 60);

        assertEquals(GameState.STARTING, match.state());
        assertFalse(match.isPvpEnabled());
        assertEquals(3, match.pollStartingCue(100).orElseThrow());
        assertEquals(2, match.pollStartingCue(120).orElseThrow());
        assertEquals(1, match.pollStartingCue(140).orElseThrow());
        assertEquals(0, match.pollStartingCue(160).orElseThrow());
        assertEquals(GameState.PLAYING, match.state());
        assertTrue(match.isPvpEnabled());
    }

    @Test
    void sectionProgressRemainsIndividual() {
        Match match = new Match(UUID.randomUUID(), arena());
        RacePlayer first = match.join(UUID.randomUUID());
        RacePlayer second = match.join(UUID.randomUUID());

        assertTrue(match.recordSectionCompleted(first.playerId(), 1));
        assertFalse(match.recordSectionCompleted(first.playerId(), 1));
        assertTrue(first.completedSections().contains(1));
        assertFalse(second.completedSections().contains(1));
    }

    @Test
    void disconnectKeepsLaneAndProgressAndDoesNotChangeRequiredFinishers() {
        Match match = activeMatch(3);
        RacePlayer player = match.players().values().iterator().next();
        player.completeSection(1);
        String lane = player.laneId();
        player.setState(PlayerState.DISCONNECTED);

        assertEquals(lane, match.player(player.playerId()).orElseThrow().laneId());
        assertTrue(match.player(player.playerId()).orElseThrow().completedSections().contains(1));
        assertEquals(2, match.requiredFinishers());
        assertEquals(3, match.players().size());
    }

    @Test
    void requiredPlacementsCloseTwoThreeAndFourPlayerMatches() {
        assertFinishingThreshold(2, 1);
        assertFinishingThreshold(3, 2);
        assertFinishingThreshold(4, 3);
    }

    @Test
    void finishedPlayerCannotReceiveASecondPlacementAndTimeoutDoesNotInventAny() {
        Match match = activeMatch(3);
        UUID first = match.players().keySet().iterator().next();
        var placement = match.recordPlacement(first, java.time.Instant.now());
        var repeated = match.recordPlacement(first, java.time.Instant.now().plusSeconds(5));

        assertEquals(placement, repeated);
        assertEquals(1, match.placements().size());
        match.beginFinishing(java.time.Instant.now(), 10);
        assertEquals(1, match.placements().size());
        assertEquals(GameState.FINISHING, match.state());
    }

    @Test
    void recoveryPreservesLaneProgressAndPlacement() {
        Arena arena = arena(3);
        UUID firstId = UUID.randomUUID();
        RacePlayer first = new RacePlayer(firstId, "a", arena.lanes().get("a").spawn());
        UUID broken = UUID.randomUUID();
        first.restoreProgress(Set.of(broken), Set.of(1), PlayerState.FINISHED, 1, java.time.Duration.ofSeconds(12));
        RacePlayer second = new RacePlayer(UUID.randomUUID(), "b", arena.lanes().get("b").spawn());
        RacePlayer third = new RacePlayer(UUID.randomUUID(), "c", arena.lanes().get("c").spawn());
        Instant now = Instant.parse("2026-01-01T00:01:00Z");
        Match restored = Match.restore(UUID.randomUUID(), arena, GameState.PLAYING, now.minusSeconds(60), null,
                null, 1800, 3, List.of(first, second, third),
                List.of(new PlacementManager.Placement(1, firstId, java.time.Duration.ofSeconds(12), now.minusSeconds(48))),
                100, now);

        RacePlayer loaded = restored.player(firstId).orElseThrow();
        assertEquals("a", loaded.laneId());
        assertEquals(Set.of(broken), loaded.brokenLuckyBlocks());
        assertEquals(Set.of(1), loaded.completedSections());
        assertEquals(1, restored.placements().size());
        assertEquals(2, restored.requiredFinishers());
    }

    @Test
    void resettingSurvivesRecoveryAndOnlyReturnsToWaitingAfterCompletion() {
        Match original = activeMatch(2);
        original.beginResetting();
        Instant now = Instant.parse("2026-01-01T00:01:00Z");
        Match restored = Match.restore(original.id(), original.arena(), GameState.RESETTING,
                now.minusSeconds(30), null, null, 1800, 2, original.players().values(), List.of(), 100, now);

        assertEquals(GameState.RESETTING, restored.state());
        assertThrows(IllegalStateException.class, () -> restored.beginStarting(100, 60));
        restored.completeReset();
        assertEquals(GameState.WAITING, restored.state());
        assertTrue(restored.players().isEmpty());
    }

    @Test
    void playingAndResettingMatchesRejectLateJoin() {
        Match playing = activeMatch(2);
        assertThrows(IllegalStateException.class, () -> playing.join(UUID.randomUUID()));

        playing.beginResetting();
        assertThrows(IllegalStateException.class, () -> playing.join(UUID.randomUUID()));
    }

    private static void assertFinishingThreshold(int players, int required) {
        Match match = activeMatch(players);
        var ids = List.copyOf(match.players().keySet());
        for (int index = 0; index < required; index++) match.recordPlacement(ids.get(index), java.time.Instant.now());
        assertTrue(match.requiredFinishersReached());
        assertEquals(required, match.requiredFinishers());
        match.beginFinishing(java.time.Instant.now(), 10);
        assertEquals(GameState.FINISHING, match.state());
    }

    private static Match activeMatch(int playerCount) {
        Match match = new Match(UUID.randomUUID(), arena(playerCount));
        for (int index = 0; index < playerCount; index++) match.join(UUID.randomUUID());
        match.beginStarting(0, 1);
        match.pollStartingCue(1);
        return match;
    }

    private static Arena arena() {
        return arena(2);
    }

    private static Arena arena(int laneCount) {
        Map<String, Lane> lanes = new LinkedHashMap<>();
        for (int index = 0; index < laneCount; index++) {
            String id = Character.toString('a' + index);
            int x = index * 10;
            Section section = new Section(1, Set.of(new BlockPos(x + 1, 64, 1)));
            lanes.put(id, new Lane(id, GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, 0)), Map.of(1, section)));
        }
        return new Arena("test", Level.OVERWORLD, null,
                lanes, List.of(), new FinishLine(new BlockRegion(new BlockPos(0, 64, 10), new BlockPos(50, 70, 12))),
                new BlockRegion(new BlockPos(-5, 60, -5), new BlockPos(60, 80, 20)), Map.of());
    }
}
