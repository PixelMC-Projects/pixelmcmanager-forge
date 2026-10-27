package com.pixelmc.pixelmcmanager.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pixelmc.pixelmcmanager.api.ManagerBridgeApi;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise the real RPC admission gate against a loopback-only HTTP endpoint. */
public final class BridgeAdmissionTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Field field(String name) throws ReflectiveOperationException {
        Field result = BridgeAgent.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static void rejected(CompletableFuture<String> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("A non-admitted player must not send an RPC");
        } catch (ExecutionException failure) {
            check(failure.getCause() instanceof SecurityException, "Admission failure remains explicit");
        }
    }
    public static void main(String[] args) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();
        AtomicInteger sent = new AtomicInteger();
        http.createContext("/bridge/rpc", exchange -> {
            requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            sent.incrementAndGet();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        http.start();
        BridgeAgent agent = new BridgeAgent(null, null, null, null, null);
        try {
            BridgeConfig bridge = new BridgeConfig();
            bridge.enabled = true;
            bridge.baseUrl = "http://127.0.0.1:" + http.getAddress().getPort();
            bridge.token = "local-test-token";
            field("bridge").set(agent, bridge);
            UUID player = UUID.randomUUID();
            String data = "{\"args\":[\"" + player + "\"]}";
            rejected(agent.rpc("dailyfortunes", "draw", data));
            rejected(agent.rpc("memorialtitles", "login", data));
            check(sent.get() == 0, "Denied player calls never reach HTTP");

            @SuppressWarnings("unchecked") Set<UUID> admitted = (Set<UUID>) field("admitted").get(agent);
            admitted.add(player);
            for (String method : new String[]{"draw", "history", "claimSlip"}) {
                agent.rpc("dailyfortunes", method, data).get(5, TimeUnit.SECONDS);
                JsonObject request = requests.poll(1, TimeUnit.SECONDS);
                check(request != null && request.get("playerProof").getAsString().equals(player.toString()), "DailyFortunes proof is attached for " + method);
                check(request.get("extension").getAsString().equals("dailyfortunes") && request.get("op").getAsString().equals(method), "RPC routing stays intact for " + method);
            }
            agent.rpc("memorialtitles", "login", data).get(5, TimeUnit.SECONDS);
            check(requests.take().get("playerProof").getAsString().equals(player.toString()), "Existing title login proof is retained");
            agent.logout(player);
            int before = sent.get();
            rejected(agent.rpc("dailyfortunes", "history", data));
            check(sent.get() == before, "Logout revokes the proof before the next RPC");
            ManagerBridgeApi.register("storage-compat","Compatibility check","1","");
            ManagerBridgeApi.registerStorage("storage-compat","{\"version\":1,\"fields\":[]}");
            int previous=sent.get();try{agent.rpc("storage-compat","platform","{\"op\":\"profiles.list\"}").get(5,TimeUnit.SECONDS);throw new AssertionError("Old service accepted namespaced module");}catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException,"Old/missing storage negotiation fails clearly");}
            check(sent.get()==previous,"Incompatible service receives no namespaced mutation");
            field("platformStorage").setInt(agent,1);agent.rpc("storage-compat","platform","{\"op\":\"profiles.list\"}").get(5,TimeUnit.SECONDS);check(requests.take().get("extension").getAsString().equals("storage-compat"),"Negotiated unified storage uses generic RPC");
            System.out.println("Bridge admission checks passed: " + checks);
        } finally {
            try { for (String name : new String[]{"rpc", "io", "disk"}) {
                try { ((ExecutorService) field(name).get(agent)).shutdownNow(); } catch (Throwable ignored) { }
            } } finally { http.stop(0); }
        }
    }
}
