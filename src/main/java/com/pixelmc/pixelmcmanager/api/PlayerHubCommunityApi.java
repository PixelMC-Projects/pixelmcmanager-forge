package com.pixelmc.pixelmcmanager.api;

import com.google.gson.*;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Optional community capability. Reads expose approved public fields and writes derive identity. */
public final class PlayerHubCommunityApi {
    private record Context(PlayerStatsStore stats, Predicate<UUID> admitted, BooleanSupplier supported, Map<UUID,Long> requests) {}
    private static volatile Context runtime;
    private PlayerHubCommunityApi() {}
    public static int protocolVersion() { return 1; }
    public static String descriptor() {
        return "{\"providerId\":\"pixelmcmanager\",\"modVersion\":\"0.9.0-dev\",\"providerVersion\":1,\"capabilities\":[{\"id\":\"community.read\",\"version\":1,\"audience\":\"public-summary\",\"actions\":[\"profile\",\"memories\",\"memory\"]},{\"id\":\"community.self\",\"version\":1,\"audience\":\"self\",\"actions\":[\"snapshot\",\"profileOptions\",\"memoryOptions\",\"saveProfile\",\"saveNote\",\"appendSupplement\",\"deleteSupplement\",\"saveCollections\"]}]}";
    }
    public static void attach(PlayerStatsStore stats, Predicate<UUID> admitted, BooleanSupplier supported) {
        runtime = stats == null ? null : new Context(stats, admitted, supported, new HashMap<>());
    }
    public static void logout(UUID player) {
        Context context = runtime;
        if (context != null) synchronized (context.requests) { context.requests.remove(player); }
    }
    static JsonObject validate(String action, String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 8192) throw new IllegalArgumentException();
        int depth = 0; boolean quoted = false, escaped = false;
        for (char ch : json.toCharArray()) {
            if (quoted) { if (escaped) escaped = false; else if (ch == '\\') escaped = true; else if (ch == '"') quoted = false; }
            else if (ch == '"') quoted = true;
            else if (ch == '{' || ch == '[') { if (++depth > 12) throw new IllegalArgumentException(); }
            else if (ch == '}' || ch == ']') depth--;
        }
        JsonObject p = JsonParser.parseString(json).getAsJsonObject();
        Set<String> fields = switch (action) {
            case "snapshot" -> Set.of(); case "profile" -> Set.of("playerId");
            case "profileOptions" -> Set.of("kind", "page"); case "memories" -> Set.of("playerId", "page");
            case "memory" -> Set.of("playerId", "archiveId", "page"); case "memoryOptions" -> Set.of("archiveId", "page");
            case "saveProfile" -> Set.of("patch", "expectedRevision"); case "saveNote" -> Set.of("archiveId", "text", "expectedRevision");
            case "appendSupplement" -> Set.of("archiveId", "text", "operationId");
            case "deleteSupplement" -> Set.of("archiveId", "supplementId", "expectedRevision");
            case "saveCollections" -> Set.of("archiveId", "selection", "expectedRevision");
            default -> throw new IllegalArgumentException();
        };
        if (!fields.containsAll(p.keySet())) throw new IllegalArgumentException();
        for (var entry : p.entrySet()) {
            if (Set.of("patch", "selection").contains(entry.getKey())) { if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException(); }
            else if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException();
        }
        for (String key : List.of("playerId", "archiveId", "supplementId", "operationId")) if (p.has(key)) { String id = string(p, key, 36); if (id.length() != 36 || !UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException(); }
        for (String required : List.of("archiveId", "supplementId", "operationId")) if (fields.contains(required) && !p.has(required)) throw new IllegalArgumentException();
        if (fields.contains("expectedRevision")) string(p, "expectedRevision", 160);
        if (p.has("page")) { if (!p.getAsJsonPrimitive("page").isNumber()) throw new IllegalArgumentException(); int page = p.get("page").getAsBigDecimal().intValueExact(); if (page < 0 || page > 100000) throw new IllegalArgumentException(); }
        if (fields.contains("text")) string(p, "text", action.equals("saveNote") ? 1200 : 600);
        if (action.equals("profileOptions") && !Set.of("titles", "fortunes", "memories").contains(string(p, "kind", 16))) throw new IllegalArgumentException();
        if (action.equals("saveProfile") && (!p.has("patch") || !Set.of("bio","background","profilePattern","featuredTitleId","featuredFortuneDate","featuredMemoryId").containsAll(p.getAsJsonObject("patch").keySet()))) throw new IllegalArgumentException();
        if (action.equals("saveCollections") && (!p.has("selection") || !p.getAsJsonObject("selection").keySet().equals(Set.of("titleEventId","fortuneEventId")))) throw new IllegalArgumentException();
        return p;
    }
    private static String string(JsonObject value, String key, int limit) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException();
        String text = value.get(key).getAsString();
        if (text.isBlank() || text.length() > limit || text.indexOf(0) >= 0) throw new IllegalArgumentException();
        return text;
    }
    public static CompletableFuture<String> request(UUID player, String action, String payload) {
        final JsonObject query;
        try { query = validate(action, payload); } catch (RuntimeException invalid) { return failure("社区请求参数无效"); }
        Context context = runtime;
        if (player == null || context == null || !context.admitted.test(player)) return failure("玩家会话已失效，请重新连接");
        if (!context.supported.getAsBoolean()) return failure("社区服务暂不可用，请确认Manager已更新并连接");
        var snapshot = context.stats.hubSnapshot(player, System.currentTimeMillis());
        if (snapshot == null) return failure("玩家会话已失效，请重新连接");
        synchronized (context.requests) {
            long now = System.nanoTime(); Long last = context.requests.put(player, now);
            if (last != null && now - last < 100_000_000L) return failure("操作过于频繁，请稍后重试");
        }
        JsonArray args = new JsonArray(); args.add(player.toString()); args.add(action); args.add(query);
        JsonObject data = new JsonObject(); data.add("args", args);
        return ManagerBridgeApi.call("playerhub", "community", data.toString()).thenApply(raw -> {
            var current = context.stats.hubSnapshot(player, System.currentTimeMillis());
            if (runtime != context || !context.admitted.test(player) || current == null || current.session() != snapshot.session()) throw new IllegalStateException("玩家会话已失效，请重新连接");
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 524288) throw new IllegalStateException("社区资料超出范围，请缩小查询");
            JsonObject response = JsonParser.parseString(raw).getAsJsonObject();
            if (response.has("error")) throw new IllegalStateException(safe(response.get("error")));
            if (!response.has("result") || !response.get("result").isJsonObject()) throw new IllegalStateException("社区服务响应无效");
            return response.getAsJsonObject("result").toString();
        }).orTimeout(7, TimeUnit.SECONDS).exceptionallyCompose(error -> {
            Throwable cause = error; while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            return failure(cause instanceof IllegalStateException ? safe(new JsonPrimitive(Objects.toString(cause.getMessage(), ""))) : "社区服务暂不可用，请稍后重试");
        });
    }
    private static String safe(JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            if (text.length() <= 160 && !text.contains("\n") && !text.contains("jdbc:") && !text.contains("Exception") && text.matches(".*[\\p{IsHan}].*")) return text;
        }
        return "社区操作未完成，请稍后重试";
    }
    private static CompletableFuture<String> failure(String message) { return CompletableFuture.failedFuture(new IllegalStateException(message)); }
}
