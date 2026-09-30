package me.oredev.escadona.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/**
 * Per-import spawn and clock layered over the main server's global settings.
 * Imported level.dat data never replaces the primary server WorldData.
 */
final class ImportedLevelData extends DerivedLevelData {
    private final String name;
    private BlockPos spawn;
    private float spawnAngle;
    private long gameTime;
    private long dayTime;

    ImportedLevelData(WorldData worldData, ServerLevelData wrapped, String name, BlockPos spawn,
                      float spawnAngle, long gameTime, long dayTime) {
        super(worldData, wrapped);
        this.name = name;
        this.spawn = spawn.immutable();
        this.spawnAngle = spawnAngle;
        this.gameTime = gameTime;
        this.dayTime = dayTime;
    }

    @Override public BlockPos getSpawnPos() { return spawn; }
    @Override public float getSpawnAngle() { return spawnAngle; }
    @Override public String getLevelName() { return name; }
    @Override public long getGameTime() { return gameTime; }
    @Override public long getDayTime() { return dayTime; }
    @Override public void setGameTime(long time) { gameTime = time; }
    @Override public void setDayTime(long time) { dayTime = time; }
    @Override public void setSpawn(BlockPos position, float angle) {
        spawn = position.immutable();
        spawnAngle = angle;
    }
}
