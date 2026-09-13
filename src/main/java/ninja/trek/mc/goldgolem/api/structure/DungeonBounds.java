package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** Inclusive plan-local bounds. */
public record DungeonBounds(BlockPos minimum, BlockPos maximum) {
    public DungeonBounds {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
    }
}
