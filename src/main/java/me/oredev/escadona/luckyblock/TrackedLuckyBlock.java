package me.oredev.escadona.luckyblock;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public final class TrackedLuckyBlock {
    private final UUID id;
    private final UUID matchId;
    private final String laneId;
    private final int sectionNumber;
    private final TrackedBlockKey location;
    private final UUID ownerId;
    private final ResourceLocation expectedBlockId;
    private final BlockState originalState;
    private LuckyBlockState state;

    public TrackedLuckyBlock(
            UUID id,
            UUID matchId,
            String laneId,
            int sectionNumber,
            TrackedBlockKey location,
            UUID ownerId,
            ResourceLocation expectedBlockId,
            BlockState originalState
    ) {
        this.id = id;
        this.matchId = matchId;
        this.laneId = laneId;
        this.sectionNumber = sectionNumber;
        this.location = location;
        this.ownerId = ownerId;
        this.expectedBlockId = expectedBlockId;
        this.originalState = originalState;
        this.state = LuckyBlockState.PENDING;
    }

    public TrackedLuckyBlock(
            UUID id,
            String laneId,
            int sectionNumber,
            TrackedBlockKey location,
            UUID ownerId,
            ResourceLocation expectedBlockId,
            BlockState originalState
    ) {
        this(id, null, laneId, sectionNumber, location, ownerId, expectedBlockId, originalState);
    }

    public UUID id() { return id; }
    public UUID matchId() { return matchId; }
    public String laneId() { return laneId; }
    public int sectionNumber() { return sectionNumber; }
    public TrackedBlockKey location() { return location; }
    public UUID ownerId() { return ownerId; }
    public ResourceLocation expectedBlockId() { return expectedBlockId; }
    public BlockState originalState() { return originalState; }
    public LuckyBlockState state() { return state; }
    public boolean isOwner(UUID playerId) { return ownerId.equals(playerId); }

    public boolean markBroken() {
        if (state != LuckyBlockState.PENDING) return false;
        state = LuckyBlockState.BROKEN;
        return true;
    }

    public void restoreBroken() { state = LuckyBlockState.BROKEN; }

    public void reset() {
        state = LuckyBlockState.PENDING;
    }
}
