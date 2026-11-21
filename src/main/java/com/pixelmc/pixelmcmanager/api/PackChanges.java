package com.pixelmc.pixelmcmanager.api;

import com.google.gson.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

final class PackChanges {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private record LootPage(ResourceLocation target, int page) {}

    private static final Map<LootPage, CompletableFuture<JsonObject>> lootPages =
            new LinkedHashMap<>(16, .75f, true);
    private static java.lang.ref.WeakReference<ResourceManager> cachedManager =
            new java.lang.ref.WeakReference<>(null);

    static synchronized void clear() {
        lootPages.clear();
        cachedManager.clear();
    }

    static synchronized CompletableFuture<JsonObject> query(
            MinecraftServer server, ResourceLocation id, int page, boolean lootOnly) {
        var manager = server.getResourceManager();
        var key = new LootPage(id, page);
        if (lootOnly) {
            if (cachedManager.get() != manager) {
                lootPages.clear();
                cachedManager = new java.lang.ref.WeakReference<>(manager);
            }
            var cached = lootPages.get(key);
            if (cached != null)
                return cached.thenApply(JsonObject::deepCopy)
                        .exceptionally(ex -> GameplayData.result("unavailable", "本次整合包资料未能读取。"));
        }
        var tags = new HashSet<String>();
        BuiltInRegistries.ITEM
                .getOptional(id)
                .ifPresent(
                        item ->
                                item.builtInRegistryHolder()
                                        .tags()
                                        .forEach(tag -> tags.add("#" + tag.location())));
        try {
            var scanPage =
                    PackGameplayApi.background(
                            () -> {
                                var scan = new Scan(id, tags, lootOnly);
                                scan.resources(manager);
                                scan.scripts(FMLPaths.GAMEDIR.get());
                                return scan.page(page);
                            });
            if (lootOnly) {
                lootPages.put(key, scanPage);
                if (lootPages.size() > 48) lootPages.remove(lootPages.keySet().iterator().next());
                scanPage.whenComplete(
                        (result, failure) -> {
                            if (failure != null
                                    || !"pack_changes".equals(result.get("status").getAsString()))
                                synchronized (PackChanges.class) {
                                    lootPages.remove(key, scanPage);
                                }
                        });
            }
            return scanPage.thenApply(JsonObject::deepCopy)
                    .exceptionally(ex -> GameplayData.result("unavailable", "本次整合包资料未能读取。"));
        } catch (RejectedExecutionException busy) {
            return CompletableFuture.completedFuture(GameplayData.result("busy", "整合包资料正在查阅。"));
        }
    }

    private static final class Scan {
        final ResourceLocation target;
        final Set<String> tags;
        final boolean lootOnly;
        final Pattern ids = Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");
        final Pattern tableCall =
                Pattern.compile(
                        "\\.addLootTableModifier\\(\\s*['\"]([a-z0-9_.-]+:[a-z0-9_./-]+)['\"]");
        final List<JsonObject> matches = new ArrayList<>();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        int inspected;
        int unreadable;

        Scan(ResourceLocation target, Set<String> tags, boolean lootOnly) {
            this.target = target;
            this.tags = tags;
            this.lootOnly = lootOnly;
        }

        String match(String text) {
            var matcher = ids.matcher(text.replace("<item:", "<").replace("<tag:items:", "<#"));
            String via = "";
            while (matcher.find()) {
                String token = matcher.group();
                if (token.equals(target.toString())) return token;
                if (via.isEmpty() && tags.contains(token)) via = token;
            }
            return via;
        }

        JsonObject add(String source, String path, String evidence, String via, int line) {
            var row = new JsonObject();
            row.addProperty("origin", source);
            row.addProperty("path", path);
            row.addProperty("evidence", evidence);
            row.addProperty("match", via);
            if (line > 0) row.addProperty("line", line);
            matches.add(row);
            return row;
        }

