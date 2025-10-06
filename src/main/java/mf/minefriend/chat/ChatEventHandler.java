package mf.minefriend.chat;

import com.mojang.logging.LogUtils;
import mf.minefriend.Minefriend;
import mf.minefriend.chat.LlmService.LlmReply;
import mf.minefriend.friend.state.FriendData;
import mf.minefriend.friend.state.FriendPhase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = Minefriend.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChatEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ChatEventHandler() {
    }

    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String playerMessage = event.getMessage().getString();

        FriendPhase phase = FriendData.get(player).map(FriendData::phase).orElse(FriendPhase.PHASE_ONE);

        LlmService.requestFriendReply(playerMessage, phase)
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
            applyPhaseSuggestion(player, reply.suggestedPhase());
            Component header = Component.literal("<" + reply.personaName() + "> ")
                    .withStyle(ChatFormatting.GRAY);
            Component message = Component.literal(reply.message());
            Component composite = Component.empty().append(header).append(message);
            player.serverLevel().getServer().getPlayerList().broadcastSystemMessage(composite, false);
        });
    }

    private static void applyPhaseSuggestion(ServerPlayer player, FriendPhase suggested) {
        if (suggested == null || suggested == FriendPhase.NONE) {
            return;
        }
        FriendData.get(player).ifPresent(data -> {
            FriendPhase current = data.phase();
            if (!isEarlyPhase(current)) {
                return;
            }
            if (suggested.getId() > FriendPhase.PHASE_TWO.getId()) {
                return;
            }
            if (suggested.getId() < current.getId()) {
                return;
            }
            if (suggested == current) {
                return;
            }
            FriendData updated = data.withPhase(suggested);
            FriendData.store(player, updated);
        });
    }

    private static boolean isEarlyPhase(FriendPhase phase) {
        return phase == FriendPhase.NONE || phase == FriendPhase.PHASE_ONE || phase == FriendPhase.PHASE_TWO;
    }
}
