package me.oredev.escadona.luckyblock;

import me.oredev.escadona.Escadona;
import me.oredev.escadona.config.EscadonaConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public final class LuckyBlockResolver {
    public ResourceLocation configuredId() {
        return EscadonaConfig.luckyBlockId();
    }

    public Optional<Block> resolve() {
        ResourceLocation id = configuredId();
        if (!BuiltInRegistries.BLOCK.containsKey(id)) return Optional.empty();
        return Optional.of(BuiltInRegistries.BLOCK.getValue(id));
    }

    public boolean matches(BlockState state) {
        return resolve().map(state::is).orElse(false);
    }

    public boolean validateAndLog() {
        ResourceLocation id = configuredId();
        Optional<Block> block = resolve();
        if (block.isEmpty()) {
            Escadona.LOGGER.error("Configured Lucky Block {} is not present in the block registry", id);
            return false;
        }
        Escadona.LOGGER.info("Resolved official Lucky Block registry ID {} to {}", id, block.get().getClass().getName());
        return true;
    }
}
