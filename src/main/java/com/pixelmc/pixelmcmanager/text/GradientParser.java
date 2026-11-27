package com.pixelmc.pixelmcmanager.text;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

public final class GradientParser {
    private GradientParser() {}

    public static MutableComponent render(String text, int startRgb, int endRgb, Style baseStyle) {
        MutableComponent result = Component.empty();
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length == 0) {
            return result;
        }

        for (int index = 0; index < codePoints.length; index++) {
            float ratio =
                    codePoints.length == 1 ? 0.0F : (float) index / (float) (codePoints.length - 1);
            int rgb = interpolate(startRgb, endRgb, ratio);
            String character = new String(Character.toChars(codePoints[index]));
            result.append(
                    Component.literal(character)
                            .withStyle(baseStyle.withColor(TextColor.fromRgb(rgb))));
        }
        return result;
    }

    private static int interpolate(int startRgb, int endRgb, float ratio) {
        int startR = (startRgb >> 16) & 0xFF;
        int startG = (startRgb >> 8) & 0xFF;
        int startB = startRgb & 0xFF;
        int endR = (endRgb >> 16) & 0xFF;
        int endG = (endRgb >> 8) & 0xFF;
        int endB = endRgb & 0xFF;

        int red = Math.round(startR + (endR - startR) * ratio);
        int green = Math.round(startG + (endG - startG) * ratio);
        int blue = Math.round(startB + (endB - startB) * ratio);
        return (red << 16) | (green << 8) | blue;
    }
}
