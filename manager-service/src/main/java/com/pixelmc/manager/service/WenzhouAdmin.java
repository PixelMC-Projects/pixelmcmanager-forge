package com.pixelmc.manager.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelmc.wenzhouai.manager.WzAdminBackend;
import com.pixelmc.wenzhouai.manager.WzDirectory;
import com.pixelmc.wenzhouai.service.WzStoredAdmin;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class WenzhouAdmin {
    static final String ID = WzDirectory.ID;
    static final String PREFIX = "/api/wenzhouai/";
    private static final int MAX_BODY = 262144;
    private final Path source;
    private final UUID owner;
    private final WenzhouKnowledge knowledge;
    private final WzAdminBackend backend;

    @FunctionalInterface
    interface Platform {
        JsonObject request(JsonObject input) throws Exception;
    }

    static WenzhouAdmin open(Path data, Platform platform, Consumer<UUID> notify, Consumer<UUID> quotaNotify) {
        Path file = data.resolve("wenzhou-admin.properties");
        if (!Files.isRegularFile(file)) return null;
        try {
            Properties configuration = new Properties();
            try (var reader = Files.newBufferedReader(file)) {
                configuration.load(reader);
            }
            return new WenzhouAdmin(Path.of(configuration.getProperty("sourceDirectory")),
                    UUID.fromString(configuration.getProperty("ownerPlayerUuid")), data, platform, notify, quotaNotify);
        } catch (IOException | RuntimeException failure) {
            System.err.println("Invalid wenzhou-admin.properties; WenZhou saved data is unavailable.");
            return null;
        }
    }

    private WenzhouAdmin(Path source, UUID owner, Path data, Platform platform, Consumer<UUID> notify, Consumer<UUID> quotaNotify) {
        if (!source.isAbsolute()) throw new IllegalArgumentException("Expected absolute source directory");
        this.source = source;
        this.knowledge = new WenzhouKnowledge(source.resolve("memory"));
        this.owner = owner;
        WzDirectory directory = new WzDirectory(input -> {
            try {
                return CompletableFuture.completedFuture(platform.request(input));
            } catch (Exception unavailable) {
                return CompletableFuture.failedFuture(unavailable);
            }
        });
        backend = new WzAdminBackend(data.resolve("wenzhou/admin-audit.jsonl"), directory,
                new WzStoredAdmin(source, directory, notify, quotaNotify));
    }

    static JsonObject storageManifest() {
        return JsonParser.parseString(WzDirectory.STORAGE_MANIFEST).getAsJsonObject();
    }

    static boolean matchesStorageManifest(JsonObject supplied, int version) {
        JsonObject expected = storageManifest();
        if (version == 2) {
            expected.addProperty("version", 2);
            var fields = expected.getAsJsonArray("fields").iterator();
            while (fields.hasNext()) {
                if (WzDirectory.INTRO_SEEN.equals(fields.next().getAsJsonObject().get("id").getAsString()))
                    fields.remove();
            }
        }
        return expected.equals(supplied);
    }

    // History uses atomic file replacement. Its parent directory changes on save and deletion.
    // Read only metadata here: the platform streams snapshots even when another page is visible.
    void addRevisions(JsonObject snapshot, UUID viewer, String boot, long players, long config) {
        if (!owner.equals(viewer)) return;
        String policy = modified("service.json");
        String history = modified("conversations");
        snapshot.addProperty("wenzhouHistoryRevision", boot + ":" + history + ":" + policy);
        snapshot.addProperty("wenzhouKnowledgeRevision", boot + ":" + modified("memory"));
        snapshot.addProperty("wenzhouPlayersRevision", players);
        snapshot.addProperty("wenzhouServiceRevision", boot + ":" + config + ":" + policy + ":" + modified("credential.json"));
    }

    private String modified(String name) {
        try {
            return Files.getLastModifiedTime(source.resolve(name)).toString();
        } catch (IOException unavailable) {
            return "-";
        }
    }

    boolean ready() {
        return Files.isDirectory(source) && Files.isReadable(source);
    }

    void handle(HttpExchange exchange, String action, UUID actor) throws Exception {
        if (!owner.equals(actor))
            throw new SecurityException("请使用已配置的服主管理账号");
        if (!"POST".equals(exchange.getRequestMethod()) || !action.matches("[a-z][a-z.]{0,31}"))
            throw new IllegalArgumentException("问舟管理接口无效");
        byte[] content = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (content.length > MAX_BODY) throw new IllegalArgumentException("问舟请求超出大小限制");
        var payload = JsonParser.parseString(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        if (!payload.isJsonObject()) throw new IllegalArgumentException("问舟请求数据无效");
        if (!ready() && !action.equals("grant") && !action.startsWith("quota.")) {
            Wire.json(exchange, 503, Map.of("error", "问舟已保存的数据暂不可用"));
            return;
        }
        JsonObject input = payload.getAsJsonObject();
        JsonObject response = switch (action) {
            case "knowledge.list" -> knowledge.list(input);
            case "knowledge.detail" -> knowledge.detail(input);
            default -> backend.request(action, input, actor);
        };
        Wire.playerJson(exchange, 200, response);
    }
}
