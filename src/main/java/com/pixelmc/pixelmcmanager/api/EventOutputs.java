package com.pixelmc.pixelmcmanager.api;

import static com.pixelmc.pixelmcmanager.api.EventOutputFlow.*;
import static com.pixelmc.pixelmcmanager.api.GameplayData.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** On-demand index of installed Forge event entrypoints and directional item operands. */
@Mod.EventBusSubscriber(modid = "pixelmcmanager")
public final class EventOutputs {
    private static final int MAX_ARCHIVES = 768;
    private static final int MAX_CLASS_BYTES = 768 * 1024;
    private static final long MAX_BYTES = 384L * 1024 * 1024;
    private static final int MAX_METHODS = 90_000;
    private static final int MAX_ROWS = 256;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_LINKS = 8192;
    private static final int MAX_GRAPH_EDGES = 250_000;
    private static final int CACHE_SIZE = 32;
    private static final long WORK_NANOS = TimeUnit.SECONDS.toNanos(7);
    private static final String FORGE_EVENT = "net/minecraftforge/event/";
    private static final ThreadPoolExecutor WORKER =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(4),
                    task -> {
                        var thread = new Thread(task, "PixelMC Manager event outputs");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final Map<Key, Report> CACHE = new LinkedHashMap<>(CACHE_SIZE, .75f, true);
    private static final Map<Key, CompletableFuture<Report>> PENDING = new HashMap<>();
    private static final Map<Item, Set<String>> VANILLA_FIELDS = new IdentityHashMap<>();
    private static long generation;

    record Source(List<String> mods, Path jar) {
        String id(String preferred, String requestedNamespace) {
            if (mods.contains(preferred)) return preferred;
            return mods.contains(requestedNamespace) ? requestedNamespace : mods.get(0);
        }
    }

    private record Key(String target, String preferred) {}

    private record MethodKey(String owner, String name, String descriptor) {
        String label() {
            return owner.replace('/', '.') + "#" + name;
        }
    }

    private record MethodInfo(Source source, String event, int roles, List<MethodKey> calls) {}

    private record Reach(MethodKey method, int depth) {}

    record Report(List<JsonObject> rows, JsonObject coverage) {}

    static CompletableFuture<JsonObject> read(String value, String preferred, int page) {
        var id = ResourceLocation.tryParse(value);
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) {
            return CompletableFuture.completedFuture(response("not_found", value, "当前未注册此物品。"));
        }
        var candidates = new HashSet<String>();
        if (id.getNamespace().equals("minecraft"))
            candidates.addAll(vanillaFields(ForgeRegistries.ITEMS.getValue(id)));
        boolean composite =
                id.toString().equals(CompositeEventProof.OUTPUT)
                        && ForgeRegistries.ITEMS.containsKey(
                                ResourceLocation.tryParse(CompositeEventProof.TOOL))
                        && ForgeRegistries.BLOCKS.containsKey(id);
        var target =
                new Target(id.toString(), id.getNamespace(), id.getPath(), Set.copyOf(candidates));
        var key = new Key(value, preferred);
        long epoch;
        CompletableFuture<Report> work;
        synchronized (CACHE) {
            epoch = generation;
            var cached = CACHE.get(key);
            if (cached != null) return CompletableFuture.completedFuture(format(key, cached, page));
            work = PENDING.get(key);
            if (work == null) {
                var sources = sources(preferred, id.getNamespace());
                work = new CompletableFuture<>();
                var queued = work;
                long deadline = System.nanoTime() + WORK_NANOS;
                try {
                    WORKER.execute(
                            () ->
                                    complete(
                                            key, epoch, queued, sources, target, composite,
                                            deadline));
                    PENDING.put(key, queued);
                } catch (RejectedExecutionException busy) {
                    return CompletableFuture.completedFuture(
                            response("busy", value, "事件证据扫描队列繁忙。"));
                }
            }
        }
        return work.copy()
                .orTimeout(9, TimeUnit.SECONDS)
                .handle(
                        (result, error) -> {
                            synchronized (CACHE) {
                                if (epoch != generation)
                                    return response("unavailable", value, "数据已重载，请重新查询事件证据。");
                            }
                            if (error != null) {
                                boolean timeout =
                                        error instanceof java.util.concurrent.TimeoutException;
                                return response(
                                        timeout ? "timeout" : "unavailable",
                                        value,
                                        timeout ? "扫描未能在时限内返回。" : "事件证据暂时无法读取。");
                            }
                            return format(key, result, page);
                        });
    }