        void resources(ResourceManager manager) {
            for (String folder :
                    List.of(
                            "recipes",
                            "tags/items",
                            "loot_tables",
                            "loot_modifiers",
                            "forge/biome_modifier",
                            "worldgen/placed_feature",
                            "worldgen/configured_feature",
                            "worldgen/biome",
                            "tags/blocks",
                            "tags/entity_types",
                            "tags/worldgen/biome")) {
                if (lootOnly && !folder.startsWith("loot_")) continue;
                boolean tag = folder.startsWith("tags/"),
                        modifier = folder.equals("loot_modifiers");
                var stacks =
                        manager.listResourceStacks(folder, id -> id.getPath().endsWith(".json"));
                for (var entry : stacks.entrySet()) {
                    if (exhausted()) return;
                    var id = entry.getKey();
                    var layers = entry.getValue();
                    if (!modifier
                            && layers.stream().noneMatch(layer -> customPack(layer.sourcePackId())))
                        continue;
                    String logical =
                            id.getNamespace()
                                    + ":"
                                    + id.getPath()
                                            .substring(
                                                    folder.length() + 1, id.getPath().length() - 5);
                    // The last resource wins for single-definition types. Item tags can merge
                    // multiple layers.
                    for (int i = layers.size() - 1; i >= 0; i--) {
                        var resource = layers.get(i);
                        String pack = resource.sourcePackId();
                        boolean kube = pack.toLowerCase(Locale.ROOT).contains("kubejs");
                        boolean custom = customPack(pack);
                        if (!custom && !modifier && (!tag || i == 0)) break;
                        try (var in = resource.open()) {
                            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                            if (bytes.length > MAX_BYTES) {
                                unreadable++;
                                break;
                            }
                            String json = new String(bytes, StandardCharsets.UTF_8);
                            String via = logical.equals(target.toString()) ? logical : match(json);
                            if (!via.isEmpty() && (custom || modifier)) {
                                String source = kube ? "KubeJS" : modifier ? "掉落修饰器" : "数据包";
                                String evidence =
                                        tag
                                                ? "已加载的标签层"
                                                : modifier
                                                        ? "当前修饰器定义，触发与启用条件另查"
                                                        : i > 0 ? "当前数据包覆盖了同名定义" : "当前数据包提供的定义";
                                var row = add(source, pack + "/" + id, evidence, via, 0);
                                if (modifier && lootOnly) {
                                    var definition = JsonParser.parseString(json).getAsJsonObject();
                                    for (String field :
                                            List.of(
                                                    "type",
                                                    "conditions",
                                                    "item",
                                                    "loot_table_id")) {
                                        var value = definition.get(field);
                                        if (value != null && value.toString().length() <= 600)
                                            row.add(field, value);
                                    }
                                }
                            }
                            if (!tag || (i > 0 && replacesTag(json))) break;
                        } catch (IOException | RuntimeException invalid) {
                            unreadable++;
                            break;
                        }
                    }
                }
            }
        }

        boolean customPack(String id) {
            String pack = id.toLowerCase(Locale.ROOT);
            return pack.startsWith("file/")
                    || pack.contains("kubejs")
                    || pack.startsWith("openloader/")
                    || pack.startsWith("paxi/")
                    || pack.startsWith("globalpacks/");
        }

        boolean replacesTag(String json) {
            var obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has("replace") && obj.get("replace").getAsBoolean();
        }

