package ninja.trek.mc.goldgolem.world.entity.strategy.wall;

import ninja.trek.mc.goldgolem.wall.WallJoinSlice;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy;

import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Represents a gap/corner turn placement (no actual blocks, just direction change).
 */
public final class GapPlacement extends ModulePlacement {
    private final int dx;
    private final int dz;
    private final int dirx;
    private final int dirz;

    public GapPlacement(int dx, int dz, Vec3 anchor, Vec3 end, int dirx, int dirz) {
        super(-1, 0, false, anchor, end);
        this.dx = dx;
        this.dz = dz;
        this.dirx = dirx;
        this.dirz = dirz;
    }

    @Override
    public void begin(GoldGolemEntity golem, WallBuildStrategy strategy) {
        this.voxels = Collections.emptyList();
        this.blockStatesMap = Collections.emptyMap();
        int prevDirX = strategy.getWallLastDirX();
        int prevDirZ = strategy.getWallLastDirZ();
        strategy.setWallLastDir(dirx, dirz);
        strategy.setCurrentOutputSlice(null); // Gap has no profile to enforce
        System.out.println("[WallGap] begin: anchor=(" + String.format("%.1f", anchor.x)
                + "," + String.format("%.1f", anchor.y) + "," + String.format("%.1f", anchor.z)
                + ") end=(" + String.format("%.1f", end.x) + "," + String.format("%.1f", end.y)
                + "," + String.format("%.1f", end.z) + ") d=(" + dx + "," + dz
                + ") dirChange=(" + prevDirX + "," + prevDirZ + ")->(" + dirx + "," + dirz + ")");
    }

    @Override
    public List<BlockPos> getRemainingBlockPositions(GoldGolemEntity golem, WallBuildStrategy strategy) {
        return Collections.emptyList();
    }

    @Override
    public boolean placeBlockAt(GoldGolemEntity golem, WallBuildStrategy strategy, BlockPos pos, BlockPos nextPos) {
        return false; // Gap placements don't place any blocks
    }

    @Override
    public boolean done() {
        return true;
    }

    @Override
    public int[] computeOutputDir(List<WallModuleTemplate> templates) {
        return new int[]{dirx, dirz};
    }

    @Override
    public WallJoinSlice computeOutputSlice(java.util.List<WallModuleTemplate> templates) {
        return null; // Gap has no profile to enforce
    }

    // ========== Serialization helpers ==========

    @Override
    public void writeTo(ValueOutput view, String prefix) {
        view.putBoolean(prefix + "isGap", true);
        view.putInt(prefix + "gdx", dx);
        view.putInt(prefix + "gdz", dz);
        view.putInt(prefix + "gdrx", dirx);
        view.putInt(prefix + "gdrz", dirz);
        view.putDouble(prefix + "ax", anchor.x);
        view.putDouble(prefix + "ay", anchor.y);
        view.putDouble(prefix + "az", anchor.z);
        view.putDouble(prefix + "ex", end.x);
        view.putDouble(prefix + "ey", end.y);
        view.putDouble(prefix + "ez", end.z);
    }

    /**
     * Read a GapPlacement from a ValueInput view.
     */
    public static GapPlacement readGapFrom(ValueInput view, String prefix) {
        int gdx = view.getIntOr(prefix + "gdx", 0);
        int gdz = view.getIntOr(prefix + "gdz", 0);
        int gdrx = view.getIntOr(prefix + "gdrx", 1);
        int gdrz = view.getIntOr(prefix + "gdrz", 0);
        double ax = view.getDoubleOr(prefix + "ax", 0);
        double ay = view.getDoubleOr(prefix + "ay", 0);
        double az = view.getDoubleOr(prefix + "az", 0);
        double ex = view.getDoubleOr(prefix + "ex", 0);
        double ey = view.getDoubleOr(prefix + "ey", 0);
        double ez = view.getDoubleOr(prefix + "ez", 0);
        return new GapPlacement(gdx, gdz, new Vec3(ax, ay, az), new Vec3(ex, ey, ez), gdrx, gdrz);
    }
}
