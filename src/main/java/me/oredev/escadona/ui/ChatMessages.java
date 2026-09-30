package me.oredev.escadona.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Consistent vanilla chat styling for every Escadona audience. */
public final class ChatMessages {
    private ChatMessages() { }

    public static Component success(String text) { return message(text, ChatFormatting.GREEN); }
    public static Component info(String text) { return message(text, ChatFormatting.AQUA); }
    public static Component warning(String text) { return message(text, ChatFormatting.YELLOW); }
    public static Component error(String text) { return message(text, ChatFormatting.RED); }
    public static Component detail(String text) { return message(text, ChatFormatting.GRAY); }

    public static MutableComponent message(String text, ChatFormatting color) {
        return prefix().append(Component.literal(text).withStyle(color));
    }

    public static MutableComponent prefix() {
        return Component.literal("[ESCADONA] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    public static Component sectionCompleted(String playerName, int section) {
        return prefix()
                .append(Component.literal(playerName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" concluiu a parte ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(section)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal("!").withStyle(ChatFormatting.GREEN));
    }

    public static Component barrierCountdown(String text) { return message(text, ChatFormatting.YELLOW); }
    public static Component barrierOpen(String text) { return message(text, ChatFormatting.GREEN); }

    public static Component death(Component vanillaDeathMessage) {
        return prefix()
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                .append(vanillaDeathMessage.copy().withStyle(ChatFormatting.RED));
    }

    public static Component kill(String victimName, String killerName) {
        return prefix()
                .append(Component.literal("⚔ ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal(victimName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" foi abatido por ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(killerName).withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal("!").withStyle(ChatFormatting.RED));
    }

    public static Component placement(int position, String playerName, String time) {
        MutableComponent rank = Component.literal(position + "º ");
        if (position == 1) rank.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        else if (position == 2) rank.withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
        else rank.withColor(0xCD7F32).withStyle(ChatFormatting.BOLD);
        return prefix()
                .append(rank)
                .append(Component.literal(playerName).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(time).withStyle(ChatFormatting.AQUA));
    }
}
