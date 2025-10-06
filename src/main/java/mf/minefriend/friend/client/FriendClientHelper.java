package mf.minefriend.friend.client;

import mf.minefriend.friend.entity.FriendEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

public final class FriendClientHelper {
    private FriendClientHelper() {
    }

    public static ResourceLocation resolveOwnerSkin(FriendEntity entity) {
        UUID ownerId = entity.getOwnerUUID();
        if (ownerId == null) {
            return DefaultPlayerSkin.getDefaultSkin(entity.getUUID());
        }
        Player owner = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getPlayerByUUID(ownerId) : null;
        if (owner instanceof AbstractClientPlayer clientPlayer) {
            return clientPlayer.getSkinTextureLocation();
        }
        return DefaultPlayerSkin.getDefaultSkin(ownerId);
    }
}
