/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Read adapters are explicit because an automation capability has no player authorization context.
 */
final class BlockGameplayStorage {
    private static final Set<Class<?>> CONTAINERS =
            Set.of(
                    ChestBlockEntity.class,
                    TrappedChestBlockEntity.class,
                    BarrelBlockEntity.class,
                    HopperBlockEntity.class,
                    DispenserBlockEntity.class,
                    DropperBlockEntity.class,
                    FurnaceBlockEntity.class,
                    BrewingStandBlockEntity.class,
                    SmokerBlockEntity.class,
                    BlastFurnaceBlockEntity.class,
                    ShulkerBoxBlockEntity.class);
    private static final Map<String, String> MOD_CONTAINERS =
            Map.of(
                    "vectorwing.farmersdelight.common.block.entity.CabinetBlockEntity",
                            "farmersdelight",
                    "earth.terrarium.handcrafted.common.blockentities.ContainerBlockEntity",
                            "handcrafted",
                    "com.mcwfurnitures.kikoz.storage.StorageTileEntity", "mcwfurnitures");
    private static final Field LOCK = field(BaseContainerBlockEntity.class, "lockKey", "f_58621_");
    private static final Field BEACON_LOCK = field(BeaconBlockEntity.class, "lockKey", "f_58655_");
    private static final Field LOOT =
            field(RandomizableContainerBlockEntity.class, "lootTable", "f_59605_");
    private static final String BACKPACK = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.";
    private static final String EXPANDED = "compasses.expandedstorage.";
    private static final Field SAVED_CACHE = field(DimensionDataStorage.class, "cache", "f_78144_");
    private static final int MAX_SLOTS = 512;

    record Inventory(
            String status,
            String source,
            String scope,
            int size,
            IntFunction<JsonObject> reader,
            String reasonCode,
            String message) {
        static Inventory unavailable(String message) {
            return new Inventory("unavailable", "", "", 0, null, "", message);
        }

        static Inventory forbidden(String reasonCode, String message) {
            return new Inventory("forbidden", "", "", 0, null, reasonCode, message);
        }

        boolean available() {
            return reader != null;
        }
    }

    static boolean supported(BlockEntity entity) {
        return entity != null
                && (containerType(entity) || backpack(entity) || expandedChest(entity));
    }

    static boolean containerType(BlockEntity entity) {
        return CONTAINERS.contains(entity.getClass())
                || entity instanceof RandomizableContainerBlockEntity
                        && MOD_CONTAINERS.containsKey(entity.getClass().getName());
    }

    static boolean backpack(BlockEntity entity) {
        return entity.getClass().getName().equals(BACKPACK + "BackpackBlockEntity");
    }

    private static boolean expandedChest(BlockEntity entity) {
        return entity.getClass().getName().equals(EXPANDED + "impl.block.entity.ChestBlockEntity");
    }

    /** Runs on the server thread immediately after the caller's access check. */
    static Inventory open(ServerPlayer player, WorldBlockGameplayApi.Target look) {
        BlockEntity entity = look.entity();
        if (!supported(entity)) return Inventory.unavailable("该方块暂无可验证的只读库存来源。");
        if (backpack(entity)) return backpackInventory(player, entity);
        if (expandedChest(entity)) return expandedInventory(look);
        var container = (BaseContainerBlockEntity) entity;
        var state = look.level().getBlockState(look.pos());
        if (!(state.getBlock() instanceof ChestBlock))
            return container(
                    MOD_CONTAINERS.getOrDefault(entity.getClass().getName(), "vanilla"), container);
        var half = ChestBlock.getBlockType(state);
        if (half == DoubleBlockCombiner.BlockType.SINGLE) return container("vanilla", container);
        var neighbor = otherHalf(look, state);
        if (neighbor == null) return Inventory.unavailable("无法核对双箱的另一半，库存范围未知。");
        Container first = half == DoubleBlockCombiner.BlockType.FIRST ? container : neighbor;
        Container second = first == container ? neighbor : container;
        if (first.getContainerSize() != 27 || second.getContainerSize() != 27)
            return Inventory.unavailable("双箱槽位范围未知。");
        return inventory(
                "vanilla",
                "doubleChest",
                54,
                index ->
                        slot(
                                index,
                                index < 27 ? first.getItem(index) : second.getItem(index - 27)));
    }

