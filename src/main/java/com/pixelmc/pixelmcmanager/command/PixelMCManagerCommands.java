package com.pixelmc.pixelmcmanager.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pixelmc.pixelmcmanager.audit.AuditEntry;
import com.pixelmc.pixelmcmanager.audit.AuditService;
import com.pixelmc.pixelmcmanager.PixelMCManager;
import com.pixelmc.pixelmcmanager.config.PermissionConfig;
import com.pixelmc.pixelmcmanager.config.WelcomeConfig;
import com.pixelmc.pixelmcmanager.config.WelcomeConfigManager;
import com.pixelmc.pixelmcmanager.maintenance.MaintenanceScheduler;
import com.pixelmc.pixelmcmanager.maintenance.StopServerScheduler;
import com.pixelmc.pixelmcmanager.maintenance.TimeArgumentParser;
import com.pixelmc.pixelmcmanager.placeholder.PlaceholderContext;
import com.pixelmc.pixelmcmanager.placeholder.PlaceholderResolver;
import com.pixelmc.pixelmcmanager.runtime.ServerRuntimeStats;
import com.pixelmc.pixelmcmanager.restart.IdleRestartScheduler;
import com.pixelmc.pixelmcmanager.stats.PlayerStats;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsEntry;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import com.pixelmc.pixelmcmanager.text.TextTemplateParser;
import com.pixelmc.pixelmcmanager.util.TimeFormatUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PixelMCManagerCommands {
    private static final DateTimeFormatter STATUS_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final List<String> STOPSERVER_SUGGESTIONS = List.of("cancel", "status", "10s", "30s", "1m", "5m", "10m", "1h");
    private static final List<String> MAINTENANCE_SUGGESTIONS = List.of("now", "off", "status", "10s", "30s", "1m", "5m", "10m", "1h");
    private static final List<String> AUDIT_COUNT_SUGGESTIONS = List.of("10", "20", "30", "50");
    private static final int DEFAULT_AUDIT_COUNT = 10;
    private static final int MAX_AUDIT_COUNT = 50;

    private final WelcomeConfigManager configManager;
    private final PlayerStatsStore statsStore;
    private final AuditService auditService;
    private final PlaceholderResolver placeholderResolver;
    private final TextTemplateParser textParser;
    private final StopServerScheduler stopServerScheduler;
    private final MaintenanceScheduler maintenanceScheduler;
    private final ServerRuntimeStats runtimeStats;
    private final IdleRestartScheduler restartScheduler;

    public PixelMCManagerCommands(WelcomeConfigManager configManager, PlayerStatsStore statsStore, AuditService auditService, PlaceholderResolver placeholderResolver, TextTemplateParser textParser, StopServerScheduler stopServerScheduler, MaintenanceScheduler maintenanceScheduler, ServerRuntimeStats runtimeStats, IdleRestartScheduler restartScheduler) {
        this.configManager = configManager;
        this.statsStore = statsStore;
        this.auditService = auditService;
        this.placeholderResolver = placeholderResolver;
        this.textParser = textParser;
        this.stopServerScheduler = stopServerScheduler;
        this.maintenanceScheduler = maintenanceScheduler;
        this.runtimeStats = runtimeStats;
        this.restartScheduler = restartScheduler;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(managerRoot());
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> managerRoot() {
        return Commands.literal("pixelmcmanager")
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("reload")
                        .requires(source -> hasPermission(source, permissions().reloadLevel))
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("preview")
                        .requires(source -> hasPermission(source, permissions().previewLevel))
                        .executes(context -> preview(context.getSource(), false))
                        .then(Commands.literal("first")
                                .executes(context -> preview(context.getSource(), true)))
                        .then(Commands.literal("returning")
                                .executes(context -> preview(context.getSource(), false))))
                .then(Commands.literal("logincount")
                        .requires(source -> hasPermission(source, permissions().statsLevel))
                        .executes(context -> listLoginCounts(context.getSource()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    if (!statsStore.ensureLoaded(context.getSource().getServer())) {
                                        return builder.buildFuture();
                                    }
                                    return SharedSuggestionProvider.suggest(statsStore.listKnownPlayerNames(), builder);
                                })
                                .executes(context -> showLoginCount(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("logintime")
                        .requires(source -> hasPermission(source, permissions().statsLevel))
                        .executes(context -> listLoginTimes(context.getSource()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    if (!statsStore.ensureLoaded(context.getSource().getServer())) {
                                        return builder.buildFuture();
                                    }
                                    return SharedSuggestionProvider.suggest(statsStore.listKnownPlayerNames(), builder);
                                })
                                .executes(context -> showLoginTime(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("save")
                        .requires(source -> hasPermission(source, permissions().saveLevel))
                        .executes(context -> save(context.getSource())))
                .then(Commands.literal("stats")
                        .requires(source -> hasPermission(source, permissions().serverStatsLevel))
                        .executes(context -> serverStats(context.getSource())))
                .then(Commands.literal("stopserver")
                        .requires(source -> hasPermission(source, permissions().stopserverLevel))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancelStopServer(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> stopServerStatus(context.getSource())))
                        .then(Commands.argument("time", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(STOPSERVER_SUGGESTIONS, builder))
                                .executes(context -> stopServer(context.getSource(), StringArgumentType.getString(context, "time")))))
                .then(Commands.literal("restart")
                        .requires(source -> hasPermission(source, permissions().restartLevel))
                        .then(Commands.literal("whenempty").executes(context -> restartAction(context.getSource(), "whenempty")))
                        .then(Commands.literal("cancel").executes(context -> restartAction(context.getSource(), "cancel")))
                        .then(Commands.literal("status").executes(context -> restartAction(context.getSource(), "status")))
                        .then(Commands.literal("onmodchange")
                                .then(Commands.literal("on").executes(context -> restartAction(context.getSource(), "on")))
                                .then(Commands.literal("off").executes(context -> restartAction(context.getSource(), "off")))
                                .then(Commands.literal("status").executes(context -> restartAction(context.getSource(), "status")))))
                .then(Commands.literal("maintenance")
                        .requires(source -> hasPermission(source, permissions().maintenanceLevel))
                        .then(Commands.literal("now")
                                .executes(context -> startMaintenanceNow(context.getSource())))
                        .then(Commands.literal("off")
                                .executes(context -> turnOffMaintenance(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> maintenanceStatus(context.getSource())))
                        .then(Commands.argument("time", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MAINTENANCE_SUGGESTIONS, builder))
                                .executes(context -> scheduleMaintenance(context.getSource(), StringArgumentType.getString(context, "time")))))
                .then(Commands.literal("audit")
                        .requires(source -> hasPermission(source, permissions().auditLevel))
                        .executes(context -> showAudit(context.getSource(), DEFAULT_AUDIT_COUNT))
                        .then(Commands.literal("last")
                                .executes(context -> showAudit(context.getSource(), DEFAULT_AUDIT_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(AUDIT_COUNT_SUGGESTIONS, builder))
                                        .executes(context -> showAudit(context.getSource(), IntegerArgumentType.getInteger(context, "count"))))));
    }

    private int help(CommandSourceStack source) {
        send(source, "&7❰ &b&l✦ &f&lPixelMC Manager &e&l✦ &7❱");
        send(source, "&b/pixelmcmanager reload");
        send(source, "&b/pixelmcmanager restart <whenempty|cancel|status>");
        send(source, "&b/pixelmcmanager restart onmodchange <on|off|status>");
        send(source, "&b/pixelmcmanager preview [first|returning]");
        send(source, "&b/pixelmcmanager logincount [player]");
        send(source, "&d/pixelmcmanager logintime [player]");
        send(source, "&a/pixelmcmanager save");
        send(source, "&e/pixelmcmanager stats");
        send(source, "&c/pixelmcmanager stopserver <time|cancel|status>");
        send(source, "&6/pixelmcmanager maintenance <time|now|off|status>");
        send(source, "&9/pixelmcmanager audit [last] [count]");
        return Command.SINGLE_SUCCESS;
    }

    private int restartAction(CommandSourceStack source, String action) {
        try {
            if (action.equals("status")) { send(source, "&e" + restartScheduler.status()); return Command.SINGLE_SUCCESS; }
            String message;
            if (action.equals("whenempty")) message = restartScheduler.schedule();
            else if (action.equals("cancel")) message = restartScheduler.cancel();
            else {
                boolean previous = configManager.getConfig().restart.restartOnModChange;
                configManager.getConfig().restart.restartOnModChange = action.equals("on");
                try { configManager.saveCurrent(); }
                catch (Exception e) {
                    configManager.getConfig().restart.restartOnModChange = previous;
                    throw new IllegalStateException("配置保存失败，自动重启开关保持原值。");
                }
                restartScheduler.optionChanged();
                message = "模组变更空服自动重启已" + (action.equals("on") ? "开启" : "关闭") + "，配置已保存。";
            }
            auditService.record(source, "idle_restart_" + action, "", message);
            send(source, "&a" + message);
            return Command.SINGLE_SUCCESS;
        } catch (IllegalStateException e) {
            source.sendFailure(textParser.parse("&c" + e.getMessage(), configManager.getConfig()));
            return 0;
        }
    }

    private int save(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        try {
            if (!statsStore.ensureLoaded(server)) {
                source.sendFailure(Component.literal("PixelMC Manager 玩家统计加载失败，请检查服务器日志。"));
                return 0;
            }
            statsStore.checkpointOnlinePlayers(server, System.currentTimeMillis(), true);
            auditService.flush();
            configManager.saveCurrent();
            server.saveEverything(false, true, true);
            auditService.record(source, "save", "", "data saved");
            source.sendSuccess(() -> textParser.parse("&a已保存 PixelMC Manager 数据并请求服务器保存世界。", configManager.getConfig()), false);
            return Command.SINGLE_SUCCESS;
        } catch (Exception exception) {
            PixelMCManager.LOGGER.error("Failed to save PixelMC Manager data or world.", exception);
            source.sendFailure(textParser.parse("&c保存过程中出现错误，请检查服务器日志。", configManager.getConfig()));
            return 0;
        }
    }

    private int serverStats(CommandSourceStack source) {
        if (!checkpointForQuery(source)) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        List<PlayerStatsEntry> entries = statsStore.listAll();
        ZoneId zoneId = ZoneId.systemDefault();
        long todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli();
        long tomorrowStart = LocalDate.now(zoneId).plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli();
        long todayActive = entries.stream()
                .filter(entry -> entry.stats().lastJoinEpochMillis >= todayStart && entry.stats().lastJoinEpochMillis < tomorrowStart)
                .count();
        long totalPlaytime = entries.stream()
                .mapToLong(entry -> Math.max(0L, entry.stats().totalOnlineMillis))
                .sum();

        send(source, "&7❰ &b&l✦ &f&lPixelMC 服务器统计 &e&l✦ &7❱");
        send(source, "&f记录玩家数：&a" + entries.size());
        send(source, "&f当前在线：&a" + server.getPlayerList().getPlayerCount() + "&7/&e" + server.getMaxPlayers());
        send(source, "&f今日活跃玩家：&a" + todayActive);
        send(source, "&f总累计游玩时长：&d" + TimeFormatUtil.formatFriendly(totalPlaytime));
        send(source, "&f本次服务器运行时间：&b" + TimeFormatUtil.formatFriendly(runtimeStats.uptimeMillis(System.currentTimeMillis())));
        send(source, "&f维护状态：&6" + maintenanceState());
        return Command.SINGLE_SUCCESS;
    }

    private int showAudit(CommandSourceStack source, int count) {
        if (count < 1 || count > MAX_AUDIT_COUNT) {
            source.sendFailure(textParser.parse("&c审计查询数量必须在 1 到 50 之间。", configManager.getConfig()));
            return 0;
        }

        List<AuditEntry> entries = auditService.getRecent(count);
        if (entries.isEmpty()) {
            send(source, "&a当前没有管理操作记录。");
            return Command.SINGLE_SUCCESS;
        }

        send(source, "&9PixelMC 管理操作记录 最近 " + count + " 条");
        for (AuditEntry entry : entries) {
            send(source, "&7[" + formatSystemTime(entry.time) + "] &e" + safeAuditText(entry.sourceName) + " &f执行 &b" + formatAuditAction(entry) + " &a成功");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int scheduleMaintenance(CommandSourceStack source, String input) {
        TimeArgumentParser.Result parsed = TimeArgumentParser.parse(input);
        if (!parsed.success()) {
            source.sendFailure(textParser.parse("&c" + parsed.errorMessage(), configManager.getConfig()));
            return 0;
        }

        boolean replaced = maintenanceScheduler.getStatusSnapshot().state() == MaintenanceScheduler.State.SCHEDULED;
        MaintenanceScheduler.Result result = maintenanceScheduler.schedule(source.getServer(), parsed.duration());
        if (!result.success()) {
            source.sendFailure(textParser.parse("&c" + result.message(), configManager.getConfig()));
            return 0;
        }
        MaintenanceScheduler.StatusSnapshot snapshot = maintenanceScheduler.getStatusSnapshot();
        auditService.record(source, replaced ? "maintenance_override" : "maintenance_schedule", input, "maintenanceStartTime=" + formatSystemTime(snapshot.maintenanceStartTimeMillis()));
        source.sendSuccess(() -> textParser.parse("&a" + result.message(), configManager.getConfig()), false);
        return Command.SINGLE_SUCCESS;
    }

    private int startMaintenanceNow(CommandSourceStack source) {
        boolean replaced = maintenanceScheduler.getStatusSnapshot().state() == MaintenanceScheduler.State.SCHEDULED;
        MaintenanceScheduler.Result result = maintenanceScheduler.startNow(source.getServer());
        if (!result.success()) {
            source.sendFailure(textParser.parse("&c" + result.message(), configManager.getConfig()));
            return 0;
        }
        MaintenanceScheduler.StatusSnapshot snapshot = maintenanceScheduler.getStatusSnapshot();
        auditService.record(source, replaced ? "maintenance_now_override" : "maintenance_now", "", "maintenanceStartedAt=" + formatSystemTime(snapshot.maintenanceStartedAtMillis()));
        source.sendSuccess(() -> textParser.parse("&a" + result.message(), configManager.getConfig()), false);
        return Command.SINGLE_SUCCESS;
    }

    private int turnOffMaintenance(CommandSourceStack source) {
        MaintenanceScheduler.Result result = maintenanceScheduler.turnOff();
        if (!result.success()) {
            source.sendFailure(textParser.parse("&c" + result.message(), configManager.getConfig()));
            return 0;
        }
        auditService.record(source, "maintenance_off", "", result.message());
        source.sendSuccess(() -> textParser.parse("&a" + result.message(), configManager.getConfig()), false);
        return Command.SINGLE_SUCCESS;
    }

    private int maintenanceStatus(CommandSourceStack source) {
        MaintenanceScheduler.StatusSnapshot snapshot = maintenanceScheduler.getStatusSnapshot();
        switch (snapshot.state()) {
            case NONE -> send(source, "&a当前没有正在进行的服务器维护计划。");
            case SCHEDULED -> {
                long nowMillis = System.currentTimeMillis();
                send(source, "&e当前存在服务器维护计划。");
                send(source, "&f距离进入维护：&b" + formatRemaining(snapshot.maintenanceStartTimeMillis() - nowMillis));
                send(source, "&f维护开始时间：&a" + formatSystemTime(snapshot.maintenanceStartTimeMillis()));
                send(source, "&f当前状态：&6等待维护开始");
            }
            case ACTIVE -> {
                send(source, "&e服务器当前处于维护状态。");
                send(source, "&f维护开始时间：&a" + formatSystemTime(snapshot.maintenanceStartedAtMillis()));
                send(source, "&f当前状态：&6拒绝玩家加入");
            }
        }

        Optional<StopServerScheduler.PlanSnapshot> stopServerPlan = stopServerScheduler.getCurrentPlanSnapshot();
        if (stopServerPlan.isPresent()) {
            if (stopServerPlan.get().maintenanceActive()) {
                send(source, "&c注意：计划停服流程已进入停机维护阶段，将优先拒绝玩家加入。请使用 /pixelmcmanager stopserver status 查看。");
            } else {
                send(source, "&e注意：当前还存在计划停服流程，请使用 /pixelmcmanager stopserver status 查看。");
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int stopServerStatus(CommandSourceStack source) {
        Optional<StopServerScheduler.PlanSnapshot> snapshot = stopServerScheduler.getCurrentPlanSnapshot();
        if (snapshot.isEmpty()) {
            send(source, "&a当前没有正在进行的服务器停机计划。");
            return Command.SINGLE_SUCCESS;
        }

        StopServerScheduler.PlanSnapshot plan = snapshot.get();
        long nowMillis = System.currentTimeMillis();
        if (nowMillis >= plan.stopTimeMillis()) {
            send(source, "&e服务器停机计划已到达最终停服时间，正在等待停止流程执行。");
            sendStopServerTimes(source, plan);
            send(source, "&f当前状态：&6等待停止流程执行");
            return Command.SINGLE_SUCCESS;
        }

        if (plan.maintenanceActive()) {
            send(source, "&e服务器已经进入停机维护流程。");
            send(source, "&f距离真正停服：&c" + formatRemaining(plan.stopTimeMillis() - nowMillis));
            sendStopServerTimes(source, plan);
            send(source, "&f当前状态：&6维护中，正在等待服务器停止");
            return Command.SINGLE_SUCCESS;
        }

        send(source, "&e当前存在服务器停机计划。");
        send(source, "&f距离停机维护：&b" + formatRemaining(plan.kickTimeMillis() - nowMillis));
        sendStopServerTimes(source, plan);
        send(source, "&f当前状态：&6等待维护开始");
        return Command.SINGLE_SUCCESS;
    }

    private void sendStopServerTimes(CommandSourceStack source, StopServerScheduler.PlanSnapshot plan) {
        send(source, "&f维护开始时间：&a" + formatSystemTime(plan.kickTimeMillis()));
        send(source, "&f真正停服时间：&c" + formatSystemTime(plan.stopTimeMillis()));
    }

    private int cancelStopServer(CommandSourceStack source) {
        StopServerScheduler.ScheduleResult result = stopServerScheduler.cancel();
        if (!result.success()) {
            source.sendFailure(textParser.parse("&c" + result.message(), configManager.getConfig()));
            return 0;
        }

        auditService.record(source, "stopserver_cancel", "", result.message());
        source.sendSuccess(() -> textParser.parse("&a" + result.message(), configManager.getConfig()), false);
        return Command.SINGLE_SUCCESS;
    }

    private int stopServer(CommandSourceStack source, String input) {
        TimeArgumentParser.Result parsed = TimeArgumentParser.parse(input);
        if (!parsed.success()) {
            source.sendFailure(textParser.parse("&c" + parsed.errorMessage(), configManager.getConfig()));
            return 0;
        }

        boolean replaced = stopServerScheduler.getCurrentPlanSnapshot().isPresent();
        StopServerScheduler.ScheduleResult result = stopServerScheduler.schedule(source.getServer(), parsed.duration());
        if (!result.success()) {
            source.sendFailure(textParser.parse("&c" + result.message(), configManager.getConfig()));
            return 0;
        }

        Optional<StopServerScheduler.PlanSnapshot> snapshot = stopServerScheduler.getCurrentPlanSnapshot();
        String detail = snapshot.map(plan -> "kickTime=" + formatSystemTime(plan.kickTimeMillis())).orElse(result.message());
        auditService.record(source, replaced ? "stopserver_override" : "stopserver", input, detail);
        source.sendSuccess(() -> textParser.parse("&a" + result.message(), configManager.getConfig()), false);
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSourceStack source) {
        WelcomeConfigManager.LoadResult result = configManager.loadOrCreate();
        if (result.success()) {
            auditService.record(source, "reload", "", "configuration reloaded");
            source.sendSuccess(() -> Component.literal("PixelMC Manager 配置已重新加载。"), false);
            return Command.SINGLE_SUCCESS;
        }

        source.sendFailure(Component.literal("PixelMC Manager 配置加载失败，已保留旧配置：" + result.message()));
        return 0;
    }

    private int preview(CommandSourceStack source, boolean first) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("该命令需要玩家执行。"));
            return 0;
        }

        WelcomeConfig config = configManager.getConfig();
        List<String> templates = first ? config.firstJoinMessages : config.returningMessages;
        if (templates.isEmpty()) {
            source.sendFailure(Component.literal("PixelMC Manager 当前预览消息为空。"));
            return 0;
        }

        PlayerStats stats = statsStore.getStats(player.getUUID());
        if (stats == null) {
            stats = new PlayerStats();
            stats.name = player.getGameProfile().getName();
            stats.joinCount = first ? 1 : 0;
            long now = System.currentTimeMillis();
            stats.firstJoinEpochMillis = now;
            stats.lastJoinEpochMillis = now;
        }

        PlaceholderContext placeholderContext = new PlaceholderContext(player, config, stats, stats.lastJoinEpochMillis);
        for (String template : templates) {
            String resolved = placeholderResolver.resolve(template, placeholderContext);
            player.sendSystemMessage(textParser.parse(resolved, config));
        }
        source.sendSuccess(() -> Component.literal("PixelMC Manager 预览已发送。"), false);
        return Command.SINGLE_SUCCESS;
    }

    private int listLoginCounts(CommandSourceStack source) {
        if (!checkpointForQuery(source)) {
            return 0;
        }
        List<PlayerStatsEntry> entries = statsStore.listAll().stream()
                .sorted(Comparator
                        .comparingInt((PlayerStatsEntry entry) -> entry.stats().joinCount).reversed()
                        .thenComparing((PlayerStatsEntry entry) -> entry.stats().lastJoinEpochMillis, Comparator.reverseOrder())
                        .thenComparing(entry -> safeName(entry.stats()), String.CASE_INSENSITIVE_ORDER))
                .toList();

        send(source, "&7❰ &b&l✦ &f&lPixelMC 登录次数 &e&l✦ &7❱");
        if (entries.isEmpty()) {
            send(source, "&7暂无玩家记录。");
            return Command.SINGLE_SUCCESS;
        }

        for (int index = 0; index < entries.size(); index++) {
            PlayerStats stats = entries.get(index).stats();
            send(source, "&8#" + (index + 1) + " &e" + safeName(stats) + " &7| &f登录 &a" + stats.joinCount + " &7次 | &f上次 &b" + formatDateTime(stats.lastJoinEpochMillis));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int showLoginCount(CommandSourceStack source, String playerName) {
        if (!checkpointForQuery(source)) {
            return 0;
        }
        Optional<PlayerStatsEntry> entry = statsStore.findByName(playerName);
        if (entry.isEmpty()) {
            send(source, "&c没有找到玩家 " + playerName + " 的记录。");
            return 0;
        }

        PlayerStats stats = entry.get().stats();
        send(source, "&7❰ &b&l✦ &f&lPixelMC 登录次数 &e&l✦ &7❱");
        send(source, "&f玩家：&e" + safeName(stats));
        send(source, "&fUUID：&7" + entry.get().uuid());
        send(source, "&f登录次数：&a" + stats.joinCount);
        send(source, "&f首次登录：&b" + formatDateTime(stats.firstJoinEpochMillis));
        send(source, "&f上次登录：&b" + formatDateTime(stats.lastJoinEpochMillis));
        return Command.SINGLE_SUCCESS;
    }

    private int listLoginTimes(CommandSourceStack source) {
        if (!checkpointForQuery(source)) {
            return 0;
        }
        List<PlayerStatsEntry> entries = statsStore.listAll().stream()
                .sorted(Comparator
                        .comparingLong((PlayerStatsEntry entry) -> entry.stats().totalOnlineMillis).reversed()
                        .thenComparing((PlayerStatsEntry entry) -> entry.stats().joinCount, Comparator.reverseOrder())
                        .thenComparing((PlayerStatsEntry entry) -> entry.stats().lastJoinEpochMillis, Comparator.reverseOrder())
                        .thenComparing(entry -> safeName(entry.stats()), String.CASE_INSENSITIVE_ORDER))
                .toList();

        send(source, "&7❰ &b&l✦ &f&lPixelMC 登录总时间 &e&l✦ &7❱");
        if (entries.isEmpty()) {
            send(source, "&7暂无玩家记录。");
            return Command.SINGLE_SUCCESS;
        }

        for (int index = 0; index < entries.size(); index++) {
            PlayerStats stats = entries.get(index).stats();
            send(source, "&8#" + (index + 1) + " &e" + safeName(stats) + " &7| &f总时长 &d" + TimeFormatUtil.formatFriendly(stats.totalOnlineMillis) + " &7| &f登录 &a" + stats.joinCount + " &7次 | &f上次 &b" + formatDateTime(stats.lastJoinEpochMillis));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int showLoginTime(CommandSourceStack source, String playerName) {
        if (!checkpointForQuery(source)) {
            return 0;
        }
        Optional<PlayerStatsEntry> entry = statsStore.findByName(playerName);
        if (entry.isEmpty()) {
            send(source, "&c没有找到玩家 " + playerName + " 的记录。");
            return 0;
        }

        PlayerStats stats = entry.get().stats();
        send(source, "&7❰ &b&l✦ &f&lPixelMC 登录总时间 &e&l✦ &7❱");
        send(source, "&f玩家：&e" + safeName(stats));
        send(source, "&fUUID：&7" + entry.get().uuid());
        send(source, "&f总游玩时长：&d" + TimeFormatUtil.formatFriendly(stats.totalOnlineMillis));
        send(source, "&f登录次数：&a" + stats.joinCount);
        send(source, "&f首次登录：&b" + formatDateTime(stats.firstJoinEpochMillis));
        send(source, "&f上次登录：&b" + formatDateTime(stats.lastJoinEpochMillis));
        return Command.SINGLE_SUCCESS;
    }

    private boolean checkpointForQuery(CommandSourceStack source) {
        if (!statsStore.ensureLoaded(source.getServer())) {
            source.sendFailure(Component.literal("PixelMC Manager 玩家统计加载失败，请检查服务器日志。"));
            return false;
        }
        statsStore.checkpointOnlinePlayers(source.getServer(), System.currentTimeMillis(), false);
        return true;
    }

    private String maintenanceState() {
        Optional<StopServerScheduler.PlanSnapshot> stopPlan = stopServerScheduler.getCurrentPlanSnapshot();
        if (stopPlan.isPresent()) {
            return stopPlan.get().maintenanceActive() ? "停机维护中" : "计划停服中";
        }

        MaintenanceScheduler.StatusSnapshot maintenance = maintenanceScheduler.getStatusSnapshot();
        return switch (maintenance.state()) {
            case ACTIVE -> "维护中";
            case SCHEDULED -> "已预定维护";
            case NONE -> "无";
        };
    }

    private PermissionConfig permissions() {
        return configManager.getConfig().permissions;
    }

    private static boolean hasPermission(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }

    private void send(CommandSourceStack source, String message) {
        WelcomeConfig config = configManager.getConfig();
        Component component = textParser.parse(message, config);
        source.sendSuccess(() -> component, false);
    }

    private String formatDateTime(long epochMillis) {
        WelcomeConfig config = configManager.getConfig();
        return TimeFormatUtil.formatDateTime(epochMillis, config.timezone, config.dateFormat, config.timeFormat);
    }

    private static String formatSystemTime(long epochMillis) {
        return STATUS_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String formatRemaining(long remainingMillis) {
        long seconds = Math.max(0L, (remainingMillis + 999L) / 1_000L);
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0L) {
            return hours + "小时" + String.format("%02d", minutes) + "分钟";
        }
        if (minutes > 0L) {
            return minutes + "分钟" + String.format("%02d", remainingSeconds) + "秒";
        }
        return seconds + "秒";
    }

    private static String formatAuditAction(AuditEntry entry) {
        String action = switch (entry.action) {
            case "stopserver" -> "stopserver " + entry.args;
            case "stopserver_cancel" -> "stopserver cancel";
            case "stopserver_override" -> "stopserver " + entry.args + " 覆盖旧计划";
            case "maintenance_schedule" -> "maintenance " + entry.args;
            case "maintenance_now" -> "maintenance now";
            case "maintenance_off" -> "maintenance off";
            case "maintenance_override" -> "maintenance " + entry.args + " 覆盖旧计划";
            case "maintenance_now_override" -> "maintenance now 覆盖旧计划";
            case "save" -> "save";
            default -> entry.action;
        };
        return safeAuditText(action);
    }

    private static String safeAuditText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("&", "＆").replace("\n", " ");
    }

    private static String safeName(PlayerStats stats) {
        return stats.name == null || stats.name.isBlank() ? "未知玩家" : stats.name;
    }
}
