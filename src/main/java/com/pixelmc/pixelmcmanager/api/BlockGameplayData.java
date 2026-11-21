/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** A small inspectable view of approved, live block data. No call serializes a block entity. */
final class BlockGameplayData {
    static final int MAX_BYTES = 6 * 1024;
    private static final int MAX_STRING = 256;
    private static final Field SPAWN_DATA =
            BlockGameplayStorage.field(BaseSpawner.class, "nextSpawnData", "f_45444_");
    private static final Map<String, Field> SPAWNER =
            fields(
                    BaseSpawner.class,
                    new String[][] {
                        {"Delay", "spawnDelay", "f_45442_"},
                        {"MinSpawnDelay", "minSpawnDelay", "f_45447_"},
                        {"MaxSpawnDelay", "maxSpawnDelay", "f_45448_"},
                        {"SpawnCount", "spawnCount", "f_45449_"},
                        {"MaxNearbyEntities", "maxNearbyEntities", "f_45451_"},
                        {"RequiredPlayerRange", "requiredPlayerRange", "f_45452_"},
                        {"SpawnRange", "spawnRange", "f_45453_"},
                    });
    private static final Map<String, Field> FURNACE =
            fields(
                    AbstractFurnaceBlockEntity.class,
                    new String[][] {
                        {"BurnTime", "litTime", "f_58316_"},
                        {"CookTime", "cookingProgress", "f_58318_"},
                        {"CookTimeTotal", "cookingTotalTime", "f_58319_"},
                    });
    private static final Map<String, Field> BREWING =
            fields(
                    BrewingStandBlockEntity.class,
                    new String[][] {
                        {"BrewTime", "brewTime", "f_58976_"}, {"Fuel", "fuel", "f_58979_"},
                    });
    private static final Map<String, Field> BEACON =
            fields(
                    BeaconBlockEntity.class,
                    new String[][] {
                        {"Levels", "levels", "f_58650_"},
                        {"Primary", "primaryPower", "f_58652_"},
                        {"Secondary", "secondaryPower", "f_58653_"},
                    });
    private static final String[] BACKPACK_NUMBERS = {
        "clothColor", "borderColor", "inventorySlots", "upgradeSlots", "columnsTaken"
    };

    record Query(String path, List<String> tokens, int offset, int limit) {}

    private BlockGameplayData() {}

    /**
     * Preflight used before an ask and before each actual tool read. No slot or NBT values are
     * enumerated.
     */
    static JsonObject authorization(ServerPlayer player, WorldBlockGameplayApi.Target look) {
        try {
            var decision = BlockClaimAccess.check(player, look.level(), look.pos(), look.face());
            if (decision == BlockClaimAccess.Decision.FORBIDDEN)
                return denied("claim", "当前服务器的领地规则不允许玩家访问这个方块。");
            if (decision == BlockClaimAccess.Decision.UNAVAILABLE)
                return error("unavailable", "当前无法核对这个方块的领地权限。");
            var refusal = BlockGameplayStorage.access(player, look);
            return refusal == null ? null : failure(refusal);
        } catch (RuntimeException | LinkageError inaccessible) {
            return error("unavailable", "暂时无法核对这个方块的数据访问权限。");
        }
    }

