package mf.minefriend.chat;

import com.mojang.logging.LogUtils;
import mf.minefriend.Minefriend;
import mf.minefriend.chat.LlmService.LlmReply;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = Minefriend.MODID)
public final class ChatEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ChatEventHandler() {
    }

    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String playerMessage = event.getMessage().getString();

        LlmService.requestFriendReply(playerMessage)
                .thenAccept(reply -> broadcastReply(player, reply))
                .exceptionally(throwable -> {
                    LOGGER.error("Failed to retrieve LLM response", throwable);
                    return null;
                });
    }

    private static void broadcastReply(ServerPlayer player, LlmReply reply) {
        if (reply == null || reply.isEmpty()) {
            return;
        }
        player.serverLevel().getServer().execute(() -> {
            Component header = Component.literal("<" + reply.personaName() + "> ")
                    .withStyle(ChatFormatting.GRAY);
            Component message = Component.literal(reply.message());
            Component composite = Component.empty().append(header).append(message);
            player.serverLevel().getServer().getPlayerList().broadcastSystemMessage(composite, false);
        });
    }
}
