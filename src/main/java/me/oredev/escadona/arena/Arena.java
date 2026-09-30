package me.oredev.escadona.arena;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;

public record Arena(
        String id,
        ResourceKey<Level> dimension,
        GlobalPos deathWait,
        Map<String, Lane> lanes,
        List<Barrier> barriers,
        FinishLine finishLine,
        BlockRegion region,
        Map<String, String> settings
) {
    public static final String KEEP_INVENTORY_ON_DEATH = "keepInventoryOnDeath";

    public Arena {
        lanes = Map.copyOf(lanes);
        barriers = List.copyOf(barriers);
        settings = Map.copyOf(settings);
    }

    public boolean keepInventoryOnDeath() {
        return Boolean.parseBoolean(settings.getOrDefault(KEEP_INVENTORY_ON_DEATH, "false"));
    }
}