    private static void complete(
            Key key,
            long epoch,
            CompletableFuture<Report> future,
            List<Source> sources,
            Target target,
            boolean composite,
            long deadline) {
        try {
            var report = scan(sources, target, key.preferred, composite, deadline);
            synchronized (CACHE) {
                if (epoch == generation && report.coverage.get("scannedArchives").getAsInt() > 0) {
                    CACHE.put(key, report);
                    if (CACHE.size() > CACHE_SIZE) CACHE.remove(CACHE.keySet().iterator().next());
                }
            }
            future.complete(report);
        } catch (RuntimeException | LinkageError failure) {
            future.completeExceptionally(failure);
        } finally {
            synchronized (CACHE) {
                PENDING.remove(key, future);
            }
        }
    }

    private static List<Source> sources(String preferred, String namespace) {
        var sources = new ArrayList<Source>();
        ModList.get()
                .getModFiles()
                .forEach(
                        file -> {
                            var ids =
                                    file.getMods().stream()
                                            .map(info -> info.getModId())
                                            .sorted()
                                            .toList();
                            if (!ids.isEmpty() && !ids.equals(List.of("minecraft")))
                                sources.add(new Source(ids, file.getFile().getFilePath()));
                        });
        sources.sort(
                Comparator.comparingInt(
                                (Source source) ->
                                        source.mods.contains(preferred)
                                                ? 0
                                                : source.mods.contains(namespace) ? 1 : 2)
                        .thenComparing(source -> source.mods.get(0)));
        return List.copyOf(sources);
    }