    private static Inventory container(String source, Container container) {
        return inventory(
                source,
                "selectedBlock",
                container.getContainerSize(),
                index -> slot(index, container.getItem(index)));
    }

    private static ChestBlockEntity otherHalf(WorldBlockGameplayApi.Target look, BlockState state) {
        BlockPos other = look.pos().relative(ChestBlock.getConnectedDirection(state));
        var level = look.level();
        if (!level.hasChunkAt(other)) return null;
        var adjacent = level.getBlockState(other);
        if (adjacent.getBlock() != state.getBlock()) return null;
        var side = ChestBlock.getBlockType(adjacent);
        if (side == DoubleBlockCombiner.BlockType.SINGLE
                || side == ChestBlock.getBlockType(state)
                || !other.relative(ChestBlock.getConnectedDirection(adjacent)).equals(look.pos()))
            return null;
        return WorldBlockGameplayApi.loadedEntity(level, other) instanceof ChestBlockEntity neighbor
                        && CONTAINERS.contains(neighbor.getClass())
                ? neighbor
                : null;
    }

    /**
     * Permissions and visibility only. This never asks a container or mod to open its inventory.
     */
    static Inventory access(ServerPlayer player, WorldBlockGameplayApi.Target look) {
        var entity = look.entity();
        if (entity == null) return null;
        if (expandedChest(entity)) return expandedAccess(player, look);
        Inventory refusal = nbtAccess(player, entity);
        var state = look.level().getBlockState(look.pos());
        if (entity instanceof ShulkerBoxBlockEntity box) {
            if (refusal != null) return refusal;
            if (!(state.getBlock() instanceof ShulkerBoxBlock))
                return Inventory.unavailable("潜影盒方块状态已改变。");
            if (box.getAnimationStatus() == ShulkerBoxBlockEntity.AnimationStatus.CLOSED) {
                var facing = state.getValue(ShulkerBoxBlock.FACING);
                var lid =
                        Shulker.getProgressDeltaAabb(facing, 0, .5f)
                                .move(look.pos())
                                .deflate(1.0E-6);
                if (!look.level().noCollision(lid))
                    return Inventory.forbidden("obstructed", "潜影盒的盖子被挡住了，当前无法打开。");
            }
            return null;
        }
        if (!containerType(entity) || !(state.getBlock() instanceof ChestBlock)) return refusal;
        if (ChestBlock.isChestBlockedAt(look.level(), look.pos()))
            return Inventory.forbidden("obstructed", "箱盖受阻，当前无法打开这个箱子。");
        if (refusal != null && refusal.status().equals("forbidden")) return refusal;
        if (ChestBlock.getBlockType(state) == DoubleBlockCombiner.BlockType.SINGLE) return refusal;
        var other = otherHalf(look, state);
        if (other == null) return Inventory.unavailable("无法核对双箱的另一半。");
        var decision =
                BlockClaimAccess.check(player, look.level(), other.getBlockPos(), look.face());
        if (decision == BlockClaimAccess.Decision.FORBIDDEN
                || !look.level().getWorldBorder().isWithinBounds(other.getBlockPos()))
            return Inventory.forbidden("claim", "当前无法访问双箱另一半所在位置。");
        if (decision == BlockClaimAccess.Decision.UNAVAILABLE)
            return Inventory.unavailable("无法核对双箱另一半的领地权限。");
        if (ChestBlock.isChestBlockedAt(look.level(), other.getBlockPos()))
            return Inventory.forbidden("obstructed", "双箱另一半的箱盖受阻。");
        var otherRefusal = containerAccess(player, other);
        if (otherRefusal != null && otherRefusal.status().equals("forbidden"))
            return Inventory.forbidden(otherRefusal.reasonCode(), "双箱另一半上锁，主手未持匹配钥匙。");
        return refusal != null ? refusal : otherRefusal;
    }

