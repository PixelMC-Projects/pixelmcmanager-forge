package com.pixelmc.pixelmcmanager.text;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public final class LegacyFormatParser {
    private LegacyFormatParser() {}

    public static Style applyLegacyCode(Style style, char code) {
        ChatFormatting formatting = ChatFormatting.getByCode(code);
        if (formatting == null) {
            return style;
        }
        if (formatting == ChatFormatting.RESET) {
            return Style.EMPTY;
        }
        return style.applyFormat(formatting);
    }

    public static boolean isLegacyCode(char code) {
        return ChatFormatting.getByCode(code) != null;
    }

    public static Style applyHexColor(Style style, String hex) {
        return style.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
    }

    public static boolean isHexColor(String value) {
        return value != null && value.matches("[0-9a-fA-F]{6}");
    }
}
