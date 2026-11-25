package com.pixelmc.pixelmcmanager.api;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.storage.loot.*;
import static com.pixelmc.pixelmcmanager.api.GameplayData.*;

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
        modid = "pixelmcmanager",
        bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.FORGE)
final class StructureGameplay {
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void reload(net.minecraftforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) clear();
    }

    private record Template(Set<String> next, Set<String> tables) {}

    private record Pool(Set<String> templates, String fallback) {}

    private record Root(String id, String pool, int depth) {}

    private record Route(String structure, String template, String table, String container) {}

    private record Index(List<Route> routes, int unreadable, int unsupported) {}

    private static ResourceManager owner;
    private static CompletableFuture<Index> index;

    static synchronized void clear() {
        owner = null;
        index = null;
    }

    static CompletableFuture<JsonObject> read(
            MinecraftServer server, String kind, String target, int page) {
        if (kind.equals("structure_generation")) {
            return CompletableFuture.completedFuture(generation(server,target,page));
        }
        if (kind.equals("chest_sources") && !net.minecraft.core.registries.BuiltInRegistries.ITEM
                .containsKey(ResourceLocation.tryParse(target))) {
            return CompletableFuture.completedFuture(result("not_registered", "物品未注册。"));
        }
        var structureIndex = cached(server);
        if (structureIndex.isCompletedExceptionally()) {
            return CompletableFuture.completedFuture(result("unavailable", "结构容器索引建立失败。"));
        }
        if (!structureIndex.isDone()) {
            var pending = result("index_building", "正在后台整理结构与容器的对应关系。");
            if (kind.equals("chest_sources")) {
                pending.addProperty("next_query", "loot_sources");
                pending.addProperty("target", target);
            }
            return CompletableFuture.completedFuture(pending);
        }
        var possible = new HashSet<String>();
        if (server.getWorldData().worldGenOptions().generateStructures()) {
            for (var level : server.getAllLevels())
                for (var set : level.getChunkSource().getGeneratorState().possibleStructureSets())
                    for (var entry : set.value().structures())
                        entry.structure()
                                .unwrapKey()
                                .ifPresent(key -> possible.add(key.location().toString()));
        }
        var active = new HashMap<ResourceLocation, LootTable>();
        if (kind.equals("chest_sources")) {
            for (var key : server.getLootData().getKeys(LootDataType.TABLE))
                active.put(key, server.getLootData().getLootTable(key));
        }
        var tags = new HashSet<String>();
        if (kind.equals("chest_sources"))
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getOptional(ResourceLocation.tryParse(target))
                    .ifPresent(
                            item ->
                                    item.builtInRegistryHolder()
                                            .tags()
                                            .forEach(t -> tags.add(t.location().toString())));
        return structureIndex
                .thenApplyAsync(
                        idx -> {
                            var rows = new ArrayList<JsonObject>();
                            int[] skipped = {0};
                            var groups = new LinkedHashMap<List<String>, JsonObject>();
                            var memo = new HashMap<String, Boolean>();
                            var json = Deserializers.createLootTableSerializer().create();
                            for (var route : idx.routes) {
                                if (!possible.contains(route.structure)
                                        || kind.equals("structure_chests")
                                                && !target.equals(route.structure)) continue;
                                if (kind.equals("chest_sources")
                                        && !contains(
                                                route.table,
                                                target,
                                                tags,
                                                active,
                                                json,
                                                memo,
                                                new HashSet<>(),
                                                skipped)) continue;
                                var r = row("structure", route.structure);
                                if (!route.template.isEmpty())
                                    r.addProperty("template", route.template);
                                r.addProperty("loot_table", route.table);
                                r.addProperty("container", route.container);
                                // Return one row per source, even if the same chest occurs in
                                // dozens of rooms.
                                if (kind.equals("chest_sources")) {
                                    var key =
                                            List.of(route.structure, route.table, route.container);
                                    var first = groups.putIfAbsent(key, r);
                                    if (first != null) {
                                        first.addProperty(
                                                "variants", first.get("variants").getAsInt() + 1);
                                        continue;
                                    }
                                    r.addProperty("variants", 1);
                                }
                                rows.add(r);
                            }
                            var out =
                                    page(
                                            rows,
                                            page,
                                            "当前维度可生成结构中的容器候选。房间、处理器与掉落条件会影响实际结果；未识别的结构代码或动态掉落可能另有来源。用 loot_table 查池、条件和数量。");
                            out.addProperty("unreadable", idx.unreadable + skipped[0]);
                            out.addProperty("structures_without_template_root", idx.unsupported);
                            return out;
                        },
                        Util.ioPool());
    }

    private static JsonObject generation(MinecraftServer server,String target,int page) {
        var ops=RegistryOps.create(JsonOps.INSTANCE,server.registryAccess());
        var rows=new GameplayData.PageWindow(page);
        for(var level:server.getAllLevels()) {
            var source=level.getChunkSource();
            var availableBiomes=source.getGenerator().getBiomeSource().possibleBiomes();
            for(var holder:source.getGeneratorState().possibleStructureSets()) {
                var set=holder.value();
                int totalWeight=set.structures().stream().mapToInt(entry->entry.weight()).sum();
                for(var entry:set.structures()) {
                    var structure=entry.structure().value();
                    var key=entry.structure().unwrapKey();
                    if(key.isEmpty())continue;
                    String name=key.get().location().toString();
                    if(!target.equals("*")&&!name.contains(target))continue;
                    var biomes=structure.biomes().stream().filter(availableBiomes::contains)
                            .flatMap(b->b.unwrapKey().stream()).map(k->k.location().toString()).sorted().toList();
                    for(int i=0;i<biomes.size();i+=12) {
                        if(!rows.includeNext())continue;
                        var data=row("structure",name);
                        data.addProperty("dimension",level.dimension().location().toString());
                        holder.unwrapKey().ifPresent(k->data.addProperty("structure_set",k.location().toString()));
                        data.addProperty("world_generation_enabled",server.getWorldData().worldGenOptions().generateStructures());
                        data.addProperty("weight",entry.weight());
                        data.addProperty("pool_weight",totalWeight);
                        data.addProperty("generation_step",structure.step().getName());
                        var group=new JsonArray();
                        biomes.subList(i,Math.min(i+12,biomes.size())).forEach(group::add);
                        data.add("biomes",group);
                        StructurePlacement.CODEC.encodeStart(ops,set.placement()).result().ifPresent(v->data.add("placement",v));
                        Structure.DIRECT_CODEC.encodeStart(ops,structure).result().filter(JsonElement::isJsonObject).ifPresent(v->{
                            var definition=v.getAsJsonObject();
                            for(String field:List.of("type","start_height","project_start_to_heightmap","terrain_adaptation",
                                    "start_pool","max_distance_from_center","start_jigsaw_name","biome_radius","size")) {
                                if(definition.has(field))data.add(field,definition.get(field));
                            }
                        });
                        rows.entries.add(data);
                    }
                }
            }
        }
        return rows.result("本服维度生成器、结构集和群系的候选条件。起始高度指结构起点；地形与自定义生成器仍可筛选位置。");
    }

    private static boolean contains(
            String table,
            String target,
            Set<String> tags,
            Map<ResourceLocation, LootTable> active,
            Gson json,
            Map<String, Boolean> memo,
            Set<String> path,
            int[] skipped) {
        if (memo.containsKey(table)) return memo.get(table);
        if (!path.add(table)) return false;
        boolean hit = false;
        try {
            var value = active.get(ResourceLocation.tryParse(table));
            if (value != null) {
                var data = json.toJsonTree(value, LootTable.class).getAsJsonObject();
                for (var pool : array(data, "pools"))
                    if (entries(
                            array(pool.getAsJsonObject(), "entries"),
                            target,
                            tags,
                            active,
                            json,
                            memo,
                            path,
                            skipped)) {
                        hit = true;
                        break;
                    }
            }
        } catch (RuntimeException bad) {
            skipped[0]++;
        }
        path.remove(table);
        if (hit || path.isEmpty()) memo.put(table, hit);
        return hit;
    }

    private static boolean entries(
            JsonArray list,
            String target,
            Set<String> tags,
            Map<ResourceLocation, LootTable> active,
            Gson json,
            Map<String, Boolean> memo,
            Set<String> path,
            int[] skipped) {
        for (var value : list) {
            var e = value.getAsJsonObject();
            String type = str(e, "type"), name = str(e, "name");
            if (type.equals("minecraft:item") && target.equals(name)
                    || type.equals("minecraft:tag") && tags.contains(name)) return true;
            if (type.equals("minecraft:loot_table")
                    && contains(name, target, tags, active, json, memo, path, skipped)) return true;
            if (entries(array(e, "children"), target, tags, active, json, memo, path, skipped))
                return true;
        }
        return false;
    }

    static JsonArray array(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    static synchronized CompletableFuture<Index> cached(MinecraftServer server) {
        var rm = server.getResourceManager();
        var access = server.registryAccess();
        if (owner != rm || index == null) {
            owner = rm;
            index = CompletableFuture.supplyAsync(() -> build(rm, access), Util.ioPool());
        }
        return index;
    }

    private static Index build(ResourceManager rm, RegistryAccess access) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, access);
        var pools = new HashMap<String, Pool>();
        int bad = 0, unsupported = 0;
        for (var entry : access.registryOrThrow(Registries.TEMPLATE_POOL).entrySet())
            try {
                var obj =
                        StructureTemplatePool.DIRECT_CODEC
                                .encodeStart(ops, entry.getValue())
                                .getOrThrow(false, s -> {})
                                .getAsJsonObject();
                Set<String> templates = new TreeSet<>();
                for (var e : array(obj, "elements")) {
                    var w = e.getAsJsonObject();
                    if (w.get("weight").getAsInt() > 0)
                        templates(w.getAsJsonObject("element"), templates);
                }
                pools.put(
                        entry.getKey().location().toString(),
                        new Pool(templates, str(obj, "fallback")));
            } catch (RuntimeException unsupportedPool) {
                bad++;
            }
        var routes = new LinkedHashSet<Route>();
        var roots = new ArrayList<Root>();
        for (var e : access.registryOrThrow(Registries.STRUCTURE).entrySet())
            try {
                var def =
                        Structure.DIRECT_CODEC
                                .encodeStart(ops, e.getValue())
                                .getOrThrow(false, s -> {})
                                .getAsJsonObject();
                String pool = str(def, "start_pool");
                if (pool.isBlank()) {
                    // These vanilla pieces assign the table in code. Identify the implementation,
                    // since packs can replace a structure definition.
                    String id = e.getKey().location().toString();
                    var tables =
                            switch (e.getValue().getClass().getSimpleName()) {
                                case "MineshaftStructure" -> List.of("abandoned_mineshaft");
                                case "DesertPyramidStructure" -> List.of("desert_pyramid");
                                case "JungleTempleStructure" ->
                                        List.of("jungle_temple", "jungle_temple_dispenser");
                                case "BuriedTreasureStructure" -> List.of("buried_treasure");
                                case "StrongholdStructure" ->
                                        List.of(
                                                "stronghold_library",
                                                "stronghold_corridor",
                                                "stronghold_crossing");
                                case "NetherFortressStructure" -> List.of("nether_bridge");
                                default -> List.<String>of();
                            };
                    if (tables.isEmpty()
                            || !e.getValue()
                                    .getClass()
                                    .getPackageName()
                                    .equals(
                                            "net.minecraft.world.level.levelgen.structure.structures"))
                        unsupported++;
                    else
                        for (String table : tables)
                            routes.add(
                                    new Route(
                                            id,
                                            "",
                                            "minecraft:chests/" + table,
                                            table.equals("abandoned_mineshaft")
                                                    ? "minecraft:chest_minecart"
                                                    : table.endsWith("dispenser")
                                                            ? "minecraft:dispenser"
                                                            : "minecraft:chest"));
                    continue;
                }
                roots.add(
                        new Root(
                                e.getKey().location().toString(),
                                pool,
                                def.has("size") ? def.get("size").getAsInt() : 0));
            } catch (RuntimeException unsupportedStructure) {
                bad++;
            }
        var loaded = new HashMap<String, Template>();
        for (var root : roots) {
            var seen = new HashSet<String>();
            Set<String> frontier = Set.of(root.pool);
            for (int depth = 0; depth <= root.depth && !frontier.isEmpty(); depth++) {
                var next = new TreeSet<String>();
                var current = new TreeSet<>(frontier);
                if (depth > 0)
                    for (String id : frontier) {
                        var p = pools.get(id);
                        if (p != null && !p.fallback.isBlank()) current.add(p.fallback);
                    }
                for (String id : current) {
                    if (!seen.add(id)) continue;
                    var p = pools.get(id);
                    if (p == null) continue;
                    for (String name : p.templates) {
                        var t = loaded.get(name);
                        if (t == null) {
                            try {
                                t = template(rm, name);
                            } catch (IOException | RuntimeException err) {
                                bad++;
                                t = new Template(Set.of(), Set.of());
                            }
                            loaded.put(name, t);
                        }
                        for (String pair : t.tables) {
                            int at = pair.indexOf(' ');
                            routes.add(
                                    new Route(
                                            root.id,
                                            name,
                                            pair.substring(0, at),
                                            pair.substring(at + 1)));
                        }
                        next.addAll(t.next);
                    }
                }
                frontier = next;
            }
        }
        return new Index(
                routes.stream()
                        .sorted(
                                Comparator.comparing(Route::structure)
                                        .thenComparing(Route::table)
                                        .thenComparing(Route::template))
                        .toList(),
                bad,
                unsupported);
    }

    static void templates(JsonObject e, Set<String> out) {
        String id = str(e, "location");
        if (!id.isEmpty()) out.add(id);
        for (var v : array(e, "elements"))
            if (v.isJsonObject()) templates(v.getAsJsonObject(), out);
    }

    static Template template(ResourceManager rm, String name) throws IOException {
        var id = ResourceLocation.tryParse(name);
        if (id == null) throw new IOException();
        var next = new TreeSet<String>();
        var tables = new TreeSet<String>();
        CompoundTag root;
        try (var input = rm.open(id.withPath("structures/" + id.getPath() + ".nbt"));
                var data = new DataInputStream(new java.util.zip.GZIPInputStream(input))) {
            root = NbtIo.read(data, new NbtAccounter(64L * 1024 * 1024));
        }
        var palettes = new ArrayList<ListTag>();
        if (root.contains("palette")) palettes.add(root.getList("palette", 10));
        else for (var p : root.getList("palettes", 9)) palettes.add((ListTag) p);
        for (var tag : root.getList("blocks", 10)) {
            var block = (CompoundTag) tag;
            if (!block.contains("nbt", 10)) continue;
            var data = block.getCompound("nbt");
            int state = block.getInt("state");
            for (var palette : palettes) {
                String type = palette.getCompound(state).getString("Name");
                if (type.equals("minecraft:jigsaw") && data.contains("pool", 8))
                    next.add(data.getString("pool"));
                if (data.contains("LootTable", 8))
                    tables.add(data.getString("LootTable") + " " + type);
            }
        }
        return new Template(next, tables);
    }
}
