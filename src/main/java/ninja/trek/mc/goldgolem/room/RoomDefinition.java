package ninja.trek.mc.goldgolem.room;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Complete reusable room set captured from one connected gold doorway network. */
public record RoomDefinition(
        BlockPos origin,
        List<RoomTemplate> rooms,
        List<String> uniqueBlockIds,
        int apertureWidth,
        int apertureHeight
) {
    public RoomDefinition {
        if (origin == null) throw new IllegalArgumentException("Room capture origin is required");
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
        uniqueBlockIds = uniqueBlockIds == null ? List.of() : List.copyOf(uniqueBlockIds);
        if (rooms.isEmpty()) throw new IllegalArgumentException("Room capture has no rooms");
        if (apertureWidth < 1 || apertureHeight < 1) {
            throw new IllegalArgumentException("Room aperture dimensions must be positive");
        }
    }
}
