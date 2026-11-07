package com.pixelmc.pixelmcmanager.api;

import com.google.gson.*;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Optional, admitted public-summary capability. Player UUID always comes from the connection. */
public final class PlayerHubRankingsApi {
    private record Context(PlayerStatsStore stats, Predicate<UUID> admitted, BooleanSupplier supported,
                           Map<UUID, Long> requests) {}
    private static volatile Context runtime;
    private PlayerHubRankingsApi() {}
    public static int protocolVersion() { return 1; }
    public static String descriptor() {
        return "{\"providerId\":\"pixelmcmanager\",\"modVersion\":\"0.9.0-dev\",\"providerVersion\":1,\"capabilities\":["
                + "{\"id\":\"leaderboards.read\",\"version\":1,\"audience\":\"public-summary\","
                + "\"actions\":[\"snapshot\",\"page\",\"aroundMe\",\"window\"],\"maxPageSize\":20,\"maxWindowPages\":5}]}";
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
        Set<String> fields = switch (action) {
            case "snapshot" -> Set.of();
            case "page" -> Set.of("board", "page", "revision");
            case "window" -> Set.of("board", "page", "count");
            case "aroundMe" -> Set.of("board", "revision");
            default -> throw new IllegalArgumentException();
        };
        JsonObject query = JsonParser.parseString(json).getAsJsonObject();
        if (!fields.containsAll(query.keySet()) || query.entrySet().stream().anyMatch(e -> !e.getValue().isJsonPrimitive()))
            throw new IllegalArgumentException();
        if (!action.equals("snapshot")) {
            if (!query.has("board") || !query.getAsJsonPrimitive("board").isString()
                    || !Set.of("food", "fortunes", "online").contains(query.get("board").getAsString())) throw new IllegalArgumentException();
            if (query.has("revision") && (!query.getAsJsonPrimitive("revision").isString()
                    || query.get("revision").getAsString().length() > 160)) throw new IllegalArgumentException();
        }
        if (action.equals("page") || action.equals("window")) {
            if (!query.has("page") || !query.getAsJsonPrimitive("page").isNumber()) throw new IllegalArgumentException();
            int page = query.get("page").getAsBigDecimal().intValueExact();
            if (page < 0 || page > 100000) throw new IllegalArgumentException();
        }
        if (action.equals("window")) {
            if (!query.has("count") || !query.getAsJsonPrimitive("count").isNumber()) throw new IllegalArgumentException();
            int count=query.get("count").getAsBigDecimal().intValueExact();
            if (count<1 || count>5) throw new IllegalArgumentException();
        }
        return query;
    }
    public static CompletableFuture<String> request(UUID player, String action, String payload) {
        final JsonObject query;
        try { query = validate(action, payload); }
        catch (RuntimeException error) { return failure("排行请求参数无效"); }
        Context context = runtime;
        if (player == null || context == null || !context.admitted.test(player)) return failure("玩家会话已失效，请重新连接");
        if (!context.supported.getAsBoolean()) return failure("排行服务暂不可用，请确认 Manager 服务已更新并连接");
        PlayerStatsStore.HubSnapshot expected = context.stats.hubSnapshot(player, System.currentTimeMillis());
        if (expected == null) return failure("玩家会话已失效，请重新连接");
        synchronized (context.requests) {
            long now = System.nanoTime();
            Long last = context.requests.get(player);
            if (last != null && now - last < 100_000_000L) return failure("排行请求过于频繁，请稍后重试");
            context.requests.put(player, now);
        }
        JsonArray args = new JsonArray(); args.add(player.toString()); args.add(action); args.add(query);
        JsonObject body = new JsonObject(); body.add("args", args);
        return ManagerBridgeApi.call("playerhub", "rankings", body.toString()).thenApply(raw -> {
            PlayerStatsStore.HubSnapshot actual = context.stats.hubSnapshot(player, System.currentTimeMillis());
            if (runtime != context || actual == null || actual.session() != expected.session() || !context.admitted.test(player))
                throw new IllegalStateException("玩家会话已失效，请重新连接");
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 524288) throw new IllegalStateException("排行响应过大");
            JsonObject response = JsonParser.parseString(raw).getAsJsonObject();
            if (response.has("error")) {
                String message = response.get("error").getAsString();
                if (message.length() > 160 || message.contains("\n") || message.contains("jdbc:") || !message.matches(".*[\\p{IsHan}].*"))
                    message = "排行暂不可用，请稍后重试";
                throw new IllegalStateException(message);
            }
            return response.getAsJsonObject("result").toString();
        }).orTimeout(7, TimeUnit.SECONDS);
    }
    private static CompletableFuture<String> failure(String text) {
        return CompletableFuture.failedFuture(new IllegalStateException(text));
    }
}
