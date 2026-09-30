package me.oredev.escadona.respawn;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.GlobalPos;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public final class RespawnManager {
    private final Map<RespawnKey, PendingRespawn> pending = new LinkedHashMap<>();
    private final Map<UUID, PendingRespawn> exitRecoveries = new LinkedHashMap<>();

    public List<ItemStack> createStarterKit() {
        return List.of(
                new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.IRON_PICKAXE),
                new ItemStack(Items.BREAD, 16));
    }

    public void prepareForMatch(ServerPlayer player) {
        giveStarterKit(player);
        restoreVitals(player);
    }

    public PendingRespawn begin(UUID matchId, ServerPlayer player, boolean keepInventory,
                                long dueAtTick, GlobalPos respawnTarget, GlobalPos exitTarget) {
        RespawnKey key = new RespawnKey(matchId, player.getUUID());
        if (pending.containsKey(key)) throw new IllegalStateException("Respawn is already pending for " + key);
        InventorySnapshot snapshot = keepInventory ? InventorySnapshot.capture(player.getInventory()) : null;
        PendingRespawn ticket = new PendingRespawn(key, keepInventory, snapshot, dueAtTick, respawnTarget, exitTarget);
        pending.put(key, ticket);
        player.getInventory().clearContent();
        player.getInventory().setChanged();
        restoreVitals(player);
        return ticket;
    }

    public Optional<PendingRespawn> find(UUID matchId, UUID playerId) {
        return Optional.ofNullable(pending.get(new RespawnKey(matchId, playerId)));
    }

    public Optional<PendingRespawn> findByPlayer(UUID playerId) {
        return pending.values().stream().filter(ticket -> ticket.key().playerId().equals(playerId)).findFirst();
    }

    public Optional<PendingRespawn> findExitRecovery(UUID playerId) {
        return Optional.ofNullable(exitRecoveries.get(playerId));
    }

    public Collection<PendingRespawn> all() { return List.copyOf(pending.values()); }

    public PendingRespawn restore(UUID matchId, UUID playerId, boolean keepInventory, InventorySnapshot snapshot,
                                  long dueAtTick, GlobalPos respawnTarget, GlobalPos exitTarget) {
        RespawnKey key = new RespawnKey(matchId, playerId);
        if (keepInventory && snapshot == null) throw new IllegalArgumentException("Preserved inventory snapshot is required");
        PendingRespawn ticket = new PendingRespawn(key, keepInventory, snapshot, dueAtTick, respawnTarget, exitTarget);
        if (pending.putIfAbsent(key, ticket) != null) throw new IllegalStateException("Duplicate respawn ticket: " + key);
        return ticket;
    }

    /** Restores exactly once. The ticket is removed only after the inventory update succeeds. */
    public boolean complete(RespawnKey key, ServerPlayer player) {
        PendingRespawn ticket = pending.get(key);
        boolean exitRecovery = false;
        if (ticket == null) {
            ticket = exitRecoveries.get(player.getUUID());
            exitRecovery = ticket != null && ticket.key().equals(key);
        }
        if (ticket == null || !ticket.key().playerId().equals(player.getUUID())) return false;
        if (ticket.keepInventory()) ticket.snapshot().orElseThrow().restore(player.getInventory());
        else giveStarterKit(player);
        restoreVitals(player);
        player.containerMenu.broadcastChanges();
        pending.remove(key);
        if (exitRecovery) exitRecoveries.remove(player.getUUID());
        return true;
    }

    public void markMatchEnded(UUID matchId) {
        pending.values().stream().filter(ticket -> ticket.key().matchId().equals(matchId))
                .forEach(PendingRespawn::markMatchEnded);
    }

    public void discardMatch(UUID matchId) {
        pending.keySet().removeIf(key -> key.matchId().equals(matchId));
    }

    public void cancelMatch(UUID matchId) {
        List<PendingRespawn> canceled = pending.values().stream()
                .filter(ticket -> ticket.key().matchId().equals(matchId)).toList();
        canceled.forEach(ticket -> {
            ticket.markMatchEnded();
            pending.remove(ticket.key());
            exitRecoveries.put(ticket.key().playerId(), ticket);
        });
    }

    private void giveStarterKit(ServerPlayer player) {
        player.getInventory().clearContent();
        createStarterKit().forEach(stack -> player.getInventory().add(stack.copy()));
        player.getInventory().setChanged();
    }

    private static void restoreVitals(ServerPlayer player) {
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
    }

    public record RespawnKey(UUID matchId, UUID playerId) { }

    public static final class PendingRespawn {
        private final RespawnKey key;
        private final boolean keepInventory;
        private final InventorySnapshot snapshot;
        private final long dueAtTick;
        private final GlobalPos respawnTarget;
        private final GlobalPos exitTarget;
        private int lastCountdownCue = Integer.MIN_VALUE;
        private boolean matchEnded;

        private PendingRespawn(RespawnKey key, boolean keepInventory, InventorySnapshot snapshot,
                               long dueAtTick, GlobalPos respawnTarget, GlobalPos exitTarget) {
            this.key = key;
            this.keepInventory = keepInventory;
            this.snapshot = snapshot;
            this.dueAtTick = dueAtTick;
            this.respawnTarget = respawnTarget;
            this.exitTarget = exitTarget;
        }

        public RespawnKey key() { return key; }
        public boolean keepInventory() { return keepInventory; }
        public Optional<InventorySnapshot> snapshot() { return Optional.ofNullable(snapshot); }
        public long dueAtTick() { return dueAtTick; }
        public GlobalPos target() { return matchEnded ? exitTarget : respawnTarget; }
        public GlobalPos respawnTarget() { return respawnTarget; }
        public GlobalPos exitTarget() { return exitTarget; }
        public boolean matchEnded() { return matchEnded; }
        public void markMatchEnded() { matchEnded = true; }

        public OptionalInt pollCountdownCue(long currentTick) {
            if (currentTick >= dueAtTick) return OptionalInt.empty();
            int cue = (int) Math.ceil((dueAtTick - currentTick) / 20.0D);
            if (cue == lastCountdownCue) return OptionalInt.empty();
            lastCountdownCue = cue;
            return OptionalInt.of(cue);
        }
    }
}