        void scripts(Path game) {
            for (String folder :
                    List.of("kubejs/server_scripts", "kubejs/startup_scripts", "scripts")) {
                boolean zen = folder.equals("scripts");
                Path root = game.resolve(folder);
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (!root.toRealPath().startsWith(game.toRealPath())) {
                        unreadable++;
                        continue;
                    }
                    try (var files = Files.walk(root)) {
                        var candidates =
                                files.filter(p -> p.toString().endsWith(zen ? ".zs" : ".js"))
                                        .filter(
                                                p ->
                                                        Files.isRegularFile(
                                                                p, LinkOption.NOFOLLOW_LINKS))
                                        .iterator();
                        while (candidates.hasNext()) {
                            if (exhausted()) return;
                            var file = candidates.next();
                            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                                if (bytes.length > MAX_BYTES) {
                                    unreadable++;
                                    continue;
                                }
                                String script = new String(bytes, StandardCharsets.UTF_8);
                                if (lootOnly && !script.toLowerCase(Locale.ROOT).contains("loot"))
                                    continue;
                                String source =
                                        zen
                                                ? "CraftTweaker"
                                                : script.contains("LootJS.") ? "LootJS" : "KubeJS";
                                String path =
                                        folder
                                                + "/"
                                                + root.relativize(file)
                                                        .toString()
                                                        .replace('\\', '/');
                                JsonArray tables = null;
                                int line = 0;
                                var lines = script.lines().toList();
                                var rules =
                                        source.equals("LootJS")
                                                ? new LootRuleEvidence(lines)
                                                : null;
                                for (String text : lines) {
                                    line++;
                                    if (text.startsWith("LootJS.modifiers("))
                                        tables = new JsonArray();
                                    if (tables != null) {
                                        var calls = tableCall.matcher(text);
                                        while (calls.find() && tables.size() < 4) {
                                            var table = new JsonPrimitive(calls.group(1));
                                            if (!tables.contains(table)) tables.add(table);
                                        }
                                    }
                                    // Keep original column offsets while recognizing Zen bracket
                                    // handlers.
                                    String searchable =
                                            zen
                                                    ? text.replace("<item:", " ".repeat(6))
                                                            .replace(
                                                                    "<tag:items:",
                                                                    " ".repeat(10) + "#")
                                                    : text;
                                    var tokens = ids.matcher(searchable);
                                    while (tokens.find()) {
                                        String via = tokens.group();
                                        if (!via.equals(target.toString()) && !tags.contains(via))
                                            continue;
                                        var row =
                                                add(
                                                        source,
                                                        path,
                                                        "脚本位置；同段表名是定位线索，执行条件另查",
                                                        via,
                                                        line);
                                        row.addProperty("column", tokens.start() + 1);
                                        if (tables != null) row.add("nearby_tables", tables);
                                        if (rules != null) rules.annotate(row, line - 1, via, text);
                                        else if (lootOnly)
                                            row.addProperty(
                                                    "excerpt", LootRuleEvidence.snippet(text));
                                    }
                                    if (text.startsWith("})")) tables = null;
                                }
                            } catch (IOException failure) {
                                unreadable++;
                            }
                        }
                    }
                } catch (IOException | UncheckedIOException error) {
                    unreadable++;
                }
            }
        }

        private boolean exhausted() {
            if (++inspected <= 24000 && matches.size() <= 40000 && System.nanoTime() < deadline)
                return false;
            unreadable++;
            return true;
        }

        JsonObject page(int page) {
            matches.sort(
                    Comparator.comparing(
                                    (JsonObject row) ->
                                            !row.get("match")
                                                    .getAsString()
                                                    .equals(target.toString()))
                            .thenComparing(row -> row.get("path").getAsString()));
            var result =
                    GameplayData.result(
                            "pack_changes", "counts 按文件或资源层去重，occurrences 按匹配位置计数。未命中不能排除动态脚本。");
            var counts = new JsonObject();
            var occurrences = new JsonObject();
            var paths = new HashSet<List<String>>();
            for (var row : matches) {
                var source = row.get("origin").getAsString();
                if (paths.add(List.of(source, row.get("path").getAsString())))
                    counts.addProperty(source, PackGameplayApi.number(counts, source) + 1);
                occurrences.addProperty(source, PackGameplayApi.number(occurrences, source) + 1);
            }
            result.add("counts", counts);
            result.add("occurrences", occurrences);
            result.addProperty("total", matches.size());
            result.addProperty("unreadable", unreadable);
            result.addProperty("page", page);
            var rows = new JsonArray();
            matches.stream().skip((page - 1L) * 12).limit(12).forEach(rows::add);
            result.add("matches", rows);
            if (lootOnly) {
                result.addProperty("scope", "loot");
                result.addProperty(
                        "lootjs_loaded", net.minecraftforge.fml.ModList.get().isLoaded("lootjs"));
                result.addProperty("more", page * 12L < matches.size());
                result.addProperty(
                        "interpretation",
                        "script_rules 标注脚本中的操作和表。代码片段可能省略外层条件；命中不代表已执行。仅有行号时需核实语义。用 pack_changes(view=loot) 继续翻页。");
            }
            return result;
        }
    }

    private PackChanges() {}
}
