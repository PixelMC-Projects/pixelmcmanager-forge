package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelmc.pixelmcmanager.bridge.PlayerHubScope;
import com.pixelmc.pixelmcmanager.stats.PlayerStatsStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Isolated own-player/session and safe-scope checks; never starts Minecraft or a database. */
public final class PlayerHubLogicTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejected(CompletableFuture<String> future, String message) throws Exception {
        try { future.get(3, TimeUnit.SECONDS); throw new AssertionError(message); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException, message); }
    }
    private static JsonObject response(CompletableFuture<String> future) throws Exception {
        return JsonParser.parseString(future.get(3, TimeUnit.SECONDS)).getAsJsonObject();
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("manager-hub-checks");
        PlayerHubScope scope = PlayerHubScope.open(directory.resolve("config"), directory.resolve("save/bridge"));
        String server = scope.snapshot().get("serverId").getAsString(), archive = scope.snapshot().get("archiveId").getAsString();
        check(!server.equals(archive), "Server and save identities are independently generated");
        check(scope.snapshot().get("metadataStatus").getAsString().equals("unavailable"), "Unknown metadata remains unavailable");
        JsonObject hello = JsonParser.parseString("{\"archive\":{\"name\":\"测试整合包\",\"version\":\"v1\",\"instance_id\":\"private-admin\",\"token\":\"never-expose\"}}").getAsJsonObject();
        scope.hello(hello);
        check(scope.snapshot().get("metadataStatus").getAsString().equals("current"), "Authenticated hello metadata is current");
        check(!scope.snapshot().toString().contains("never-expose") && !Files.readString(directory.resolve("save/bridge/player-hub-scope.json")).contains("private-admin"), "Only safe pack fields are copied/cached");
        scope.disconnected();
        check(scope.snapshot().get("metadataStatus").getAsString().equals("cached"), "Disconnection is explicit and retains known metadata");
        PlayerHubScope reopened = PlayerHubScope.open(directory.resolve("config"), directory.resolve("save/bridge"));
        check(reopened.snapshot().get("serverId").getAsString().equals(server) && reopened.worldId().equals(archive), "Identities persist across restart");
        check(reopened.snapshot().get("packName").getAsString().equals("测试整合包"), "Safe pack metadata survives restart");
        PlayerHubScope otherSave = PlayerHubScope.open(directory.resolve("config"), directory.resolve("other-save/bridge"));
        check(otherSave.snapshot().get("serverId").getAsString().equals(server) && !otherSave.worldId().equals(archive), "New archive is isolated on the same server");
        scope.managed(false);
        check(scope.snapshot().get("metadataStatus").getAsString().equals("standalone") && scope.snapshot().get("packName").getAsString().isEmpty(), "Standalone mode never presents cached managed pack as current");

        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        PlayerStatsStore stats = new PlayerStatsStore(null);
        long now = System.currentTimeMillis();
        check(stats.recordLogin(player, "OwnPlayer", now - 5000).previousLastJoinEpochMillis() == 0, "New player has unknown previous login");
        stats.settleAll(now - 4000);
        stats.recordLogin(player, "OwnPlayer", now - 2000);
        PlayerStatsStore.HubSnapshot first = stats.hubSnapshot(player, now);
        check(first.previousLoginAt() == now - 5000 && first.stats().joinCount == 2, "Previous login excludes current session");
        check(first.stats().totalOnlineMillis == 3000, "Current online duration includes unsettled real wall time");
        check(stats.hubSnapshot(player, now + 1000).previousLoginAt() == first.previousLoginAt(), "Repeated opens retain exact previous login");
        check(stats.getStats(player).totalOnlineMillis == 1000, "Hub read never mutates or saves counters");
        check(stats.hubSnapshot(other, now) == null, "Other/unknown player has no authenticated session");
        Set<UUID> admitted = ConcurrentHashMap.newKeySet();
        admitted.add(player);
        PlayerHubApi.attach(stats, admitted::contains, scope::snapshot);
        check(PlayerHubApi.protocolVersion() == 1, "Public protocol version is fixed");
        rejected(PlayerHubApi.request(player, "rpc", "{}"), "Admin/general RPC is rejected");
        rejected(PlayerHubApi.request(player, "snapshot", "{\"uuid\":\"" + other + "\"}"), "Payload cannot choose another player");
        rejected(PlayerHubApi.request(player, "snapshot", "[]"), "Only JSON object accepted");
        rejected(PlayerHubApi.request(player, "snapshot", "{"), "Malformed JSON rejected");
        rejected(PlayerHubApi.request(other, "snapshot", "{}"), "Online admission required");
        JsonObject value = response(PlayerHubApi.request(player, "snapshot", "{}"));
        check(value.getAsJsonObject("player").get("uuid").getAsString().equals(player.toString()), "Only authenticated own identity returned");
        check(value.getAsJsonObject("player").get("previousLoginAt").getAsLong() == now - 5000, "API preserves previous login timestamp");
        check(value.entrySet().size() == 4 && value.getAsJsonObject("player").entrySet().size() == 6, "Response uses explicit field allowlist");
        rejected(PlayerHubApi.request(player, "snapshot", "{}"), "Refresh throttle is explicit");
        admitted.remove(player);
        PlayerHubApi.logout(player);
        rejected(PlayerHubApi.request(player, "snapshot", "{}"), "Logout immediately revokes access");

        admitted.add(player);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PlayerHubApi.attach(stats, admitted::contains, () -> {
            entered.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Check timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Check interrupted"); }
            return scope.snapshot();
        });
        CompletableFuture<String> stale = PlayerHubApi.request(player, "snapshot", "{}");
        check(entered.await(2, TimeUnit.SECONDS), "Worker reached delayed response");
        long oldSession = stats.hubSnapshot(player, now).session();
        stats.settleAll(now);
        stats.recordLogin(player, "OwnPlayer", now - 2000); // Deliberately same login millisecond.
        check(stats.hubSnapshot(player, now).session() != oldSession, "Session token changes even with equal timestamps");
        release.countDown();
        rejected(stale, "Old-session completion is rejected after reconnect");
        PlayerHubApi.attach(null, null, null);
        rejected(PlayerHubApi.request(player, "snapshot", "{}"), "Shutdown detaches provider runtime");
        System.out.println("Player Hub checks passed: " + checks);
    }
}
