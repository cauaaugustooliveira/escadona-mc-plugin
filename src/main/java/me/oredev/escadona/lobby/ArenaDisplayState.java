package me.oredev.escadona.lobby;

import me.oredev.escadona.game.GameState;
import net.minecraft.ChatFormatting;

public enum ArenaDisplayState {
    WAITING("Aguardando", ChatFormatting.GREEN, 0, true),
    STARTING("Iniciando", ChatFormatting.YELLOW, 1, false),
    PLAYING("Em partida", ChatFormatting.RED, 2, false),
    FINISHING("Finalizando", ChatFormatting.GOLD, 3, false),
    RESETTING("Restaurando", ChatFormatting.GRAY, 4, false),
    UNAVAILABLE("Indisponível", ChatFormatting.DARK_RED, 5, false);

    private final String label;
    private final ChatFormatting color;
    private final int priority;
    private final boolean joinable;

    ArenaDisplayState(String label, ChatFormatting color, int priority, boolean joinable) {
        this.label = label;
        this.color = color;
        this.priority = priority;
        this.joinable = joinable;
    }

    public String label() { return label; }
    public ChatFormatting color() { return color; }
    public int priority() { return priority; }
    public boolean joinable() { return joinable; }

    public static ArenaDisplayState from(GameState state) {
        return switch (state) {
            case WAITING -> WAITING;
            case STARTING -> STARTING;
            case PLAYING -> PLAYING;
            case FINISHING -> FINISHING;
            case RESETTING -> RESETTING;
        };
    }
}
