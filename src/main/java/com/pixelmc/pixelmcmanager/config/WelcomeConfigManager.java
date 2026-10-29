package com.pixelmc.pixelmcmanager.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.pixelmc.pixelmcmanager.PixelMCManager;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class WelcomeConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String LEGACY_MOD_ID = "pixelmcwelcome";

    private final Logger logger;
    private final Path configPath;
    private final Path legacyConfigPath;
    private WelcomeConfig config = WelcomeConfig.defaults();
    private String lastError = "";

    public WelcomeConfigManager(Logger logger) {
        this.logger = logger;
        this.configPath = FMLPaths.CONFIGDIR.get().resolve(PixelMCManager.MOD_ID + ".json");
        this.legacyConfigPath = FMLPaths.CONFIGDIR.get().resolve(LEGACY_MOD_ID + ".json");
    }

    public WelcomeConfig getConfig() {
        return config;
    }

    public String getLastError() {
        return lastError;
    }

    public LoadResult loadOrCreate() {
        try {
            Files.createDirectories(configPath.getParent());
            migrateLegacyConfigIfNeeded();
            if (Files.notExists(configPath)) {
                config = WelcomeConfig.defaults();
                writeDefault();
                lastError = "";
                return LoadResult.success("Created default config.");
            }

            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            sanitizeConfigJson(root);
            WelcomeConfig loaded = GSON.fromJson(root, WelcomeConfig.class);
            if (loaded == null) {
                throw new JsonParseException("Config file is empty.");
            }
            loaded.normalize(logger);
            config = loaded;
            lastError = "";
            return LoadResult.success("Config loaded.");
        } catch (Exception exception) {
            lastError = exception.getMessage();
            logger.error("Failed to load PixelMC Manager config from {}. Keeping previous valid config.", configPath, exception);
            return LoadResult.failure(lastError);
        }
    }

    private void writeDefault() throws IOException {
        Files.writeString(configPath, GSON.toJson(config), StandardCharsets.UTF_8);
    }

    public void saveCurrent() throws IOException {
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, GSON.toJson(config), StandardCharsets.UTF_8);
    }

    public String snapshotJson() { return GSON.toJson(config); }

    public void saveSnapshot(String json) throws IOException {
        Files.createDirectories(configPath.getParent());
        Path temp=configPath.resolveSibling(configPath.getFileName()+".bridge-tmp");
        Files.writeString(temp,json,StandardCharsets.UTF_8);
        try { Files.move(temp,configPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp,configPath,StandardCopyOption.REPLACE_EXISTING); }
    }

    private void sanitizeConfigJson(JsonObject root) {
        sanitizeSection(root, "permissions");
        sanitizeInteger(root, "permissions", "reloadLevel", 3);
        sanitizeInteger(root, "permissions", "previewLevel", 2);
        sanitizeInteger(root, "permissions", "statsLevel", 2);
        sanitizeInteger(root, "permissions", "auditLevel", 4);
        sanitizeInteger(root, "permissions", "stopserverLevel", 4);
        sanitizeInteger(root, "permissions", "maintenanceLevel", 4);
        sanitizeInteger(root, "permissions", "saveLevel", 4);
        sanitizeInteger(root, "permissions", "serverStatsLevel", 2);
        sanitizeInteger(root, "permissions", "announcementLevel", 4);
        sanitizeInteger(root, "permissions", "restartLevel", 4);
        sanitizeSection(root, "restart");
        sanitizeInteger(root, "restart", "metadataRecheckSeconds", 60);
        sanitizeSection(root, "announcements");
        sanitizeInteger(root, "announcements", "intervalMinutes", 30);
        sanitizeInteger(root, "announcements", "initialDelayMinutes", 5);
    }

    private void sanitizeSection(JsonObject root, String sectionName) {
        JsonElement section = root.get(sectionName);
        if (section != null && !section.isJsonObject()) {
            logger.warn("Invalid PixelMC Manager config section {}; using defaults.", sectionName);
            root.remove(sectionName);
        }
    }

    private void sanitizeInteger(JsonObject root, String sectionName, String fieldName, int fallback) {
        JsonElement section = root.get(sectionName);
        if (section == null || !section.isJsonObject()) {
            return;
        }
        JsonObject sectionObject = section.getAsJsonObject();
        JsonElement field = sectionObject.get(fieldName);
        if (field == null) {
            return;
        }
        if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber() || !field.getAsString().matches("-?\\d+")) {
            logger.warn("Invalid PixelMC Manager config value {}.{}={}; using default {}.", sectionName, fieldName, field, fallback);
            sectionObject.addProperty(fieldName, fallback);
        }
    }

    private void migrateLegacyConfigIfNeeded() throws IOException {
        if (Files.notExists(configPath) && Files.exists(legacyConfigPath)) {
            Files.copy(legacyConfigPath, configPath, StandardCopyOption.COPY_ATTRIBUTES);
            logger.info("Copied legacy pixelmcwelcome config from {} to {}.", legacyConfigPath, configPath);
        }
    }

    public record LoadResult(boolean success, String message) {
        public static LoadResult success(String message) {
            return new LoadResult(true, message);
        }

        public static LoadResult failure(String message) {
            return new LoadResult(false, message);
        }
    }
}
