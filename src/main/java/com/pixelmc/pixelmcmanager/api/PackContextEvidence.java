package com.pixelmc.pixelmcmanager.api;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

/** Correlate code candidates with pack settings and server scripts before they reach the model. */
final class PackContextEvidence {
    private static final Pattern ID =
            Pattern.compile("(?<![a-z0-9_.-])#?([a-z0-9_.-]+:[a-z0-9_./-]+)");
    private static final Map<String, Pattern> EVENTS =
            Map.of(
                    "trade",
                            Pattern.compile(
                                    "trade|merchant|villager|wandering|交易|商人|村民",
                                    Pattern.CASE_INSENSITIVE),
                    "loot", Pattern.compile("loot|drop|掉落|战利品", Pattern.CASE_INSENSITIVE),
                    "interact",
                            Pattern.compile(
                                    "interact|clicked|rightclick|useon|交互|右键",
                                    Pattern.CASE_INSENSITIVE),
                    "worldgen", Pattern.compile("worldgen|biomemodifier|placedfeature|装饰器|地物|群系"),
                    "food",
                            Pattern.compile(
                                    "finishusing|foodeaten|foodproperties|食用|吃下",
                                    Pattern.CASE_INSENSITIVE),
                    "spawn", Pattern.compile("spawn|刷怪|刷新生物", Pattern.CASE_INSENSITIVE));
    private static final Pattern GAME_SETTING =
            Pattern.compile(
                    "enable|disable|allow|require|chance|probab|weight|spawn|trad|loot|drop|rate|rarity|generat|count|height|grow|food|cooldown|damage|duration|frequency|density");
    private static final Pattern PRIVATE =
            Pattern.compile(
                    "password|credential|secret|token|apikey|webhook|address|private|permission|account|username|network|url");
    private static CompletableFuture<Scripts> scripts;

    static synchronized void clear() {
        scripts = null;
    }

    private static synchronized CompletableFuture<Scripts> scripts() {
        if (scripts == null || scripts.isCompletedExceptionally()) {
            List<String> roots = new ArrayList<>();
            if (ModList.get().isLoaded("kubejs"))
                roots.addAll(List.of("kubejs/server_scripts", "kubejs/startup_scripts"));
            if (ModList.get().isLoaded("crafttweaker")) roots.add("scripts");
            Path game = FMLPaths.GAMEDIR.get();
            scripts = PackGameplayApi.background(() -> Scripts.read(game, roots));
        }
        return scripts;
    }

    static CompletableFuture<JsonObject> query(
            MinecraftServer server,
            Set<String> requestedIds,
            Set<String> requestedMods,
            Set<String> domains) {
        return server.submit(
                        () -> {
                            var ids = new LinkedHashSet<>(requestedIds);
                            var mods = new TreeSet<>(requestedMods);
                            var terms = new HashSet<String>();
                            for (String id : requestedIds) {
                                ResourceLocation loc =
                                        ResourceLocation.tryParse(
                                                id.startsWith("#") ? id.substring(1) : id);
                                if (loc == null) continue;
                                mods.add(loc.getNamespace());
                                for (String word : loc.getPath().split("[_/]"))
                                    if (word.length() >= 4) terms.add(word);
                                if (id.startsWith("#")) continue;
                                BuiltInRegistries.ITEM
                                        .getOptional(loc)
                                        .ifPresent(
                                                item ->
                                                        item.builtInRegistryHolder()
                                                                .tags()
                                                                .limit(64)
                                                                .forEach(
                                                                        tag ->
                                                                                ids.add(
                                                                                        "#"
                                                                                                + tag
                                                                                                        .location())));
                                BuiltInRegistries.ENTITY_TYPE
                                        .getOptional(loc)
                                        .ifPresent(
                                                entity ->
                                                        entity.builtInRegistryHolder()
                                                                .tags()
                                                                .limit(64)
                                                                .forEach(
                                                                        tag ->
                                                                                ids.add(
                                                                                        "#"
                                                                                                + tag
                                                                                                        .location())));
                                BuiltInRegistries.BLOCK
                                        .getOptional(loc)
                                        .ifPresent(
                                                block ->
                                                        block.builtInRegistryHolder()
                                                                .tags()
                                                                .limit(64)
                                                                .forEach(
                                                                        tag ->
                                                                                ids.add(
                                                                                        "#"
                                                                                                + tag
                                                                                                        .location())));
                            }
                            var settings = new JsonObject();
                            for (var config : ConfigTracker.INSTANCE.fileMap().values()) {
                                if (!mods.contains(config.getModId())
                                        || config.getType() == ModConfig.Type.CLIENT
                                        || !(config.getSpec() instanceof ForgeConfigSpec spec)
                                        || !spec.isLoaded()) continue;
                                settings(
                                        spec.getValues(),
                                        "",
                                        config.getModId() + "/" + config.getType().name() + "/",
                                        terms,
                                        domains,
                                        settings);
                            }
                            return scripts()
                                    .thenApply(
                                            index -> {
                                                var check = index.select(ids, domains);
                                                if (settings.size() > 0)
                                                    check.add("loaded_settings", settings);
                                                check.addProperty(
                                                        "scope",
                                                        "脚本位置只供核对条件；当前设置只证明键值。仅凭代码、未匹配或未读不能确认实际玩法。");
                                                return check;
                                            });
                        })
                .thenCompose(response -> response);
    }

