/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.pixelmcmanager.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/** Read-only optional protection queries. A detected provider must answer before data is read. */
final class BlockClaimAccess {
    private static final String OPAC = "xaero.pac.common.";
    private static final String FTB = "dev.ftb.mods.ftbchunks.api.";
    private static final boolean HYBRID = present("org.bukkit.Bukkit");

    private BlockClaimAccess() {}

    enum Decision {
        ALLOWED,
        FORBIDDEN,
        UNAVAILABLE
    }

    static Decision check(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face) {
        if (!level.mayInteract(player, pos)) return Decision.FORBIDDEN;
        ModList mods = ModList.get();
        try {
            if (mods.isLoaded("openpartiesandclaims")) {
                // The policy may consider an item used on the neighbor of the targeted face.
                if (!level.hasChunkAt(pos.relative(face))) return Decision.UNAVAILABLE;
                if (!opac(player, level, pos, face)) return Decision.FORBIDDEN;
            }
            if (mods.isLoaded("ftbchunks") && !ftb(player, pos)) return Decision.FORBIDDEN;
            if (HYBRID
                    || mods.isLoaded("flan")
                    || mods.isLoaded("claimchunk")
                    || mods.isLoaded("chunk_claim")
                    || mods.isLoaded("griefdefender")
                    || mods.isLoaded("griefprevention")
                    || mods.isLoaded("worldguard")) return Decision.UNAVAILABLE;
            return Decision.ALLOWED;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError inaccessible) {
            return Decision.UNAVAILABLE;
        }
    }

    private static boolean present(String type) {
        try {
            Class.forName(type, false, BlockClaimAccess.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        } catch (LinkageError present) {
            return true;
        }
    }

    private static boolean opac(
            ServerPlayer player, ServerLevel level, BlockPos pos, Direction face)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(OPAC + "server.api.OpenPACServerAPI");
        Object api = type.getMethod("get", MinecraftServer.class).invoke(null, level.getServer());
        Class<?> protection =
                Class.forName(OPAC + "server.claims.protection.api.IChunkProtectionAPI");
        Object policy = type.getMethod("getChunkProtection").invoke(api);
        // OPAC's right-click protection applies the claim's actual interaction rules and
        // block exceptions, including containers made available to other players.
        return Boolean.FALSE.equals(
                protection
                        .getMethod(
                                "onBlockInteraction",
                                Entity.class,
                                InteractionHand.class,
                                ItemStack.class,
                                ServerLevel.class,
                                BlockPos.class,
                                Direction.class,
                                boolean.class,
                                boolean.class,
                                boolean.class)
                        .invoke(
                                policy,
                                player,
                                InteractionHand.MAIN_HAND,
                                null,
                                level,
                                pos,
                                face,
                                false,
                                false,
                                true));
    }

    private static boolean ftb(ServerPlayer player, BlockPos pos)
            throws ReflectiveOperationException {
        Class<?> root = Class.forName(FTB + "FTBChunksAPI");
        Class<?> type = Class.forName(FTB + "FTBChunksAPI$API");
        Object api = root.getMethod("api").invoke(null);
        if (!Boolean.TRUE.equals(type.getMethod("isManagerLoaded").invoke(api)))
            throw new IllegalStateException("FTB Chunks manager is not ready");
        Object manager = type.getMethod("getManager").invoke(api);
        Class<?> protection = Class.forName(FTB + "Protection");
        Object policy = protection.getField("INTERACT_BLOCK").get(null);
        return Boolean.FALSE.equals(
                Class.forName(FTB + "ClaimedChunkManager")
                        .getMethod(
                                "shouldPreventInteraction",
                                Entity.class,
                                InteractionHand.class,
                                BlockPos.class,
                                protection,
                                Entity.class)
                        .invoke(manager, player, InteractionHand.MAIN_HAND, pos, policy, null));
    }
}
