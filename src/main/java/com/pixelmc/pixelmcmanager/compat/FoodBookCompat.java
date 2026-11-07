package com.pixelmc.pixelmcmanager.compat;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;

/** Optional server-thread adapter. The SOL Carrot API never appears in a linked signature. */
public final class FoodBookCompat {
    private record Access(Method get, Method progress, Method invalidate, Field foodsEaten) {}
    private record Sample(ServerPlayer owner, int count) {}

    private final Predicate<UUID> admitted;
    private final Map<UUID, Sample> samples = new HashMap<>();
    private final Set<UUID> pending = new LinkedHashSet<>();
    private final Set<UUID> failed = new LinkedHashSet<>();
    private volatile MinecraftServer server;
    private Access access;
    private boolean installed;
    private String version = "";

    public FoodBookCompat(Predicate<UUID> admitted) {
        this.admitted = admitted;
    }

    public void start(MinecraftServer server) {
        clear();
        this.server = server;
        installed = ModList.get().isLoaded("solcarrot");
        if (!installed) return;
        version = ModList.get().getModContainerById("solcarrot")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("");
        try {
            Class<?> foodList = Class.forName("com.cazsius.solcarrot.tracking.FoodList");
            Class<?> progress = Class.forName("com.cazsius.solcarrot.tracking.ProgressInfo");
            access = new Access(foodList.getMethod("get", Player.class),
                    foodList.getMethod("getProgressInfo"), foodList.getMethod("invalidateProgressInfo"),
                    progress.getField("foodsEaten"));
        } catch (ReflectiveOperationException | LinkageError unsupportedVersion) {
            access = null;
        }
    }

    public void clear() {
        samples.clear();
        pending.clear();
        failed.clear();
        server = null;
        access = null;
        installed = false;
        version = "";
    }

    public void login(ServerPlayer player) {
        if (access != null && admitted.test(player.getUUID())) pending.add(player.getUUID());
    }

    public void logout(UUID player) {
        samples.remove(player);
        pending.remove(player);
        failed.remove(player);
    }

    public JsonObject capability() {
        JsonObject result = new JsonObject();
        result.addProperty("id", "solcarrot.effectiveFoods");
        result.addProperty("version", 1);
        result.addProperty("modVersion", version);
        result.addProperty("status", !installed ? "unsupported" : access == null || !failed.isEmpty() ? "unavailable" : "ready");
        return result;
    }

    /** Adds a previously captured value to the existing bridge checkpoint. No capability read here. */
    public void append(ServerPlayer player, JsonObject stats) {
        Sample sample = samples.get(player.getUUID());
        if (sample != null && sample.owner == player) stats.addProperty("effectiveFoods", sample.count);
    }

    private void capture(UUID id) {
        if (access == null || server == null || !admitted.test(id)) return;
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || player.hasDisconnected()) return;
        try {
            Object foods = access.get.invoke(null, player);
            // Match the book's shouldCount-filtered progress, including config/data-pack reloads.
            access.invalidate.invoke(foods);
            Object progress = access.progress.invoke(foods);
            int count = access.foodsEaten.getInt(progress);
            if (count < 0) throw new IllegalStateException("Invalid effective food count");
            samples.put(id, new Sample(player, count));
            failed.remove(id);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            samples.remove(id);
            failed.add(id);
        }
    }

    public void configurationReloaded(String modId) {
        MinecraftServer active = server;
        if (active == null || !modId.equals("solcarrot")) return;
        active.execute(() -> {
            if (server != active) return;
            for (ServerPlayer player : active.getPlayerList().getPlayers()) login(player);
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void consumed(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof ServerPlayer player) login(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void respawned(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) login(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void reloaded(OnDatapackSyncEvent event) {
        if (server == null || access == null) return;
        if (event.getPlayer() != null) login(event.getPlayer());
        else for (ServerPlayer player : server.getPlayerList().getPlayers()) login(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void command(CommandEvent event) {
        if (server == null || access == null || event.isCanceled()) return;
        String input = event.getParseResults().getReader().getString().stripLeading();
        if (input.startsWith("/")) input = input.substring(1);
        String root = input.split("\\s+", 2)[0];
        if (!root.equals("foodlist") && !root.equals("solcarrot:foodlist")) return;
        // Capture after the command finishes, including reset/add/remove operations.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) login(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void endTick(ServerTickEvent.Post event) {
        if (pending.isEmpty()) return;
        var iterator = pending.iterator();
        for (int count = 0; count < 16 && iterator.hasNext(); count++) {
            UUID id = iterator.next();
            iterator.remove();
            capture(id);
        }
    }
}
