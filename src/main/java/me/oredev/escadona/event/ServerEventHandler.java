package me.oredev.escadona.event;

import me.oredev.escadona.Escadona;
import me.oredev.escadona.command.EscadonaCommand;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import me.oredev.escadona.game.VoidRescuePolicy;
import me.oredev.escadona.luckyblock.BreakDecision;
import me.oredev.escadona.luckyblock.TrackedBlockKey;
import me.oredev.escadona.ui.ChatMessages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class ServerEventHandler {
    private final ServerRuntimeRegistry runtimes;

    public ServerEventHandler(ServerRuntimeRegistry runtimes) {
        this.runtimes = runtimes;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        EscadonaCommand.register(event.getDispatcher(), runtimes);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockBreakGuard(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        boolean mainLobbyConfigured = runtime.mainLobby().location().isPresent();
        if (runtime.games().shouldPreventBlockBreak(player.getUUID(), mainLobbyConfigured)) {
            event.setCanceled(true);
            player.displayClientMessage(ChatMessages.warning(
                    runtime.games().findByPlayer(player.getUUID()).isPresent()
                            ? "Você não pode quebrar blocos enquanto aguarda o início da partida."
                            : "Você não pode quebrar blocos no lobby."), true);
            return;
        }
        TrackedBlockKey key = new TrackedBlockKey(player.level().dimension(), event.getPos());
        var tracked = runtime.luckyBlocks().find(key).orElse(null);
        if (tracked != null && tracked.matchId() != null && !runtime.games().isPlaying(tracked.matchId())) {
            event.setCanceled(true);
            player.displayClientMessage(ChatMessages.warning("A partida ainda não está em andamento."), true);
            return;
        }
        BreakDecision decision = runtime.luckyBlocks().inspectBreak(key, player.getUUID());
        if (decision == BreakDecision.DENY_NOT_OWNER) {
            event.setCanceled(true);
            player.displayClientMessage(ChatMessages.error("Esta Lucky Block pertence a outra lane."), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreakComplete(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        TrackedBlockKey key = new TrackedBlockKey(player.level().dimension(), event.getPos());
        var runtime = runtimes.getOrCreate(player.getServer());
        var tracked = runtime.luckyBlocks().find(key).orElse(null);
        BreakDecision decision = runtime.luckyBlocks().completeBreak(key, player.getUUID());
        if (tracked != null && decision == BreakDecision.ALLOW_AND_COMPLETE) {
            runtime.games().onLuckyBlockBroken(player.getServer(), runtime.luckyBlocks(), tracked);
            runtime.scoreboards().refreshPlayer(player.getServer(), runtime.games(), runtime.luckyBlocks(), player.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        ServerPlayer killer = event.getSource().getEntity() instanceof ServerPlayer source ? source : null;
        var result = runtime.games().onPlayerDeath(player, killer == null ? null : killer.getUUID());
        if (result.handled()) {
            event.setCanceled(true);
            if (result.firstOccurrence()) {
                Component announcement = result.killerId() == null
                        ? ChatMessages.death(event.getSource().getLocalizedDeathMessage(player))
                        : ChatMessages.kill(player.getGameProfile().getName(),
                                killer == null ? result.killerId().toString() : killer.getGameProfile().getName());
                player.getServer().getPlayerList().broadcastSystemMessage(announcement, false);
            }
            runtime.scoreboards().refreshPlayer(player.getServer(), runtime.games(), runtime.luckyBlocks(), player.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void keepManagedDeathCanceled(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (runtimes.getOrCreate(player.getServer()).games().isRespawning(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        runtime.games().onPlayerLogout(player.getServer(), player.getUUID());
        runtime.scoreboards().forget(player.getUUID());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        runtime.games().onPlayerLogin(player.getServer(), player);
        if (runtime.games().findByPlayer(player.getUUID()).isEmpty()
                && !runtime.games().isRespawning(player.getUUID())) {
            if (runtime.lobbyManager().returnToLobby(player)) {
                runtime.scoreboards().refreshLobby(player.getServer(), runtime.games(), player);
            } else {
                runtime.scoreboards().clear(player);
            }
        } else {
            runtime.scoreboards().refreshPlayer(player.getServer(), runtime.games(), runtime.luckyBlocks(), player.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLobbySelectorUse(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        if (!runtime.lobbyManager().handleSelectorUse(player, runtime.games(), event.getItemStack())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLobbySelectorUseOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        if (!runtime.lobbyManager().handleSelectorUse(player, runtime.games(), event.getItemStack())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLobbySelectorDrop(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        if (runtime.lobbyManager().protectDroppedSelector(player, runtime.games(), event.getEntity().getItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        if (runtime.games().findByPlayer(player.getUUID()).isEmpty()
                && !runtime.games().isRespawning(player.getUUID())) {
            runtime.lobbyManager().returnToLobby(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var runtime = runtimes.getOrCreate(player.getServer());
        var games = runtime.games();
        boolean mainLobbyConfigured = runtime.mainLobby().location().isPresent();
        ServerPlayer attacker = event.getSource().getEntity() instanceof ServerPlayer sourcePlayer
                ? sourcePlayer
                : null;
        boolean blockedVictim = games.shouldProtectLobbyPlayer(player.getUUID(), mainLobbyConfigured);
        boolean blockedAttacker = attacker != null
                && games.shouldProtectLobbyPlayer(attacker.getUUID(), mainLobbyConfigured);
        boolean preMatchPvp = attacker != null && games.shouldBlockPvp(attacker.getUUID(), player.getUUID());
        boolean combatInvulnerability = games.isCombatInvulnerable(player.getUUID());
        if (blockedVictim || blockedAttacker || preMatchPvp || combatInvulnerability) {
            event.setCanceled(true);
            return;
        }
        if (event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)
                && games.shouldInstantlyKillFromVoid(player.getUUID())) {
            event.setAmount(Float.MAX_VALUE);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        var runtime = runtimes.getOrCreate(event.getServer());
        runtime.luckyBlocks().tick(event.getServer());
        runtime.games().tick(event.getServer(), runtime.luckyBlocks());
        runtime.lobbyManager().tick(event.getServer(), runtime.games());
        boolean mainLobbyConfigured = runtime.mainLobby().location().isPresent();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            var match = runtime.games().findByPlayer(player.getUUID()).orElse(null);
            boolean belowWorld = player.getY() < player.level().getMinY();
            var rescue = VoidRescuePolicy.destination(belowWorld, match != null,
                    match == null ? null : match.state(), mainLobbyConfigured);
            if (rescue == VoidRescuePolicy.Destination.LANE_SPAWN
                    && runtime.games().rescueWaitingPlayer(player)) {
                player.sendSystemMessage(ChatMessages.warning("Você caiu no void e voltou ao spawn da sua lane."));
            } else if (rescue == VoidRescuePolicy.Destination.MAIN_LOBBY
                    && runtime.lobbyManager().returnToLobby(player)) {
                player.sendSystemMessage(ChatMessages.warning("Você caiu no void e voltou ao lobby principal."));
            }
            if (!runtime.games().shouldProtectLobbyPlayer(player.getUUID(), mainLobbyConfigured)) continue;
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
        }
        runtime.scoreboards().tick(event.getServer(), runtime.games(), runtime.luckyBlocks(), mainLobbyConfigured);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        var runtime = runtimes.getOrCreate(event.getServer());
        boolean resolved = runtime.luckyBlockResolver().validateAndLog();
        try {
            var worlds = runtime.loadRegisteredWorlds(event.getServer());
            int playerStatistics = runtime.loadPlayerStatistics();
            int loaded = runtime.loadArenas();
            boolean mainLobbyLoaded = runtime.loadMainLobby();
            int recovered = runtime.recoverSessions(event.getServer());
            Escadona.LOGGER.info("Escadona server runtime started; Lucky Block integration ready={}; imported worlds registered={}/loaded={}/invalid={}; player statistics loaded={}; arenas loaded={}; main lobby loaded={}; sessions recovered={}",
                    resolved, worlds.registered(), worlds.loaded(), worlds.invalid(), playerStatistics, loaded,
                    mainLobbyLoaded, recovered);
        } catch (java.io.IOException exception) {
            Escadona.LOGGER.error("Could not initialize Escadona persistent runtime state", exception);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        runtimes.remove(event.getServer());
        Escadona.LOGGER.info("Escadona server runtime stopped");
    }
}
