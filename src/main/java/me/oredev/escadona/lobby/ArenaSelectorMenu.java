package me.oredev.escadona.lobby;

import me.oredev.escadona.game.GameManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

public final class ArenaSelectorMenu extends ChestMenu {
    private final Container selector;
    private final LobbyManager lobby;
    private final GameManager games;
    private int page;

    public ArenaSelectorMenu(int containerId, Inventory inventory, Container selector, int rows,
                             LobbyManager lobby, GameManager games, int page) {
        super(type(rows), containerId, inventory, selector, rows);
        this.selector = selector;
        this.lobby = lobby;
        this.games = games;
        this.page = page;
        refresh();
    }

    public void refresh() {
        if (!(this.slots.getLast().container instanceof Inventory inventory)
                || !(inventory.player instanceof ServerPlayer player)) return;
        ArenaMenuModel.Page current = lobby.page(player.getServer(), games, page);
        if (current.rows() != getRowCount()) {
            player.closeContainer();
            lobby.openSelector(player, games, current.index());
            return;
        }
        page = current.index();
        selector.clearContent();
        int arenaSlots = current.paged() ? Math.max(0, selector.getContainerSize() - 9) : selector.getContainerSize();
        for (int index = 0; index < Math.min(arenaSlots, current.entries().size()); index++) {
            selector.setItem(index, LobbyItems.arena(current.entries().get(index)));
        }
        if (current.paged()) {
            if (current.hasPrevious()) selector.setItem(selector.getContainerSize() - 9, LobbyItems.previousPage());
            if (current.hasNext()) selector.setItem(selector.getContainerSize() - 1, LobbyItems.nextPage());
        }
        broadcastChanges();
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (slotId < 0 || slotId >= selector.getContainerSize()) {
            broadcastChanges();
            return;
        }
        ItemStack clicked = selector.getItem(slotId);
        if (LobbyItems.isPreviousPage(clicked)) {
            page--;
            refresh();
            return;
        }
        if (LobbyItems.isNextPage(clicked)) {
            page++;
            refresh();
            return;
        }
        LobbyItems.arenaId(clicked).ifPresent(arenaId -> {
            serverPlayer.closeContainer();
            lobby.joinFromMenu(serverPlayer, games, arenaId);
        });
        broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player instanceof ServerPlayer serverPlayer && lobby.isLobbyContext(serverPlayer, games);
    }

    private static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Rows must be between 1 and 6");
        };
    }
}
