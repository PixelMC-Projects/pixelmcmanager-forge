package com.pixelmc.pixelmcmanager.api;

import static com.pixelmc.pixelmcmanager.api.GameplayData.*;

import com.google.gson.*;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Player-scoped, read-only server capability. No HTTP or client dependency. */
public final class PlayerGameplayApi {
    private static final List<String> ACTIONS =
            List.of(
                    "food_support",
                    "food_book_stats",
                    "structure_generation",
                    "structure_chests",
                    "chest_sources",
                    "event_outputs",
                    "trade_offers",
                    "merchant_offers",
                    "quest_search",
                    "quest_rewards",
                    "quest_detail",
                    "shop_balance",
                    "shop_categories",
                    "shop_entries",
                    "shop_detail",
                    "shop_quote",
                    "shop_item");

    public static int protocolVersion() {
        return 1;
    }

    public static String descriptor() {
        var r = row("providerId", "pixelmcmanager");
        var caps = new JsonArray();
        var cap = row("id", "gameplay.read");
        cap.addProperty("version", 1);
        cap.addProperty("audience", "self");
        cap.addProperty("maxPageSize", 8);
        var actions = new JsonArray();
        ACTIONS.forEach(actions::add);
        cap.add("actions", actions);
        caps.add(cap);
        r.add("capabilities", caps);
        return r.toString();
    }

    public static CompletableFuture<String> request(UUID requester, String action, String payload) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null
                || requester == null
                || (action == null || !ACTIONS.contains(action))
                || payload == null
                || payload.length() > 1400) return reply("invalid_request", "无效的玩法查询。");
        try {
            var args = JsonParser.parseString(payload).getAsJsonObject();
            if (!Set.of("target", "view", "page", "quantity").containsAll(args.keySet()))
                return reply("invalid_request", "不接受额外字段。");
            for (String key : List.of("target", "view")) {
                if (args.has(key)
                        && (!args.get(key).isJsonPrimitive()
                                || !args.getAsJsonPrimitive(key).isString()))
                    return reply("invalid_request", "查询条件需要文本。");
            }
            for (String key : List.of("page", "quantity")) {
                if (args.has(key)
                        && (!args.get(key).isJsonPrimitive()
                                || !args.getAsJsonPrimitive(key).isNumber()))
                    return reply("invalid_request", "页码和交易次数需要整数。");
            }
            if (args.has("quantity") && !action.equals("shop_quote"))
                return reply("invalid_request", "只有报价接受交易次数。");
            String target = args.has("target") ? args.get("target").getAsString() : "*",
                    view = args.has("view") ? args.get("view").getAsString() : "";
            int page = args.has("page") ? args.get("page").getAsBigDecimal().intValueExact() : 1;
            int count =
                    args.has("quantity")
                            ? args.get("quantity").getAsBigDecimal().intValueExact()
                            : 1;
            if (target.isBlank()
                    || target.length() > 200
                    || page < 1
                    || count < 1
                    || !validView(action, view)) return reply("invalid_request", "目标、数量、筛选或页码无效。");
            if (Set.of(
                                    "shop_item",
                                    "quest_rewards",
                                    "chest_sources",
                                    "structure_chests",
                                    "event_outputs")
                            .contains(action)
                    && (!target.contains(":") || ResourceLocation.tryParse(target) == null))
                return reply("invalid_request", "请提供完整的注册 ID。");
            return server.submit(
                            () -> {
                                var p = server.getPlayerList().getPlayer(requester);
                                if (p == null
                                        || p.hasDisconnected()
                                        || !PlayerHubApi.gameplayAdmitted(requester))
                                    return reply("unavailable", "当前连接无法读取玩家玩法数据。");
                                try {
                                    return query(server, p, action, target, view, count, page)
                                            .thenCompose(
                                                    r ->
                                                            server.submit(
                                                                    () -> {
                                                                        if (server.getPlayerList()
                                                                                                .getPlayer(
                                                                                                        requester)
                                                                                        != p
                                                                                || p
                                                                                        .hasDisconnected()
                                                                                || !PlayerHubApi
                                                                                        .gameplayAdmitted(
                                                                                                requester))
                                                                            return result(
                                                                                            "unavailable",
                                                                                            "玩家会话已结束。")
                                                                                    .toString();
                                                                        return r.toString();
                                                                    }));
                                } catch (ReflectiveOperationException
                                        | LinkageError
                                        | RuntimeException unavailable) {
                                    return reply("unavailable", "当前版本的玩法数据未能核实。");
                                }
                            })
                    .thenCompose(v -> v)
                    .exceptionally(err -> result("unavailable", "当前玩法数据未能读取。").toString());
        } catch (RuntimeException malformed) {
            return reply("invalid_request", "无效的玩法查询字段。");
        }
    }

    private static boolean validView(String kind, String view) {
        if (view.isEmpty()) return true;
        if (kind.equals("event_outputs")) return view.matches("[a-z][a-z0-9_]{0,63}");
        if (kind.equals("food_book_stats")) return view.equals("global");
        return (kind.equals("shop_entries") || kind.equals("shop_item"))
                && Set.of("buy", "sell", "unlocked", "locked", "buyable").contains(view);
    }

    static CompletableFuture<JsonObject> query(
            MinecraftServer server,
            ServerPlayer p,
            String kind,
            String target,
            String view,
            int quantity,
            int page)
            throws ReflectiveOperationException {
        if (kind.equals("event_outputs")) return EventOutputs.read(target, view, page);
        if (kind.startsWith("structure_") || kind.equals("chest_sources"))
            return StructureGameplay.read(server, kind, target, page);
        var r =
                switch (kind) {
                    case "food_support" ->
                            ModList.get().isLoaded("solcarrot")
                                    ? result("live", "已安装")
                                    : missing("solcarrot");
                    case "food_book_stats" ->
                            FoodGameplay.query(
                                    ResourceLocation.tryParse(target),
                                    page,
                                    p,
                                    !view.equals("global"));
                    case "trade_offers", "merchant_offers" ->
                            TradeGameplay.read(p, kind, target, page);
                    case "quest_detail", "quest_search", "quest_rewards" ->
                            QuestGameplay.read(p, kind, target, page);
                    case "shop_balance",
                                    "shop_categories",
                                    "shop_entries",
                                    "shop_item",
                                    "shop_detail",
                                    "shop_quote" ->
                            ShopGameplay.read(p, kind, target, view, quantity, page);
                    default -> result("unsupported", "未知玩法查询。");
                };
        return CompletableFuture.completedFuture(r);
    }

    private static CompletableFuture<String> reply(String status, String msg) {
        return CompletableFuture.completedFuture(result(status, msg).toString());
    }

    private PlayerGameplayApi() {}
}