    private static Inventory containerAccess(
            ServerPlayer player, BaseContainerBlockEntity container) {
        try {
            if (LOCK == null
                    || container instanceof RandomizableContainerBlockEntity && LOOT == null)
                return Inventory.unavailable("无法核对容器锁和战利品状态。");
            if (!(LOCK.get(container) instanceof LockCode key))
                return Inventory.unavailable("无法核对容器锁。");
            if (!key.unlocksWith(player.getMainHandItem()))
                return Inventory.forbidden("locked", "容器上锁，主手未持匹配钥匙。");
            if (container instanceof RandomizableContainerBlockEntity
                    && LOOT.get(container) != null) return Inventory.unavailable("该容器的战利品尚未生成。");
            return null;
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            return Inventory.unavailable("无法核对容器的访问权限。");
        }
    }

    private static Inventory expandedAccess(
            ServerPlayer player, WorldBlockGameplayApi.Target look) {
        var entity = look.entity();
        var type = entity.getClass();
        var state = look.level().getBlockState(look.pos());
        var block = state.getBlock();
        if (!block.getClass().getName().equals(EXPANDED + "impl.block.ChestBlock"))
            return Inventory.unavailable("该扩展箱类型暂无已核对的只读库存。");
        try {
            Object lock = type.getMethod("getLockable").invoke(entity);
            if (lock == null
                    || !lock.getClass()
                            .getName()
                            .equals(EXPANDED + "impl.block.misc.BasicLockable"))
                return Inventory.unavailable("该扩展箱的锁定实现无法核对。");
            if (!Boolean.TRUE.equals(
                    type.getMethod("canBeUsedBy", ServerPlayer.class).invoke(entity, player)))
                return Inventory.forbidden("container_access", "当前无法打开这个扩展箱，请检查距离和箱锁。");
            if (!Boolean.FALSE.equals(
                    block.getClass()
                            .getMethod("isAccessBlocked", LevelAccessor.class, BlockPos.class)
                            .invoke(block, look.level(), look.pos())))
                return Inventory.forbidden("obstructed", "当前扩展箱的箱盖受阻。");
            if (!singleExpandedChest(state))
                return Inventory.unavailable("当前只支持核对扩展存储的单箱，双箱库存在此不可确认。");
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError inaccessible) {
            return Inventory.unavailable("无法核对当前扩展箱的访问权限。");
        }
    }

    private static Inventory expandedInventory(WorldBlockGameplayApi.Target look) {
        try {
            var entity = look.entity();
            if (entity.getClass().getMethod("getItems").invoke(entity) instanceof List<?> items)
                return inventory(
                        "expandedstorage",
                        "selectedBlock",
                        items.size(),
                        index ->
                                items.get(index) instanceof ItemStack stack
                                        ? slot(index, stack)
                                        : unknownSlot(index));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError inaccessible) {
            return Inventory.unavailable("当前扩展箱版本没有可核对的只读库存。");
        }
        return Inventory.unavailable("无法核对扩展箱库存。");
    }

    private static boolean singleExpandedChest(BlockState state) {
        for (var entry : state.getValues().entrySet()) {
            if (entry.getKey().getName().equals("type")
                    && entry.getValue() instanceof Enum<?> kind
                    && kind.getDeclaringClass().getName().equals(EXPANDED + "api.EsChestType"))
                return kind.name().equals("SINGLE");
        }
        return false;
    }

    private static Inventory nbtAccess(ServerPlayer player, BlockEntity entity) {
        if (containerType(entity))
            return containerAccess(player, (BaseContainerBlockEntity) entity);
        try {
            if (entity.getClass() == BeaconBlockEntity.class) {
                if (BEACON_LOCK == null) return Inventory.unavailable("无法核对信标锁。");
                if (!(BEACON_LOCK.get(entity) instanceof LockCode key))
                    return Inventory.unavailable("无法核对信标锁。");
                if (!key.unlocksWith(player.getMainHandItem()))
                    return Inventory.forbidden("locked", "信标上锁，主手未持匹配钥匙。");
            }
            return backpack(entity) ? backpackAccess(backpackTag(entity)) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError inaccessible) {
            return Inventory.unavailable("无法核对方块的数据访问权限。");
        }
    }

