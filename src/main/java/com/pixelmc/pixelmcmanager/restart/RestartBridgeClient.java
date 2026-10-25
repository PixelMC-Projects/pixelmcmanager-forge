package com.pixelmc.pixelmcmanager.restart;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.UUID;

/** Only local IPC. Panel keys and arbitrary commands never enter Minecraft. */
public final class RestartBridgeClient {
    private static final Gson GSON = new Gson();
    private final Path directory;
    private final String session = UUID.randomUUID().toString();

    public RestartBridgeClient(Path directory) { this.directory = directory; }
    public record View(boolean ready, String backend, String bridgeId, String responseId, String responseStatus, String message) {}

    public View read(long now) throws IOException {
        Path marker = directory.resolve("bridge.json");
        if (!Files.exists(marker)) return new View(false, "", "", "", "", "未检测到MCSM桥接或普通启动托管脚本。");
        JsonObject value = readJson(marker);
        String backend = value.get("backend").getAsString();
        String bridge = value.get("bridgeId").getAsString();
        long expires = value.get("expiresAt").getAsLong();
        boolean ready = value.get("protocol").getAsInt() == 1 && (backend.equals("mcsm") || backend.equals("standalone"))
                && !bridge.isBlank() && expires > now && expires - now <= 15_000;
        String id = "", status = "", message = ready ? "" : "重启托管程序不在线或协议不匹配。";
        if (Files.exists(directory.resolve("response.json"))) {
            JsonObject response = readJson(directory.resolve("response.json"));
            if (response.has("session") && session.equals(response.get("session").getAsString())) {
                id = response.get("id").getAsString();
                status = response.get("status").getAsString();
                message = response.has("message") ? response.get("message").getAsString() : "";
            }
        }
        return new View(ready, backend, bridge, id, status, message);
    }

    public void heartbeat(int players, boolean locked, long emptyMillis, long now) throws IOException {
        JsonObject heartbeat = new JsonObject();
        heartbeat.addProperty("protocol", 1);
        heartbeat.addProperty("session", session);
        heartbeat.addProperty("players", players);
        heartbeat.addProperty("locked", locked);
        heartbeat.addProperty("emptyMillis", emptyMillis);
        heartbeat.addProperty("at", now);
        write("game.json", heartbeat);
    }

    public void request(String id, String reason, long emptyMillis, long now) throws IOException {
        View current = read(now);
        if (!current.ready) throw new IOException("重启托管程序不可用。");
        heartbeat(0, true, emptyMillis, now);
        JsonObject request = new JsonObject();
        request.addProperty("protocol", 1);
        request.addProperty("bridgeId", current.bridgeId);
        request.addProperty("session", session);
        request.addProperty("id", id);
        request.addProperty("action", "restart");
        request.addProperty("reason", reason);
        request.addProperty("createdAt", now);
        request.addProperty("expiresAt", now + 10_000);
        write("request.json", request);
    }

    private JsonObject readJson(Path path) throws IOException {
        if (Files.size(path) > 16_384) throw new IOException("重启状态文件超过大小限制。");
        try { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("重启状态文件格式错误。"); }
    }

    private void write(String name, JsonObject value) throws IOException {
        Files.createDirectories(directory);
        Path temporary = directory.resolve(name + ".game-tmp");
        Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8);
        try { Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
    }
}
