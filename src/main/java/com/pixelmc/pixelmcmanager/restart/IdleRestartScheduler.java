package com.pixelmc.pixelmcmanager.restart;

import com.pixelmc.pixelmcmanager.PixelMCManager;
import com.pixelmc.pixelmcmanager.audit.AuditService;
import com.pixelmc.pixelmcmanager.config.WelcomeConfigManager;
import com.pixelmc.pixelmcmanager.maintenance.StopServerScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

public final class IdleRestartScheduler {
    private final WelcomeConfigManager config;
    private final StopServerScheduler stopScheduler;
    private final AuditService audit;
    private IdleRestartState state = new IdleRestartState();
    private RestartBridgeClient bridge;
    private ExecutorService controlIo;
    private ModDirectoryMonitor monitor;
    private Future<RestartBridgeClient.View> polling;
    private Future<?> submitting;
    private RestartBridgeClient.View view = new RestartBridgeClient.View(false, "", "", "", "", "重启服务尚未初始化。");
    private long lastRevision, lastPoll, submittedAt;
    private boolean stopped = true, lastAuto;
    private String requestId = "";

    public IdleRestartScheduler(WelcomeConfigManager config, StopServerScheduler stopScheduler, AuditService audit) {
        this.config = config;
        this.stopScheduler = stopScheduler;
        this.audit = audit;
    }

    private static ThreadFactory worker(String name) {
        return action -> {
            Thread thread = new Thread(action, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    public void start(MinecraftServer server) {
        if (!server.isDedicatedServer()) return;
        state = new IdleRestartState();
        stopped = false;
        lastAuto = config.getConfig().restart.restartOnModChange;
        lastRevision = -1;
        lastPoll = 0;
        requestId = "";
        bridge = new RestartBridgeClient(FMLPaths.GAMEDIR.get().resolve("pixelmcmanager/restart"));
        controlIo = Executors.newSingleThreadExecutor(worker("PixelMCManager-RestartIPC"));
        monitor = new ModDirectoryMonitor(FMLPaths.MODSDIR.get(), config.getConfig().restart.metadataRecheckSeconds);
        monitor.start();
    }

    public String schedule() {
        if (!view.ready()) throw new IllegalStateException(view.message());
        state.arm();
        return "已预约：所有玩家下线并持续空服15秒后重启；由" + backendName() + "执行。";
    }

    public String cancel() {
        return state.cancel() ? "已取消本次空服重启预约。模组变更自动重启开关不受影响。" : "当前没有手动空服重启预约。";
    }

    public void optionChanged() { state.resetFailure(); }

    public void playerJoined() { state.playerJoined(); }

    public boolean rejectRestartJoin(ServerPlayer player) {
        if (!state.dispatched()) return false;
        player.connection.disconnect(Component.literal("服务器正在重启，请稍后重连。"));
        return true;
    }

    public String status() {
        ModDirectoryMonitor.View files = monitor == null ? null : monitor.view();
        String mods = files == null || !files.ready() ? "初始化中" : !files.error().isEmpty() ? files.error()
                : files.changed() ? "与开服时不同或被重写" : "与开服时一致";
        return "空服重启：" + (state.dispatched() ? "请求已提交" : state.manual() ? "已预约" : "未预约")
                + "；空服计时 " + state.emptyMillis(System.nanoTime()) / 1000 + "/15秒"
                + "；模组变更自动重启=" + config.getConfig().restart.restartOnModChange
                + "；模组列表=" + mods + "；执行方式=" + backendName()
                + (state.failure().isEmpty() ? "" : "；已暂停：" + state.failure());
    }

    private String backendName() {
        if (!view.ready()) return "不可用（" + view.message() + "）";
        return view.backend().equals("mcsm") ? "MCSM" : "普通启动托管脚本";
    }

    public void tick(MinecraftServer server) {
        if (stopped) return;
        long nano = System.nanoTime();
        boolean auto = config.getConfig().restart.restartOnModChange;
        if (auto != lastAuto) { lastAuto = auto; state.resetFailure(); }
        monitor.setRecheckSeconds(config.getConfig().restart.metadataRecheckSeconds);
        ModDirectoryMonitor.View files = monitor.view();
        if (files.revision() != lastRevision) { state.resetTimer(); lastRevision = files.revision(); }
        finishControl(server);
        if (state.dispatched() && nano - submittedAt > 65_000_000_000L) {
            fail(server, "重启请求65秒未完成；不自动重复提交，请检查托管程序或MCSM。 ");
        }
        boolean pending = auto && files.ready() && files.changed() && files.stable();
        if (state.ready(nano, server.getPlayerCount(), pending, stopScheduler.getCurrentPlanSnapshot().isPresent(), view.ready())) {
            state.markDispatched();
            submittedAt = nano;
            requestId = UUID.randomUUID().toString();
            String id = requestId;
            String reason = state.manual() ? "manual-empty-15s" : "mods-changed-empty-15s";
            long empty = state.emptyMillis(nano);
            audit.record(server.createCommandSourceStack(), "restart_submit", reason, "backend=" + view.backend() + ", request=" + id);
            PixelMCManager.LOGGER.info("Empty server restart requested through {} ({}).", view.backend(), reason);
            submitting = controlIo.submit(() -> { bridge.request(id, reason, empty, System.currentTimeMillis()); return null; });
        }
        if (polling == null && nano - lastPoll >= 1_000_000_000L) {
            int players = server.getPlayerCount();
            boolean locked = state.dispatched();
            long empty = state.emptyMillis(nano);
            polling = controlIo.submit(() -> {
                long now = System.currentTimeMillis();
                bridge.heartbeat(players, locked, empty, now);
                return bridge.read(now);
            });
            lastPoll = nano;
        }
    }

    private void finishControl(MinecraftServer server) {
        if (submitting != null && submitting.isDone()) {
            try { submitting.get(); }
            catch (Exception e) { fail(server, "无法提交本地重启请求，请检查托管程序；未执行关服。"); }
            submitting = null;
        }
        if (polling == null || !polling.isDone()) return;
        try {
            view = polling.get();
            if (state.dispatched() && requestId.equals(view.responseId()) && view.responseStatus().equals("error"))
                fail(server, view.message());
        } catch (Exception e) {
            view = new RestartBridgeClient.View(false, "", "", "", "", "无法读取本地重启托管状态。");
        } finally { polling = null; }
    }

    private void fail(MinecraftServer server, String message) {
        state.fail(message);
        audit.record(server.createCommandSourceStack(), "restart_failed", "", message);
        PixelMCManager.LOGGER.error("Idle restart paused: {}", message);
    }

    public void stop() {
        stopped = true;
        if (monitor != null) monitor.close();
        if (controlIo != null) controlIo.shutdownNow();
        polling = null;
        submitting = null;
    }
}