    static CompoundTag backpackTag(BlockEntity entity) throws ReflectiveOperationException {
        Object wrapper = wrapper(entity);
        return backpackStack(wrapper).getTag();
    }

    private static Object wrapper(BlockEntity entity) throws ReflectiveOperationException {
        Object wrapper = entity.getClass().getMethod("getBackpackWrapper").invoke(entity);
        if (wrapper == null
                || !wrapper.getClass().getName().equals(BACKPACK + "wrapper.BackpackWrapper"))
            throw new ReflectiveOperationException("Unverified backpack wrapper");
        return wrapper;
    }

    private static ItemStack backpackStack(Object wrapper) throws ReflectiveOperationException {
        return (ItemStack) wrapper.getClass().getMethod("getBackpack").invoke(wrapper);
    }

    private static Inventory backpackAccess(CompoundTag tag) {
        if (tag != null
                && (tag.contains("Lock")
                        || tag.contains("lock")
                        || tag.contains("Owner")
                        || tag.contains("owner")
                        || tag.contains("ownerUuid")
                        || tag.contains("ownerUUID")))
            return Inventory.unavailable("这个背包带有无法核对的锁定或归属限制。");
        if (tag != null && tag.contains("lootTableName"))
            return Inventory.unavailable("这个背包的战利品尚未生成。");
        return null;
    }

    private static Inventory backpackInventory(ServerPlayer player, BlockEntity entity) {
        try {
            var wrapper = wrapper(entity);
            var tag = backpackStack(wrapper).getTag();
            var refusal = backpackAccess(tag);
            if (refusal != null) return refusal;
            // Both public handler factories can allocate saved data; the IO factory also fills
            // loot.
            // Calling the public inventory API is safe only when the audited wrapper already owns
            // its handler.
            Field initialized = field(wrapper.getClass(), "handler");
            if (initialized != null && initialized.get(wrapper) instanceof IItemHandler handler) {
                if (!handler.getClass()
                        .getName()
                        .equals(BACKPACK + "wrapper.BackpackInventoryHandler"))
                    return Inventory.unavailable("这个背包有未核实的库存实现。");
                // The public backing-slot API reads the stored stack directly. The capability's
                // getStackInSlot dispatches through upgrades, including mutable virtual slots.
                Method physicalSlot = handler.getClass().getMethod("getSlotStack", int.class);
                return inventory(
                        "sophisticatedbackpacks",
                        "physicalSlots",
                        handler.getSlots(),
                        index -> readPhysicalSlot(handler, physicalSlot, index));
            }
            if (tag != null
                    && tag.contains("inventory", Tag.TAG_COMPOUND)
                    && !tag.hasUUID("contentsUuid"))
                return storedBackpack(tag.getCompound("inventory"));
            if (tag != null && tag.hasUUID("contentsUuid")) {
                var stored = cachedBackpack(player, tag.getUUID("contentsUuid"));
                if (stored != null && stored.contains("inventory", Tag.TAG_COMPOUND))
                    return storedBackpack(stored.getCompound("inventory"));
            }
            return Inventory.unavailable("背包库存尚未载入，请先在游戏中正常打开这个背包。");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError inaccessible) {
            return Inventory.unavailable("当前背包版本没有可核对的只读库存。");
        }
    }

    private static JsonObject readPhysicalSlot(IItemHandler handler, Method method, int index) {
        try {
            return method.invoke(handler, index) instanceof ItemStack item
                    ? slot(index, item)
                    : unknownSlot(index);
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            return unknownSlot(index);
        }
    }

