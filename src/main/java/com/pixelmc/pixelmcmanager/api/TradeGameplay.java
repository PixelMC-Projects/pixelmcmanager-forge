package com.pixelmc.pixelmcmanager.api;

import static com.pixelmc.pixelmcmanager.api.GameplayData.*;

import com.google.gson.*;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

final class TradeGameplay {
    static JsonObject read(ServerPlayer player, String kind, String target, int page) {
        if (kind.equals("merchant_offers")) return merchant(player, target, page);
        var rows = new ArrayList<JsonObject>();
        int unknown = 0;
        for (var entry :
                VillagerTrades.TRADES.entrySet().stream()
                        .sorted(
                                Comparator.comparing(
                                        e ->
                                                String.valueOf(
                                                        ForgeRegistries.VILLAGER_PROFESSIONS.getKey(
                                                                e.getKey()))))
                        .toList())
            unknown +=
                    pool(
                            String.valueOf(
                                    ForgeRegistries.VILLAGER_PROFESSIONS.getKey(entry.getKey())),
                            entry.getValue(),
                            target,
                            rows);
        unknown +=
                pool(
                        "minecraft:wandering_trader",
                        VillagerTrades.WANDERING_TRADER_TRADES,
                        target,
                        rows);
        var out = page(rows, page, "已加载的可解析报价池。职业等级或游商组别仅代表可能抽到，事件仍可能改写实际报价。");
        out.addProperty("unresolved_factories", unknown);
        return out;
    }

    private static JsonObject merchant(ServerPlayer player, String target, int page) {
        var rows = new ArrayList<JsonObject>();
        if (player.containerMenu instanceof MerchantMenu menu) {
            for (MerchantOffer offer : menu.getOffers()) {
                var row = visibleOffer(offer);
                if (matches(row, target)) rows.add(row);
            }
            var opened = personal(page(rows, page, "本人当前打开商人的实际报价。"));
            opened.addProperty("provenance", "opened");
            return opened;
        }
        long now = System.nanoTime();
        var history = MerchantOfferHistory.recent(player, now);
        if (history == null) {
            return personal(result("unavailable", "当前未打开商人交易界面，也没有可用的近期报价。请打开商人的交易界面。"));
        }
        for (var row : history.rows()) {
            var copy = row.deepCopy();
            if (matches(copy, target)) rows.add(copy);
        }
        String message = "这是本次连接最近打开的商人报价快照，价格和库存可能已变化。重新打开交易界面可确认当前报价。";
        if (history.truncated()) message += "已达到快照报价条数上限。";
        var seen = personal(page(rows, page, message));
        if (seen.get("status").getAsString().equals("live")) {
            seen.addProperty("status", "last_seen");
            seen.addProperty("provenance", "last_seen");
            seen.addProperty("ageMs", history.ageMs(now));
            if (history.truncated()) seen.addProperty("truncated", true);
        }
        return seen;
    }

    static JsonObject visibleOffer(MerchantOffer offer) {
        var row = offer(offer, true);
        row.addProperty("sold_out", offer.isOutOfStock());
        row.addProperty("uses", offer.getUses());
        row.addProperty("max_uses", offer.getMaxUses());
        return row;
    }

    static int pool(
            String profession,
            Map<Integer, VillagerTrades.ItemListing[]> levels,
            String target,
            List<JsonObject> rows) {
        int unknown = 0;
        for (int level : new TreeSet<>(levels.keySet()))
            for (var factory : levels.get(level)) {
                String type = factory.getClass().getName();
                try {
                    JsonObject r;
                    if (type.equals(
                            "com.almostreliable.morejs.features.villager.trades.SimpleTrade")) {
                        var parent = factory.getClass().getSuperclass();
                        if (declared(factory, parent, "transformer") != null) {
                            unknown++;
                            continue;
                        }
                        r = new JsonObject();
                        for (String key : List.of("firstInput", "secondInput", "output")) {
                            var trade =
                                    declared(
                                            factory,
                                            key.equals("output") ? factory.getClass() : parent,
                                            key);
                            var stack = (ItemStack) declared(trade, trade.getClass(), "itemStack");
                            if (stack.isEmpty()) continue;
                            var s = stack(stack);
                            var range = declared(trade, trade.getClass(), "countRange");
                            s.remove("count");
                            s.addProperty("min", (Number) call(range, "getMin"));
                            s.addProperty("max", (Number) call(range, "getMax"));
                            r.add(
                                    key.equals("output")
                                            ? "gives"
                                            : key.equals("firstInput") ? "cost" : "second_cost",
                                    s);
                        }
                    } else if (type.equals("net.minecraftforge.common.BasicItemListing")
                            || type.startsWith("net.minecraft.world.entity.npc.VillagerTrades$")
                                    && Set.of(
                                                    "EmeraldForItems",
                                                    "ItemsForEmeralds",
                                                    "ItemsAndEmeraldsToItems")
                                            .contains(factory.getClass().getSimpleName())) {
                        MerchantOffer value = factory.getOffer(null, RandomSource.create(0));
                        if (value == null) {
                            unknown++;
                            continue;
                        }
                        r = offer(value, false);
                    } else {
                        unknown++;
                        continue;
                    }
                    if (!matches(r, target) && !target.equals(profession)) continue;
                    r.addProperty("merchant", profession);
                    r.addProperty(
                            profession.endsWith(":wandering_trader") ? "pool" : "level", level);
                    rows.add(r);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    unknown++;
                }
            }
        return unknown;
    }

    static Object declared(Object obj, Class<?> type, String key)
            throws ReflectiveOperationException {
        var f = type.getDeclaredField(key);
        f.setAccessible(true);
        return f.get(obj);
    }

    static JsonObject stack(ItemStack s) {
        var r = row("item", String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem())));
        r.addProperty("count", s.getCount());
        r.addProperty("name", shortText(s.getHoverName()));
        return r;
    }

    static JsonObject offer(MerchantOffer offer, boolean current) {
        var r = new JsonObject();
        r.add("gives", stack(offer.getResult()));
        r.add("cost", stack(current ? offer.getCostA() : offer.getBaseCostA()));
        if (!offer.getCostB().isEmpty()) r.add("second_cost", stack(offer.getCostB()));
        return r;
    }

    private static boolean matches(JsonObject r, String key) {
        if (key.equals("*")) return true;
        boolean pays = isItem(r, "cost", key) || isItem(r, "second_cost", key);
        boolean receives = isItem(r, "gives", key);
        if (!pays && !receives) return false;
        r.addProperty(
                "matched_as", pays && receives ? "both" : pays ? "player_pays" : "player_receives");
        return true;
    }

    private static boolean isItem(JsonObject row, String field, String id) {
        return row.has(field) && row.getAsJsonObject(field).get("item").getAsString().equals(id);
    }
}
