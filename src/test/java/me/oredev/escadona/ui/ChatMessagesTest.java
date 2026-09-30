package me.oredev.escadona.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessagesTest {
    @Test
    void successUsesGoldPrefixAndGreenBody() {
        var message = ChatMessages.success("Operação concluída.");

        assertEquals("[ESCADONA] Operação concluída.", message.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD), message.getStyle().getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN),
                message.getSiblings().getFirst().getStyle().getColor());
    }

    @Test
    void placementKeepsReadableSemanticContent() {
        assertEquals("[ESCADONA] 1º Cauã • 12:31",
                ChatMessages.placement(1, "Cauã", "12:31").getString());
    }

    @Test
    void killIdentifiesVictimAndKiller() {
        assertEquals("[ESCADONA] ⚔ Vítima foi abatido por Killer!",
                ChatMessages.kill("Vítima", "Killer").getString());
    }

    @Test
    void environmentalDeathKeepsVanillaCause() {
        assertEquals("[ESCADONA] ☠ Vítima caiu de um lugar alto",
                ChatMessages.death(Component.literal("Vítima caiu de um lugar alto")).getString());
    }
}
