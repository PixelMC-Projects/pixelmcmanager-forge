package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/** Allowlisted in-process pack evidence for trusted server consumers. */
@Mod.EventBusSubscriber(modid = "pixelmcmanager")
public final class PackGameplayApi {
    private static final int PAGE_SIZE = 12;
    private static final int RESPONSE_BYTES = 131_072;
    private static final Pattern ID =
            Pattern.compile("#?[a-z0-9_-][a-z0-9_.-]*:[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");
    private static final Pattern MOD = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Set<String> DOMAINS =
            Set.of("trade", "loot", "interact", "worldgen", "food", "spawn");
    private static final ThreadPoolExecutor IO =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    task -> {
                        var thread = new Thread(task, "PixelMC Manager pack evidence");
                        thread.setDaemon(true);
                        return thread;
                    });

    public static int protocolVersion() {
        return 1;
    }

    public static String descriptor() {
        return descriptor(
                "pack.evidence.read",
                PAGE_SIZE,
                "packChanges",
                "entitySpawns",
                "context",
                "languageAssetRoots");
    }

    static String descriptor(String id, int maxPageSize, String... actions) {
        var provider = GameplayData.row("providerId", "pixelmcmanager");
        provider.addProperty(
                "modVersion",
                ModList.get()
                        .getModContainerById("pixelmcmanager")
                        .map(mod -> mod.getModInfo().getVersion().toString())
                        .orElse("unknown"));
        provider.addProperty("providerVersion", 1);
        var capability = GameplayData.row("id", id);
        capability.addProperty("version", 1);
        capability.addProperty("audience", "trusted-server");
        capability.addProperty("maxPageSize", maxPageSize);
        var names = new JsonArray();
        for (String action : actions) names.add(action);
        capability.add("actions", names);
        var capabilities = new JsonArray();
        capabilities.add(capability);
        provider.add("capabilities", capabilities);
        return provider.toString();
    }

    public static CompletableFuture<JsonObject> packChanges(
            MinecraftServer server, ResourceLocation id, int page, boolean lootOnly) {
        if (server == null || !valid(id, page)) return invalid();
        return finish(
                server.submit(() -> PackChanges.query(server, id, page, lootOnly))
                        .thenCompose(value -> value));
    }

    public static CompletableFuture<JsonObject> entitySpawns(
            MinecraftServer server, ResourceLocation id, int page) {
        if (server == null || !valid(id, page)) return invalid();
        return finish(
                server.submit(() -> SpawnPackEvidence.query(server, id, page))
                        .thenCompose(value -> value));
    }

    public static CompletableFuture<JsonObject> context(
            MinecraftServer server, Set<String> ids, Set<String> mods, Set<String> domains) {
        if (server == null
                || ids == null
                || ids.size() > 128
                || mods == null
                || mods.size() > 16
                || domains == null
                || ids.stream().anyMatch(id -> !validId(id))
                || mods.stream().anyMatch(mod -> mod == null || !MOD.matcher(mod).matches())
                || domains.stream().anyMatch(domain -> domain == null || !DOMAINS.contains(domain)))
            return invalid();
        return finish(
                PackContextEvidence.query(
                        server, Set.copyOf(ids), Set.copyOf(mods), Set.copyOf(domains)));
    }

    /** Fixed language overlay roots. Game server clients never receive these paths. */
    public static List<Path> languageAssetRoots() {
        return List.of(FMLPaths.GAMEDIR.get().resolve("kubejs/assets"));
    }

    @SubscribeEvent
    public static void dataPackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) clear();
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        clear();
    }

    private static void clear() {
        PackChanges.clear();
        PackContextEvidence.clear();
    }

    static <T> CompletableFuture<T> background(Supplier<T> query) {
        try {
            return CompletableFuture.supplyAsync(query, IO);
        } catch (RejectedExecutionException busy) {
            return CompletableFuture.failedFuture(busy);
        }
    }

    private static CompletableFuture<JsonObject> finish(CompletableFuture<JsonObject> response) {
        return response.thenApply(
                        result -> {
                            if (result.toString().getBytes(StandardCharsets.UTF_8).length
                                    > RESPONSE_BYTES) {
                                return GameplayData.result("unavailable", "整合包资料超过单次响应上限。");
                            }
                            return result;
                        })
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(failure -> GameplayData.result("unavailable", "本次整合包资料未能读取。"));
    }

    static boolean valid(ResourceLocation id, int page) {
        return id != null && page >= 1 && validId(id.toString());
    }

    private static boolean validId(String id) {
        return id != null
                && id.length() <= 240
                && ID.matcher(id).matches()
                && !id.contains("..")
                && !id.contains(":.")
                && !id.contains("/./")
                && !id.endsWith("/.");
    }

    private static CompletableFuture<JsonObject> invalid() {
        return CompletableFuture.completedFuture(
                GameplayData.result("invalid_request", "整合包查询无效。"));
    }

    static int number(JsonObject result, String key) {
        return result.has(key) ? result.get(key).getAsInt() : 0;
    }

    static JsonObject entriesPage(JsonArray rows, int page, String description) {
        long start = (page - 1L) * PAGE_SIZE;
        if (page < 1 || page > 1 && start >= rows.size()) {
            return GameplayData.result("invalid_page", "页码超出范围。");
        }
        var result = GameplayData.result("live", description);
        var selected = new JsonArray();
        for (int i = (int) start; i < Math.min(rows.size(), start + PAGE_SIZE); i++)
            selected.add(rows.get(i));
        result.add("entries", selected);
        result.addProperty("total", rows.size());
        result.addProperty("more", start + PAGE_SIZE < rows.size());
        return result;
    }

    private PackGameplayApi() {}
}
