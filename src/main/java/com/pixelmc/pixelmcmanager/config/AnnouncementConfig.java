package com.pixelmc.pixelmcmanager.config;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public final class AnnouncementConfig {
    public boolean enabled = false;
    public int intervalMinutes = 30;
    public int initialDelayMinutes = 5;
    public List<String> messages =
            new ArrayList<>(List.of("&bQQ群：&e768322731", "&7文明游玩，禁止恶意破坏。", "&7遇到问题请联系管理员。"));

    public void normalize(Logger logger) {
        if (intervalMinutes <= 0) {
            logger.warn(
                    "Invalid PixelMC Manager announcements.intervalMinutes={}; using default 30.",
                    intervalMinutes);
            intervalMinutes = 30;
        }
        if (initialDelayMinutes < 0) {
            logger.warn(
                    "Invalid PixelMC Manager announcements.initialDelayMinutes={}; using default 5.",
                    initialDelayMinutes);
            initialDelayMinutes = 5;
        }
        if (messages == null) {
            logger.warn(
                    "Invalid PixelMC Manager announcements.messages=null; using an empty list.");
            messages = new ArrayList<>();
        }
    }
}
