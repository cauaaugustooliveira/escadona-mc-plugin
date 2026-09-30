package me.oredev.escadona.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.oredev.escadona.Escadona;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import me.oredev.escadona.ui.ChatMessages;
import me.oredev.escadona.world.WorldImportManager;
import me.oredev.escadona.world.WorldNameResolver;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

final class WorldCommand {
    private static final WorldNameResolver RESOLVER = new WorldNameResolver();

    private WorldCommand() { }

    static LiteralArgumentBuilder<CommandSourceStack> node(ServerRuntimeRegistry runtimes) {
        return Commands.literal("world")
                .then(Commands.literal("list").executes(context -> list(context.getSource(), runtimes)))
                .then(Commands.literal("load")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        runtimes.getOrCreate(context.getSource().getServer()).worldImportManager()
                                                .availableImportNames(), builder))
                                .executes(context -> load(context.getSource(), runtimes,
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("tp")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        worldIds(context.getSource().getServer()).stream()
                                                .map(WorldCommand::displayName), builder))
                                .executes(context -> teleportSelf(context.getSource(), runtimes,
                                        StringArgumentType.getString(context, "name")))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> teleportOther(context.getSource(), runtimes,
                                                StringArgumentType.getString(context, "name"),
                                                EntityArgument.getPlayer(context, "player"))))));
    }

    private static int load(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            WorldImportManager manager = runtimes.getOrCreate(source.getServer()).worldImportManager();
            var result = manager.load(source.getServer(), name, completed -> {
                if (completed.accepted()) source.sendSuccess(() -> ChatMessages.success(completed.message()), true);
                else source.sendFailure(ChatMessages.error(completed.message()));
            });
            if (!result.accepted()) {
                source.sendFailure(ChatMessages.error(result.message()));
                return 0;
            }
            source.sendSuccess(() -> result.status() == WorldImportManager.LoadStatus.IMPORTING
                    ? ChatMessages.warning(result.message()) : ChatMessages.info(result.message()), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int list(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        var listing = runtimes.getOrCreate(source.getServer()).worldImportManager().list(source.getServer());
        source.sendSuccess(() -> ChatMessages.info("Mundos Escadona:"), false);
        if (listing.entries().isEmpty()) {
            source.sendSuccess(() -> ChatMessages.detail("• nenhum mapa disponível ou importado"), false);
        } else {
            listing.entries().forEach(entry -> source.sendSuccess(() -> {
                String detail = entry.detail() == null ? "" : " — " + entry.detail();
                String line = "• " + entry.name() + " - " + entry.state() + detail;
                return entry.state() == WorldImportManager.WorldListState.INVALID
                        ? ChatMessages.error(line) : ChatMessages.detail(line);
            }, false));
        }
        return listing.entries().size();
    }

    private static int teleportSelf(CommandSourceStack source, ServerRuntimeRegistry runtimes, String input) {
        try {
            ServerPlayer player = source.getPlayer();
            if (player == null) throw new IllegalStateException(
                    "Console deve informar um jogador: /escadona world tp <nome> <player>");
            return teleport(source, runtimes, input, player, false);
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int teleportOther(CommandSourceStack source, ServerRuntimeRegistry runtimes,
                                     String input, ServerPlayer player) {
        try { return teleport(source, runtimes, input, player, true); }
        catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int teleport(CommandSourceStack source, ServerRuntimeRegistry runtimes,
                                String input, ServerPlayer target, boolean reportTarget) {
        var runtime = runtimes.getOrCreate(source.getServer());
        var preparation = runtime.worldImportManager().prepareSpawnForTeleport(source.getServer(), input);
        if (preparation != null) {
            source.sendSuccess(() -> ChatMessages.warning(
                    "O spawn de '" + input + "' está sendo preparado; o teleporte continuará automaticamente."), false);
            preparation.whenComplete((ignored, error) -> source.getServer().execute(() -> {
                if (error != null) {
                    source.sendFailure(ChatMessages.error("Não foi possível preparar o spawn de '" + input + "'."));
                } else {
                    try { teleportNow(source, runtime, input, target, reportTarget); }
                    catch (Exception exception) { CommandSupport.fail(source, exception); }
                }
            }));
            return 1;
        }
        return teleportNow(source, runtime, input, target, reportTarget);
    }

    private static int teleportNow(CommandSourceStack source, me.oredev.escadona.game.EscadonaRuntime runtime,
                                   String input, ServerPlayer target, boolean reportTarget) {
        WorldNameResolver.Resolution resolution = resolve(input, worldIds(source.getServer()));
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, resolution.world());
        ResourceLocation from = target.level().dimension().location();
        var result = runtime.worldTeleportService().teleport(target, key);
        if (!result.success()) {
            if (source.getEntity() != target) source.sendFailure(ChatMessages.error(result.message()));
            return 0;
        }
        if (reportTarget && source.getEntity() != target) {
            source.sendSuccess(() -> ChatMessages.success(target.getGameProfile().getName()
                    + " foi teleportado para " + displayName(resolution.world()) + "."), true);
        }
        Escadona.LOGGER.info("Admin {} teleported {} from {} to {}", source.getTextName(),
                target.getGameProfile().getName(), from, resolution.world());
        return 1;
    }

    private static WorldNameResolver.Resolution resolve(String input, List<ResourceLocation> available) {
        WorldNameResolver.Resolution resolution = RESOLVER.resolve(input, available);
        switch (resolution.status()) {
            case INVALID -> throw new IllegalArgumentException("Identificador de mundo inválido: '" + input + "'.");
            case NOT_FOUND -> throw new IllegalArgumentException("O mundo '" + input + "' não está carregado.");
            case AMBIGUOUS -> throw new IllegalArgumentException("Nome de mundo ambíguo. Use: "
                    + String.join(", ", resolution.candidates().stream().map(ResourceLocation::toString).toList()));
            case FOUND -> { }
        }
        return resolution;
    }

    private static List<ResourceLocation> worldIds(MinecraftServer server) {
        return StreamSupport.stream(server.getAllLevels().spliterator(), false)
                .map(level -> level.dimension().location()).distinct()
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();
    }

    private static String displayName(ResourceLocation id) {
        return id.getNamespace().equals(Escadona.MOD_ID) ? id.getPath() : id.toString();
    }
}
