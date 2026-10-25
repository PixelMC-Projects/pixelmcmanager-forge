package com.pixelmc.pixelmcmanager.api;

import com.google.gson.*;
import com.pixelmc.pixelmcmanager.bridge.BridgeAgent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Optional server-only extension boundary. No Minecraft types in its public signatures. */
public final class ManagerBridgeApi {
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Map<String, JsonObject> EXTENSIONS = new ConcurrentHashMap<>();
    private static final Map<String, BiConsumer<String,String>> CALLBACKS = new ConcurrentHashMap<>();
    private static volatile BridgeAgent agent;
    public static void attach(BridgeAgent value) { agent = value; }
    public static void register(String id, String name, String version, String capabilities) {
        if (!id.matches("[a-z0-9_.-]{1,64}") || name.length()>128 || version.length()>64 || capabilities.length()>2048)
            throw new IllegalArgumentException("Invalid extension metadata");
        if (EXTENSIONS.size()>=64 && !EXTENSIONS.containsKey(id)) throw new IllegalStateException("Extension limit");
        JsonObject value=new JsonObject();value.addProperty("id",id);value.addProperty("name",name);value.addProperty("version",version);
        value.add("capabilities",JSON.toJsonTree(capabilities.isBlank()?List.of():Arrays.asList(capabilities.split(","))));EXTENSIONS.put(id,value);
    }
    public static JsonArray extensions() { JsonArray result=new JsonArray();EXTENSIONS.values().stream().sorted(Comparator.comparing(v->v.get("id").getAsString())).forEach(v->result.add(v.deepCopy()));return result; }
    public static void onCallback(String extension, BiConsumer<String,String> handler) { CALLBACKS.put(extension,handler); }
    public static void callback(String extension,String op,String json) { var handler=CALLBACKS.get(extension);if(handler!=null)handler.accept(op,json); }
    public static String publicUrl() { BridgeAgent a=agent;return a==null?"":a.publicUrl(); }
    public static boolean admitted(UUID player) { BridgeAgent a=agent;return a!=null&&a.admitted(player); }
    public static CompletableFuture<String> call(String extension,String method,String json) {
        BridgeAgent a=agent;
        if(a==null || !EXTENSIONS.containsKey(extension))return CompletableFuture.failedFuture(new IllegalStateException("Manager bridge is not connected"));
        return a.rpc(extension,method,json);
    }
    private ManagerBridgeApi() {}
}
