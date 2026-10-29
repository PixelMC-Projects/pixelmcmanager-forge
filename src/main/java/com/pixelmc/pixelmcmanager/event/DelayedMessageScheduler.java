package com.pixelmc.pixelmcmanager.event;

import com.pixelmc.pixelmcmanager.config.WelcomeConfig;
import com.pixelmc.pixelmcmanager.config.WelcomeConfigManager;
import com.pixelmc.pixelmcmanager.placeholder.PlaceholderContext;
import com.pixelmc.pixelmcmanager.placeholder.PlaceholderResolver;
import com.pixelmc.pixelmcmanager.stats.PlayerStats;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import com.pixelmc.pixelmcmanager.text.TextTemplateParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public final class DelayedMessageScheduler {
    private final WelcomeConfigManager configManager;
    private final PlayerStatsStore statsStore;
    private final PlaceholderResolver placeholderResolver;
    private final TextTemplateParser textParser;
    private final List<ScheduledMessage> queue = new LinkedList<>();
    private long serverTicks = 0L;

    public DelayedMessageScheduler(WelcomeConfigManager configManager, PlayerStatsStore statsStore, PlaceholderResolver placeholderResolver, TextTemplateParser textParser) {
        this.configManager = configManager;
        this.statsStore = statsStore;
        this.placeholderResolver = placeholderResolver;
        this.textParser = textParser;
    }

    public void schedule(ServerPlayer player, boolean firstJoin, long lastJoinDateEpochMillis) {
        WelcomeConfig config = configManager.getConfig();
        queue.removeIf(entry -> entry.playerId().equals(player.getUUID()));
        queue.add(new ScheduledMessage(player.getUUID(), serverTicks + config.delayTicks, firstJoin, lastJoinDateEpochMillis));
    }

    public void cancel(UUID playerId) {
        queue.removeIf(entry -> entry.playerId().equals(playerId));
    }

    public void tick(MinecraftServer server) {
        serverTicks++;
        Iterator<ScheduledMessage> iterator = queue.iterator();
        while (iterator.hasNext()) {
            ScheduledMessage entry = iterator.next();
            if (entry.dueTick() > serverTicks) {
                continue;
            }
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.playerId());
            if (player != null) {
                sendMessages(player, entry.firstJoin(), entry.lastJoinDateEpochMillis());
            }
        }
    }

    public void sendMessages(ServerPlayer player, boolean firstJoin, long lastJoinDateEpochMillis) {
        WelcomeConfig config = configManager.getConfig();
        List<String> templates = firstJoin ? config.firstJoinMessages : config.returningMessages;
        if (!config.enabled || templates.isEmpty()) {
            return;
        }

        PlayerStats stats = statsStore.getStats(player.getUUID());
        PlaceholderContext context = new PlaceholderContext(player, config, stats, lastJoinDateEpochMillis);
        for (String template : templates) {
            String resolved = placeholderResolver.resolve(template, context);
            player.sendSystemMessage(textParser.parse(resolved, config));
        }
    }

    private record ScheduledMessage(UUID playerId, long dueTick, boolean firstJoin, long lastJoinDateEpochMillis) {
    }
}
