package com.pixelmc.pixelmcmanager.maintenance;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeArgumentParser {
    private static final Pattern TIME_PATTERN = Pattern.compile("^([1-9][0-9]*)([smhSMH])$");
    private static final Duration MAX_DURATION = Duration.ofHours(24);
    private static final String USAGE = "时间格式无效，请使用正整数加 s/m/h，例如 30s、10m、1h，最大 24h。";

    private TimeArgumentParser() {}

    public static Result parse(String input) {
        if (input == null || input.isBlank()) {
            return Result.failure(USAGE);
        }

        Matcher matcher = TIME_PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            return Result.failure(USAGE);
        }

        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            return Result.failure(USAGE);
        }

        Duration duration;
        try {
            duration =
                    switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                        case "s" -> Duration.ofSeconds(amount);
                        case "m" -> Duration.ofMinutes(amount);
                        case "h" -> Duration.ofHours(amount);
                        default -> null;
                    };
        } catch (ArithmeticException exception) {
            return Result.failure("计划时间最大为 24h。");
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return Result.failure(USAGE);
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            return Result.failure("计划时间最大为 24h。");
        }
        return Result.success(duration);
    }

    public record Result(boolean success, Duration duration, String errorMessage) {
        public static Result success(Duration duration) {
            return new Result(true, duration, "");
        }

        public static Result failure(String errorMessage) {
            return new Result(false, Duration.ZERO, errorMessage);
        }
    }
}
