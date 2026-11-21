package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fml.ModList;

/** Read-only optional adapters for live machine recipes. Invoke on the game server thread. */
public final class RecipeGameplayApi {
    private static final String IMBUEMENT =
            "com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe";
    private static final String CUTTING =
            "vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe";

    public static int protocolVersion() {
        return 1;
    }

    public static String descriptor() {
        return PackGameplayApi.descriptor(
                "pack.recipes.read", 12, "extras", "detail", "cupConversions");
    }

    public record Use(String role, int slot, List<Item> items) {}

    public record Product(String role, Item item, int count, Number baseChance) {}

    public record Extras(List<Use> uses, List<Product> products) {}

    private record Imbuement(
            Ingredient input, ItemStack output, int source, List<Ingredient> pedestals) {}

    /** Returns null for recipes without a specialized output projection. */
    public static Extras extras(Recipe<?> recipe) throws ReflectiveOperationException {
        var type = recipe.getClass();
        if (type.getName().equals(IMBUEMENT)) {
            var imbuement = imbuement(recipe);
            var uses = new ArrayList<Use>();
            uses.add(use(imbuement.input(), "chamber", 0));
            for (int i = 0; i < imbuement.pedestals().size(); i++) {
                uses.add(use(imbuement.pedestals().get(i), "pedestal", i));
            }
            var stack = imbuement.output();
            var products =
                    stack.isEmpty()
                            ? List.<Product>of()
                            : List.of(
                                    new Product(
                                            "primary", stack.getItem(), stack.getCount(), null));
            return new Extras(List.copyOf(uses), products);
        }
        if (!type.getName().equals(CUTTING)) return null;
        var tool = (Ingredient) type.getMethod("getTool").invoke(recipe);
        // The result list preserves order, repeated items and independent base chances.
        var rolls = (List<?>) type.getMethod("getRollableResults").invoke(recipe);
        var products = new ArrayList<Product>();
        for (int i = 0; i < rolls.size(); i++) {
            var roll = rolls.get(i);
            var stack = (ItemStack) roll.getClass().getMethod("getStack").invoke(roll);
            var chance = (Number) roll.getClass().getMethod("getChance").invoke(roll);
            if (!stack.isEmpty()) {
                products.add(
                        new Product(
                                i == 0 ? "primary" : "additional",
                                stack.getItem(),
                                stack.getCount(),
                                chance));
            }
        }
        return new Extras(List.of(use(tool, "tool", 0)), List.copyOf(products));
    }

    private static Use use(Ingredient ingredient, String role, int slot) {
        var items =
                ingredient == Ingredient.EMPTY
                        ? List.<Item>of()
                        : Arrays.stream(ingredient.getItems())
                                .filter(stack -> !stack.isEmpty())
                                .map(ItemStack::getItem)
                                .distinct()
                                .toList();
        return new Use(role, slot, items);
    }

    /** Returns null where the caller should use its generic recipe inspector. */
    public static JsonObject detail(Recipe<?> recipe) throws ReflectiveOperationException {
        if (!recipe.getClass().getName().equals(IMBUEMENT)) return null;
        var imbuement = imbuement(recipe);
        var stack = imbuement.output();
        if (stack.isEmpty()) return null;
        var detail = GameplayData.row("id", recipe.getId().toString());
        detail.addProperty(
                "serializer",
                BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()).toString());
        detail.addProperty("machine", "ars_nouveau:imbuement_chamber");
        detail.add("chamber_input", imbuement.input().toJson());
        var pedestals = new JsonArray();
        imbuement.pedestals().forEach(value -> pedestals.add(value.toJson()));
        detail.add("pedestal_ingredients", pedestals);
        detail.addProperty("source_cost", imbuement.source());
        var output =
                GameplayData.row("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        output.addProperty("count", stack.getCount());
        if (stack.hasTag()) {
            String snbt = stack.getTag().toString();
            if (snbt.getBytes(StandardCharsets.UTF_8).length > 2048) return null;
            output.addProperty("snbt", snbt);
        }
        detail.add("result", output);
        if (detail.toString().getBytes(StandardCharsets.UTF_8).length > 8192) return null;
        var result = GameplayData.result("live", "从已加载的灌注配方实例读取。");
        result.add("recipe", detail);
        return result;
    }

    private static Imbuement imbuement(Recipe<?> recipe) throws ReflectiveOperationException {
        var type = recipe.getClass();
        var raw = (List<?>) type.getField("pedestalItems").get(recipe);
        return new Imbuement(
                (Ingredient) type.getField("input").get(recipe),
                (ItemStack) type.getField("output").get(recipe),
                type.getField("source").getInt(recipe),
                raw.stream().map(Ingredient.class::cast).toList());
    }

    public static JsonObject cupConversions(
            RecipeManager manager, RegistryAccess access, ResourceLocation id, int page) {
        if (!PackGameplayApi.valid(id, page))
            return GameplayData.result("invalid_request", "物品 ID 或页码无效。");
        var target = BuiltInRegistries.ITEM.getOptional(id);
        if (target.isEmpty()) return GameplayData.result("not_registered", "此物品未注册。");
        if (!ModList.get().isLoaded("miners_delight"))
            return GameplayData.result("unsupported", "当前适配的铜锅模组未安装。");
        try {
            var type = Class.forName("com.sammy.minersdelight.logic.CupConversionReloadListener");
            var conversions = (Map<?, ?>) type.getField("BOWL_TO_CUP").get(null);
            var rows = new JsonArray();
            // Only the cooking recipe type feeds the copper pot.
            var cooking =
                    BuiltInRegistries.RECIPE_TYPE.getOptional(
                            new ResourceLocation("farmersdelight", "cooking"));
            if (cooking.isEmpty()) return GameplayData.result("unavailable", "当前没有注册烹饪锅配方类型。");
            for (var recipe :
                    manager.getRecipes().stream()
                            .filter(entry -> entry.getType() == cooking.get())
                            .sorted(Comparator.comparing(Recipe::getId))
                            .toList()) {
                var original = recipe.getResultItem(access);
                if (original.isEmpty() || conversions.get(original.getItem()) != target.get())
                    continue;
                var row = GameplayData.row("recipe", recipe.getId().toString());
                row.addProperty(
                        "regular_output",
                        BuiltInRegistries.ITEM.getKey(original.getItem()).toString());
                row.addProperty("regular_count", original.getCount());
                row.addProperty("cup_output", id.toString());
                var ingredients = new JsonArray();
                for (var ingredient : recipe.getIngredients()) ingredients.add(ingredient.toJson());
                if (ingredients.toString().getBytes(StandardCharsets.UTF_8).length <= 2500)
                    row.add("ingredients", ingredients);
                rows.add(row);
            }
            var result =
                    PackGameplayApi.entriesPage(
                            rows, page, "仅覆盖矿工乐事铜锅。匹配当前烹饪配方与已加载的杯装映射；碗装产物是原配方输出，不是投入材料。");
            result.addProperty("machine", "miners_delight:copper_pot");
            result.addProperty("serving_container", "miners_delight:copper_cup");
            return result;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return GameplayData.result("unavailable", "当前版本的铜锅转换表未能完整读取。");
        }
    }

    private RecipeGameplayApi() {}
}
