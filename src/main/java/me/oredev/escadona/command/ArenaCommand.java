package me.oredev.escadona.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.oredev.escadona.arena.ArenaDraft;
import me.oredev.escadona.arena.ArenaManager;
import me.oredev.escadona.game.ServerRuntimeRegistry;
import me.oredev.escadona.ui.ChatMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class ArenaCommand {
    private ArenaCommand() { }

    static LiteralArgumentBuilder<CommandSourceStack> node(ServerRuntimeRegistry runtimes) {
        return Commands.literal("arena")
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> create(context.getSource(), runtimes, id(context.getArgument("name", String.class))))))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), runtimes)))
                .then(snapshotNode(runtimes))
                .then(Commands.literal("reset").then(Commands.literal("retry")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(context -> resetRetry(
                                context.getSource(), runtimes, id(context.getArgument("name", String.class)))))))
                .then(Commands.literal("info").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> info(context.getSource(), runtimes, id(context.getArgument("name", String.class))))))
                .then(Commands.literal("keepinventory").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("value", BoolArgumentType.bool()).executes(context -> mutate(
                                context.getSource(), runtimes, id(context.getArgument("name", String.class)),
                                draft -> draft.setKeepInventoryOnDeath(BoolArgumentType.getBool(context, "value")),
                                "keepInventoryOnDeath atualizado.")))))
                .then(positionNode("setdeathwait", (draft, player, ignored) -> draft.setDeathWait(player.blockPosition()), runtimes, false))
                .then(positionNode("setregionpos1", (draft, player, ignored) -> draft.setRegionPos1(player.blockPosition()), runtimes, false))
                .then(positionNode("setregionpos2", (draft, player, ignored) -> draft.setRegionPos2(player.blockPosition()), runtimes, false))
                .then(Commands.literal("addlane").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("lane", StringArgumentType.word()).executes(context -> mutate(
                                context.getSource(), runtimes, id(context.getArgument("name", String.class)),
                                draft -> draft.addLane(id(context.getArgument("lane", String.class))), "Lane adicionada.")))))
                .then(Commands.literal("setspawn").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("lane", StringArgumentType.word()).executes(context -> withPlayerDraft(
                                context.getSource(), runtimes, id(context.getArgument("name", String.class)),
                                (draft, player) -> draft.setLaneSpawn(id(context.getArgument("lane", String.class)), player.blockPosition()),
                                "Spawn da lane definido.")))))
                .then(Commands.literal("setcheckpoint").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("lane", StringArgumentType.word())
                                .then(Commands.argument("section", IntegerArgumentType.integer(1)).executes(context -> withPlayerDraft(
                                        context.getSource(), runtimes, id(context.getArgument("name", String.class)),
                                        (draft, player) -> draft.setLaneCheckpoint(
                                                id(context.getArgument("lane", String.class)),
                                                IntegerArgumentType.getInteger(context, "section"), player.blockPosition()),
                                        "Checkpoint da lane definido."))))))
                .then(Commands.literal("clearcheckpoint").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("lane", StringArgumentType.word())
                                .then(Commands.argument("section", IntegerArgumentType.integer(1)).executes(context -> mutate(
                                        context.getSource(), runtimes, id(context.getArgument("name", String.class)),
                                        draft -> draft.clearLaneCheckpoint(
                                                id(context.getArgument("lane", String.class)),
                                                IntegerArgumentType.getInteger(context, "section")),
                                        "Checkpoint da lane removido."))))))
                .then(Commands.literal("addsection").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("section", IntegerArgumentType.integer(1)).executes(context -> mutate(
                                context.getSource(), runtimes, id(context.getArgument("name", String.class)),
                                draft -> draft.addSection(IntegerArgumentType.getInteger(context, "section")), "Seção adicionada.")))))
                .then(Commands.literal("trackblock").then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("lane", StringArgumentType.word())
                                .then(Commands.argument("section", IntegerArgumentType.integer(1)).executes(context -> trackBlock(
                                        context.getSource(), runtimes, id(context.getArgument("name", String.class)),
                                        id(context.getArgument("lane", String.class)), IntegerArgumentType.getInteger(context, "section")))))))
                .then(positionNode("barrierpos1", (draft, player, section) -> draft.setBarrierPos1(section, player.blockPosition()), runtimes, true))
                .then(positionNode("barrierpos2", (draft, player, section) -> draft.setBarrierPos2(section, player.blockPosition()), runtimes, true))
                .then(positionNode("finishpos1", (draft, player, ignored) -> draft.setFinishPos1(player.blockPosition()), runtimes, false))
                .then(positionNode("finishpos2", (draft, player, ignored) -> draft.setFinishPos2(player.blockPosition()), runtimes, false))
                .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> save(context.getSource(), runtimes, id(context.getArgument("name", String.class))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> snapshotNode(ServerRuntimeRegistry runtimes) {
        return Commands.literal("snapshot")
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> snapshotCreate(context.getSource(), runtimes, id(context.getArgument("name", String.class))))))
                .then(Commands.literal("info").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> snapshotInfo(context.getSource(), runtimes, id(context.getArgument("name", String.class))))))
                .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> snapshotDelete(context.getSource(), runtimes, id(context.getArgument("name", String.class))))))
                .then(Commands.literal("restore").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> snapshotRestore(context.getSource(), runtimes, id(context.getArgument("name", String.class))))))
                .then(Commands.literal("verify").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> snapshotVerify(context.getSource(), runtimes, id(context.getArgument("name", String.class))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> positionNode(
            String literal, PositionMutation mutation, ServerRuntimeRegistry runtimes, boolean sectionArgument) {
        var nameArgument = Commands.argument("name", StringArgumentType.word());
        if (sectionArgument) {
            nameArgument.then(Commands.argument("section", IntegerArgumentType.integer(1)).executes(context -> withPlayerDraft(
                    context.getSource(), runtimes, id(context.getArgument("name", String.class)),
                    (draft, player) -> mutation.apply(draft, player, IntegerArgumentType.getInteger(context, "section")),
                    "Posição definida.")));
        } else {
            nameArgument.executes(context -> withPlayerDraft(context.getSource(), runtimes,
                    id(context.getArgument("name", String.class)),
                    (draft, player) -> mutation.apply(draft, player, 0), "Posição definida."));
        }
        return Commands.literal(literal).then(nameArgument);
    }

    private static int create(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            validateName(name);
            ServerPlayer player = CommandSupport.player(source);
            ArenaDraft draft = arenas(source, runtimes).createDraft(name);
            draft.setDimension(player.level().dimension());
            source.sendSuccess(() -> ChatMessages.success("Arena " + name + " criada na dimensão "
                    + player.level().dimension().location() + "."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int list(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        ArenaManager manager = arenas(source, runtimes);
        Set<String> names = new LinkedHashSet<>();
        manager.all().forEach(arena -> names.add(arena.id() + " [salva]"));
        manager.drafts().forEach(draft -> names.add(draft.id() + " [rascunho]"));
        source.sendSuccess(() -> ChatMessages.info(names.isEmpty() ? "Nenhuma arena configurada."
                : "Arenas: " + String.join(", ", names)), false);
        return names.size();
    }

    private static int info(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            ArenaDraft draft = requireDraft(source, runtimes, name);
            var validation = draft.validate();
            source.sendSuccess(() -> ChatMessages.info("Arena " + name + ": lanes=" + draft.lanes().size()
                    + ", sections=" + draft.sections().size()
                    + ", checkpoints=" + draft.lanes().values().stream().mapToInt(lane -> lane.checkpoints().size()).sum()
                    + ", deathWait=" + (draft.deathWait() == null ? "não definido" : draft.deathWait().toShortString())
                    + ", keepInventoryOnDeath=" + draft.keepInventoryOnDeath()
                    + ", valid=" + validation.isValid()), false);
            validation.errors().forEach(error -> source.sendFailure(ChatMessages.error("ERRO: " + error)));
            validation.warnings().forEach(warning -> source.sendSuccess(() -> ChatMessages.warning("AVISO: " + warning), false));
            return validation.isValid() ? 1 : 0;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int trackBlock(CommandSourceStack source, ServerRuntimeRegistry runtimes,
                                  String arenaName, String lane, int section) {
        try {
            ServerPlayer player = CommandSupport.player(source);
            var runtime = runtimes.getOrCreate(source.getServer());
            var hit = CommandSupport.lookedAt(player);
            if (!runtime.luckyBlockResolver().matches(player.level().getBlockState(hit.getBlockPos()))) {
                throw new IllegalStateException("O bloco mirado não é a Lucky Block configurada.");
            }
            ArenaDraft draft = requireDraft(source, runtimes, arenaName);
            ensureDimension(draft, player);
            draft.addLuckyBlock(lane, section, hit.getBlockPos());
            source.sendSuccess(() -> ChatMessages.success("Lucky Block adicionada em "
                    + hit.getBlockPos().toShortString() + "."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int save(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            ArenaDraft draft = requireDraft(source, runtimes, name);
            var validation = draft.validate();
            if (!validation.isValid()) throw new IllegalStateException(String.join("; ", validation.errors()));
            arenas(source, runtimes).save(name);
            runtimes.getOrCreate(source.getServer()).lobbyManager().invalidateArena(name);
            validation.warnings().forEach(warning -> source.sendSuccess(() -> ChatMessages.warning("Aviso: " + warning), false));
            source.sendSuccess(() -> ChatMessages.success("Arena " + name + " salva em JSON."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int snapshotCreate(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            var metadata = runtimes.getOrCreate(source.getServer()).games()
                    .createArenaSnapshot(source.getServer(), name);
            runtimes.getOrCreate(source.getServer()).lobbyManager().invalidateArena(name);
            source.sendSuccess(() -> ChatMessages.success("Snapshot criado para " + name + ": " + metadata.width() + "x"
                    + metadata.height() + "x" + metadata.length() + " (" + metadata.blockCount() + " blocos)."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int snapshotInfo(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            var metadata = runtimes.getOrCreate(source.getServer()).games()
                    .arenaSnapshotMetadata(source.getServer(), name);
            source.sendSuccess(() -> ChatMessages.info("Snapshot " + name + ": schema=" + metadata.schemaVersion()
                    + " format=" + metadata.format() + " dimension=" + metadata.dimension().location()
                    + " bounds=" + metadata.region().min().toShortString() + " -> " + metadata.region().max().toShortString()
                    + " size=" + metadata.width() + "x" + metadata.height() + "x" + metadata.length()
                    + " blocks=" + metadata.blockCount() + " createdAt=" + metadata.createdAt()), false);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int snapshotDelete(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            runtimes.getOrCreate(source.getServer()).games().deleteArenaSnapshot(name);
            runtimes.getOrCreate(source.getServer()).lobbyManager().invalidateArena(name);
            source.sendSuccess(() -> ChatMessages.warning("Snapshot de " + name
                    + " apagado. A Arena não poderá iniciar até um novo snapshot ser criado."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int snapshotRestore(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            runtimes.getOrCreate(source.getServer()).games().restoreArenaSnapshot(source.getServer(), name);
            source.sendSuccess(() -> ChatMessages.warning("Restauração física de " + name + " iniciada."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int snapshotVerify(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            var result = runtimes.getOrCreate(source.getServer()).games().verifyArenaSnapshot(source.getServer(), name);
            String message = "Verificação " + name + ": valid=" + result.valid()
                    + " blocks=" + result.totalBlocks() + " blockMismatches=" + result.mismatchedBlocks()
                    + " blockEntityMismatches=" + result.mismatchedBlockEntities();
            source.sendSuccess(() -> result.valid() ? ChatMessages.success(message) : ChatMessages.error(message), false);
            return result.valid() ? 1 : 0;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int resetRetry(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        try {
            runtimes.getOrCreate(source.getServer()).games().retryArenaReset(source.getServer(), name);
            source.sendSuccess(() -> ChatMessages.warning("Reset de " + name + " reiniciado do bloco zero."), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int mutate(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name,
                              DraftMutation mutation, String success) {
        try {
            mutation.apply(requireDraft(source, runtimes, name));
            source.sendSuccess(() -> ChatMessages.success(success), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static int withPlayerDraft(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name,
                                       PlayerDraftMutation mutation, String success) {
        try {
            ServerPlayer player = CommandSupport.player(source);
            ArenaDraft draft = requireDraft(source, runtimes, name);
            ensureDimension(draft, player);
            mutation.apply(draft, player);
            source.sendSuccess(() -> ChatMessages.success(success), true);
            return 1;
        } catch (Exception exception) { return CommandSupport.fail(source, exception); }
    }

    private static void ensureDimension(ArenaDraft draft, ServerPlayer player) {
        if (draft.dimension() == null) draft.setDimension(player.level().dimension());
        if (!draft.dimension().equals(player.level().dimension())) {
            throw new IllegalStateException("A posição deve estar na dimensão da arena: " + draft.dimension().location());
        }
    }

    private static ArenaDraft requireDraft(CommandSourceStack source, ServerRuntimeRegistry runtimes, String name) {
        return arenas(source, runtimes).draft(name).orElseThrow(() -> new IllegalArgumentException("Arena desconhecida: " + name));
    }

    private static ArenaManager arenas(CommandSourceStack source, ServerRuntimeRegistry runtimes) {
        return runtimes.getOrCreate(source.getServer()).games().arenas();
    }

    private static String id(String value) { return value.toLowerCase(Locale.ROOT); }
    private static void validateName(String value) {
        if (!value.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Use apenas a-z, 0-9, _ ou - (máximo 32).");
    }

    @FunctionalInterface private interface DraftMutation { void apply(ArenaDraft draft) throws Exception; }
    @FunctionalInterface private interface PlayerDraftMutation { void apply(ArenaDraft draft, ServerPlayer player) throws Exception; }
    @FunctionalInterface private interface PositionMutation { void apply(ArenaDraft draft, ServerPlayer player, int section) throws Exception; }
}
