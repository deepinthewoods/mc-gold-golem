package ninja.trek.mc.goldgolem.wall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

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
    public final WallJoinSlice.Axis aSliceAxis; // axis of the join slice at A side
    public final WallJoinSlice.Axis bSliceAxis; // axis of the join slice at B side

    // Lazy-computed full join slice profiles
    private WallJoinSlice aSlice;
    private WallJoinSlice bSlice;
    private boolean aSliceComputed;
    private boolean bSliceComputed;

    public WallModuleTemplate(BlockPos aMarker, BlockPos bMarker, List<Voxel> voxels, int minY) {
        this(aMarker, bMarker, voxels, minY, null, null);
    }

    public WallModuleTemplate(BlockPos aMarker, BlockPos bMarker, List<Voxel> voxels, int minY,
                              WallJoinSlice.Axis aSliceAxis, WallJoinSlice.Axis bSliceAxis) {
        this.aMarker = aMarker;
        this.bMarker = bMarker;
        this.voxels = Collections.unmodifiableList(new ArrayList<>(voxels));
        this.minY = minY;
        this.aSliceAxis = aSliceAxis;
        this.bSliceAxis = bSliceAxis;
    }

    public double horizLen() {
        double dx = (double) (bMarker.getX() - aMarker.getX());
        double dz = (double) (bMarker.getZ() - aMarker.getZ());
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Lazily compute the full join slice at the A side from template voxels. */
    public WallJoinSlice getASlice() {
        if (!aSliceComputed) {
            aSliceComputed = true;
            if (aSliceAxis != null) {
                aSlice = WallJoinSlice.fromTemplateVoxels(voxels, BlockPos.ZERO, aSliceAxis).orElse(null);
            }
        }
        return aSlice;
    }

    /** Lazily compute the full join slice at the B side from template voxels. */
    public WallJoinSlice getBSlice() {
        if (!bSliceComputed) {
            bSliceComputed = true;
            if (bSliceAxis != null) {
                BlockPos bRel = new BlockPos(
                        bMarker.getX() - aMarker.getX(),
                        bMarker.getY() - aMarker.getY(),
                        bMarker.getZ() - aMarker.getZ()
                );
                bSlice = WallJoinSlice.fromTemplateVoxels(voxels, bRel, bSliceAxis).orElse(null);
            }
        }
        return bSlice;
    }
}
