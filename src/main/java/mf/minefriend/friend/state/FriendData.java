package mf.minefriend.friend.state;

import mf.minefriend.friend.entity.FriendEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;

public record FriendData(UUID owner, UUID entityId, String friendName, int skinIndex, FriendPhase phase, int negativeResponses,
                         boolean hardcoreActive, boolean phaseOneScriptDisabled) {
    private static final String DATA_KEY = "MineFriend";

    public FriendData(UUID owner, UUID entityId, String friendName, int skinIndex, FriendPhase phase, int negativeResponses,
                      boolean hardcoreActive, boolean phaseOneScriptDisabled) {
        this.owner = owner;
        this.entityId = entityId;
        this.friendName = friendName;
        this.skinIndex = skinIndex;
        this.phase = phase;
        this.negativeResponses = negativeResponses;
        this.hardcoreActive = hardcoreActive;
        this.phaseOneScriptDisabled = phaseOneScriptDisabled;
    }

    public static FriendData create(ServerPlayer player, String friendName, int skinIndex) {
        return new FriendData(player.getUUID(), null, friendName, skinIndex, FriendPhase.PHASE_ONE, 0, false, false);
    }

    public Component getDisplayNameComponent() {
        return Component.literal(friendName);
    }

    public FriendData withEntity(UUID entityId) {
        return new FriendData(owner, entityId, friendName, skinIndex, phase, negativeResponses, hardcoreActive, phaseOneScriptDisabled);
    }

    public FriendData withPhase(FriendPhase newPhase) {
        return new FriendData(owner, entityId, friendName, skinIndex, newPhase, negativeResponses, hardcoreActive, phaseOneScriptDisabled);
    }

    public FriendData withNegativeResponses(int count) {
        return new FriendData(owner, entityId, friendName, skinIndex, phase, count, hardcoreActive, phaseOneScriptDisabled);
    }

    public FriendData withName(String newName) {
        return new FriendData(owner, entityId, newName, skinIndex, phase, negativeResponses, hardcoreActive, phaseOneScriptDisabled);
    }

    public FriendData withSkinIndex(int newSkinIndex) {
        return new FriendData(owner, entityId, friendName, newSkinIndex, phase, negativeResponses, hardcoreActive, phaseOneScriptDisabled);
    }

    public FriendData withHardcore(boolean active) {
        return new FriendData(owner, entityId, friendName, skinIndex, phase, negativeResponses, active, phaseOneScriptDisabled);
    }

    public FriendData withPhaseOneScriptDisabled(boolean disabled) {
        return new FriendData(owner, entityId, friendName, skinIndex, phase, negativeResponses, hardcoreActive, disabled);
    }

    public static Optional<FriendData> get(ServerPlayer player) {
        CompoundTag data = player.getPersistentData().getCompound(DATA_KEY);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        UUID owner = player.getUUID();
        UUID entityId = data.hasUUID("FriendEntity") ? data.getUUID("FriendEntity") : null;
        String name = data.getString("FriendName");
        int skin = data.getInt("SkinIndex");
        FriendPhase phase = FriendPhase.byId(data.getInt("Phase"));
        int negatives = data.getInt("Negatives");
        boolean hardcore = data.getBoolean("HardcoreActive");
        boolean phaseOneDisabled = data.getBoolean("PhaseOneScriptDisabled");
        return Optional.of(new FriendData(owner, entityId, name, skin, phase, negatives, hardcore, phaseOneDisabled));
    }

    public static Optional<FriendData> get(Entity entity) {
        if (entity instanceof FriendEntity friendEntity) {
            return friendEntity.getFriendPlayer().flatMap(FriendData::get);
        }
        return Optional.empty();
    }

    public static void store(ServerPlayer player, FriendData data) {
        CompoundTag tag = player.getPersistentData().getCompound(DATA_KEY);
        tag.putUUID("Owner", data.owner);
        if (data.entityId != null) {
            tag.putUUID("FriendEntity", data.entityId);
        }
        tag.putString("FriendName", data.friendName);
        tag.putInt("SkinIndex", data.skinIndex);
        tag.putInt("Phase", data.phase.getId());
        tag.putInt("Negatives", data.negativeResponses);
        tag.putBoolean("HardcoreActive", data.hardcoreActive);
        tag.putBoolean("PhaseOneScriptDisabled", data.phaseOneScriptDisabled);
        player.getPersistentData().put(DATA_KEY, tag);
    }

    public static void clearEntity(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData().getCompound(DATA_KEY);
        if (tag.contains("FriendEntity")) {
            tag.remove("FriendEntity");
        }
        player.getPersistentData().put(DATA_KEY, tag);
    }

    public static void restore(ServerLevel level, UUID entityId, UUID ownerId, String name, int skinIndex, FriendPhase phase) {
        if (ownerId == null) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(ownerId);
        if (player == null) {
            return;
        }
        FriendData data = new FriendData(ownerId, entityId, name, skinIndex, phase, 0, false, false);
        store(player, data);
    }
}
