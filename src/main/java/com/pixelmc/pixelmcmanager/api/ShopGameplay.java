package com.pixelmc.pixelmcmanager.api;

import static com.pixelmc.pixelmcmanager.api.GameplayData.*;

import com.google.gson.JsonObject;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

final class ShopGameplay {
    private static final String ROOT = "net.sixik.sdmshoprework.";
    private static final String FTB = "dev.ftb.mods.ftbquests.quest.";
    private static final String CURRENCY = "basic_money";
    private static final String UNLOCKED = "unlocked";
    private static final String LOCKED = "locked";
    private static final String UNKNOWN = "unknown";
    private static final int UNLIMITED = -1;
    private static final int UNKNOWN_LIMIT = -2;

    private final ServerPlayer player;
    private final Object limits;
    private final MethodHandle sellerBalance;
    private final MethodHandle sellerValue;
    private final Map<String, String> questLocks = new HashMap<>();
    private final Map<InventoryKey, Integer> itemCounts = new HashMap<>();
    private Long moneyBalance;
    private Object questFile;
    private Object questTeam;
    private boolean checkedQuestTeam;

    private ShopGameplay(ServerPlayer player) throws ReflectiveOperationException {
        this.player = player;
        limits = Class.forName(ROOT + "common.data.LimiterData").getField("SERVER").get(null);
        var seller = Class.forName(ROOT + "api.shop.AbstractShopSellerType");
        var lookup = MethodHandles.publicLookup();
        // The seller class also declares client GUI methods. Resolve these common signatures
        // directly.
        sellerBalance =
                lookup.findVirtual(
                        seller, "getCount", MethodType.methodType(long.class, Player.class));
        sellerValue = lookup.findVirtual(seller, "getType", MethodType.methodType(Object.class));
    }

