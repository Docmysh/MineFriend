package mf.minefriend.friend.entity;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

public class FriendFollowPlayerGoal extends Goal {
    private final PathfinderMob mob;
    private final double speedModifier;
    private final float stopDistance;
    private final float startDistance;
    private Player player;

    public FriendFollowPlayerGoal(PathfinderMob mob, double speedModifier, float stopDistance, float startDistance) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.stopDistance = stopDistance;
        this.startDistance = startDistance;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        player = mob.level().getNearestPlayer(mob, startDistance);
        if (player == null) {
            return false;
        }
        return player.distanceToSqr(mob) > (double) (stopDistance * stopDistance);
    }

    @Override
    public boolean canContinueToUse() {
        return player != null && player.distanceToSqr(mob) > (double) (stopDistance * stopDistance) && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        if (player != null) {
            mob.getNavigation().moveTo(player, speedModifier);
        }
    }

    @Override
    public void stop() {
        player = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (player != null) {
            mob.getLookControl().setLookAt(player, 10.0F, mob.getMaxHeadXRot());
            if (!mob.isLeashed()) {
                mob.getNavigation().moveTo(player, speedModifier);
            }
        }
    }
}
