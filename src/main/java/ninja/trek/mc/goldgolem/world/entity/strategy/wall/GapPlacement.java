package ninja.trek.mc.goldgolem.world.entity.strategy.wall;

import net.minecraft.util.math.Vec3d;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy;

import java.util.Collections;

/**
 * Represents a gap/corner turn placement (no actual blocks, just direction change).
 * Extracted from GoldGolemEntity inner class.
 */
public final class GapPlacement extends ModulePlacement {
    private final int dx;
    private final int dz;
    private final int dirx;
    private final int dirz;

    public GapPlacement(int dx, int dz, Vec3d anchor, Vec3d end, int dirx, int dirz) {
        super(-1, 0, false, anchor, end);
        this.dx = dx;
        this.dz = dz;
        this.dirx = dirx;
        this.dirz = dirz;
    }

    @Override
    public void begin(GoldGolemEntity golem, WallBuildStrategy strategy) {
        this.voxels = Collections.emptyList();
        strategy.setWallLastDir(dirx, dirz);
    }

    @Override
    public void placeSome(GoldGolemEntity golem, WallBuildStrategy strategy, int maxOps) {
        // Gap placements don't place any blocks
    }

    @Override
    public boolean done() {
        return true;
    }
}