    private static Set<String> vanillaFields(Item item) {
        if (VANILLA_FIELDS.isEmpty()) {
            for (var field : Items.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && Item.class.isAssignableFrom(field.getType())) {
                    try {
                        VANILLA_FIELDS
                                .computeIfAbsent((Item) field.get(null), unused -> new HashSet<>())
                                .add(Type.getInternalName(Items.class) + "." + field.getName());
                    } catch (IllegalAccessException ignored) {
                        // A non-readable constant is not an operand alias.
                    }
                }
            }
            VANILLA_FIELDS
                    .computeIfAbsent(Items.SPAWNER, unused -> new HashSet<>())
                    .addAll(
                            Set.of(
                                    "net/minecraft/world/item/Items.SPAWNER",
                                    "net/minecraft/world/item/Items.f_42007_"));
        }
        return VANILLA_FIELDS.getOrDefault(item, Set.of());
    }

    private static final class Budget {
        final long deadline;
        int archives;
        int classes;
        int analyzed;
        int skipped;
        int failedClasses;
        int failedMethods;
        int failedArchives;
        long inflated;
        String stop = "";
        final List<String> failures = new ArrayList<>();

        Budget(long deadline) {
            this.deadline = deadline;
        }

        boolean running() {
            if (!stop.isEmpty()) return false;
            if (System.nanoTime() - deadline >= 0) stop = "timeout";
            else if (inflated >= MAX_BYTES) stop = "archive_bytes_limit";
            return stop.isEmpty();
        }
    }

    static Report scan(
            List<Source> sources,
            Target requested,
            String preferred,
            boolean composite,
            long deadline) {
        var budget = new Budget(deadline - TimeUnit.MILLISECONDS.toNanos(250));
        var fields = new HashSet<>(requested.fields());
        if (!requested.namespace().equals("minecraft")) {
            for (var source : sources) {
                if (source.mods.contains(requested.namespace())) {
                    aliases(source, requested, fields, budget);
                    break;
                }
            }
        }
        var target =
                new Target(
                        requested.id(),
                        requested.namespace(),
                        requested.path(),
                        Set.copyOf(fields));
        var methods = new LinkedHashMap<MethodKey, MethodInfo>();
        var verified = new LinkedHashMap<MethodKey, JsonObject>();
        for (var source : sources) {
            if (!budget.running()) break;
            if (budget.archives >= MAX_ARCHIVES) {
                budget.stop = "archive_count_limit";
                break;
            }
            try (var zip = new ZipFile(source.jar.toFile())) {
                budget.archives++;
                boolean proofAllowed =
                        composite
                                && source.mods.contains("composite_material")
                                && compositeToolBinding(zip);
                var entries = zip.entries();
                while (entries.hasMoreElements() && budget.running()) {
                    var entry = entries.nextElement();
                    if (!classEntry(entry.getName())) continue;
                    budget.classes++;
                    if (entry.getSize() < 1 || entry.getSize() > MAX_CLASS_BYTES) {
                        budget.skipped++;
                        continue;
                    }
                    try (var stream = zip.getInputStream(entry)) {
                        byte[] bytes = stream.readNBytes(MAX_CLASS_BYTES + 1);
                        budget.inflated += bytes.length;
                        if (bytes.length > MAX_CLASS_BYTES) {
                            budget.skipped++;
                            continue;
                        }
                        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
                        if (!relevant(pool, target)) continue;
                        var type = readClass(bytes);
                        budget.analyzed++;
                        for (var method : type.methods) {
                            if (methods.size() >= MAX_METHODS) {
                                budget.stop = "method_limit";
                                break;
                            }
                            var key = new MethodKey(type.name, method.name, method.desc);
                            var event = event(method);
                            if (proofAllowed && CompositeEventProof.matches(type, method)) {
                                verified.putIfAbsent(key, CompositeEventProof.fact(key.label()));
                            }
                            int roles;
                            try {
                                roles = EventOutputFlow.roles(type.name, method, target);
                            } catch (AnalyzerException | RuntimeException unsupported) {
                                budget.failedMethods++;
                                roles = REFERENCED;
                            }
                            var calls = calls(method);
                            if (event != null || roles != 0 || !calls.isEmpty()) {
                                methods.putIfAbsent(
                                        key, new MethodInfo(source, event, roles, calls));
                            }
                        }
                    } catch (IOException | RuntimeException malformed) {
                        budget.failedClasses++;
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                budget.failedArchives++;
                if (budget.failures.size() < 16)
                    budget.failures.add(source.id(preferred, target.namespace()));
            }
        }
        var rows = new ArrayList<JsonObject>(verified.values());
        boolean rowLimit =
                candidates(methods, verified.keySet(), target, preferred, rows, deadline);
        rows.sort(
                Comparator.comparingInt((JsonObject v) -> order(v, preferred))
                        .thenComparing(v -> v.get("handler").getAsString())
                        .thenComparing(v -> v.has("method") ? v.get("method").getAsString() : ""));
        var coverage = row("scope", "installed_forge_event_bytecode");
        coverage.addProperty("eligibleArchives", sources.size());
        coverage.addProperty("scannedArchives", budget.archives);
        coverage.addProperty("examinedClasses", budget.classes);
        coverage.addProperty("analyzedClasses", budget.analyzed);
        coverage.addProperty("skippedClasses", budget.skipped);
        coverage.addProperty("failedClasses", budget.failedClasses);
        coverage.addProperty("failedMethods", budget.failedMethods);
        coverage.addProperty("failedArchiveCount", budget.failedArchives);
        var failures = new JsonArray();
        budget.failures.forEach(failures::add);
        coverage.add("failedArchives", failures);
        coverage.addProperty(
                "partial",
                !budget.stop.isEmpty()
                        || rowLimit
                        || !failures.isEmpty()
                        || budget.skipped > 0
                        || budget.failedClasses > 0
                        || budget.failedMethods > 0);
        if (!budget.stop.isEmpty()) coverage.addProperty("limitedBy", budget.stop);
        else if (rowLimit) coverage.addProperty("limitedBy", "call_graph_or_result_limit");
        coverage.addProperty("dynamicSourcesUnresolved", true);
        return new Report(List.copyOf(rows), coverage);
    }

    private static boolean candidates(
            Map<MethodKey, MethodInfo> methods,
            Set<MethodKey> verified,
            Target target,
            String preferred,
            List<JsonObject> rows,
            long deadline) {
        var incoming = new HashMap<MethodKey, List<MethodKey>>();
        int edges = 0;
        boolean limited = false;
        for (var entry : methods.entrySet()) {
            if (System.nanoTime() - deadline >= 0) {
                limited = true;
                break;
            }
            for (var call : entry.getValue().calls)
                if (methods.containsKey(call)) {
                    incoming.computeIfAbsent(call, unused -> new ArrayList<>()).add(entry.getKey());
                    if (++edges >= MAX_GRAPH_EDGES) break;
                }
            if (edges >= MAX_GRAPH_EDGES) {
                limited = true;
                break;
            }
        }
        int links = 0;
        for (var evidence : methods.entrySet()) {
            if (evidence.getValue().roles == 0) continue;
            var pending = new ArrayDeque<Reach>();
            var visited = new HashSet<MethodKey>();
            pending.add(new Reach(evidence.getKey(), 0));
            while (!pending.isEmpty()) {
                if (rows.size() >= MAX_ROWS
                        || ++links >= MAX_LINKS
                        || System.nanoTime() - deadline >= 0) return true;
                var root = pending.remove();
                if (!visited.add(root.method)) continue;
                var info = methods.get(root.method);
                if (info.event != null && !verified.contains(root.method)) {
                    rows.add(
                            candidate(
                                    root.method,
                                    info,
                                    new Reach(evidence.getKey(), root.depth),
                                    evidence.getValue(),
                                    target,
                                    preferred));
                }
                if (root.depth < MAX_DEPTH)
                    for (var caller : incoming.getOrDefault(root.method, List.of())) {
                        pending.add(new Reach(caller, root.depth + 1));
                    }
            }
        }
        return limited;
    }

    private static void aliases(Source source, Target target, Set<String> fields, Budget budget) {
        try (var zip = new ZipFile(source.jar.toFile())) {
            var files = zip.entries();
            long remaining = 16L * 1024 * 1024;
            while (files.hasMoreElements() && remaining > 0 && budget.running()) {
                var next = files.nextElement();
                if (!classEntry(next.getName())
                        || next.getSize() < 1
                        || next.getSize() > MAX_CLASS_BYTES) continue;
                try (var stream = zip.getInputStream(next)) {
                    byte[] code = stream.readNBytes(MAX_CLASS_BYTES + 1);
                    remaining -= code.length;
                    budget.inflated += code.length;
                    String pool = new String(code, StandardCharsets.ISO_8859_1);
                    if (!pool.contains("net/minecraftforge/registries/DeferredRegister")
                            || !pool.contains(target.path())) continue;
                    var type = readClass(code);
                    for (var method : type.methods) findAliases(method, target, fields);
                } catch (IOException | RuntimeException unsupported) {
                    budget.failedClasses++;
                }
            }
            if (remaining <= 0) budget.skipped++;
        } catch (IOException | RuntimeException ignored) {
            // The full scan reports unreadable archives in its coverage metadata.
        }
    }

    private static void findAliases(MethodNode method, Target target, Set<String> fields) {
        if (!method.name.equals("<clinit>")) return;
        var ops =
                java.util.Arrays.stream(method.instructions.toArray())
                        .filter(op -> op.getOpcode() >= 0)
                        .toList();
        for (int i = 2; i + 1 < ops.size(); i++) {
            if (ops.get(i) instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraftforge/registries/DeferredRegister")
                    && call.name.equals("register")
                    && call.desc.startsWith("(Ljava/lang/String;")
                    && ops.get(i - 1) instanceof InvokeDynamicInsnNode
                    && ops.get(i - 2) instanceof LdcInsnNode literal
                    && target.path().equals(literal.cst)
                    && ops.get(i + 1) instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.desc.equals("Lnet/minecraftforge/registries/RegistryObject;")) {
                fields.add(field.owner + "." + field.name);
            }
        }
    }

    private static boolean compositeToolBinding(ZipFile zip) throws IOException {
        var entry = zip.getEntry(CompositeEventProof.TOOL_REGISTRY + ".class");
        if (entry == null || entry.getSize() > MAX_CLASS_BYTES) return false;
        try (var in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
            if (bytes.length > MAX_CLASS_BYTES) return false;
            var fields = new HashSet<String>();
            var target =
                    new Target(
                            CompositeEventProof.TOOL,
                            "composite_material",
                            "dungeon_pickaxe",
                            Set.of());
            for (var method : readClass(bytes).methods) findAliases(method, target, fields);
            return fields.contains(
                    CompositeEventProof.TOOL_REGISTRY + "." + CompositeEventProof.TOOL_FIELD);
        }
    }

    private static boolean classEntry(String name) {
        return name.endsWith(".class") && !name.startsWith("META-INF/");
    }

    private static ClassNode readClass(byte[] bytes) {
        var type = new ClassNode();
        new ClassReader(bytes).accept(type, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return type;
    }

    private static boolean relevant(String pool, Target target) {
        return pool.contains(FORGE_EVENT)
                || pool.contains("net/minecraft/world/item/ItemStack")
                || pool.contains(target.id())
                || pool.contains(target.path())
                || target.fields().stream()
                        .anyMatch(
                                field ->
                                        pool.contains(field.substring(field.lastIndexOf('.') + 1)));
    }

    private static String event(MethodNode method) {
        var args = Type.getArgumentTypes(method.desc);
        if (!method.desc.endsWith(")V") || args.length != 1 || args[0].getSort() != Type.OBJECT)
            return null;
        String name = args[0].getInternalName();
        if (!name.startsWith(FORGE_EVENT)) return null;
        String event = name.substring(name.lastIndexOf('/') + 1);
        return event.startsWith("BlockEvent$")
                        || event.startsWith("PlayerInteractEvent$")
                        || Set.of(
                                        "LivingDropsEvent",
                                        "LivingDeathEvent",
                                        "AnvilRepairEvent",
                                        "ItemCraftedEvent",
                                        "ItemTossEvent")
                                .contains(event)
                ? event
                : null;
    }

    private static List<MethodKey> calls(MethodNode method) {
        var calls = new ArrayList<MethodKey>();
        for (var insn : method.instructions) {
            if (calls.size() >= 64) break;
            if (insn instanceof MethodInsnNode call && modOwner(call.owner)) {
                calls.add(new MethodKey(call.owner, call.name, call.desc));
            } else if (insn instanceof InvokeDynamicInsnNode dynamic
                    && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                for (Object argument : dynamic.bsmArgs)
                    if (argument instanceof Handle handle && modOwner(handle.getOwner())) {
                        calls.add(
                                new MethodKey(
                                        handle.getOwner(), handle.getName(), handle.getDesc()));
                    }
            }
        }
        return List.copyOf(calls);
    }

    private static boolean modOwner(String owner) {
        return !owner.startsWith("net/minecraft/")
                && !owner.startsWith("net/minecraftforge/")
                && !owner.startsWith("java/")
                && !owner.startsWith("com/google/");
    }

    private static JsonObject candidate(
            MethodKey root,
            MethodInfo info,
            Reach step,
            MethodInfo leaf,
            Target target,
            String preferred) {
        var out = row("confidence", "candidate");
        out.addProperty("sourceMod", info.source.id(preferred, target.namespace()));
        out.addProperty("event", info.event);
        out.addProperty("handler", shortText(root.label()));
        out.addProperty("handlerDescriptor", shortText(root.descriptor));
        if (step.depth > 0) {
            out.addProperty("method", shortText(step.method.label()));
            out.addProperty("methodDescriptor", shortText(step.method.descriptor));
            out.addProperty("callDepth", step.depth);
            out.addProperty("methodMod", leaf.source.id(preferred, target.namespace()));
        }
        var roles = new JsonArray();
        if ((leaf.roles & OUTPUT) != 0) roles.add("output_candidate");
        if ((leaf.roles & CONSUMED) != 0) roles.add("consumed");
        if ((leaf.roles & CHECKED) != 0) roles.add("checked");
        if (roles.isEmpty()) roles.add("referenced");
        out.addProperty("role", roles.get(0).getAsString());
        out.add("roles", roles);
        out.addProperty("conditionsVerified", false);
        return out;
    }

    private static int order(JsonObject value, String preferred) {
        if (value.get("confidence").getAsString().equals("verified")) return 0;
        int rank = value.get("sourceMod").getAsString().equals(preferred) ? 1 : 5;
        return rank
                + switch (value.get("role").getAsString()) {
                    case "output_candidate" -> 0;
                    case "consumed" -> 1;
                    case "checked" -> 2;
                    default -> 3;
                };
    }

    private static JsonObject format(Key key, Report report, int page) {
        var coverage = report.coverage.deepCopy();
        if (!key.preferred.isEmpty()) coverage.addProperty("preferredMod", key.preferred);
        JsonObject out;
        if (report.rows.isEmpty() && coverage.get("scannedArchives").getAsInt() == 0) {
            boolean timeout =
                    coverage.has("limitedBy")
                            && coverage.get("limitedBy").getAsString().equals("timeout");
            out = response(timeout ? "timeout" : "unavailable", key.target, "暂未读到可用的已安装模组事件证据。");
        } else {
            out =
                    page(
                            report.rows,
                            page,
                            report.rows.isEmpty()
                                    ? "本次扫描没有定位到该物品的事件证据。动态行为和未解析调用仍需另查。"
                                    : "已安装事件实现的方向证据。候选行的触发条件与产物仍需核实。");
            out.addProperty("target", key.target);
        }
        out.add("coverage", coverage);
        return out;
    }

    private static JsonObject response(String state, String target, String message) {
        var out = result(state, message);
        out.addProperty("target", target);
        return out;
    }

    @SubscribeEvent
    public static void reload(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) clear();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        clear();
    }

    private static void clear() {
        synchronized (CACHE) {
            generation++;
            CACHE.clear();
            PENDING.clear();
        }
    }

    private EventOutputs() {}
}
