package com.pixelmc.pixelmcmanager.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeFormatUtil {
    private TimeFormatUtil() {}

    public static String formatFriendly(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1_000L;
        if (totalSeconds < 60L) {
            return totalSeconds + "秒";
        }

        long totalMinutes = totalSeconds / 60L;
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes % (24L * 60L)) / 60L;
        long minutes = totalMinutes % 60L;

        StringBuilder result = new StringBuilder();
        if (days > 0L) {
            result.append(days).append("天");
            if (hours > 0L) {
                result.append(hours).append("小时");
            }
            return result.toString();
        }
        if (hours > 0L) {
            result.append(hours).append("小时");
            result.append(String.format("%02d", minutes)).append("分钟");
            return result.toString();
        }
        return minutes + "分钟";
    }

    public static String formatDateTime(
            long epochMillis, String timezone, String dateFormat, String timeFormat) {
        if (epochMillis <= 0L) {
            return "未知";
        }
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern(dateFormat + " " + timeFormat).withZone(zoneId);
            return formatter.format(Instant.ofEpochMilli(epochMillis));
        } catch (RuntimeException ignored) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Shanghai"))
                    .format(Instant.ofEpochMilli(epochMillis));
        }
    }
}
