package com.pixelmc.pixelmcmanager;

import com.mojang.logging.LogUtils;
import com.pixelmc.pixelmcmanager.audit.AuditService;
import com.pixelmc.pixelmcmanager.command.PixelMCManagerCommands;
import com.pixelmc.pixelmcmanager.config.WelcomeConfigManager;
import com.pixelmc.pixelmcmanager.event.AnnouncementScheduler;
import com.pixelmc.pixelmcmanager.event.DelayedMessageScheduler;
import com.pixelmc.pixelmcmanager.event.WelcomeEventHandler;
import com.pixelmc.pixelmcmanager.maintenance.MaintenanceScheduler;
import com.pixelmc.pixelmcmanager.maintenance.StopServerScheduler;
import com.pixelmc.pixelmcmanager.placeholder.PlaceholderResolver;
import com.pixelmc.pixelmcmanager.runtime.ServerRuntimeStats;
import com.pixelmc.pixelmcmanager.restart.IdleRestartScheduler;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import com.pixelmc.pixelmcmanager.text.TextTemplateParser;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(PixelMCManager.MOD_ID)
public final class PixelMCManager {
    public static final String MOD_ID = "pixelmcmanager";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PixelMCManager() {
        WelcomeConfigManager configManager = new WelcomeConfigManager(LOGGER);
        PlayerStatsStore statsStore = new PlayerStatsStore(LOGGER);
        AuditService auditService = new AuditService(LOGGER);
        PlaceholderResolver placeholderResolver = new PlaceholderResolver();
        TextTemplateParser textParser = new TextTemplateParser(LOGGER);
        DelayedMessageScheduler scheduler = new DelayedMessageScheduler(configManager, statsStore, placeholderResolver, textParser);
        AnnouncementScheduler announcementScheduler = new AnnouncementScheduler(configManager, placeholderResolver, textParser);
        StopServerScheduler stopServerScheduler = new StopServerScheduler(configManager, textParser);
        MaintenanceScheduler maintenanceScheduler = new MaintenanceScheduler(configManager, textParser);
        IdleRestartScheduler restartScheduler = new IdleRestartScheduler(configManager, stopServerScheduler, auditService);
        ServerRuntimeStats runtimeStats = new ServerRuntimeStats();
        var bridge = new com.pixelmc.pixelmcmanager.bridge.BridgeAgent(statsStore, configManager, maintenanceScheduler, stopServerScheduler, restartScheduler);

        NeoForge.EVENT_BUS.register(new com.pixelmc.pixelmcmanager.event.NeoForgeEvents(new WelcomeEventHandler(configManager, statsStore, auditService, scheduler, announcementScheduler, stopServerScheduler, maintenanceScheduler, runtimeStats, restartScheduler, bridge), bridge));
        NeoForge.EVENT_BUS.register(new PixelMCManagerCommands(configManager, statsStore, auditService, placeholderResolver, textParser, stopServerScheduler, maintenanceScheduler, runtimeStats, restartScheduler));

        configManager.loadOrCreate();
        LOGGER.info("PixelMC Manager loaded.");
    }
}
