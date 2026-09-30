package me.oredev.escadona.scoreboard;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextFormatterTest {
    @Test
    void parsesColorsFormattingResetAndEscapedAmpersand() {
        var component = LegacyTextFormatter.parse("&6&lOuro &r&fBranco && texto");

        assertEquals("Ouro Branco & texto", component.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD),
                component.getSiblings().getFirst().getStyle().getColor());
        assertTrue(component.getSiblings().getFirst().getStyle().isBold());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),
                component.getSiblings().get(1).getStyle().getColor());
    }

    @Test
    void parsesUppercaseAndHexColors() {
        var component = LegacyTextFormatter.parse("&AOK &#CD7F32Bronze");

        assertEquals("OK Bronze", component.getString());
        assertEquals(0xCD7F32, component.getSiblings().get(1).getStyle().getColor().getValue());
    }
}