    static JsonObject inspect(
            ServerPlayer player, WorldBlockGameplayApi.Target look, Query query, boolean read) {
        var refusal = authorization(player, look);
        if (refusal != null) {
            refusal.addProperty("path", query.path);
            return refusal;
        }
        var level = look.level();
        var state = level.getBlockState(look.pos());
        var entity = look.entity();
        if (query.tokens.isEmpty()) return root(query, entity);
        String first = query.tokens.get(0);
        if (first.equals("blockState"))
            return tree(
                    query, blockState(state), query.tokens.subList(1, query.tokens.size()), read);
        if (!first.equals("inventory") && !first.equals("nbt") && !first.equals("blockEntityType"))
            return missing();
        if (entity == null)
            return error(
                    "unavailable", state.hasBlockEntity() ? "方块实体当前没有载入，请重新选择。" : "所选方块没有方块实体。");
        if (first.equals("blockEntityType")) {
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
            return type == null
                    ? missing()
                    : tree(
                            query,
                            new JsonPrimitive(type.toString()),
                            query.tokens.subList(1, query.tokens.size()),
                            read);
        }
        if (first.equals("inventory")) return inventory(player, look, query, 1, read);
        if (BlockGameplayStorage.containerType(entity)
                && query.tokens.size() >= 2
                && query.tokens.get(1).equals("Items"))
            return inventory(player, look, query, 2, read);
        var nbt = nbt(entity);
        if (BlockGameplayStorage.containerType(entity) && query.tokens.size() == 1) {
            if (nbt == null) nbt = new JsonObject();
            // Items is a virtual branch. Listing its descriptor never serializes a container.
            nbt.add("Items", new JsonArray());
        }
        if (nbt == null || nbt.size() == 0) return error("unavailable", "该方块没有可验证的只读 NBT 字段来源。");
        var result = tree(query, nbt, query.tokens.subList(1, query.tokens.size()), read);
        if (result.get("status").getAsString().equals("ok"))
            result.addProperty("scope", "safeGameplayFields");
        return result;
    }

    private static JsonObject root(Query query, BlockEntity entity) {
        var names =
                entity == null
                        ? List.of("blockState")
                        : List.of("blockState", "blockEntityType", "nbt", "inventory");
        return page(
                query,
                "compound",
                "fields",
                names.size(),
                index -> {
                    String name = names.get(index);
                    String type =
                            switch (name) {
                                case "blockEntityType" -> "string";
                                case "inventory" -> "list";
                                default -> "compound";
                            };
                    var field = descriptor(query, name, type);
                    if ((name.equals("inventory") && !BlockGameplayStorage.supported(entity))
                            || (name.equals("nbt") && !hasNbtView(entity))) {
                        field.addProperty("availability", "unavailable");
                    }
                    return field;
                });
    }

    private static JsonObject inventory(
            ServerPlayer player,
            WorldBlockGameplayApi.Target look,
            Query query,
            int prefixLength,
            boolean read) {
        var inventory = BlockGameplayStorage.open(player, look);
        if (!inventory.available()) return failure(inventory);
        int depth = query.tokens.size();
        JsonObject result;
        if (depth == prefixLength) {
            result =
                    page(
                            query,
                            "list",
                            read ? "items" : "fields",
                            inventory.size(),
                            index ->
                                    read
                                            ? inventory.reader().apply(index)
                                            : descriptor(
                                                    query, Integer.toString(index), "compound"));
        } else {
            int slot = index(query.tokens.get(prefixLength), inventory.size());
            if (slot < 0) return missing();
            result =
                    tree(
                            query,
                            inventory.reader().apply(slot),
                            query.tokens.subList(prefixLength + 1, depth),
                            read);
        }
        result.addProperty("source", inventory.source());
        if (!result.has("scope")) result.addProperty("scope", inventory.scope());
        if (inventory.scope().equals("physicalSlots"))
            result.addProperty("note", "背包的实体槽位，不包含升级虚拟库存。");
        if (inventory.scope().equals("persistedSlots"))
            result.addProperty("note", "已载入的背包持久化槽位，不包含升级虚拟库存，可能落后于当前界面。");
        return result;
    }

