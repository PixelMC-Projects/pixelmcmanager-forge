package com.pixelmc.pixelmcmanager.text;

import com.pixelmc.pixelmcmanager.config.WelcomeConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;

import java.util.Locale;

public final class TextTemplateParser {
    private static final String GRADIENT_PREFIX = "<gradient:";
    private static final String GRADIENT_SUFFIX = "</gradient>";

    private final Logger logger;

    public TextTemplateParser(Logger logger) {
        this.logger = logger;
    }

    public MutableComponent parse(String input, WelcomeConfig config) {
        MutableComponent result = Component.empty();
        StringBuilder buffer = new StringBuilder();
        Style style = Style.EMPTY;
        int index = 0;

        while (index < input.length()) {
            if (input.startsWith(GRADIENT_PREFIX, index)) {
                GradientMatch match = findGradient(input, index);
                if (match != null) {
                    flush(result, buffer, style);
                    result.append(
                            GradientParser.render(
                                    match.text(), match.startRgb(), match.endRgb(), style));
                    index = match.endIndex();
                    continue;
                }
                if (config.debugLogging) {
                    logger.warn("Malformed gradient in PixelMC Manager message: {}", input);
                }
            }

            char character = input.charAt(index);
            if (character == '&') {
                if (index + 8 <= input.length() && input.charAt(index + 1) == '#') {
                    String hex = input.substring(index + 2, index + 8);
                    if (LegacyFormatParser.isHexColor(hex)) {
                        flush(result, buffer, style);
                        style = LegacyFormatParser.applyHexColor(style, hex);
                        index += 8;
                        continue;
                    }
                }
                if (index + 1 < input.length()) {
                    char code = Character.toLowerCase(input.charAt(index + 1));
                    if (LegacyFormatParser.isLegacyCode(code)) {
                        flush(result, buffer, style);
                        style = LegacyFormatParser.applyLegacyCode(style, code);
                        index += 2;
                        continue;
                    }
                }
            }

            int codePoint = input.codePointAt(index);
            buffer.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }

        flush(result, buffer, style);
        return result;
    }

    private static void flush(MutableComponent result, StringBuilder buffer, Style style) {
        if (buffer.length() == 0) {
            return;
        }
        result.append(Component.literal(buffer.toString()).withStyle(style));
        buffer.setLength(0);
    }

    private static GradientMatch findGradient(String input, int startIndex) {
        int headerEnd = input.indexOf('>', startIndex);
        if (headerEnd < 0) {
            return null;
        }
        int closeIndex = input.indexOf(GRADIENT_SUFFIX, headerEnd + 1);
        if (closeIndex < 0) {
            return null;
        }

        String header = input.substring(startIndex + GRADIENT_PREFIX.length(), headerEnd);
        String[] colors = header.split(":");
        if (colors.length != 2) {
            return null;
        }

        String startColor = stripHash(colors[0]);
        String endColor = stripHash(colors[1]);
        if (!LegacyFormatParser.isHexColor(startColor)
                || !LegacyFormatParser.isHexColor(endColor)) {
            return null;
        }

        String text = input.substring(headerEnd + 1, closeIndex);
        return new GradientMatch(
                Integer.parseInt(startColor, 16),
                Integer.parseInt(endColor, 16),
                text,
                closeIndex + GRADIENT_SUFFIX.length());
    }

    private static String stripHash(String color) {
        String normalized = color.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("#") ? normalized.substring(1) : normalized;
    }

    private record GradientMatch(int startRgb, int endRgb, String text, int endIndex) {}
}
