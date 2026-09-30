package me.oredev.escadona.respawn;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** A deep copy of every player inventory slot, including equipment and off-hand. */
public final class InventorySnapshot {
    public static final Codec<InventorySnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("slots").forGetter(snapshot -> snapshot.slots),
            Codec.INT.fieldOf("selectedSlot").forGetter(InventorySnapshot::selectedSlot)
    ).apply(instance, InventorySnapshot::new));

    private final List<ItemStack> slots;
    private final int selectedSlot;

    public InventorySnapshot(List<ItemStack> slots, int selectedSlot) {
        this.slots = slots.stream().map(ItemStack::copy).toList();
        this.selectedSlot = selectedSlot;
    }

    public static InventorySnapshot capture(Inventory inventory) {
        return new InventorySnapshot(
                java.util.stream.IntStream.range(0, inventory.getContainerSize())
                        .mapToObj(slot -> inventory.getItem(slot).copy())
                        .toList(),
                inventory.getSelectedSlot());
    }

    public int selectedSlot() { return selectedSlot; }

    public List<ItemStack> slots() {
        return slots.stream().map(ItemStack::copy).toList();
    }

    public void restore(Inventory inventory) {
        inventory.clearContent();
        int size = Math.min(inventory.getContainerSize(), slots.size());
        for (int slot = 0; slot < size; slot++) inventory.setItem(slot, slots.get(slot).copy());
        inventory.setSelectedSlot(Math.max(0, Math.min(Inventory.getSelectionSize() - 1, selectedSlot)));
        inventory.setChanged();
    }
}
