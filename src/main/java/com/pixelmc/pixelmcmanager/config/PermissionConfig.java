package com.pixelmc.pixelmcmanager.config;

import org.slf4j.Logger;

public final class PermissionConfig {
    public int reloadLevel = 3;
    public int previewLevel = 2;
    public int statsLevel = 2;
    public int auditLevel = 4;
    public int stopserverLevel = 4;
    public int maintenanceLevel = 4;
    public int saveLevel = 4;
    public int serverStatsLevel = 2;
    public int announcementLevel = 4;
    public int restartLevel = 4;

    public void normalize(Logger logger) {
        reloadLevel = normalizeLevel("reloadLevel", reloadLevel, 3, logger);
        previewLevel = normalizeLevel("previewLevel", previewLevel, 2, logger);
        statsLevel = normalizeLevel("statsLevel", statsLevel, 2, logger);
        auditLevel = normalizeLevel("auditLevel", auditLevel, 4, logger);
        stopserverLevel = normalizeLevel("stopserverLevel", stopserverLevel, 4, logger);
        maintenanceLevel = normalizeLevel("maintenanceLevel", maintenanceLevel, 4, logger);
        saveLevel = normalizeLevel("saveLevel", saveLevel, 4, logger);
        serverStatsLevel = normalizeLevel("serverStatsLevel", serverStatsLevel, 2, logger);
        announcementLevel = normalizeLevel("announcementLevel", announcementLevel, 4, logger);
        restartLevel = normalizeLevel("restartLevel", restartLevel, 4, logger);
    }

    private static int normalizeLevel(String field, int value, int fallback, Logger logger) {
        if (value >= 0 && value <= 4) {
            return value;
        }
        logger.warn("Invalid PixelMC Manager permission level permissions.{}={}; using default {}.", field, value, fallback);
        return fallback;
    }
}
