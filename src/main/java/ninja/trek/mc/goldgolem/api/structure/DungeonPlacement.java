package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Objects;

/** World anchoring and materialization options for an already completed plan. */
public record DungeonPlacement(
        BlockPos entranceMarkerPosition,
        Direction orientation,
        RoomTerrainPolicy terrainPolicy,
        DungeonEnvelope envelope,
        int maxGeneratedBlocks
) {
    public static final int DEFAULT_MAX_GENERATED_BLOCKS = 8_000_000;

    public DungeonPlacement {
        Objects.requireNonNull(entranceMarkerPosition, "entranceMarkerPosition");
        if (orientation == null || orientation == Direction.UP || orientation == Direction.DOWN) {
            throw new IllegalArgumentException("orientation must be horizontal");
        }
        Objects.requireNonNull(terrainPolicy, "terrainPolicy");
        envelope = envelope == null ? DungeonEnvelope.NONE : envelope;
        if (maxGeneratedBlocks < 1 || maxGeneratedBlocks > 32_000_000) {
            throw new IllegalArgumentException("maxGeneratedBlocks must be between 1 and 32000000");
        }
    }

    public DungeonPlacement(BlockPos entranceMarkerPosition, Direction orientation,
                            RoomTerrainPolicy terrainPolicy, DungeonEnvelope envelope) {
        this(entranceMarkerPosition, orientation, terrainPolicy, envelope, DEFAULT_MAX_GENERATED_BLOCKS);
    }
}
