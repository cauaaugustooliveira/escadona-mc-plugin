package me.oredev.escadona.game;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Canonical full inventory cleanup used whenever an Escadona player enters a lobby. */
public final class PlayerInventoryService {
    private PlayerInventoryService() { }

    public static void clear(ServerPlayer player) {
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        player.getInventory().clearContent();
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }
}
