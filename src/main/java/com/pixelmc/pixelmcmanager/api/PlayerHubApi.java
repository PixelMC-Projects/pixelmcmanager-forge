package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Optional player-only Java boundary. The caller supplies the authenticated connection UUID. */
public final class PlayerHubApi {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "Manager-PlayerHub");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static volatile RuntimeContext runtime;

    private record RuntimeContext(PlayerStatsStore stats, Predicate<UUID> admitted,
                                  Supplier<JsonObject> scope, Map<UUID, Long> requests) { }

    public static int protocolVersion() { return 1; }

    /** Internal lifecycle hook; no HTTP credentials or administrative transport enter this API. */
    public static void attach(PlayerStatsStore stats, Predicate<UUID> admitted, Supplier<JsonObject> scope) {
        runtime = stats == null ? null : new RuntimeContext(stats, admitted, scope, new HashMap<>());
    }

    public static void logout(UUID player) {
        RuntimeContext current = runtime;
        if (current != null) synchronized (current.requests) { current.requests.remove(player); }
    }

    public static CompletableFuture<String> request(UUID player, String action, String payload) {
        if (player == null || !"snapshot".equals(action)) return failure("未注册的玩家资料操作");
        try {
            if (payload == null || payload.length() > 1024
                    || !JsonParser.parseString(payload).getAsJsonObject().entrySet().isEmpty())
                return failure("玩家资料请求参数无效");
        } catch (RuntimeException invalid) { return failure("玩家资料请求参数无效"); }
        RuntimeContext current = runtime;
        if (current == null || !current.admitted.test(player)) return failure("玩家会话已失效，请重新打开");
        long now = System.nanoTime();
        synchronized (current.requests) {
            Long last = current.requests.get(player);
            if (last != null && now - last < 500_000_000L) return failure("资料刷新过于频繁，请稍后重试");
            current.requests.put(player, now);
        }
        PlayerStatsStore.HubSnapshot session = current.stats.hubSnapshot(player, System.currentTimeMillis());
        if (session == null) return failure("玩家会话已失效，请重新打开");
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            WORKER.execute(() -> {
                try {
                    long at = System.currentTimeMillis();
                    PlayerStatsStore.HubSnapshot value = current.stats.hubSnapshot(player, at);
                    requireSession(current, player, session, value);
                    JsonObject scope = current.scope.get();
                    if (scope == null) throw new IllegalStateException("玩家资料正在准备，请稍后重试");
                    JsonObject data = new JsonObject(), own = new JsonObject();
                    own.addProperty("uuid", player.toString());
                    own.addProperty("name", value.stats().name);
                    own.addProperty("firstJoinedAt", Math.max(0, value.stats().firstJoinEpochMillis));
                    own.addProperty("previousLoginAt", value.previousLoginAt());
                    own.addProperty("joinCount", Math.max(0, value.stats().joinCount));
                    own.addProperty("totalOnlineMillis", Math.max(0, value.stats().totalOnlineMillis));
                    data.add("player", own);
                    data.add("scope", scope);
                    data.addProperty("revision", UUID.nameUUIDFromBytes(data.toString().getBytes(StandardCharsets.UTF_8)).toString());
                    data.addProperty("serverTime", at);
                    requireSession(current, player, session, current.stats.hubSnapshot(player, at));
                    result.complete(ManagerBridgeApi.JSON.toJson(data));
                } catch (IllegalStateException invalid) { result.completeExceptionally(invalid); }
                catch (RuntimeException unavailable) { result.completeExceptionally(new IllegalStateException("玩家资料暂不可用，请稍后重试")); }
            });
        } catch (RejectedExecutionException full) { result.completeExceptionally(new IllegalStateException("玩家资料服务繁忙，请稍后重试")); }
        return result.orTimeout(5, TimeUnit.SECONDS).exceptionallyCompose(error -> error instanceof TimeoutException
                ? failure("玩家资料服务繁忙，请稍后重试") : CompletableFuture.failedFuture(error));
    }

    private static void requireSession(RuntimeContext current, UUID player, PlayerStatsStore.HubSnapshot expected,
                                       PlayerStatsStore.HubSnapshot actual) {
        if (runtime != current || !current.admitted.test(player) || actual == null || expected.session() != actual.session())
            throw new IllegalStateException("玩家会话已失效，请重新打开");
    }

    private static CompletableFuture<String> failure(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    private PlayerHubApi() { }
}
