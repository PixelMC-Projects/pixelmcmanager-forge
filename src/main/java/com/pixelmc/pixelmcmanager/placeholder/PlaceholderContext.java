package com.pixelmc.pixelmcmanager.placeholder;

import com.pixelmc.pixelmcmanager.config.WelcomeConfig;
import com.pixelmc.pixelmcmanager.stats.PlayerStats;
import net.minecraft.server.level.ServerPlayer;

public record PlaceholderContext(
        ServerPlayer player,
        WelcomeConfig config,
        PlayerStats stats,
        long lastJoinDateEpochMillis) {}
