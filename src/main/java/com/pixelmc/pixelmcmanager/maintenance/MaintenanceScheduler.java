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

/**
 * Handles manual maintenance mode: timed or immediate kick-and-reject without stopping the server.
 */
public final class MaintenanceScheduler {
    private static final List<Integer> MINUTE_WARNINGS = List.of(15, 10, 5, 4, 3, 2, 1);
    private static final String MINUTE_WARNING = "&e服务器将在 &c{x} &e分钟后进入维护!";
    private static final String SECOND_WARNING = "&e服务器将在 &c{x} &e秒后进入维护!";
    private static final String KICK_MESSAGE = "&6服务器开始执行维护!\n&b详细信息见QQ群:768322731";
    private static final String REJECT_MESSAGE = "&6服务器正在维护中!\n&b详细信息见QQ群:768322731";
    private static final DateTimeFormatter SYSTEM_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WelcomeConfigManager configManager;
    private final TextTemplateParser textParser;
    private MaintenancePlan plan;
    private boolean manualMaintenanceActive = false;
    private long maintenanceStartedAtMillis = 0L;

    public MaintenanceScheduler(WelcomeConfigManager configManager, TextTemplateParser textParser) {
        this.configManager = configManager;
        this.textParser = textParser;
    }

    public synchronized Result schedule(MinecraftServer server, Duration delay) {
        if (manualMaintenanceActive) {
            return Result.failure("服务器已经处于维护状态，请先执行 /pixelmcmanager maintenance off。");
        }

        long nowMillis = System.currentTimeMillis();
        boolean replaced = plan != null;
        plan = new MaintenancePlan(nowMillis, delay);
        sendDueWarnings(server, nowMillis);

        String startTime =
                SYSTEM_TIME_FORMAT
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(plan.startTimeMillis()));
        if (replaced) {
            return Result.success(
                    "已终止当前存在的服务器维护计划,并成功预定服务器在 "
                            + formatDelay(delay)
                            + " 后进入维护,对应系统时间 "
                            + startTime);
        }
        return Result.success("已成功预定服务器在 " + formatDelay(delay) + " 后进入维护,对应系统时间 " + startTime);
    }

    public synchronized Result startNow(MinecraftServer server) {
        if (manualMaintenanceActive) {
            return Result.failure("服务器已经处于维护状态，请先执行 /pixelmcmanager maintenance off。");
        }

        boolean replaced = plan != null;
        plan = null;
        enterMaintenance(server, System.currentTimeMillis());
        if (replaced) {
            return Result.success("已终止当前存在的服务器维护计划，并立即进入维护状态。");
        }
        return Result.success("服务器已立即进入维护状态。");
    }

    public synchronized Result turnOff() {
        if (plan != null) {
            plan = null;
            return Result.success("已取消当前预定的服务器维护计划。");
        }
        if (!manualMaintenanceActive) {
            return Result.failure("当前没有正在进行的服务器维护计划。");
        }
        manualMaintenanceActive = false;
        maintenanceStartedAtMillis = 0L;
        return Result.success("已解除服务器维护状态，玩家现在可以重新加入。");
    }

    public synchronized StatusSnapshot getStatusSnapshot() {
        if (manualMaintenanceActive) {
            return StatusSnapshot.active(maintenanceStartedAtMillis);
        }
        if (plan != null) {
            return StatusSnapshot.scheduled(plan.startTimeMillis());
        }
        return StatusSnapshot.none();
    }

    public synchronized boolean rejectMaintenanceJoin(ServerPlayer player) {
        if (!manualMaintenanceActive) {
            return false;
        }
        player.connection.disconnect(parse(REJECT_MESSAGE));
        return true;
    }

    public synchronized boolean isManualMaintenanceActive() {
        return manualMaintenanceActive;
    }

    public synchronized void tick(MinecraftServer server) {
        if (plan == null || manualMaintenanceActive) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        sendDueWarnings(server, nowMillis);
        if (nowMillis >= plan.startTimeMillis()) {
            enterMaintenance(server, nowMillis);
        }
    }

    private void sendDueWarnings(MinecraftServer server, long nowMillis) {
        if (plan == null) {
            return;
        }

        long remainingMillis = Math.max(0L, plan.startTimeMillis() - nowMillis);
        long originalMillis = plan.originalDelay().toMillis();
        int minuteBucket = (int) Math.ceil(remainingMillis / 60_000.0D);
        if (MINUTE_WARNINGS.contains(minuteBucket)
                && originalMillis >= minuteBucket * 60_000L
                && plan.markMinuteWarning(minuteBucket)) {
            broadcastChat(server, MINUTE_WARNING.replace("{x}", Integer.toString(minuteBucket)));
        }

        int secondBucket = (int) Math.ceil(remainingMillis / 1_000.0D);
        if (secondBucket >= 1
                && secondBucket <= 10
                && originalMillis >= secondBucket * 1_000L
                && plan.markSecondWarning(secondBucket)) {
            String message = SECOND_WARNING.replace("{x}", Integer.toString(secondBucket));
            broadcastChat(server, message);
            broadcastSubtitle(server, message);
        }
    }

    private void enterMaintenance(MinecraftServer server, long nowMillis) {
        plan = null;
        manualMaintenanceActive = true;
        maintenanceStartedAtMillis = nowMillis;
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

    public record Result(boolean success, String message) {
        public static Result success(String message) {
            return new Result(true, message);
        }

        public static Result failure(String message) {
            return new Result(false, message);
        }
    }

    public record StatusSnapshot(
            State state, long maintenanceStartTimeMillis, long maintenanceStartedAtMillis) {
        public static StatusSnapshot none() {
            return new StatusSnapshot(State.NONE, 0L, 0L);
        }

        public static StatusSnapshot scheduled(long maintenanceStartTimeMillis) {
            return new StatusSnapshot(State.SCHEDULED, maintenanceStartTimeMillis, 0L);
        }

        public static StatusSnapshot active(long maintenanceStartedAtMillis) {
            return new StatusSnapshot(State.ACTIVE, 0L, maintenanceStartedAtMillis);
        }
    }

    public enum State {
        NONE,
        SCHEDULED,
        ACTIVE
    }
}
