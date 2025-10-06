package mf.minefriend.friend.state;

import java.util.Arrays;

public enum FriendPhase {
    NONE(0),
    PHASE_ONE(1),
    PHASE_TWO(2),
    PHASE_THREE(3),
    PHASE_FOUR(4);

    private final int id;

    FriendPhase(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static FriendPhase byId(int id) {
        return Arrays.stream(values()).filter(phase -> phase.id == id).findFirst().orElse(NONE);
    }

    public FriendPhase next() {
        return switch (this) {
            case NONE -> PHASE_ONE;
            case PHASE_ONE -> PHASE_TWO;
            case PHASE_TWO -> PHASE_THREE;
            case PHASE_THREE -> PHASE_FOUR;
            case PHASE_FOUR -> PHASE_FOUR;
        };
    }
}