    private static void settings(
            UnmodifiableConfig node,
            String prefix,
            String mod,
            Set<String> terms,
            Set<String> domains,
            JsonObject out) {
        for (var entry : node.entrySet()) {
            String key = prefix + entry.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            if (PRIVATE.matcher(lower).find() || !key.matches("[\\p{L}\\d_. ()-]{1,160}")) continue;
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig child) {
                settings(child, key + ".", mod, terms, domains, out);
            } else if (value instanceof ForgeConfigSpec.ConfigValue<?> setting
                    && GAME_SETTING.matcher(lower).find()
                    && (terms.stream().anyMatch(lower::contains)
                            || domains.stream()
                                    .anyMatch(d -> EVENTS.get(d).matcher(lower).find()))) {
                value = setting.get();
                if (!(value instanceof Number || value instanceof Boolean)) continue;
                if (out.size() >= 16) {
                    out.addProperty("_more", true);
                    continue;
                }
                if (value instanceof Boolean flag) out.addProperty(mod + key, flag);
                else out.addProperty(mod + key, (Number) value);
            }
        }
    }

    /** Store only resource IDs and event locations, never the script text or player data. */
    static final class Scripts {
        final Map<String, Set<String>> byId = new HashMap<>(), byEvent = new HashMap<>();
        int skipped;

        static Scripts read(Path game, List<String> roots) {
            var index = new Scripts();
            long timeout = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            int inspected = 0;
            for (String root : roots) {
                Path dir = game.resolve(root);
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (!dir.toRealPath().startsWith(game.toRealPath())) {
                        index.skipped++;
                        continue;
                    }
                    try (var files = Files.walk(dir)) {
                        var candidates =
                                files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                                        .filter(
                                                p ->
                                                        p.toString()
                                                                .endsWith(
                                                                        root.equals("scripts")
                                                                                ? ".zs"
                                                                                : ".js"))
                                        .iterator();
                        while (candidates.hasNext()) {
                            if (++inspected > 8192 || System.nanoTime() >= timeout) {
                                index.skipped++;
                                return index;
                            }
                            Path file = candidates.next();
                            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                                var bytes = in.readNBytes(2_097_153);
                                if (bytes.length > 2_097_152) {
                                    index.skipped++;
                                    continue;
                                }
                                index.file(
                                        root
                                                + "/"
                                                + dir.relativize(file)
                                                        .toString()
                                                        .replace('\\', '/'),
                                        new String(bytes, StandardCharsets.UTF_8));
                            } catch (IOException ex) {
                                index.skipped++;
                            }
                        }
                    }
                } catch (IOException | UncheckedIOException ex) {
                    index.skipped++;
                }
            }
            return index;
        }

        void file(String path, String script) {
            int line = 0;
            for (String text : script.lines().toList()) {
                line++;
                String trimmed = text.stripLeading();
                if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*"))
                    continue;
                String at = path + ":" + line;
                var matches = ID.matcher(text.replace("<item:", "<").replace("<tag:items:", "<#"));
                while (matches.find())
                    byId.computeIfAbsent(matches.group(), k -> new TreeSet<>()).add(at);
                // Ordinary event registrations and rules naming another item aren't cross-item
                // evidence.
                // Keep recognizable bulk operations. A caller can still inspect arbitrary scripts
                // in the sandbox.
                var calls = CALL.matcher(text);
                while (calls.find()) {
                    String name = calls.group(1).toLowerCase(Locale.ROOT);
                    String domain =
                            switch (name) {
                                case "removemoddedtrades", "removevanillatrades", "cleartrades" ->
                                        "trade";
                                case "removeloot",
                                        "replaceloot",
                                        "removespawn",
                                        "removespawns",
                                        "removefeatures" -> {
                                    boolean literal = ID.matcher(text).find();
                                    if (literal
                                            && !text.contains("Ingredient.all")
                                            && !text.contains("*")) yield "";
                                    if (name.endsWith("loot")) yield "loot";
                                    yield name.endsWith("features") ? "worldgen" : "spawn";
                                }
                                default -> "";
                            };
                    if (!domain.isEmpty())
                        byEvent.computeIfAbsent(domain, k -> new TreeSet<>()).add(at);
                }
            }
        }

        private static final Pattern CALL =
                Pattern.compile("(?<![A-Za-z0-9_$])([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");

        JsonObject select(Set<String> ids, Set<String> domains) {
            var result = new JsonObject();
            var hits = new LinkedHashSet<String>();
            for (String id : ids) hits.addAll(byId.getOrDefault(id, Set.of()));
            locations(result, "matching_ids", hits);
            var global = new LinkedHashSet<String>();
            for (String event : domains) global.addAll(byEvent.getOrDefault(event, Set.of()));
            global.removeAll(hits);
            var files = new LinkedHashMap<String, String>();
            for (String at : global) files.putIfAbsent(at.substring(0, at.lastIndexOf(':')), at);
            locations(result, "event_candidates", new LinkedHashSet<>(files.values()));
            if (skipped != 0) result.addProperty("unreadable_files", skipped);
            return result;
        }

        private static void locations(JsonObject obj, String field, Set<String> matches) {
            if (matches.isEmpty()) return;
            var rows = new JsonArray();
            matches.stream().limit(4).forEach(rows::add);
            obj.add(field, rows);
            if (matches.size() > rows.size()) obj.addProperty(field + "_total", matches.size());
        }
    }
}
