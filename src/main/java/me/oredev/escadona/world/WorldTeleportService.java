package me.oredev.escadona.world;

import me.oredev.escadona.Escadona;
import me.oredev.escadona.ui.ChatMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Optional;
import java.util.Set;

/** Server-only dimension transfer service. It never clones or recreates a player. */
public final class WorldTeleportService {
    static final int SEARCH_RADIUS = 16;
    private static final int VERTICAL_SEARCH = 64;

    public TeleportResult teleport(ServerPlayer player, ResourceKey<Level> destination) {
        MinecraftServer server = player.getServer();
        ServerLevel level = server.getLevel(destination);
        if (level == null) {
            TeleportResult result = TeleportResult.failure("O mundo " + destination.location() + " não existe ou não está carregado.");
            player.sendSystemMessage(ChatMessages.error(result.message()));
            return result;
        }
        return teleport(player, level);
    }

    public TeleportResult teleport(ServerPlayer player, ServerLevel destination) {
        Optional<BlockPos> safe = findSafePosition(destination);
        if (safe.isEmpty()) {
            TeleportResult result = TeleportResult.failure("Nenhuma posição segura foi encontrada em "
                    + destination.dimension().location() + ".");
            player.sendSystemMessage(ChatMessages.error(result.message()));
            Escadona.LOGGER.warn("No safe teleport position found for player {} in {}",
                    player.getUUID(), destination.dimension().location());
            return result;
        }
        return teleport(player, destination, safe.get(), player.getYRot(), player.getXRot(), true);
    }

    public TeleportResult teleport(ServerPlayer player, ResourceKey<Level> destination, BlockPos target,
                                   float yaw, float pitch, boolean feedback) {
        ServerLevel level = player.getServer().getLevel(destination);
        if (level == null) {
            TeleportResult result = TeleportResult.failure("O mundo " + destination.location() + " não existe ou não está carregado.");
            if (feedback) player.sendSystemMessage(ChatMessages.error(result.message()));
            return result;
        }
        if (!isSafe(level, target)) {
            TeleportResult result = TeleportResult.failure("A posição configurada em " + destination.location() + " não é segura.");
            if (feedback) player.sendSystemMessage(ChatMessages.error(result.message()));
            Escadona.LOGGER.warn("Unsafe configured teleport position {} for player {} in {}",
                    target, player.getUUID(), destination.location());
            return result;
        }
        return teleport(player, level, target, yaw, pitch, feedback);
    }

    private TeleportResult teleport(ServerPlayer player, ServerLevel destination, BlockPos target,
                                    float yaw, float pitch, boolean feedback) {
        boolean moved;
        try {
            moved = player.teleportTo(destination, target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                    Set.<Relative>of(), yaw, pitch, false);
        } catch (Exception exception) {
            Escadona.LOGGER.error("Teleport failed for player {} to {} at {}", player.getUUID(),
                    destination.dimension().location(), target, exception);
            TeleportResult result = TeleportResult.failure("Falha ao teleportar para " + destination.dimension().location() + ".");
            if (feedback) player.sendSystemMessage(ChatMessages.error(result.message()));
            return result;
        }
        if (!moved) {
            TeleportResult result = TeleportResult.failure("O servidor recusou o teleporte para "
                    + destination.dimension().location() + ".");
            if (feedback) player.sendSystemMessage(ChatMessages.error(result.message()));
            return result;
        }
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.fallDistance = 0.0F;
        TeleportResult result = TeleportResult.success(destination.dimension(), target,
                "Teleportado para " + destination.dimension().location() + ".");
        if (feedback) player.sendSystemMessage(ChatMessages.success(result.message()));
        return result;
    }

    Optional<BlockPos> findSafePosition(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        if (isSafe(level, spawn)) return Optional.of(spawn);
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = spawn.getX() + dx;
                    int z = spawn.getZ() + dz;
                    BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            new BlockPos(x, spawn.getY(), z));
                    if (isSafe(level, surface)) return Optional.of(surface);
                    for (int offset = 0; offset <= VERTICAL_SEARCH; offset++) {
                        BlockPos above = new BlockPos(x, spawn.getY() + offset, z);
                        if (isSafe(level, above)) return Optional.of(above);
                        if (offset > 0) {
                            BlockPos below = new BlockPos(x, spawn.getY() - offset, z);
                            if (isSafe(level, below)) return Optional.of(below);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public boolean isSafe(ServerLevel level, BlockPos feet) {
        if (feet.getY() <= level.getMinY() || feet.getY() + 1 >= level.getMaxY()) return false;
        if (!level.getWorldBorder().isWithinBounds(feet)) return false;
        BlockPos floorPos = feet.below();
        BlockPos headPos = feet.above();
        var floor = level.getBlockState(floorPos);
        var body = level.getBlockState(feet);
        var head = level.getBlockState(headPos);
        boolean solidFloor = !floor.getCollisionShape(level, floorPos).isEmpty() && floor.getFluidState().isEmpty();
        boolean clearBody = body.getCollisionShape(level, feet).isEmpty() && body.getFluidState().isEmpty();
        boolean clearHead = head.getCollisionShape(level, headPos).isEmpty() && head.getFluidState().isEmpty();
        boolean dangerous = body.is(BlockTags.FIRE) || head.is(BlockTags.FIRE)
                || body.is(Blocks.POWDER_SNOW) || floor.is(Blocks.MAGMA_BLOCK)
                || floor.is(Blocks.CACTUS) || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE);
        return solidFloor && clearBody && clearHead && !dangerous;
    }

    public record TeleportResult(boolean success, ResourceKey<Level> destination, BlockPos position, String message) {
        public static TeleportResult success(ResourceKey<Level> destination, BlockPos position, String message) {
            return new TeleportResult(true, destination, position.immutable(), message);
        }
        public static TeleportResult failure(String message) { return new TeleportResult(false, null, null, message); }
    }
}
