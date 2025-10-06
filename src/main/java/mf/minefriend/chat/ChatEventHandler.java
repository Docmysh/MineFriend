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
        // --- DEBUG LOG 1: Confirm the event is firing ---
        LOGGER.info("[MineFriend] onPlayerChat event fired.");

        ServerPlayer player = event.getPlayer();
        String playerMessage = event.getMessage().getString();
        String playerName = player.getGameProfile().getName();

        // --- DEBUG LOG 2: Confirm we have the correct data ---
        LOGGER.info("[MineFriend] Captured message: '{}' from player: '{}'", playerMessage, playerName);

        FriendPhase phase = FriendData.get(player).map(FriendData::phase).orElse(FriendPhase.PHASE_ONE);

        // --- DEBUG LOG 3: Confirm the current phase ---
        LOGGER.info("[MineFriend] Current friend phase is: {}", phase);

        // --- DEBUG LOG 4: Confirm we are about to make the request ---
        LOGGER.info("[MineFriend] Sending request to LlmService...");
        LlmService.requestFriendReply(playerMessage, playerName, phase)
                .thenAccept(reply -> {
                    // --- DEBUG LOG 5: Confirm a successful reply ---
                    LOGGER.info("[MineFriend] Successfully received LLM reply. Broadcasting...");
                    broadcastReply(player, reply);
                })
                .exceptionally(throwable -> {
                    // --- DEBUG LOG 6: Make errors very clear ---
                    LOGGER.error("==========================================================");
                    LOGGER.error("[MineFriend] CRITICAL: FAILED TO GET LLM RESPONSE!");
                    LOGGER.error("Check your network, RadminVPN IP, port, and LLM server status.");
                    LOGGER.error("Error details: ", throwable);
                    LOGGER.error("==========================================================");
                    return null;
                });
    }

    public static void requestInitialGreeting(ServerPlayer player, FriendPhase phase) {
        String playerName = player.getGameProfile().getName();
        String kickoffPrompt = "The player just entered the world. Offer the first message to start the conversation.";
        LOGGER.info("[MineFriend] Triggering initial greeting for player '{}'.", playerName);
        LlmService.requestFriendReply(kickoffPrompt, playerName, phase)
                .thenAccept(reply -> {
                    LOGGER.info("[MineFriend] Initial greeting received. Broadcasting to players.");
                    broadcastReply(player, reply);
                })
                .exceptionally(throwable -> {
                    LOGGER.error("[MineFriend] Failed to retrieve initial greeting for player '{}'.", playerName, throwable);
                    return null;
                });
    }

    private static void broadcastReply(ServerPlayer player, LlmReply reply) {
        if (reply == null || reply.isEmpty()) {
            LOGGER.warn("[MineFriend] LLM Reply was null or empty. Nothing to broadcast.");
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
            LOGGER.info("[MineFriend] Phase transition suggested from {} to {}. Applying.", current, suggested);
            FriendData updated = data.withPhase(suggested);
            FriendData.store(player, updated);
        });
    }

    private static boolean isEarlyPhase(FriendPhase phase) {
        return phase == FriendPhase.NONE || phase == FriendPhase.PHASE_ONE || phase == FriendPhase.PHASE_TWO;
    }
}

