package com.pixelmc.pixelmcmanager.stats;

public record PlayerLoginSnapshot(
        PlayerStats stats, boolean firstJoin, long previousLastJoinEpochMillis) {}
