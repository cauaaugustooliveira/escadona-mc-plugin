package me.oredev.escadona.scoreboard;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Parses familiar ampersand color codes into vanilla Components without client-side code. */
public final class LegacyTextFormatter {
    private LegacyTextFormatter() { }

    public static Component parse(String input) {
        MutableComponent result = Component.empty();
        StringBuilder text = new StringBuilder();
        Style style = Style.EMPTY;
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current != '&' || index + 1 >= input.length()) {
                text.append(current);
                continue;
            }
            char code = input.charAt(index + 1);
            if (code == '&') {
                text.append('&');
                index++;
                continue;
            }
            if (code == '#' && index + 7 < input.length()) {
                try {
                    int color = Integer.parseInt(input.substring(index + 2, index + 8), 16);
                    append(result, text, style);
                    style = style.withColor(color);
                    index += 7;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Preserve an invalid sequence as ordinary text.
                }
            }
            ChatFormatting formatting = ChatFormatting.getByCode(Character.toLowerCase(code));
            if (formatting == null) {
                text.append(current);
                continue;
            }
            append(result, text, style);
            style = formatting == ChatFormatting.RESET ? Style.EMPTY : style.applyLegacyFormat(formatting);
            index++;
        }
        append(result, text, style);
        return result;
    }

    private static void append(MutableComponent result, StringBuilder text, Style style) {
        if (text.isEmpty()) return;
        result.append(Component.literal(text.toString()).setStyle(style));
        text.setLength(0);
    }
}
