package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Builds a deterministic, closed dungeon from a captured room set. */
public record RoomBuildRequest(
        BlockPos origin,
        Direction initialDirection,
        long seed,
        int maxRooms,
        RoomTerrainPolicy terrainPolicy
) implements StructureBuildRequest {
    public RoomBuildRequest {
        if (origin == null) throw new IllegalArgumentException("origin is required");
        if (initialDirection == null || initialDirection == Direction.UP || initialDirection == Direction.DOWN) {
            throw new IllegalArgumentException("initialDirection must be horizontal");
        }
        if (maxRooms < 1 || maxRooms > 1000) {
            throw new IllegalArgumentException("maxRooms must be between 1 and 1000");
        }
        if (terrainPolicy == null) throw new IllegalArgumentException("terrainPolicy is required");
    }

    @Override
    public TemplateMode mode() {
        return TemplateMode.ROOM;
    }
}
