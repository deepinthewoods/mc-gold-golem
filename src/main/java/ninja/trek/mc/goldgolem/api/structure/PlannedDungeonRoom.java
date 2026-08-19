package ninja.trek.mc.goldgolem.api.structure;

import ninja.trek.mc.goldgolem.room.RoomPlacement;

import java.util.Objects;

/** One concrete template assignment in plan-local coordinates. */
public record PlannedDungeonRoom(int index, RoomPlacement placement, Role role) {
    public PlannedDungeonRoom {
        if (index < 0) throw new IllegalArgumentException("Room index cannot be negative");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(role, "role");
    }

    public enum Role {
        ORDINARY,
        SPECIAL,
        ENTRANCE,
        BOSS
    }
}
