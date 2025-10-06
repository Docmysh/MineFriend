package mf.minefriend.friend.client;

import mf.minefriend.friend.entity.FriendEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class FriendRenderer extends HumanoidMobRenderer<FriendEntity, PlayerModel<FriendEntity>> {
    public FriendRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(FriendEntity entity) {
        return entity.getSkinTexture();
    }
}
