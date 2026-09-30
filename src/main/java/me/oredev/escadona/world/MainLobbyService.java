package me.oredev.escadona.world;

import me.oredev.escadona.Escadona;
import me.oredev.escadona.game.PlayerInventoryService;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Optional;

public final class MainLobbyService {
    private final JsonMainLobbyRepository repository;
    private final WorldTeleportService teleports;
    private MainLobbyLocation lobby;

    public MainLobbyService(JsonMainLobbyRepository repository, WorldTeleportService teleports) {
        this.repository = repository;
        this.teleports = teleports;
    }

    public boolean load() {
        try {
            lobby = repository.load().orElse(null);
            return lobby != null;
        } catch (Exception exception) {
            Escadona.LOGGER.error("Could not load main lobby configuration", exception);
            lobby = null;
            return false;
        }
    }

    public MainLobbyLocation setFrom(ServerPlayer player) throws IOException {
        if (!teleports.isSafe((net.minecraft.server.level.ServerLevel) player.level(), player.blockPosition())) {
            throw new IllegalStateException("A posição atual não é segura para o lobby principal.");
        }
        MainLobbyLocation configured = new MainLobbyLocation(player.level().dimension(), player.blockPosition(),
                player.getYRot(), player.getXRot());
        repository.save(configured);
        lobby = configured;
        return configured;
    }

    public Optional<MainLobbyLocation> location() { return Optional.ofNullable(lobby); }

    public GlobalPos requireGlobalPosition(MinecraftServer server) {
        MainLobbyLocation configured = Optional.ofNullable(lobby)
                .orElseThrow(() -> new IllegalStateException(
                        "O lobby principal ainda não foi definido. Use /escadona lobby set."));
        if (server.getLevel(configured.dimension()) == null) {
            throw new IllegalStateException("A dimensão do lobby principal não está carregada: "
                    + configured.dimension().location());
        }
        return GlobalPos.of(configured.dimension(), configured.position());
    }

    public boolean teleportIfConfigured(ServerPlayer player) {
        return teleport(player, true);
    }

    public boolean teleport(ServerPlayer player, boolean clearInventory) {
        if (lobby == null) return false;
        var result = teleports.teleport(player, lobby.dimension(), lobby.position(), lobby.yaw(), lobby.pitch(), false);
        if (!result.success()) {
            Escadona.LOGGER.warn("Could not send player {} to the main lobby: {}", player.getUUID(), result.message());
            return false;
        }
        if (clearInventory) PlayerInventoryService.clear(player);
        return true;
    }
}
