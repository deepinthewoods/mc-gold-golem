package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.world.level.block.Block;

import java.util.Objects;

/** Inclusive per-room occurrence limits for a captured marker block. */
public record SpecialRoomQuota(Block marker, int minimumRooms, int maximumRooms) {
    public SpecialRoomQuota {
        Objects.requireNonNull(marker, "marker");
        if (minimumRooms < 0 || maximumRooms < minimumRooms || maximumRooms > 150) {
            throw new IllegalArgumentException("Special-room quota must satisfy 0 <= minimum <= maximum <= 150");
        }
    }
}
