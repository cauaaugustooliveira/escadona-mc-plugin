package me.oredev.escadona.lobby;

import me.oredev.escadona.Escadona;
import me.oredev.escadona.arena.Arena;
import me.oredev.escadona.arena.ArenaDraft;
import me.oredev.escadona.arena.ArenaManager;
import me.oredev.escadona.config.EscadonaConfig;
import me.oredev.escadona.game.GameManager;
import me.oredev.escadona.game.JoinException;
import me.oredev.escadona.game.PlayerInventoryService;
import me.oredev.escadona.reset.ArenaResetManager;
import me.oredev.escadona.reset.ArenaSnapshotValidator;
import me.oredev.escadona.ui.ChatMessages;
import me.oredev.escadona.world.MainLobbyService;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class LobbyManager {
    private static final Component MENU_TITLE = Component.literal("Escolha uma Arena");
    private final MainLobbyService mainLobby;
    private final ArenaManager arenas;
    private final ArenaResetManager resets;
    private final Map<String, CachedAvailability> availabilityCache = new LinkedHashMap<>();

    public LobbyManager(MainLobbyService mainLobby, ArenaManager arenas, ArenaResetManager resets) {
        this.mainLobby = mainLobby;
        this.arenas = arenas;
        this.resets = resets;
    }

    public GlobalPos requireGlobalPosition(MinecraftServer server) {
        return mainLobby.requireGlobalPosition(server);
    }

    public boolean teleportToLobby(ServerPlayer player, boolean clearInventory) {
        return mainLobby.teleport(player, clearInventory);
    }

    public boolean returnToLobby(ServerPlayer player) {
        if (!mainLobby.teleport(player, false)) return false;
        prepareLobbyInventory(player);
        return true;
    }

    public void prepareLobbyInventory(ServerPlayer player) {
        player.closeContainer();
        PlayerInventoryService.clear(player);
        player.getInventory().setItem(EscadonaConfig.lobbySelectorSlot(), LobbyItems.selector());
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    public void removeLobbyItems(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (LobbyItems.isSelector(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        if (LobbyItems.isSelector(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    public boolean isLobbyContext(ServerPlayer player, GameManager games) {
        return mainLobby.location().isPresent()
                && games.findByPlayer(player.getUUID()).isEmpty()
                && !games.isRespawning(player.getUUID());
    }

    public boolean handleSelectorUse(ServerPlayer player, GameManager games, ItemStack used) {
        if (!LobbyItems.isSelector(used) || !isLobbyContext(player, games)) return false;
        openSelector(player, games, 0);
        return true;
    }

    public boolean protectDroppedSelector(ServerPlayer player, GameManager games, ItemStack dropped) {
        if (!LobbyItems.shouldPreventDrop(dropped, isLobbyContext(player, games))) return false;
        if (ensureSelector(player)) player.containerMenu.broadcastChanges();
        return true;
    }

    public void tick(MinecraftServer server, GameManager games) {
        boolean refreshMenus = server.getTickCount() % 20 == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isLobbyContext(player, games)) continue;
            boolean changed = protectSelectorInOpenContainer(player);
            changed |= ensureSelector(player);
            if (changed) player.containerMenu.broadcastChanges();
            if (refreshMenus && player.containerMenu instanceof ArenaSelectorMenu menu) menu.refresh();
        }
    }

    public void openSelector(ServerPlayer player, GameManager games, int requestedPage) {
        ArenaMenuModel.Page page = page(player.getServer(), games, requestedPage);
        player.openMenu(new SimpleMenuProvider((containerId, inventory, ignored) -> {
            SimpleContainer contents = new SimpleContainer(page.rows() * 9);
            return new ArenaSelectorMenu(containerId, inventory, contents, page.rows(), this, games, page.index());
        }, MENU_TITLE));
    }

    public ArenaMenuModel.Page page(MinecraftServer server, GameManager games, int requestedPage) {
        return ArenaMenuModel.page(entries(server, games), requestedPage);
    }

    public void invalidateArena(String arenaId) {
        availabilityCache.remove(arenaId);
    }

    public List<ArenaMenuEntry> entries(MinecraftServer server, GameManager games) {
        List<ArenaMenuEntry> result = new ArrayList<>();
        for (Arena arena : arenas.all()) {
            if (!isStructurallyAvailable(server, arena)) continue;
            var match = games.findByArena(arena.id()).orElse(null);
            ArenaDisplayState state;
            if (resets.failure(arena.id()).isPresent()) state = ArenaDisplayState.UNAVAILABLE;
            else if (resets.isBlocked(arena.id())) state = ArenaDisplayState.RESETTING;
            else state = match == null ? ArenaDisplayState.WAITING : ArenaDisplayState.from(match.state());
            int players = match == null || match.state() == me.oredev.escadona.game.GameState.RESETTING
                    ? 0 : match.players().size();
            result.add(ArenaMenuEntry.from(arena, players, state));
        }
        return ArenaMenuModel.sort(result);
    }

    public void joinFromMenu(ServerPlayer player, GameManager games, String arenaId) {
        if (!isLobbyContext(player, games)) return;
        try {
            var joined = games.join(player.getServer(), arenaId, player.getUUID());
            player.sendSystemMessage(ChatMessages.success("Você entrou na arena "
                    + ArenaMenuEntry.displayName(joined.match().arena()) + "!"));
        } catch (JoinException exception) {
            String message = exception.reason() == me.oredev.escadona.game.JoinFailureReason.FULL
                    ? "Esta arena ficou cheia." : exception.getMessage();
            player.sendSystemMessage(ChatMessages.error(message));
        } catch (Exception exception) {
            Escadona.LOGGER.error("Could not join arena {} from lobby selector for {}",
                    arenaId, player.getUUID(), exception);
            player.sendSystemMessage(ChatMessages.error("Não foi possível entrar nesta arena."));
        }
    }

    private boolean isStructurallyAvailable(MinecraftServer server, Arena arena) {
        CachedAvailability cached = availabilityCache.get(arena.id());
        if (cached != null && cached.arena() == arena
                && (cached.available() || server.getTickCount() < cached.retryAtTick())) {
            return cached.available();
        }
        boolean available;
        try {
            if (!ArenaDraft.fromArena(arena).validate().isValid()) throw new IllegalStateException("Invalid arena");
            if (server.getLevel(arena.dimension()) == null) throw new IllegalStateException("Dimension not loaded");
            var metadata = resets.snapshots().metadata(arena.id(), server.registryAccess());
            ArenaSnapshotValidator.requireMetadataCompatible(arena, metadata);
            available = true;
        } catch (Exception ignored) {
            available = false;
        }
        availabilityCache.put(arena.id(), new CachedAvailability(arena, available,
                available ? Long.MAX_VALUE : server.getTickCount() + 200L));
        return available;
    }

    private boolean ensureSelector(ServerPlayer player) {
        int target = EscadonaConfig.lobbySelectorSlot();
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (slot != target && LobbyItems.isSelector(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
                changed = true;
            }
        }
        if (LobbyItems.isSelector(player.getInventory().getItem(target))) return changed;
        ItemStack displaced = player.getInventory().getItem(target);
        if (!displaced.isEmpty()) {
            int free = player.getInventory().getFreeSlot();
            if (free < 0 || free == target) return changed;
            player.getInventory().setItem(free, displaced);
        }
        player.getInventory().setItem(target, LobbyItems.selector());
        player.getInventory().setChanged();
        return true;
    }

    private boolean protectSelectorInOpenContainer(ServerPlayer player) {
        boolean changed = false;
        if (LobbyItems.isSelector(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            changed = true;
        }
        for (var slot : player.containerMenu.slots) {
            if (slot.container != player.getInventory() && LobbyItems.isSelector(slot.getItem())) {
                slot.set(ItemStack.EMPTY);
                changed = true;
            }
        }
        return changed;
    }

    private record CachedAvailability(Arena arena, boolean available, long retryAtTick) { }
}
