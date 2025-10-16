package ninja.trek.mc.goldgolem.wall;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WallModuleTemplate {
    public static final class Voxel {
        public final BlockPos rel; // relative to module's A marker
        public final BlockState state; // captured block state
        public Voxel(BlockPos rel, BlockState state) { this.rel = rel; this.state = state; }
    }

    public final BlockPos aMarker; // module start (relative to combined origin)
    public final BlockPos bMarker; // module end (relative to combined origin)
    public final List<Voxel> voxels; // positions relative to aMarker
    public final int minY; // min rel Y within module (for bottom reference)

    public WallModuleTemplate(BlockPos aMarker, BlockPos bMarker, List<Voxel> voxels, int minY) {
        this.aMarker = aMarker;
        this.bMarker = bMarker;
        this.voxels = Collections.unmodifiableList(new ArrayList<>(voxels));
        this.minY = minY;
    }

    public double horizLen() {
        double dx = (double) (bMarker.getX() - aMarker.getX());
        double dz = (double) (bMarker.getZ() - aMarker.getZ());
        return Math.sqrt(dx * dx + dz * dz);
    }
}
