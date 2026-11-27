package com.pixelmc.pixelmcmanager.event;

import com.pixelmc.pixelmcmanager.config.AnnouncementConfig;
import com.pixelmc.pixelmcmanager.config.WelcomeConfig;
import com.pixelmc.pixelmcmanager.config.WelcomeConfigManager;
import com.pixelmc.pixelmcmanager.placeholder.PlaceholderResolver;
import com.pixelmc.pixelmcmanager.text.TextTemplateParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class AnnouncementScheduler {
    private final WelcomeConfigManager configManager;
    private final PlaceholderResolver placeholderResolver;
    private final TextTemplateParser textParser;
    private WelcomeConfig lastConfig;
    private long nextAnnouncementAtMillis = 0L;
    private int nextMessageIndex = 0;

    public AnnouncementScheduler(
            WelcomeConfigManager configManager,
            PlaceholderResolver placeholderResolver,
            TextTemplateParser textParser) {
        this.configManager = configManager;
        this.placeholderResolver = placeholderResolver;
        this.textParser = textParser;
    }

    public void tick(MinecraftServer server) {
        WelcomeConfig config = configManager.getConfig();
        AnnouncementConfig announcements = config.announcements;
        if (config != lastConfig) {
            lastConfig = config;
            nextAnnouncementAtMillis = 0L;
            nextMessageIndex = 0;
        }
        if (!announcements.enabled) {
            nextAnnouncementAtMillis = 0L;
            return;
        }

        long nowMillis = System.currentTimeMillis();
        if (nextAnnouncementAtMillis <= 0L) {
            nextAnnouncementAtMillis = nowMillis + announcements.initialDelayMinutes * 60_000L;
            return;
        }
        if (nowMillis < nextAnnouncementAtMillis) {
            return;
        }

        List<String> messages = announcements.messages;
        if (!messages.isEmpty() && !server.getPlayerList().getPlayers().isEmpty()) {
            String template = messages.get(nextMessageIndex % messages.size());
            nextMessageIndex = (nextMessageIndex + 1) % messages.size();
            String resolved = placeholderResolver.resolveGlobal(template, config, server);
            Component component = textParser.parse(resolved, config);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(component);
            }
        }
        nextAnnouncementAtMillis = nowMillis + announcements.intervalMinutes * 60_000L;
    }
}
