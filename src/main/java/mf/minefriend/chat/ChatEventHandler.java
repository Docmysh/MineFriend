package mf.minefriend.chat;

import mf.minefriend.Minefriend;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Minefriend.MODID)
public final class ChatEventHandler {

    private ChatEventHandler() {
    }

    @SubscribeEvent
    public static void onPlayerChat(ServerChatEvent event) {
        String playerMessage = event.getMessage().getString();
        // We'll add the logic here in the next steps...
    }
}
