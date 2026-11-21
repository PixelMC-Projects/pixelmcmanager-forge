package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonObject;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Server-thread snapshots of offers that were present during a villager trading session. */
@Mod.EventBusSubscriber(modid = "pixelmcmanager")
public final class MerchantOfferHistory {
    private static final int MAX_SESSIONS = 256;
    private static final int MAX_OFFERS = 128;
    private static final int SAMPLE_TICKS = 10;
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final Field MENU_TRADER =
            BlockGameplayStorage.field(MerchantMenu.class, "trader", "f_40027_");
    private static final Field VILLAGER_OFFERS =
            BlockGameplayStorage.field(AbstractVillager.class, "offers", "f_35261_");
    private static final String OFFERS_METHOD = offersMethod();
    private static final Map<ServerPlayer, OpenMenu> OPEN = new IdentityHashMap<>();
    private static final Map<ServerPlayer, Snapshot> RECENT = new LinkedHashMap<>();

    private static final class OpenMenu {
        final MerchantMenu menu;
        final AbstractVillager villager;
        int nextSample;
        Snapshot snapshot;

        OpenMenu(MerchantMenu menu, AbstractVillager villager) {
            this.menu = menu;
            this.villager = villager;
        }
    }

    record Snapshot(ServerLevel level, long captured, List<JsonObject> rows, boolean truncated) {
        long ageMs(long now) {
            return TimeUnit.NANOSECONDS.toMillis(now - captured);
        }
    }

    @SubscribeEvent
    public static void opened(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getContainer() instanceof MerchantMenu menu)
                || !connected(player)) return;
        RECENT.remove(player);
        OPEN.remove(player);
        if (OPEN.size() >= MAX_SESSIONS
                || MENU_TRADER == null
                || VILLAGER_OFFERS == null
                || OFFERS_METHOD == null) return;
        try {
            if (MENU_TRADER.get(menu) instanceof AbstractVillager villager
                    && villager.getClass().getMethod(OFFERS_METHOD).getDeclaringClass()
                            == AbstractVillager.class
                    && villager.getTradingPlayer() == player) {
                OPEN.put(player, new OpenMenu(menu, villager));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A version without the verified backing offers has no historical fallback.
        }
    }

    private static String offersMethod() {
        for (String name : List.of("getOffers", "m_6616_")) {
            try {
                if (AbstractVillager.class.getDeclaredMethod(name).getReturnType()
                        == MerchantOffers.class) return name;
            } catch (NoSuchMethodException ignored) {
                // Try the other mapping.
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        // No traversal of the player list. Only menus installed by the open event are sampled.
        int tick = event.getServer().getTickCount();
        var menus = OPEN.entrySet().iterator();
        while (menus.hasNext()) {
            var entry = menus.next();
            var player = entry.getKey();
            if (player.server != event.getServer()) continue;
            var state = entry.getValue();
            if (player.containerMenu != state.menu
                    || !connected(player)
                    || state.villager.getTradingPlayer() != player
                    || state.villager.level() != player.serverLevel()) {
                menus.remove();
                continue;
            }
            if (state.nextSample - tick > 0) continue;
            state.nextSample = tick + SAMPLE_TICKS;
            state.snapshot = snapshot(player, state.villager, now);
        }
        if (tick % 20 == 0)
            RECENT.values().removeIf(snapshot -> now - snapshot.captured() >= TTL_NANOS);
    }

    private static Snapshot snapshot(ServerPlayer player, AbstractVillager villager, long now) {
        try {
            // Merchant#getOffers can call updateTrades when the list has never been initialized.
            if (!(VILLAGER_OFFERS.get(villager) instanceof MerchantOffers offers)
                    || offers.isEmpty()) return null;
            int shown = Math.min(offers.size(), MAX_OFFERS);
            var rows = new ArrayList<JsonObject>(shown);
            for (int index = 0; index < shown; index++)
                rows.add(TradeGameplay.visibleOffer(offers.get(index)));
            return new Snapshot(
                    player.serverLevel(), now, List.copyOf(rows), shown < offers.size());
        } catch (IllegalAccessException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @SubscribeEvent
    public static void closed(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var state = OPEN.get(player);
        if (state == null || state.menu != event.getContainer()) return;
        OPEN.remove(player);
        // Close runs after Villager resets the player's special prices. Use only the earlier
        // capture.
        var seen = state.snapshot;
        if (seen == null || !connected(player) || seen.level() != player.serverLevel()) return;
        if (RECENT.size() >= MAX_SESSIONS) RECENT.remove(RECENT.keySet().iterator().next());
        RECENT.put(player, seen);
    }

    static Snapshot recent(ServerPlayer player, long now) {
        var snapshot = RECENT.get(player);
        if (snapshot == null) return null;
        if (!connected(player)
                || snapshot.level() != player.serverLevel()
                || now - snapshot.captured() >= TTL_NANOS) {
            RECENT.remove(player);
            return null;
        }
        return snapshot;
    }

    private static boolean connected(ServerPlayer player) {
        return !(player instanceof FakePlayer)
                && player.server.isSameThread()
                && player.isAlive()
                && !player.isSpectator()
                && !player.hasDisconnected()
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity());
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        clear(event.getEntity());
    }

    @SubscribeEvent
    public static void cloned(PlayerEvent.Clone event) {
        clear(event.getOriginal());
    }

    private static void clear(Object player) {
        OPEN.remove(player);
        RECENT.remove(player);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        OPEN.keySet().removeIf(player -> player.server == event.getServer());
        RECENT.keySet().removeIf(player -> player.server == event.getServer());
    }

    private MerchantOfferHistory() {}
}
