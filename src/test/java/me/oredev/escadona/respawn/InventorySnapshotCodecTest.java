package me.oredev.escadona.respawn;

import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySnapshotCodecTest {
    @BeforeAll
    static void bootstrap() {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripPreservesComponentsDamageAndCount() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(37);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Lucky Blade"));
        ItemStack arrows = new ItemStack(Items.ARROW, 17);
        InventorySnapshot snapshot = new InventorySnapshot(List.of(sword, arrows), 4);
        RegistryAccess access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RegistryOps<com.google.gson.JsonElement> ops = access.createSerializationContext(JsonOps.INSTANCE);

        var encoded = InventorySnapshot.CODEC.encodeStart(ops, snapshot).getOrThrow();
        var decoded = InventorySnapshot.CODEC.parse(ops, encoded).getOrThrow();

        assertEquals(4, decoded.selectedSlot());
        assertTrue(ItemStack.matches(sword, decoded.slots().get(0)));
        assertTrue(ItemStack.matches(arrows, decoded.slots().get(1)));
    }
}
