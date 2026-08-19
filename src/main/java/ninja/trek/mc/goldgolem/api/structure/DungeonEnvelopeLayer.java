package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** One solid contour layer, ordered from nearest the rooms to outermost. */
public record DungeonEnvelopeLayer(BlockState state, int thickness) {
    public DungeonEnvelopeLayer {
        Objects.requireNonNull(state, "state");
        if (state.isAir()) throw new IllegalArgumentException("Envelope state cannot be air");
        if (thickness < 1 || thickness > 64) {
            throw new IllegalArgumentException("Envelope thickness must be between 1 and 64");
        }
    }
}