    static JsonObject read(
            ServerPlayer player, String kind, String target, String view, int count, int page)
            throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("sdmshoprework")) {
            return missing("SDM Shop（sdmshoprework）");
        }
        if (kind.equals("shop_balance")) {
            return personal(balance(player, target, page));
        }
        return personal(new ShopGameplay(player).query(kind, target, view, count, page));
    }

    private static JsonObject balance(ServerPlayer player, String target, int page)
            throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("sdm_economy")) {
            return missing("SDM Economy（sdm_economy）");
        }
        var helper = Class.forName("net.sixik.sdm_economy.api.CurrencyHelper");
        var keys = new ArrayList<String>();
        for (Object key : (Collection<?>) helper.getMethod("getAllCurrencyKeys").invoke(null)) {
            if (target.equals("*") || target.equals(key)) {
                keys.add(key.toString());
            }
        }
        keys.sort(String::compareTo);
        var getMoney = helper.getMethod("getMoney", Player.class, String.class);
        var rows = new PageWindow(page);
        for (String key : keys) {
            if (rows.includeNext()) {
                var entry = row("currency", key);
                putLong(entry, "balance", (long) getMoney.invoke(null, player, key));
                rows.entries.add(entry);
            }
        }
        return rows.result("本人各币种当前余额。");
    }

    private JsonObject query(String kind, String target, String view, int count, int page)
            throws ReflectiveOperationException {
        Object shop = Class.forName(ROOT + "common.shop.ShopBase").getField("SERVER").get(null);
        if (shop == null) {
            return result("unavailable", "商店尚未加载。");
        }
        boolean categories = kind.equals("shop_categories");
        boolean reverse = kind.equals("shop_item");
        boolean single = kind.equals("shop_detail") || kind.equals("shop_quote");
        ResourceLocation itemId = reverse ? ResourceLocation.tryParse(target) : null;
        Item item =
                itemId != null && ForgeRegistries.ITEMS.containsKey(itemId)
                        ? ForgeRegistries.ITEMS.getValue(itemId)
                        : null;
        if (reverse && item == null) {
            return result("invalid_request", "请用已注册的物品 ID 反查商店商品。");
        }
        if (single && !target.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            return result("invalid_request", "请用商店检索返回的交易 ID。");
        }
        String needle = target.toLowerCase(Locale.ROOT);
        int opaqueBuy = 0;
        int opaqueSell = 0;
        var rows = new PageWindow(page);
        for (Object tab : (Collection<?>) call(shop, "getShopTabs")) {
            UUID categoryId = (UUID) field(tab, "shopTabUUID");
            String categoryTitle = text(field(tab, "title"));
            boolean categoryMatch = matches(needle, categoryId.toString(), categoryTitle);
            var category = new Category(tab, categoryId, categoryTitle);
            var entries = (Collection<?>) call(tab, "getTabEntry");
            if (categories) {
                if (categoryMatch && matchesLock(view, category.lock()) && rows.includeNext()) {
                    var entry = row("id", categoryId.toString());
                    entry.addProperty("title", shortText(categoryTitle));
                    entry.addProperty("lock", category.lock());
                    entry.addProperty("entries", entries.size());
                    remaining(entry, "category_remaining", category.left());
                    rows.entries.add(entry);
                }
                continue;
            }

            for (Object entry : entries) {
                UUID entryId = (UUID) field(entry, "entryUUID");
                if (single && !entryId.toString().equals(needle)) {
                    continue;
                }
                boolean sell = (boolean) field(entry, "isSell");
                if (sell
                                && (reverse && !view.equals("sell")
                                        || view.equals("buy")
                                        || view.equals("buyable"))
                        || !sell && view.equals("sell")) {
                    continue;
                }
                var product = product(entry);
                if (reverse) {
                    if (!product.known()) {
                        if (sell) {
                            opaqueSell++;
                        } else {
                            opaqueBuy++;
                        }
                        continue;
                    }
                    if (!Objects.equals(itemId, product.id()) && !(sell && product.accepts(item))) {
                        continue;
                    }
                }
                String title = text(field(entry, "title"));
                if (title.isBlank() && !product.stack().isEmpty()) {
                    title = text(product.stack().getHoverName());
                }
                if (!single
                        && !reverse
                        && !categoryMatch
                        && !matches(
                                needle,
                                entryId.toString(),
                                Objects.toString(product.id(), ""),
                                title,
                                Objects.toString(product.tag(), ""))) {
                    continue;
                }

                boolean lockFilter = view.equals(LOCKED) || view.equals(UNLOCKED);
                boolean buyable = view.equals("buyable");
                String lock = lockFilter || buyable ? entryLock(category, entry, entryId) : null;
                if (lockFilter && !matchesLock(view, lock)) {
                    continue;
                }
                JsonObject projected =
                        buyable
                                ? entryView(
                                        category, entry, entryId, product, title, sell, count, lock)
                                : null;
                if (buyable && !projected.get("available_now").getAsString().equals("yes")) {
                    continue;
                }
                if (single || rows.includeNext()) {
                    if (projected == null) {
                        projected =
                                entryView(
                                        category, entry, entryId, product, title, sell, count,
                                        lock);
                    }
                    if (single) {
                        var details = new PageWindow(page);
                        for (Object line : (Collection<?>) field(entry, "descriptionList")) {
                            QuestGameplay.addText(details, "description", text(line));
                        }
                        var result = details.result("当前商品与报价；只读核算。");
                        result.add("entry", projected);
                        return result;
                    }
                    if (reverse && !Objects.equals(itemId, product.id())) {
                        projected.addProperty("matched_item", itemId.toString());
                    }
                    rows.entries.add(projected);
                }
            }
        }

        if (single) {
            return result("not_found", "当前商店未找到这个交易 ID。");
        }
        var result =
                rows.result(
                        categories ? "当前商店分区。" : "当前商品；buy_from_shop 为玩家购买，sell_to_shop 为商店回收。");
        if (reverse) {
            result.addProperty("unresolved_other_buy_entries", opaqueBuy);
            result.addProperty("unresolved_other_sell_entries", opaqueSell);
        }
        return result;
    }

    private Product product(Object entry) throws ReflectiveOperationException {
        Object type = call(entry, "getEntryType");
        if (type == null) {
            return new Product("unknown", ItemStack.EMPTY, null, null);
        }
        String className = type.getClass().getName();
        if (className.equals(ROOT + "common.shop.type.ShopItemEntryType")) {
            var stack = (ItemStack) field(type, "itemStack");
            ResourceLocation id =
                    stack.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(stack.getItem());
            return new Product(type.getClass().getSimpleName(), stack, id, null);
        }
        ResourceLocation tag =
                className.equals(ROOT + "common.shop.type.ShopTagEntryType")
                        ? (ResourceLocation) field(type, "tagKey")
                        : null;
        return new Product(type.getClass().getSimpleName(), ItemStack.EMPTY, null, tag);
    }

    private JsonObject entryView(
            Category category,
            Object entry,
            UUID id,
            Product product,
            String title,
            boolean sell,
            int count,
            String lock)
            throws ReflectiveOperationException {
        String currentLock = lock == null ? entryLock(category, entry, id) : lock;
        int entryLeft = left(entry, id, false);
        int categoryLeft = category.left();
        var output = row("id", id.toString());
        output.addProperty("title", shortText(title));
        output.addProperty("category", shortText(category.title));
        output.addProperty("category_id", category.id.toString());
        output.addProperty("direction", sell ? "sell_to_shop" : "buy_from_shop");
        output.addProperty("type", product.type());
        output.addProperty("lock", currentLock);
        if (product.id() != null) {
            output.addProperty("item", product.id().toString());
            if (product.stack().hasTag()) {
                output.addProperty("item_requires_matching_nbt", sell);
            }
        }
        if (product.tag() != null) {
            output.addProperty("accepted_tag", product.tag().toString());
        }
        remaining(output, "entry_remaining", entryLeft);
        remaining(output, "category_remaining", categoryLeft);
        int units = (int) field(entry, "entryCount");
        long price = (long) field(entry, "entryPrice");
        output.addProperty("units_per_trade", units);
        putLong(output, "price_per_trade", price);
        output.addProperty("trades", count);
        long totalUnits = (long) units * count;
        putLong(output, "total_units", totalUnits);
        Long totalPrice = multiplyPrice(price, count);
        if (totalPrice == null) {
            output.addProperty("quote_status", "amount_out_of_range");
        } else {
            putLong(output, "total_payment", totalPrice);
        }

        boolean insufficient = false;
        boolean verified = totalPrice != null && units > 0 && price >= 0;
        Object seller = field(entry, "shopSellerType");
        if (product.tag() != null && sell) {
            // Tag-based sales in this version pay SDM's base wallet directly.
            output.addProperty("currency", CURRENCY);
        } else {
            verified &= payment(output, seller, sell, totalPrice);
            insufficient =
                    output.has("funds_sufficient")
                            && !output.get("funds_sufficient").getAsBoolean();
        }
        if (sell && (product.id() != null || product.tag() != null)) {
            Integer held = held(product);
            if (held != null) {
                output.addProperty("held", held);
                putLong(output, "short_items", Math.max(0, totalUnits - held));
                insufficient = held < totalUnits;
            }
            verified &= held != null && totalUnits <= Integer.MAX_VALUE;
        } else {
            verified &= product.id() != null;
        }

        boolean blocked =
                currentLock.equals(LOCKED)
                        || insufficient
                        || entryLeft >= 0 && entryLeft < count
                        || categoryLeft >= 0 && categoryLeft < count;
        if (blocked) {
            output.addProperty("available_now", "no");
        } else if (verified
                && currentLock.equals(UNLOCKED)
                && entryLeft != UNKNOWN_LIMIT
                && categoryLeft != UNKNOWN_LIMIT) {
            output.addProperty("available_now", "yes");
        } else {
            output.addProperty("available_now", UNKNOWN);
        }
        return output;
    }

    private boolean payment(JsonObject output, Object seller, boolean sell, Long total)
            throws ReflectiveOperationException {
        if (seller == null) {
            return false;
        }
        String type = seller.getClass().getName();
        Long balance;
        if (type.equals(ROOT + "common.shop.sellerType.MoneySellerType")) {
            output.addProperty("currency", CURRENCY);
            if (moneyBalance == null) {
                moneyBalance = (long) invoke(sellerBalance, seller, player);
            }
            balance = moneyBalance;
        } else if (type.equals(ROOT + "common.shop.sellerType.ItemSellerType")
                && invoke(sellerValue, seller) instanceof ItemStack payment
                && !payment.isEmpty()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(payment.getItem());
            if (id == null) {
                return false;
            }
            output.addProperty("payment_item", id.toString());
            output.addProperty("payment_requires_matching_nbt", payment.hasTag());
            balance = (long) countItems(payment);
            if (sell && total != null && total > Integer.MAX_VALUE) {
                return false;
            }
        } else {
            output.addProperty("payment_type", seller.getClass().getSimpleName());
            return false;
        }
        putLong(output, "balance", balance);
        if (!sell && total != null && total >= 0) {
            output.addProperty("funds_sufficient", balance >= total);
            putShortPayment(output, total, balance);
        }
        return true;
    }

    private Integer held(Product product) {
        if (product.id() != null) {
            return countItems(product.stack());
        }
        TagKey<Item> tag = TagKey.create(Registries.ITEM, product.tag());
        if (BuiltInRegistries.ITEM.getTag(tag).isEmpty()) {
            return null;
        }
        int count = 0;
        var inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (stack.is(tag)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItems(ItemStack match) {
        // Same item and NBT rules as SDMItemHelper. Count alone is irrelevant to matching.
        var key = new InventoryKey(match.getItem(), match.hasTag() ? match.getTag() : null);
        return itemCounts.computeIfAbsent(
                key,
                ignored -> {
                    int count = 0;
                    var inventory = player.getInventory();
                    for (int index = 0; index < inventory.getContainerSize(); index++) {
                        ItemStack stack = inventory.getItem(index);
                        if (!stack.isEmpty()
                                && (match.hasTag()
                                        ? ItemStack.isSameItemSameTags(stack, match)
                                        : ItemStack.isSameItem(stack, match))) {
                            count += stack.getCount();
                        }
                    }
                    return count;
                });
    }

    private String entryLock(Category category, Object entry, UUID entryId)
            throws ReflectiveOperationException {
        String categoryLock = category.lock();
        if (categoryLock.equals(LOCKED) || left(entry, entryId, false) == 0) {
            return LOCKED;
        }
        String own = conditionLock((Collection<?>) call(entry, "getEntryConditions"));
        if (own.equals(LOCKED)) {
            return LOCKED;
        }
        return categoryLock.equals(UNKNOWN) || own.equals(UNKNOWN) ? UNKNOWN : UNLOCKED;
    }

    private String conditionLock(Collection<?> conditions) throws ReflectiveOperationException {
        String state = UNLOCKED;
        for (Object condition : conditions) {
            if (!condition
                    .getClass()
                    .getName()
                    .equals(ROOT + "common.shop.condition.ShopFTBQuestsCondition")) {
                state = UNKNOWN;
                continue;
            }
            var field = condition.getClass().getDeclaredField("questID");
            field.setAccessible(true);
            var ids = ((Collection<?>) field.get(condition)).iterator();
            if (ids.hasNext()) {
                // This SDM release returns after the first ID, including an unresolved ID.
                String id = ids.next().toString();
                String lock = questLocks.get(id);
                if (lock == null) {
                    lock = questLock(id);
                    questLocks.put(id, lock);
                }
                if (lock.equals(LOCKED)) {
                    return LOCKED;
                }
                if (lock.equals(UNKNOWN)) {
                    state = UNKNOWN;
                }
            }
        }
        return state;
    }

    private String questLock(String id) throws ReflectiveOperationException {
        if (!checkedQuestTeam) {
            checkedQuestTeam = true;
            if (ModList.get().isLoaded("ftbquests")) {
                questFile = Class.forName(FTB + "ServerQuestFile").getField("INSTANCE").get(null);
                questTeam = QuestGameplay.playerTeam(questFile, player.getUUID());
            }
        }
        if (questFile == null || questTeam == null) {
            return UNKNOWN;
        }
        long code =
                (long)
                        Class.forName(FTB + "QuestObjectBase")
                                .getMethod("parseCodeString", String.class)
                                .invoke(null, id);
        Object quest = call(questFile, "getQuest", long.class, code);
        return quest == null
                        || (boolean)
                                call(
                                        questTeam,
                                        "isCompleted",
                                        Class.forName(FTB + "QuestObject"),
                                        quest)
                ? UNLOCKED
                : LOCKED;
    }

    private int left(Object product, UUID id, boolean tab) throws ReflectiveOperationException {
        int max = (int) field(product, "limit");
        if (max == 0) {
            return UNLIMITED;
        }
        if (max < 0 || limits == null) {
            return UNKNOWN_LIMIT;
        }
        Object owner =
                (boolean) field(product, "globalLimit")
                        ? field(limits, "defaul_")
                        : player.getUUID();
        var data = (Map<?, ?>) field(limits, tab ? "PLAYER_TAB_DATA" : "PLAYER_ENTRY_DATA");
        Object counters = data.get(owner);
        Object used = counters instanceof Map<?, ?> map ? map.get(id) : null;
        int already = used instanceof Number number ? number.intValue() : 0;
        return already < 0 ? UNKNOWN_LIMIT : Math.max(0, max - already);
    }

    private static boolean matches(String target, String... values) {
        if (target.equals("*")) {
            return true;
        }
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).contains(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesLock(String view, String lock) {
        return (!view.equals(UNLOCKED) && !view.equals(LOCKED)) || view.equals(lock);
    }

    private static void remaining(JsonObject output, String key, int remaining) {
        if (remaining >= 0) {
            output.addProperty(key, remaining);
        } else {
            output.addProperty(key, remaining == UNLIMITED ? "unlimited" : UNKNOWN);
        }
    }

    private static Long multiplyPrice(long price, int count) {
        try {
            return Math.multiplyExact(price, (long) count);
        } catch (ArithmeticException outOfRange) {
            return null;
        }
    }

    private static void putShortPayment(JsonObject output, long total, long balance) {
        try {
            putLong(output, "short_payment", Math.max(0, Math.subtractExact(total, balance)));
        } catch (ArithmeticException outOfRange) {
            output.addProperty(
                    "short_payment",
                    BigInteger.valueOf(total).subtract(BigInteger.valueOf(balance)).toString());
        }
    }

    private static Object invoke(MethodHandle handle, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error direct) {
            throw direct;
        } catch (Throwable failure) {
            throw new ReflectiveOperationException(failure);
        }
    }

    private final class Category {
        final Object tab;
        final UUID id;
        final String title;
        private String lock;
        private Integer remaining;

        Category(Object tab, UUID id, String title) {
            this.tab = tab;
            this.id = id;
            this.title = title;
        }

        int left() throws ReflectiveOperationException {
            if (remaining == null) {
                remaining = ShopGameplay.this.left(tab, id, true);
            }
            return remaining;
        }

        String lock() throws ReflectiveOperationException {
            if (lock == null) {
                lock =
                        left() == 0
                                ? LOCKED
                                : conditionLock((Collection<?>) call(tab, "getTabConditions"));
            }
            return lock;
        }
    }

    private record Product(
            String type, ItemStack stack, ResourceLocation id, ResourceLocation tag) {
        boolean known() {
            return id != null || tag != null;
        }

        boolean accepts(Item item) {
            if (tag == null) {
                return false;
            }
            return BuiltInRegistries.ITEM
                    .getHolder(BuiltInRegistries.ITEM.getId(item))
                    .filter(holder -> holder.is(TagKey.create(Registries.ITEM, tag)))
                    .isPresent();
        }
    }

    private record InventoryKey(Item item, CompoundTag tag) {}
}
