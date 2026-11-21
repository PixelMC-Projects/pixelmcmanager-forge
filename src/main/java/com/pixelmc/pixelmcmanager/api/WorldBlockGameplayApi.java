/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.pixelmcmanager.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/** Trusted server-thread capability for a live block selected by a connected player. */
public final class WorldBlockGameplayApi {
    private static final int MAX_PATH_LENGTH = 256;
    private static final int MAX_PATH_SEGMENTS = 12;
    private static final int MAX_PAGE_SIZE = 32;

    /** The calling mod binds these server objects to its authorized selection before dispatch. */
    public record Target(
            ServerLevel level, BlockPos pos, Block block, BlockEntity entity, Direction face) {
        public Target {
            if (pos != null) pos = pos.immutable();
        }
    }

    public static int protocolVersion() {
        return 1;
    }

    public static String descriptor() {
        var provider = new JsonObject();
        provider.addProperty("providerId", "pixelmcmanager");
        provider.addProperty(
                "modVersion",
                ModList.get()
                        .getModContainerById("pixelmcmanager")
                        .map(mod -> mod.getModInfo().getVersion().toString())
                        .orElse("unknown"));
        provider.addProperty("providerVersion", protocolVersion());
        var actions = new JsonArray();
        List.of("authorize", "fields", "read").forEach(actions::add);
        var capability = new JsonObject();
        capability.addProperty("id", "world_block.read");
        capability.addProperty("version", 1);
        capability.addProperty("audience", "trusted-server");
        capability.add("actions", actions);
        capability.addProperty("maxPathLength", MAX_PATH_LENGTH);
        capability.addProperty("maxPathSegments", MAX_PATH_SEGMENTS);
        capability.addProperty("maxPageSize", MAX_PAGE_SIZE);
        capability.addProperty("maxResponseBytes", BlockGameplayData.MAX_BYTES);
        var capabilities = new JsonArray();
        capabilities.add(capability);
        provider.add("capabilities", capabilities);
        return provider.toString();
    }

    /** Returns the current access decision without enumerating slots or projected values. */
    public static JsonObject authorize(ServerPlayer player, Target selection) {
        if (!live(player, selection)) return stale();
        var refusal = BlockGameplayData.authorization(player, selection);
        if (refusal != null) return refusal;
        var allowed = new JsonObject();
        allowed.addProperty("status", "ok");
        return allowed;
    }

    /**
     * Lists field descriptors or reads permitted values. Authorization is rechecked for each call.
     */
    public static JsonObject inspect(
            ServerPlayer player,
            Target selection,
            String path,
            int offset,
            int limit,
            boolean read) {
        var query = query(path, offset, limit, read);
        if (query == null) return BlockGameplayData.error("invalid_request", "无效的方块字段路径或分页范围。");
        if (!live(player, selection)) return stale();
        try {
            return BlockGameplayData.bounded(
                    BlockGameplayData.inspect(player, selection, query, read));
        } catch (RuntimeException | LinkageError inaccessible) {
            return BlockGameplayData.error("unavailable", "当前无法安全读取这个方块的数据。");
        }
    }

    private static BlockGameplayData.Query query(String path, int offset, int limit, boolean read) {
        if (path == null
                || path.length() > MAX_PATH_LENGTH
                || offset < 0
                || limit < 1
                || limit > MAX_PAGE_SIZE) return null;
        if (path.isEmpty())
            return read ? null : new BlockGameplayData.Query(path, List.of(), offset, limit);
        if (!path.startsWith("/")) return null;
        String[] segments = path.substring(1).split("/", -1);
        if (segments.length > MAX_PATH_SEGMENTS) return null;
        var tokens = new ArrayList<String>(segments.length);
        for (String segment : segments) {
            if (segment.isEmpty()
                    || segment.matches(".*~(?![01]).*")
                    || segment.chars().anyMatch(Character::isISOControl)) return null;
            tokens.add(segment.replace("~1", "/").replace("~0", "~"));
        }
        return new BlockGameplayData.Query(path, tokens, offset, limit);
    }

    private static boolean live(ServerPlayer player, Target selection) {
        if (player == null
                || player instanceof FakePlayer
                || player.server == null
                || !player.server.isSameThread()
                || selection == null
                || selection.level() == null
                || selection.pos() == null
                || selection.block() == null
                || selection.face() == null) return false;
        if (!player.isAlive()
                || player.isSpectator()
                || player.hasDisconnected()
                || player.server.getPlayerList().getPlayer(player.getUUID()) != player)
            return false;
        var level = selection.level();
        var pos = selection.pos();
        return player.serverLevel() == level
                && player.server.getLevel(level.dimension()) == level
                && level.getWorldBorder().isWithinBounds(pos)
                && level.hasChunkAt(pos)
                && level.getBlockState(pos).getBlock() == selection.block()
                && loadedEntity(level, pos) == selection.entity();
    }

    static BlockEntity loadedEntity(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        var entity = chunk == null ? null : chunk.getBlockEntities().get(pos);
        return entity == null || entity.isRemoved() ? null : entity;
    }

    private static JsonObject stale() {
        return BlockGameplayData.error("stale", "方块引用已失效，请重新选择。");
    }

    private WorldBlockGameplayApi() {}
}
