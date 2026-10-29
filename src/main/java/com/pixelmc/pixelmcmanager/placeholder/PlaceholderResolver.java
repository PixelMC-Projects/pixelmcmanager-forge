package com.pixelmc.pixelmcmanager.placeholder;

import com.pixelmc.pixelmcmanager.stats.PlayerStats;
import com.pixelmc.pixelmcmanager.util.TimeFormatUtil;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceholderResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    public String resolve(String input, PlaceholderContext context) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        Map<String, Supplier<String>> values = buildValues(context);
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Supplier<String> supplier = values.get(matcher.group(1));
            if (supplier == null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(supplier.get()));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public String resolveGlobal(String input, com.pixelmc.pixelmcmanager.config.WelcomeConfig config, MinecraftServer server) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        ZoneId zoneId = parseZone(config.timezone);
        Map<String, Supplier<String>> values = new HashMap<>();
        values.put("online", () -> Integer.toString(server.getPlayerList().getPlayerCount()));
        values.put("max", () -> Integer.toString(server.getMaxPlayers()));
        values.put("date", () -> formatNow(config.dateFormat, zoneId));
        values.put("time", () -> formatNow(config.timeFormat, zoneId));
        return resolve(input, values);
    }

    private Map<String, Supplier<String>> buildValues(PlaceholderContext context) {
        Map<String, Supplier<String>> values = new HashMap<>();
        PlayerStats stats = context.stats();
        MinecraftServer server = context.player().getServer();
        ZoneId zoneId = parseZone(context.config().timezone);

        values.put("player", () -> context.player().getGameProfile().getName());
        values.put("uuid", () -> context.player().getUUID().toString());
        values.put("online", () -> Integer.toString(server == null ? 1 : server.getPlayerCount()));
        values.put("max", () -> Integer.toString(server == null ? 0 : server.getMaxPlayers()));
        values.put("join_count", () -> Integer.toString(stats == null ? 0 : stats.joinCount));
        values.put("playtime", () -> TimeFormatUtil.formatFriendly(stats == null ? 0L : stats.totalOnlineMillis));
        values.put("playtime_hours", () -> Long.toString((stats == null ? 0L : stats.totalOnlineMillis) / 3_600_000L));
        values.put("playtime_minutes", () -> Long.toString((stats == null ? 0L : stats.totalOnlineMillis) / 60_000L));
        values.put("ping", () -> Integer.toString(Math.max(0, context.player().connection.latency())));
        values.put("date", () -> formatNow(context.config().dateFormat, zoneId));
        values.put("time", () -> formatNow(context.config().timeFormat, zoneId));
        values.put("first_join_date", () -> formatEpoch(stats == null ? 0L : stats.firstJoinEpochMillis, context.config().dateFormat, zoneId));
        values.put("last_join_date", () -> formatEpoch(context.lastJoinDateEpochMillis(), context.config().dateFormat, zoneId));
        return values;
    }

    private static String resolve(String input, Map<String, Supplier<String>> values) {
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Supplier<String> supplier = values.get(matcher.group(1));
            if (supplier == null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(supplier.get()));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static ZoneId parseZone(String zone) {
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static String formatNow(String pattern, ZoneId zoneId) {
        return formatter(pattern, zoneId).format(Instant.now());
    }

    private static String formatEpoch(long epochMillis, String pattern, ZoneId zoneId) {
        if (epochMillis <= 0L) {
            return "-";
        }
        return formatter(pattern, zoneId).format(Instant.ofEpochMilli(epochMillis));
    }

    private static DateTimeFormatter formatter(String pattern, ZoneId zoneId) {
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(zoneId);
        } catch (RuntimeException ignored) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zoneId);
        }
    }
}
