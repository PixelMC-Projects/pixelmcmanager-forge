package com.pixelmc.manager.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.pixelmc.wenzhouai.manager.WzAdminBackend;
import com.pixelmc.wenzhouai.manager.WzDirectory;
import com.pixelmc.wenzhouai.service.WzStoredAdmin;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
    private final WzOpenAiCredential credential;
    private final WenzhouOpenAi openAi;
    private final WzAdminBackend backend;
    private final WzTerraLedger terraLedger;
    private final WzTerraGateway terraGateway;
    private final WzProjectBilling billing;
    private final Consumer<UUID> terraGrantChanged;
    private final Runnable terraConfigChanged;
    private final Consumer<UUID> terraQuotaChanged;

    @FunctionalInterface
    interface Platform {
        JsonObject request(JsonObject input) throws Exception;
    }

    static WenzhouAdmin open(
            Path data,
            ManagerStore store,
            Platform platform,
            Consumer<UUID> notify,
            Consumer<UUID> quotaNotify,
            Consumer<UUID> terraGrantChanged,
            Runnable terraConfigChanged,
            Consumer<UUID> terraQuotaChanged) {
        Path file = data.resolve("wenzhou-admin.properties");
        if (!Files.isRegularFile(file)) return null;
        try {
            Properties configuration = new Properties();
            try (var reader = Files.newBufferedReader(file)) {
                configuration.load(reader);
            }
            return new WenzhouAdmin(
                    Path.of(configuration.getProperty("sourceDirectory")),
                    UUID.fromString(configuration.getProperty("ownerPlayerUuid")),
                    data,
                    store,
                    platform,
                    notify,
                    quotaNotify,
                    terraGrantChanged,
                    terraConfigChanged,
                    terraQuotaChanged);
        } catch (IOException | RuntimeException failure) {
            System.err.println("Invalid wenzhou-admin.properties; WenZhou saved data is unavailable.");
            return null;
        }
    }

    private WenzhouAdmin(
            Path source,
            UUID owner,
            Path data,
            ManagerStore store,
            Platform platform,
            Consumer<UUID> notify,
            Consumer<UUID> quotaNotify,
            Consumer<UUID> terraGrantChanged,
            Runnable terraConfigChanged,
            Consumer<UUID> terraQuotaChanged) {
        if (!source.isAbsolute()) throw new IllegalArgumentException("Expected absolute source directory");
        this.source = source;
        this.knowledge = new WenzhouKnowledge(source.resolve("memory"));
        this.owner = owner;
        this.terraGrantChanged = terraGrantChanged;
        this.terraConfigChanged = terraConfigChanged;
        terraLedger = new WzTerraLedger(store);
        this.terraQuotaChanged = terraQuotaChanged;
        credential = new WzOpenAiCredential(data);
        billing = new WzProjectBilling(data, credential::revision, terraConfigChanged);
        openAi = new WenzhouOpenAi(credential, billing);
        terraGateway = new WzTerraGateway(
                terraLedger, data, openAi::available, openAi::projectConnection, billing);
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
        if (version >= 2 && version < expected.get("version").getAsInt()) {
            expected.addProperty("version", version);
            if (version < 6) {
                var tables = expected.getAsJsonArray("tables").iterator();
                while (tables.hasNext()) {
                    if (WzTerraLedger.TABLES.contains(tables.next().getAsString())) tables.remove();
                }
                var playerTables = expected.getAsJsonArray("playerTables").iterator();
                while (playerTables.hasNext()) {
                    String table = playerTables.next().getAsJsonObject().get("table").getAsString();
                    if (WzTerraLedger.TABLES.contains(table)) playerTables.remove();
                }
            }
            var fields = expected.getAsJsonArray("fields").iterator();
            while (fields.hasNext()) {
                String id = fields.next().getAsJsonObject().get("id").getAsString();
                if ((version < 5 && (id.equals("reminders") || id.equals("received_shares")))
                        || (version < 4 && WzDirectory.MODEL_PREFERENCES.equals(id))
                        || (version < 3 && WzDirectory.INTRO_SEEN.equals(id)))
                    fields.remove();
            }
        }
        return expected.equals(supplied);
    }

    // History uses atomic file replacement. Its parent directory changes on save and deletion.
    // Read only metadata here: the platform streams snapshots even when another page is visible.
    void addRevisions(JsonObject snapshot, UUID viewer, String boot, long players, long config) {
        if (!owner.equals(viewer)) return;
        String service = modified("service.json");
        String history = modified("conversations");
        snapshot.addProperty("wenzhouHistoryRevision", boot + ":" + history + ":" + service);
        snapshot.addProperty("wenzhouKnowledgeRevision", boot + ":" + modified("memory"));
        snapshot.addProperty("wenzhouPlayersRevision", players);
        snapshot.addProperty("wenzhouServiceRevision", boot + ":" + config + ":" + service + ":" + modified("credential.json") + ":" + modified("chatgpt-revision"));
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

    private JsonObject terraAdmin(String action, JsonObject input, UUID actor) throws Exception {
        return switch (action) {
            case "terra.account" -> {
                requireFields(input, "playerId");
                yield terraLedger.account(UUID.fromString(Wire.text(input, "playerId", 36)));
            }
            case "terra.grant" -> {
                requireFields(input, "playerId", "allowed", "revision");
                UUID player = UUID.fromString(Wire.text(input, "playerId", 36));
                JsonObject response =
                        terraLedger.grant(
                                player, requiredFlag(input, "allowed"), revision(input), actor);
                terraGrantChanged.accept(player);
                yield response;
            }
            case "terra.settings" -> {
                requireFields(input);
                yield withCredential(terraLedger.settings());
            }
            case "terra.configure" -> {
                boolean limits = input.size() != 3;
                if (limits) requireFields(input, "effort", "fast", "revision", "weeklyLimitNanoUsd", "monthlyLimitNanoUsd",
                        "serverMonthlyLimitNanoUsd", "quotaEnabled", "serverQuotaEnabled");
                else requireFields(input, "effort", "fast", "revision");
                String effort = requiredString(input, "effort", 16);
                boolean fast = requiredFlag(input, "fast");
                long revision = revision(input);
                JsonObject response = limits
                        ? terraLedger.configure(effort, fast, revision, amount(input, "weeklyLimitNanoUsd"),
                                amount(input, "monthlyLimitNanoUsd"), amount(input, "serverMonthlyLimitNanoUsd"),
                                requiredFlag(input, "quotaEnabled"), requiredFlag(input, "serverQuotaEnabled"), actor)
                        : terraLedger.configure(effort, fast, revision, actor);
                terraConfigChanged.run();
                yield withCredential(response);
            }
            case "terra.resume" -> {
                requireFields(input, "revision", "holdId");
                billing.resume(requiredString(input, "revision", 100), requiredString(input, "holdId", 36));
                yield withCredential(terraLedger.settings());
            }
            case "terra.credential" -> {
                requireFields(input, "key", "revision");
                String key = input.get("key").isJsonNull() ? null : requiredString(input, "key", 4096);
                JsonObject response = credential.change(key, requiredString(input, "revision", 100));
                terraConfigChanged.run();
                yield response;
            }
            case "terra.quota" -> {
                String mode = requiredString(input, "mode", 16);
                boolean custom = mode.equals("custom");
                if (custom) requireFields(input, "playerId", "mode", "revision", "customWeeklyLimitNanoUsd", "customMonthlyLimitNanoUsd");
                else requireFields(input, "playerId", "mode", "revision");
                UUID player = UUID.fromString(requiredString(input, "playerId", 36));
                JsonObject result = terraLedger.editQuota(player, mode, custom ? amount(input, "customWeeklyLimitNanoUsd") : null,
                        custom ? amount(input, "customMonthlyLimitNanoUsd") : null, revision(input), actor);
                terraQuotaChanged.accept(player);
                yield result;
            }
            default -> throw new IllegalArgumentException("Terra 管理接口无效");
        };
    }

    private JsonObject withCredential(JsonObject settings) {
        settings.add("credential", credential.status());
        billing.status().entrySet().forEach(field -> settings.add(field.getKey(), field.getValue()));
        return settings;
    }

    private static String requiredString(JsonObject input, String field, int max) {
        if (!(input.get(field) instanceof JsonPrimitive value) || !value.isString())
            throw new IllegalArgumentException(field + " 须为文本");
        return Wire.text(input, field, max);
    }

    private static long amount(JsonObject input, String field) {
        if (!(input.get(field) instanceof JsonPrimitive number) || !number.isNumber())
            throw new IllegalArgumentException(field + " 须为整数");
        return Wire.number(input, field, -1, 0, WzTerraLedger.MAX_LIMIT_NANO_USD);
    }

    private static void requireFields(JsonObject input, String... fields) {
        if (!input.keySet().equals(Set.of(fields)))
            throw new IllegalArgumentException("Terra 管理请求字段无效");
    }

    private static boolean requiredFlag(JsonObject input, String field) {
        if (!(input.get(field) instanceof JsonPrimitive flag) || !flag.isBoolean())
            throw new IllegalArgumentException(field + " 须为布尔值");
        return flag.getAsBoolean();
    }

    private static long revision(JsonObject input) {
        if (!(input.get("revision") instanceof JsonPrimitive revision) || !revision.isNumber())
            throw new IllegalArgumentException("revision 须为整数");
        return Wire.number(input, "revision", -1, 0, Long.MAX_VALUE - 1);
    }

    JsonObject terraBridge(String instance, UUID player, String op, JsonObject input)
            throws Exception {
        if (!terraLedger.hasBridgeAccess(player)) {
            if (!op.equals("terra.view")) throw new SecurityException("Terra 使用权限未开通");
            JsonObject disabled = new JsonObject();
            disabled.addProperty("granted", false);
            return disabled;
        }
        return terraGateway.bridge(instance, player, op, input);
    }

    void close() {
        terraGateway.close();
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
        if (action.equals("openai.status") || action.equals("openai.respond")) {
            JsonObject input = payload.getAsJsonObject();
            if (action.equals("openai.status") && input.size() != 0)
                throw new IllegalArgumentException("OpenAI 状态查询无需参数");
            try {
                JsonObject result =
                        action.equals("openai.status") ? openAi.status() : openAi.respond(input);
                Wire.playerJson(exchange, 200, result);
            } catch (WenzhouOpenAi.Failure failure) {
                Wire.json(exchange, failure.status, Map.of("error", failure.getMessage()));
            }
            return;
        }
        if (action.startsWith("terra.")) {
            Wire.playerJson(exchange, 200, terraAdmin(action, payload.getAsJsonObject(), actor));
            return;
        }
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
