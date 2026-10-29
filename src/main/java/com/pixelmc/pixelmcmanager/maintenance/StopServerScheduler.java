package com.pixelmc.pixelmcmanager.maintenance;

import com.pixelmc.pixelmcmanager.config.WelcomeConfig;
import com.pixelmc.pixelmcmanager.config.WelcomeConfigManager;
import com.pixelmc.pixelmcmanager.text.TextTemplateParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public final class StopServerScheduler {
    private static final List<Integer> MINUTE_WARNINGS = List.of(15, 10, 5, 4, 3, 2, 1);
    private static final String MINUTE_WARNING = "&e服务器将在 &c{x} &e分钟后停机维护!";
    private static final String SECOND_WARNING = "&e服务器将在 &c{x} &e秒后停机维护!";
    private static final String KICK_MESSAGE = "&6服务器开始执行停机维护!\n&b详细信息见QQ群:768322731";
    private static final String REJECT_MESSAGE = "&6服务器即将停机维护!\n&b详细信息见QQ群:768322731";
    private static final DateTimeFormatter SYSTEM_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WelcomeConfigManager configManager;
    private final TextTemplateParser textParser;
    private StopServerPlan plan;
    private boolean stopCommandIssued = false;

    public StopServerScheduler(WelcomeConfigManager configManager, TextTemplateParser textParser) {
        this.configManager = configManager;
        this.textParser = textParser;
    }

    public synchronized ScheduleResult schedule(MinecraftServer server, Duration delay) {
        long nowMillis = System.currentTimeMillis();
        boolean replaced = plan != null;
        plan = new StopServerPlan(nowMillis, delay);
        stopCommandIssued = false;
        sendDueWarnings(server, nowMillis);

        String kickTime = SYSTEM_TIME_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(plan.kickTimeMillis()));
        if (replaced) {
            return ScheduleResult.success("已终止当前存在的服务器终止进程,并成功预定服务器在 " + formatDelay(delay) + " 后停机,对应系统时间 " + kickTime);
        }
        return ScheduleResult.success("已成功预定服务器在 " + formatDelay(delay) + " 后停机,对应系统时间 " + kickTime);
    }

    public synchronized ScheduleResult cancel() {
        if (plan == null) {
            return ScheduleResult.failure("当前没有正在进行的服务器停机计划。");
        }
        plan = null;
        stopCommandIssued = false;
        return ScheduleResult.success("已取消当前预定的服务器停机计划。");
    }

    public synchronized Optional<PlanSnapshot> getCurrentPlanSnapshot() {
        if (plan == null) {
            return Optional.empty();
        }
        return Optional.of(new PlanSnapshot(plan.kickTimeMillis(), plan.stopTimeMillis(), plan.maintenanceActive()));
    }

    public synchronized boolean rejectMaintenanceJoin(ServerPlayer player) {
        if (plan == null || !plan.maintenanceActive()) {
            return false;
        }
        player.connection.disconnect(parse(REJECT_MESSAGE));
        return true;
    }

    public synchronized void tick(MinecraftServer server) {
        if (plan == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        if (!plan.maintenanceActive()) {
            sendDueWarnings(server, nowMillis);
            if (nowMillis >= plan.kickTimeMillis()) {
                activateMaintenance(server);
            }
            return;
        }

        if (nowMillis >= plan.stopTimeMillis() && !stopCommandIssued) {
            stopCommandIssued = true;
            kickAllPlayers(server, KICK_MESSAGE);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "stop");
        }
    }

    private void sendDueWarnings(MinecraftServer server, long nowMillis) {
        if (plan == null || plan.maintenanceActive()) {
            return;
        }

        long remainingMillis = Math.max(0L, plan.kickTimeMillis() - nowMillis);
        long originalMillis = plan.originalDelay().toMillis();
        int minuteBucket = (int) Math.ceil(remainingMillis / 60_000.0D);
        if (MINUTE_WARNINGS.contains(minuteBucket) && originalMillis >= minuteBucket * 60_000L && plan.markMinuteWarning(minuteBucket)) {
            broadcastChat(server, MINUTE_WARNING.replace("{x}", Integer.toString(minuteBucket)));
        }

        int secondBucket = (int) Math.ceil(remainingMillis / 1_000.0D);
        if (secondBucket >= 1 && secondBucket <= 10 && originalMillis >= secondBucket * 1_000L && plan.markSecondWarning(secondBucket)) {
            String message = SECOND_WARNING.replace("{x}", Integer.toString(secondBucket));
            broadcastChat(server, message);
            broadcastSubtitle(server, message);
        }
    }

    private void activateMaintenance(MinecraftServer server) {
        if (plan == null || plan.maintenanceActive()) {
            return;
        }
        plan.activateMaintenance();
        kickAllPlayers(server, KICK_MESSAGE);
    }

    private void kickAllPlayers(MinecraftServer server, String message) {
        Component reason = parse(message);
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            player.connection.disconnect(reason);
        }
    }

    private void broadcastChat(MinecraftServer server, String message) {
        Component component = parse(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(component);
        }
    }

    private void broadcastSubtitle(MinecraftServer server, String message) {
        Component component = parse(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 20, 5));
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
            player.connection.send(new ClientboundSetSubtitleTextPacket(component));
        }
    }

    private Component parse(String message) {
        WelcomeConfig config = configManager.getConfig();
        return textParser.parse(message, config);
    }

    private static String formatDelay(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds % 3_600L == 0L) {
            return (seconds / 3_600L) + "小时";
        }
        if (seconds % 60L == 0L) {
            return (seconds / 60L) + "分钟";
        }
        return seconds + "秒";
    }

    public record ScheduleResult(boolean success, String message) {
        public static ScheduleResult success(String message) {
            return new ScheduleResult(true, message);
        }

        public static ScheduleResult failure(String message) {
            return new ScheduleResult(false, message);
        }
    }

    public record PlanSnapshot(long kickTimeMillis, long stopTimeMillis, boolean maintenanceActive) {
    }
}