    private static JsonObject tree(
            Query query, JsonElement root, List<String> tokens, boolean read) {
        JsonElement node = root;
        for (String token : tokens) {
            if (node.isJsonObject()) {
                node = node.getAsJsonObject().get(token);
            } else if (node.isJsonArray()) {
                int index = index(token, node.getAsJsonArray().size());
                node = index < 0 ? null : node.getAsJsonArray().get(index);
            } else {
                node = null;
            }
            if (node == null) return missing();
        }
        String type = type(node);
        if (node.isJsonObject()) {
            var fields =
                    node.getAsJsonObject().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .toList();
            return page(
                    query,
                    type,
                    "fields",
                    fields.size(),
                    index -> {
                        var field = fields.get(index);
                        return child(query, field.getKey(), field.getValue(), read);
                    });
        }
        if (node.isJsonArray()) {
            var items = node.getAsJsonArray();
            return page(
                    query,
                    type,
                    read ? "items" : "fields",
                    items.size(),
                    index -> child(query, Integer.toString(index), items.get(index), read));
        }
        var response = header(query, type);
        if (read) value(response, node);
        return response;
    }

    private static JsonObject child(Query query, String name, JsonElement element, boolean read) {
        var field = descriptor(query, name, type(element));
        if (element.isJsonObject()) field.addProperty("size", element.getAsJsonObject().size());
        if (element.isJsonArray() && !(query.path.equals("/nbt") && name.equals("Items")))
            field.addProperty("size", element.getAsJsonArray().size());
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
            field.addProperty("length", element.getAsString().length());
        if (read && (element.isJsonPrimitive() || element.isJsonNull())) value(field, element);
        return field;
    }

    private static void value(JsonObject result, JsonElement element) {
        if (element instanceof JsonPrimitive primitive && primitive.isString()) {
            String value = primitive.getAsString();
            if (value.length() > MAX_STRING) {
                int end = MAX_STRING;
                if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
                result.addProperty("value", value.substring(0, end));
                result.addProperty("truncated", true);
                return;
            }
        }
        result.add("value", element);
    }

    private static JsonObject page(
            Query query, String type, String key, int total, IntFunction<JsonObject> row) {
        var response = header(query, type);
        response.addProperty("offset", query.offset);
        response.addProperty("limit", query.limit);
        response.addProperty("total", total);
        var rows = new JsonArray();
        response.add(key, rows);
        int bytes = utf8(response) + 256;
        int from = Math.min(query.offset, total);
        int end = Math.min(from + query.limit, total);
        for (int index = from; index < end; index++) {
            JsonObject value = row.apply(index);
            int cost = utf8(value) + 1;
            if (bytes + cost > MAX_BYTES) {
                response.addProperty("truncated", true);
                break;
            }
            bytes += cost;
            rows.add(value);
        }
        response.addProperty("hasMore", from + rows.size() < total);
        return response;
    }

    private static JsonObject descriptor(Query query, String name, String type) {
        var field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("type", type);
        field.addProperty("path", query.path + "/" + name.replace("~", "~0").replace("/", "~1"));
        return field;
    }

    private static JsonObject header(Query query, String type) {
        var result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("path", query.path);
        result.addProperty("type", type);
        return result;
    }

    static JsonObject bounded(JsonObject result) {
        return utf8(result) <= MAX_BYTES ? result : error("unavailable", "字段回执超出单次大小限制，请缩小查询范围。");
    }

