package com.pixelmc.pixelmcmanager.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.KillCommand;
import net.minecraft.server.level.ServerPlayer;

/** Player-only self-kill, using the current Minecraft version's vanilla command. */
public final class KillMeCommand {
    private KillMeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Capture only vanilla's no-target callback, never another mod's /kill replacement.
        // Calling this callback requires no permission elevation or arbitrary command parsing.
        CommandDispatcher<CommandSourceStack> vanilla = new CommandDispatcher<>();
        KillCommand.register(vanilla);
        Command<CommandSourceStack> killSelf = vanilla.getRoot().getChild("kill").getCommand();

        dispatcher.register(Commands.literal("killme")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    ServerPlayer player = source.getPlayerOrException();
                    if (!player.isAlive()) {
                        return 0;
                    }
                    source.getServer().getPlayerList().broadcastSystemMessage(
                            Component.translatable("chat.type.text", player.getDisplayName(),
                                    Component.literal("自刎归天!!!")), false);
                    // Vanilla death rules/events still apply; suppress only command success feedback.
                    return killSelf.run(context.copyFor(source.withSuppressedOutput()));
                }));
    }
}
