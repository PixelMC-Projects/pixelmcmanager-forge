package com.pixelmc.pixelmcmanager.api;

import com.google.gson.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import com.electronwill.nightconfig.toml.TomlParser;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class SpawnPackEvidence {

    private static final Set<String> NONPUBLIC =
            Set.of("command", "commands", "message", "nbt", "playerhelditem", "playerwearing");

    private SpawnPackEvidence() {}

    static CompletableFuture<JsonObject> query(
            MinecraftServer server, ResourceLocation id, int page) {
        var entity = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (entity.isEmpty())
            return CompletableFuture.completedFuture(
                    GameplayData.result("not_registered", "没有注册这个实体。"));
        JsonArray rows = new JsonArray();
        var biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
        for (var entry :
                biomes.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.comparing(k -> k.location())))
                        .toList()) {
            for (var spawn :
                    entry.getValue()
                            .getMobSettings()
                            .getMobs(entity.get().getCategory())
                            .unwrap()) {
                if (spawn.type != entity.get()) continue;
                var row = new JsonObject();
                row.addProperty("biome", entry.getKey().location().toString());
                row.addProperty("weight", spawn.getWeight().asInt());
                row.addProperty("min_group", spawn.minCount);
                row.addProperty("max_group", spawn.maxCount);
                rows.add(row);
            }
        }
        var tags = new HashSet<String>();
        entity.get().builtInRegistryHolder().tags().forEach(tag -> tags.add("#" + tag.location()));
        boolean installed = ModList.get().isLoaded("incontrol");
        boolean badMobs = ModList.get().isLoaded("badmobs");
        return PackGameplayApi.background(
                () -> {
                    JsonArray rules = new JsonArray(), warnings = new JsonArray();
                    if (installed) {
                        var dir = FMLPaths.CONFIGDIR.get().resolve("incontrol");
                        if (Files.isSymbolicLink(dir)) {
                            warnings.add("InControl 配置目录未能读取");
                        } else {
                            for (String file : List.of("spawn.json", "spawner.json")) {
                                inspect(dir.resolve(file), id.toString(), tags, rules, warnings);
                            }
                        }
                    }
                    // Put explicit rules first so an extensive biome list cannot bury a pack
                    // override.
                    if (badMobs)
                        badMobsRule(
                                FMLPaths.CONFIGDIR.get().resolve("badmobs-common.toml"),
                                id,
                                rules,
                                warnings);
                    int ruleCount = rules.size();
                    rules.addAll(rows);
                    var result =
                            PackGameplayApi.entriesPage(
                                    rules,
                                    page,
                                    "群系表是自然生成候选。InControl 和 Bad Mobs 条目来自磁盘配置；InControl 按文件顺序列出实体匹配与可能适用的通用规则。全局条件、其他模组和实地判定仍可改变结果。");
                    result.addProperty("biome_entries", rows.size());
                    result.addProperty("targeted_config_rules", ruleCount);
                    result.add("warnings", warnings);
                    return result;
                });
    }

    private static void inspect(
            Path file, String id, Set<String> tags, JsonArray rows, JsonArray warnings) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IOException();
            int order = 0;
            for (JsonElement element :
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonArray()) {
                order++;
                if (!element.isJsonObject()) continue;
                var rule = element.getAsJsonObject();
                if (rule.has("mob") && !matches(rule.get("mob"), id, tags)) continue;
                if (!rule.has("mob") && !rule.has("result")) continue;
                var row = new JsonObject();
                row.addProperty("config", "incontrol/" + file.getFileName());
                row.addProperty("rule_number", order);
                if (!rule.has("mob")) row.addProperty("scope", "未限定实体 ID；须核对其余选择条件和规则顺序");
                var fields = new JsonObject();
                for (String key :
                        List.of(
                                "result",
                                "when",
                                "biome",
                                "biometags",
                                "biometype",
                                "dimension",
                                "minheight",
                                "maxheight",
                                "persecond",
                                "attempts",
                                "amount",
                                "gamestage")) {
                    addCondition(fields, key, rule.get(key));
                }
                if (rule.has("conditions") && rule.get("conditions").isJsonObject()) {
                    var conditions = rule.getAsJsonObject("conditions");
                    var spawnFields = new JsonObject();
                    for (String key :
                            List.of(
                                    "dimension",
                                    "biome",
                                    "mindist",
                                    "maxdist",
                                    "minheight",
                                    "maxheight",
                                    "maxthis",
                                    "maxlocal",
                                    "maxhostile",
                                    "maxneutral",
                                    "maxpassive",
                                    "inwater",
                                    "inlava",
                                    "inair",
                                    "seesky")) {
                        addCondition(spawnFields, key, conditions.get(key));
                    }
                    fields.add("conditions", spawnFields);
                    if (conditions.size() > spawnFields.size())
                        row.addProperty("additional_conditions", true);
                }
                var other = new JsonArray();
                for (String key : rule.keySet()) {
                    if (!key.equals("mob")
                            && !fields.has(key)
                            && !NONPUBLIC.contains(key)
                            && key.matches("[a-z][a-z0-9_]{0,47}")
                            && other.size() < 32) other.add(key);
                }
                if (!other.isEmpty()) row.add("other_rule_fields", other);
                if (fields.toString().length() <= 5000) row.add("conditions_and_result", fields);
                else row.addProperty("omitted", "条件过长，请按文件和序号核对。");
                rows.add(row);
            }
        } catch (IOException | RuntimeException e) {
            warnings.add(file.getFileName() + " 未能完整读取");
        }
    }

    private static void addCondition(JsonObject result, String key, JsonElement value) {
        if (isCondition(value)) {
            result.add(key, value);
        } else if (value != null && value.isJsonArray()) {
            var selected = new JsonArray();
            for (var entry : value.getAsJsonArray()) {
                if (selected.size() >= 64) break;
                if (isCondition(entry)) selected.add(entry);
            }
            if (!selected.isEmpty()) result.add(key, selected);
        }
    }

    private static boolean isCondition(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return false;
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return true;
        if (primitive.isNumber()) return Double.isFinite(primitive.getAsDouble());
        return primitive
                .getAsString()
                .matches("#?[a-zA-Z0-9_.-]{1,100}(?::[a-zA-Z0-9_./-]{1,139})?");
    }

    private static void badMobsRule(
            Path file, ResourceLocation id, JsonArray rows, JsonArray warnings) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(3_000_001);
            if (bytes.length > 3_000_000) throw new IOException();
            var config = new TomlParser().parse(new String(bytes, StandardCharsets.UTF_8));
            var flags = new JsonObject();
            for (String key :
                    List.of(
                            "allowNormalSpawning",
                            "allowSpawners",
                            "allowSpawnEggs",
                            "allowConversions")) {
                Object value = config.get(List.of(id.getNamespace(), id.getPath(), key));
                if (value instanceof Boolean enabled) flags.addProperty(key, enabled);
            }
            if (flags.size() == 0) return;
            var row = new JsonObject();
            row.addProperty("config", "badmobs-common.toml");
            row.add("permissions", flags);
            rows.add(row);
        } catch (IOException | RuntimeException failed) {
            warnings.add("Bad Mobs 配置未能完整读取");
        }
    }

    private static boolean matches(JsonElement selector, String id, Set<String> tags) {
        if (selector == null) return false;
        if (selector.isJsonArray()) {
            for (var candidate : selector.getAsJsonArray())
                if (matches(candidate, id, tags)) return true;
        } else if (selector.isJsonPrimitive()) {
            String value = selector.getAsString();
            return value.equals(id) || tags.contains(value);
        }
        return false;
    }
}
