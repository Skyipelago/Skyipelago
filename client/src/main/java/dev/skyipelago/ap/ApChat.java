package dev.skyipelago.ap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ApChat {
    private ApChat() {
    }

    public static Component prefix(String message) {
        return prefix(Component.literal(message));
    }

    public static Component prefix(Component message) {
        MutableComponent prefix = Component.literal("[AP] ").withStyle(ChatFormatting.GOLD);
        return prefix.append(message.copy().withStyle(ChatFormatting.WHITE));
    }

    public static void broadcast(MinecraftServer server, String message) {
        if (server == null) {
            return;
        }
        Component text = prefix(message);
        server.getPlayerList().broadcastSystemMessage(text, false);
    }

    public static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(prefix(message));
    }
}
