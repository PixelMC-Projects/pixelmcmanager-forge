package com.pixelmc.pixelmcmanager.config;

import java.util.ArrayList;
import java.util.List;

public final class WelcomeConfig {
    public boolean enabled = true;
    public int delayTicks = 40;
    public String timezone = "Asia/Shanghai";
    public String dateFormat = "yyyy-MM-dd";
    public String timeFormat = "HH:mm:ss";
    public List<String> firstJoinMessages = new ArrayList<>();
    public List<String> returningMessages = new ArrayList<>();
    public int statsAutoSaveSeconds = 30;
    public boolean debugLogging = false;
    public PermissionConfig permissions = new PermissionConfig();
    public AnnouncementConfig announcements = new AnnouncementConfig();
    public RestartConfig restart = new RestartConfig();

    public static WelcomeConfig defaults() {
        WelcomeConfig config = new WelcomeConfig();
        config.firstJoinMessages.add("&7❰ &b&l✦ <gradient:#64D8FF:#F7D774>欢迎来到香草纪元 2.7</gradient> &e&l✦ &7❱");
        config.firstJoinMessages.add("&7你好，&e{player}&7。这里是 PixelMC 的长期世界。");
        config.firstJoinMessages.add("&7当前在线：&a{online}&7/&e{max}&7。请文明游玩，祝你旅途愉快。");
        config.returningMessages.add("&7❰ &b&l✦ <gradient:#64D8FF:#F7D774>香草纪元 2.7</gradient> &e&l✦ &7❱");
        config.returningMessages.add("&7欢迎回来，&e{player}&7。");
        config.returningMessages.add("&7这是你第 &a{join_count} &7次登录，累计游玩 &b{playtime}&7。");
        config.returningMessages.add("&7当前在线：&a{online}&7/&e{max} &8┃ &7延迟：&a{ping}ms");
        return config;
    }

    public void normalize(org.slf4j.Logger logger) {
        if (delayTicks < 0) {
            delayTicks = 0;
        }
        if (timezone == null || timezone.isBlank()) {
            timezone = "Asia/Shanghai";
        }
        if (dateFormat == null || dateFormat.isBlank()) {
            dateFormat = "yyyy-MM-dd";
        }
        if (timeFormat == null || timeFormat.isBlank()) {
            timeFormat = "HH:mm:ss";
        }
        if (firstJoinMessages == null) {
            firstJoinMessages = new ArrayList<>();
        }
        if (returningMessages == null) {
            returningMessages = new ArrayList<>();
        }
        if (statsAutoSaveSeconds < 5 || statsAutoSaveSeconds > 600) {
            statsAutoSaveSeconds = 30;
        }
        if (permissions == null) {
            permissions = new PermissionConfig();
        }
        permissions.normalize(logger);
        if (announcements == null) {
            announcements = new AnnouncementConfig();
        }
        announcements.normalize(logger);
        if (restart == null) restart = new RestartConfig();
        restart.normalize();
    }
}
