package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

final class FoodGameplay {
    static final String MISSING = "当前服务器未安装生活调味料：胡萝卜版（solcarrot），无法查询这本食物手册。";

    static JsonObject query(
            ResourceLocation book,
            int page,
            net.minecraft.server.level.ServerPlayer player,
            boolean includeSelf) {
        if (!ModList.get().isLoaded("solcarrot"))
            return GameplayData.result("mod_not_installed", MISSING);
        if (book == null || !book.toString().equals("solcarrot:food_book"))
            return GameplayData.result("unsupported", "当前可核对的是已安装的生活调味料胡萝卜版食物手册。");
        if (page != 1) return GameplayData.result("invalid_page", "统计只有一页。");
        try {
            // The mod's common config API is available on dedicated servers. Never load its client
            // GUI classes here.
            var api = Class.forName("com.cazsius.solcarrot.SOLCarrotConfig");
            var qualifies = api.getMethod("shouldCount", Item.class);
            var eligible = new java.util.HashSet<Item>();
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                if (item.isEdible() && Boolean.TRUE.equals(qualifies.invoke(null, item)))
                    eligible.add(item);
            }
            var result =
                    GameplayData.result(
                            "live",
                            "当前已注册物品按手册的可食用、黑白名单及最低食物值规则计数。book_total 是手册分母；last_milestone 是奖励门槛。");
            result.addProperty("book_total", eligible.size());
            var milestones = (List<?>) api.getMethod("getMilestones").invoke(null);
            var values = milestones.stream().map(v -> ((Number) v).intValue()).sorted().toList();
            result.addProperty("milestone_count", values.size());
            if (!values.isEmpty()) {
                result.addProperty("first_milestone", values.get(0));
                result.addProperty("last_milestone", values.get(values.size() - 1));
                int step = values.size() > 1 ? values.get(1) - values.get(0) : 0;
                if (step > 0
                        && java.util.stream.IntStream.range(1, values.size())
                                .allMatch(i -> values.get(i) - values.get(i - 1) == step))
                    result.addProperty("milestone_step", step);
                result.addProperty(
                        "milestones_within_book_total",
                        values.stream()
                                .filter(v -> v <= result.get("book_total").getAsInt())
                                .count());
            }
            result.addProperty(
                    "minimum_food_value",
                    (Integer) api.getMethod("getMinimumFoodValue").invoke(null));
            result.addProperty(
                    "base_hearts", (Integer) api.getMethod("getBaseHearts").invoke(null));
            result.addProperty(
                    "hearts_per_milestone",
                    (Integer) api.getMethod("getHeartsPerMilestone").invoke(null));
            if (includeSelf) result.add("personal", personal(player, eligible, values));
            return result;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unsupported) {
            return GameplayData.result("unavailable", "当前手册实现或配置未能核对，无法报告总数。");
        }
    }

    private static JsonObject personal(
            net.minecraft.server.level.ServerPlayer player,
            java.util.Set<Item> eligible,
            List<Integer> milestones) {
        if (player == null || player.hasDisconnected())
            return GameplayData.result("unavailable", "当前没有可读取的提问玩家，个人进度未知。");
        try {
            Class<?> list = Class.forName("com.cazsius.solcarrot.tracking.FoodList");
            var foods =
                    list.getMethod("get", net.minecraft.world.entity.player.Player.class)
                            .invoke(null, player);
            var getItem =
                    Class.forName("com.cazsius.solcarrot.tracking.FoodInstance")
                            .getMethod("getItem");
            int collected = 0;
            int counted = com.pixelmc.pixelmcmanager.compat.FoodBookCompat.effectiveCount(foods);
            // Re-evaluate the recorded foods with the current filter, just as a newly built
            // ProgressInfo does.
            for (Object food : (java.util.Set<?>) list.getMethod("getEatenFoods").invoke(foods)) {
                Item item = (Item) getItem.invoke(food);
                if (eligible.contains(item)) collected++;
            }
            var self = new JsonObject();
            self.addProperty("status", "live");
            self.addProperty("collected", collected);
            self.addProperty("remaining", eligible.size() - collected);
            self.addProperty("milestone_foods_eaten", counted);
            int reached = 0;
            while (reached < milestones.size() && milestones.get(reached) <= counted) reached++;
            self.addProperty("milestones_reached", reached);
            if (reached < milestones.size()) {
                int next = milestones.get(reached);
                self.addProperty("next_milestone", next);
                self.addProperty("until_next", next - counted);
            }
            self.addProperty("has_next_milestone", reached < milestones.size());
            return self;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return GameplayData.result("unavailable", "无法读取提问玩家的有效品尝记录，个人进度未知。");
        }
    }

    private FoodGameplay() {}
}