    private static int utf8(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    static JsonObject error(String status, String message) {
        var error = new JsonObject();
        error.addProperty("status", status);
        error.addProperty("message", message);
        return error;
    }

    private static JsonObject failure(BlockGameplayStorage.Inventory refusal) {
        if (refusal.status().equals("forbidden"))
            return denied(refusal.reasonCode(), refusal.message());
        return error(refusal.status(), refusal.message());
    }

    private static JsonObject denied(String reasonCode, String message) {
        var result = error("forbidden", message);
        result.addProperty("reasonCode", reasonCode);
        return result;
    }

    private static JsonObject missing() {
        return error("unavailable", "未找到该路径的公开字段。");
    }

    private static String type(JsonElement value) {
        if (value.isJsonObject()) return "compound";
        if (value.isJsonArray()) return "list";
        if (value.isJsonNull()) return "null";
        if (value.getAsJsonPrimitive().isBoolean()) return "boolean";
        if (value.getAsJsonPrimitive().isNumber()) return "number";
        return "string";
    }

    private static int index(String candidate, int count) {
        if (!candidate.matches("0|[1-9][0-9]{0,8}")) return -1;
        int index = Integer.parseInt(candidate);
        return index < count ? index : -1;
    }

    private static JsonObject blockState(BlockState state) {
        var result = new JsonObject();
        for (Property<?> property : state.getProperties()) property(result, state, property);
        return result;
    }

    private static <T extends Comparable<T>> void property(
            JsonObject result, BlockState state, Property<T> property) {
        result.addProperty(property.getName(), property.getName(state.getValue(property)));
    }

    private static boolean hasNbtView(BlockEntity entity) {
        if (entity == null) return false;
        var type = entity.getClass();
        return BlockGameplayStorage.containerType(entity)
                || BlockGameplayStorage.backpack(entity)
                || type == SpawnerBlockEntity.class
                || type == BeaconBlockEntity.class;
    }

    private static JsonObject nbt(BlockEntity entity) {
        try {
            if (entity.getClass() == SpawnerBlockEntity.class) {
                var spawner = ((SpawnerBlockEntity) entity).getSpawner();
                var data = projected(spawner, SPAWNER);
                if (SPAWN_DATA != null && SPAWN_DATA.get(spawner) instanceof SpawnData spawn)
                    data.add("SpawnData", spawnData(spawn));
                return data;
            }
            if (entity instanceof AbstractFurnaceBlockEntity
                    && BlockGameplayStorage.supported(entity)) return projected(entity, FURNACE);
            if (entity.getClass() == BrewingStandBlockEntity.class)
                return projected(entity, BREWING);
            if (entity.getClass() == BeaconBlockEntity.class) return projected(entity, BEACON);
            if (BlockGameplayStorage.backpack(entity)) {
                CompoundTag tag = BlockGameplayStorage.backpackTag(entity);
                var data = new JsonObject();
                if (tag != null)
                    for (String field : BACKPACK_NUMBERS)
                        if (tag.contains(field, Tag.TAG_INT))
                            data.addProperty(field, tag.getInt(field));
                return data;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError inaccessible) {
            return null;
        }
        return null;
    }

    private static JsonObject spawnData(SpawnData data) {
        var result = new JsonObject();
        String rawId = data.getEntityToSpawn().getString("id");
        var mob = rawId.length() <= 192 ? ResourceLocation.tryParse(rawId) : null;
        if (mob != null && BuiltInRegistries.ENTITY_TYPE.containsKey(mob)) {
            var entity = new JsonObject();
            entity.addProperty("id", mob.toString());
            result.add("entity", entity);
        }
        data.getCustomSpawnRules()
                .ifPresent(
                        rules -> {
                            var light = new JsonObject();
                            light.add("block_light_limit", range(rules.blockLightLimit()));
                            light.add("sky_light_limit", range(rules.skyLightLimit()));
                            result.add("custom_spawn_rules", light);
                        });
        return result;
    }

    private static JsonObject range(InclusiveRange<Integer> range) {
        var result = new JsonObject();
        result.addProperty("min_inclusive", range.minInclusive());
        result.addProperty("max_inclusive", range.maxInclusive());
        return result;
    }

    private static JsonObject projected(Object source, Map<String, Field> fields)
            throws IllegalAccessException {
        var result = new JsonObject();
        for (var entry : fields.entrySet()) {
            Object value = entry.getValue().get(source);
            if (value instanceof Number number) result.addProperty(entry.getKey(), number);
            if (value instanceof MobEffect effect)
                result.addProperty(
                        entry.getKey(), BuiltInRegistries.MOB_EFFECT.getKey(effect).toString());
        }
        return result;
    }

    private static Map<String, Field> fields(Class<?> type, String[][] definitions) {
        var fields = new LinkedHashMap<String, Field>();
        for (String[] names : definitions) {
            Field field = BlockGameplayStorage.field(type, names[1], names[2]);
            if (field != null) fields.put(names[0], field);
        }
        return fields;
    }
}
