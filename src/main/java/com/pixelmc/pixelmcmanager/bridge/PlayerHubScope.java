package com.pixelmc.pixelmcmanager.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.UUID;

/** Safe metadata only. Called by the bridge IO worker; snapshots never perform disk or network IO. */
public final class PlayerHubScope {
    private final String serverId, worldId;
    private final Path cache;
    private volatile boolean managed = true, connected;
    private volatile JsonObject pack;

    private PlayerHubScope(String serverId, String worldId, Path cache) {
        this.serverId = serverId;
        this.worldId = worldId;
        this.cache = cache;
    }

    public static PlayerHubScope open(Path configDirectory, Path bridgeDirectory) throws IOException {
        PlayerHubScope value = new PlayerHubScope(identity(configDirectory.resolve("pixelmcmanager/player-hub-server-id.txt")),
                identity(bridgeDirectory.resolve("world-id.txt")), bridgeDirectory.resolve("player-hub-scope.json"));
        if (Files.isRegularFile(value.cache) && Files.size(value.cache) <= 4096) {
            try { value.pack = safePack(JsonParser.parseString(Files.readString(value.cache)).getAsJsonObject()); }
            catch (RuntimeException invalid) { /* Unknown cached metadata stays unknown. */ }
        }
        return value;
    }

    public String worldId() { return worldId; }
    public void managed(boolean value) { managed = value; }
    public void disconnected() { connected = false; }

    public void hello(JsonObject response) throws IOException {
        JsonObject next = response.has("archive") && response.get("archive").isJsonObject()
                ? safePack(response.getAsJsonObject("archive")) : null;
        pack = next;
        connected = next != null;
        if (next == null) Files.deleteIfExists(cache);
        else atomic(cache, next.toString());
    }

    public JsonObject snapshot() {
        JsonObject metadata = pack, value = new JsonObject();
        value.addProperty("serverId", serverId);
        // This is the stable gameplay save identity, not the service's administrative archive row ID.
        value.addProperty("archiveId", worldId);
        value.addProperty("packName", managed && metadata != null ? metadata.get("packName").getAsString() : "");
        value.addProperty("packVersion", managed && metadata != null ? metadata.get("packVersion").getAsString() : "");
        value.addProperty("timezone", ZoneId.systemDefault().getId());
        value.addProperty("metadataAvailable", managed && metadata != null);
        value.addProperty("metadataStatus", !managed ? "standalone" : metadata == null ? "unavailable" : connected ? "current" : "cached");
        return value;
    }

    private static JsonObject safePack(JsonObject raw) {
        String name = text(raw, raw.has("packName") ? "packName" : "name", 256);
        String version = text(raw, raw.has("packVersion") ? "packVersion" : "version", 128);
        JsonObject result = new JsonObject();
        result.addProperty("packName", name);
        result.addProperty("packVersion", version);
        return result;
    }

    private static String text(JsonObject raw, String key, int limit) {
        if (!raw.has(key) || !raw.get(key).isJsonPrimitive() || !raw.get(key).getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Invalid pack metadata");
        String text = raw.get(key).getAsString();
        if (text.length() > limit) throw new IllegalArgumentException("Invalid pack metadata");
        return text;
    }

    private static String identity(Path file) throws IOException {
        if (Files.exists(file)) return UUID.fromString(Files.readString(file).strip()).toString();
        String id = UUID.randomUUID().toString();
        atomic(file, id);
        return id;
    }

    private static void atomic(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, value);
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
