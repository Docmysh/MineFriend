package mf.minefriend.friend.entity;

import mf.minefriend.Minefriend;
import mf.minefriend.friend.FriendManager;
import mf.minefriend.friend.client.FriendClientHelper;
import mf.minefriend.friend.state.FriendData;
import mf.minefriend.friend.state.FriendPhase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public class FriendEntity extends TamableAnimal {
    private static final float FOLLOW_DISTANCE = 3.0F;
    public static final int PLAYER_SKIN_INDEX = -1;

    private int chatCooldown;
    private int skinIndex;
    private String friendName = "Friend";

    public FriendEntity(EntityType<? extends TamableAnimal> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes();
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new FriendFollowPlayerGoal(this, 1.0D, FOLLOW_DISTANCE, FOLLOW_DISTANCE + 6.0F));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (chatCooldown > 0) {
                chatCooldown--;
            }
        }
    }

    public void setChatCooldown(int ticks) {
        this.chatCooldown = ticks;
    }

    public boolean canSendChat() {
        return this.chatCooldown <= 0;
    }

    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        FriendData.get(this).ifPresent(data -> {
            tag.putUUID("OwnerUUID", data.owner());
            tag.putString("FriendName", data.friendName());
            tag.putInt("Phase", data.phase().getId());
            tag.putInt("SkinIndex", data.skinIndex());
        });
        tag.putInt("LocalSkin", skinIndex);
        tag.putString("LocalName", friendName);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (!level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
            UUID owner = tag.hasUUID("OwnerUUID") ? tag.getUUID("OwnerUUID") : null;
            int phaseId = tag.getInt("Phase");
            int skinIndex = tag.getInt("SkinIndex");
            String friendName = tag.getString("FriendName");
            FriendPhase phase = FriendPhase.byId(phaseId);
            FriendData.restore(serverLevel, this.getUUID(), owner, friendName, skinIndex, phase);
        }
        this.skinIndex = tag.contains("LocalSkin") ? tag.getInt("LocalSkin") : this.skinIndex;
        this.friendName = tag.contains("LocalName") ? tag.getString("LocalName") : this.friendName;
    }

    public static Optional<FriendEntity> create(ServerLevel level, ServerPlayer player, FriendData data) {
        FriendEntity entity = Minefriend.FRIEND_ENTITY.get().create(level);
        if (entity == null) {
            return Optional.empty();
        }
        entity.moveTo(player.getX(), player.getY(), player.getZ());
        entity.tame(player);
        entity.setOwnerUUID(player.getUUID());
        entity.setCustomName(data.getDisplayNameComponent());
        entity.setCustomNameVisible(false);
        entity.setSkinIndex(data.skinIndex());
        entity.setFriendName(data.friendName());
        level.addFreshEntity(entity);
        return Optional.of(entity);
    }

    public void followPlayer(ServerPlayer player) {
        Vec3 vec3 = new Vec3(player.getX(), player.getY(), player.getZ());
        if (this.distanceToSqr(vec3) > FOLLOW_DISTANCE * FOLLOW_DISTANCE) {
            this.getNavigation().moveTo(player, 1.0D);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide && this.getOwner() instanceof ServerPlayer player) {
            FriendData.get(player).ifPresent(data -> {
                if (data.phase() == FriendPhase.PHASE_ONE || data.phase() == FriendPhase.PHASE_TWO) {
                    this.followPlayer(player);
                }
            });
        }
    }

    public static boolean checkSpawnRules(EntityType<FriendEntity> type, ServerLevelAccessor levelAccessor, MobSpawnType spawnType, Vec3 position, RandomSource randomSource) {
        return true;
    }

    public Optional<ServerPlayer> getFriendPlayer() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        UUID ownerUUID = this.getOwnerUUID();
        if (ownerUUID == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(serverLevel.getServer().getPlayerList().getPlayer(ownerUUID));
    }

    public void handlePhaseChange(FriendPhase newPhase) {
        this.chatCooldown = 0;
        this.setCustomNameVisible(newPhase == FriendPhase.PHASE_FOUR);
    }

    public void setSkinIndex(int skinIndex) {
        this.skinIndex = skinIndex;
    }

    public int getSkinIndex() {
        return skinIndex;
    }

    public void setFriendName(String friendName) {
        this.friendName = friendName;
        this.setCustomName(Component.literal(friendName));
    }

    public String getFriendName() {
        return friendName;
    }

    public ResourceLocation getSkinTexture() {
        if (skinIndex == PLAYER_SKIN_INDEX) {
            ResourceLocation playerSkin = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> FriendClientHelper.resolveOwnerSkin(this));
            if (playerSkin != null) {
                return playerSkin;
            }
        }
        return FriendManager.getSkin(skinIndex).orElse(ResourceLocation.parse("textures/entity/steve.png"));
    }
}