    private static CompoundTag cachedBackpack(ServerPlayer player, UUID contentsId)
            throws ReflectiveOperationException {
        if (SAVED_CACHE == null) return null;
        var storage = player.server.overworld().getDataStorage();
        if (!(SAVED_CACHE.get(storage) instanceof Map<?, ?> cache)) return null;
        Object backpacks = cache.get("sophisticatedbackpacks");
        if (backpacks == null
                || !backpacks.getClass().getName().equals(BACKPACK + "BackpackStorage"))
            return null;
        Field contents = field(backpacks.getClass(), "backpackContents");
        if (contents == null || !(contents.get(backpacks) instanceof Map<?, ?> values)) return null;
        return values.get(contentsId) instanceof CompoundTag row ? row : null;
    }

    static Inventory storedBackpack(CompoundTag inventory) {
        if (!inventory.contains("Size", Tag.TAG_INT)
                || !(inventory.get("Items") instanceof ListTag items))
            return Inventory.unavailable("保存的背包库存格式未知。");
        int size = inventory.getInt("Size");
        if (size < 0 || size > MAX_SLOTS || items.size() > size)
            return Inventory.unavailable("保存的背包槽位范围未知。");
        var indexed = new HashMap<Integer, CompoundTag>();
        for (var item : items) {
            if (!(item instanceof CompoundTag row) || !row.contains("Slot", Tag.TAG_INT))
                return Inventory.unavailable("保存的背包槽位格式未知。");
            int index = row.getInt("Slot");
            if (index < 0 || index >= size || indexed.put(index, row) != null)
                return Inventory.unavailable("保存的背包槽位与当前容器不一致。");
        }
        return inventory(
                "sophisticatedbackpacks",
                "persistedSlots",
                size,
                index -> {
                    var row = indexed.get(index);
                    if (row == null) return slot(index, ItemStack.EMPTY);
                    String rawId = row.getString("id");
                    ResourceLocation id =
                            rawId.length() <= 192 ? ResourceLocation.tryParse(rawId) : null;
                    int count =
                            row.contains("realCount", Tag.TAG_INT)
                                    ? row.getInt("realCount")
                                    : row.getByte("Count");
                    if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || count <= 0)
                        return unknownSlot(index);
                    return slot(index, id, count, safeItemTag(row.getCompound("tag")));
                });
    }

    private static JsonObject unknownSlot(int index) {
        var unknown = new JsonObject();
        unknown.addProperty("slot", index);
        unknown.addProperty("status", "unavailable");
        return unknown;
    }

    private static Inventory inventory(
            String source, String scope, int size, IntFunction<JsonObject> reader) {
        return size >= 0 && size <= MAX_SLOTS
                ? new Inventory("ok", source, scope, size, reader, "", "")
                : Inventory.unavailable("库存槽位数量超出当前只读范围。");
    }

    private static JsonObject slot(int index, ItemStack stack) {
        if (!stack.isEmpty())
            return slot(
                    index,
                    BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    stack.getCount(),
                    safeItemTag(stack.getTag()));
        JsonObject result = new JsonObject();
        result.addProperty("slot", index);
        result.addProperty("empty", true);
        return result;
    }

    private static JsonObject slot(int index, ResourceLocation id, int count, JsonObject itemTag) {
        var item = new JsonObject();
        item.addProperty("slot", index);
        item.addProperty("itemId", id.toString());
        item.addProperty("count", count);
        if (itemTag.has("Damage")) item.add("damage", itemTag.get("Damage"));
        if (itemTag.size() != 0) item.add("itemNbt", itemTag);
        item.addProperty("empty", false);
        return item;
    }

    private static JsonObject safeItemTag(CompoundTag tag) {
        var result = new JsonObject();
        if (tag == null) return result;
        if (tag.contains("Damage", Tag.TAG_INT)) result.addProperty("Damage", tag.getInt("Damage"));
        if (tag.contains("Unbreakable", Tag.TAG_BYTE))
            result.addProperty("Unbreakable", tag.getBoolean("Unbreakable"));
        return result;
    }

    static Field field(Class<?> type, String... names) {
        for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                if (field.trySetAccessible()) return field;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next mapping name.
            }
        }
        return null;
    }
}
