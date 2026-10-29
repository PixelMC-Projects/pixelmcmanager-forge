package com.pixelmc.pixelmcmanager.event;

import com.pixelmc.pixelmcmanager.config.WelcomeConfigManager;
import com.pixelmc.pixelmcmanager.audit.AuditService;
import com.pixelmc.pixelmcmanager.maintenance.MaintenanceScheduler;
import com.pixelmc.pixelmcmanager.maintenance.StopServerScheduler;
import com.pixelmc.pixelmcmanager.runtime.ServerRuntimeStats;
import com.pixelmc.pixelmcmanager.restart.IdleRestartScheduler;
import com.pixelmc.pixelmcmanager.stats.PlayerLoginSnapshot;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

public final class WelcomeEventHandler {
    private final WelcomeConfigManager configManager;
    private final PlayerStatsStore statsStore;
    private final AuditService auditService;
    private final DelayedMessageScheduler scheduler;
    private final AnnouncementScheduler announcementScheduler;
    private final StopServerScheduler stopServerScheduler;
    private final MaintenanceScheduler maintenanceScheduler;
    private final ServerRuntimeStats runtimeStats;
    private final IdleRestartScheduler restartScheduler;
    private final com.pixelmc.pixelmcmanager.bridge.BridgeAgent bridge;
    private long lastStatsCheckpointMillis = 0L;

    public WelcomeEventHandler(WelcomeConfigManager configManager, PlayerStatsStore statsStore, AuditService auditService, DelayedMessageScheduler scheduler, AnnouncementScheduler announcementScheduler, StopServerScheduler stopServerScheduler, MaintenanceScheduler maintenanceScheduler, ServerRuntimeStats runtimeStats, IdleRestartScheduler restartScheduler, com.pixelmc.pixelmcmanager.bridge.BridgeAgent bridge) {
        this.configManager = configManager;
        this.statsStore = statsStore;
        this.auditService = auditService;
        this.scheduler = scheduler;
        this.announcementScheduler = announcementScheduler;
        this.stopServerScheduler = stopServerScheduler;
        this.maintenanceScheduler = maintenanceScheduler;
        this.runtimeStats = runtimeStats;
        this.restartScheduler = restartScheduler;
        this.bridge = bridge;
    }

    public void onServerStarted(MinecraftServer server) {
        configManager.loadOrCreate();
        statsStore.load(server);
        auditService.load(server);
        lastStatsCheckpointMillis = System.currentTimeMillis();
        runtimeStats.markServerStarted(lastStatsCheckpointMillis);
        restartScheduler.start(server);
    }

    public void onPlayerLoggedIn(ServerPlayer player) {
        if (stopServerScheduler.rejectMaintenanceJoin(player)) {
            return;
        }
        restartScheduler.playerJoined();
        if (restartScheduler.rejectRestartJoin(player)) return;
        if (maintenanceScheduler.rejectMaintenanceJoin(player)) {
            return;
        }
        if (com.pixelmc.pixelmcmanager.bridge.BridgeAgent.fake(player)) return;

        long nowMillis = System.currentTimeMillis();
        if (!statsStore.ensureLoaded(player.level().getServer())) {
            return;
        }
        PlayerLoginSnapshot snapshot = statsStore.recordLogin(player, nowMillis);
        statsStore.save();
        bridge.login(player, nowMillis);
        scheduler.schedule(player, snapshot.firstJoin(), snapshot.previousLastJoinEpochMillis());
    }

    public void onPlayerLoggedOut(ServerPlayer player) {

        scheduler.cancel(player.getUUID());
        bridge.logout(player.getUUID());
        statsStore.recordLogout(player, System.currentTimeMillis());
        statsStore.save();
    }

    public void onServerTick(MinecraftServer server) {
        stopServerScheduler.tick(server);
        maintenanceScheduler.tick(server);
        restartScheduler.tick(server);
        scheduler.tick(server);
        announcementScheduler.tick(server);
        if (!statsStore.hasOnlineSessions()) return;

        long nowMillis = System.currentTimeMillis();
        long intervalMillis = configManager.getConfig().statsAutoSaveSeconds * 1_000L;
        if (lastStatsCheckpointMillis <= 0L || nowMillis - lastStatsCheckpointMillis >= intervalMillis) {
            statsStore.checkpointOnlinePlayers(server, nowMillis, true);
            lastStatsCheckpointMillis = nowMillis;
        }
    }

    public void onServerStopping(MinecraftServer server) {
        restartScheduler.stop();
        statsStore.settleAll(System.currentTimeMillis());
        statsStore.save();
    }
}
