package ninja.trek.mc.goldgolem.world.entity.strategy.path;

import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Represents a line segment for path building.
 * Extracted from GoldGolemEntity inner class.
 */
public class LineSeg {
    public final Vec3 a;
    public final Vec3 b;
    public final double dirX;
    public final double dirZ;
    public final List<BlockPos> cells;

    // Pending placement state (initialized on begin)
    public int widthSnapshot = 1;
    public int half = 0;
    public BitSet processed; // per (cellIndex * width + (j+half))
    public int totalBits = 0;
    public int scanBit = 0;

    public LineSeg(Vec3 a, Vec3 b) {
        this.a = a;
        this.b = b;
        this.dirX = b.x - a.x;
        this.dirZ = b.z - a.z;
        this.cells = computeCells(BlockPos.containing(a.x, 0, a.z), BlockPos.containing(b.x, 0, b.z));
    }

    public void begin(GoldGolemEntity golem) {
        this.widthSnapshot = Math.max(1, Math.min(9, golem.getPathWidth()));
        this.half = (widthSnapshot - 1) / 2;
        this.totalBits = Math.max(0, cells.size() * widthSnapshot);
        this.processed = new BitSet(totalBits);
        this.scanBit = 0;
    }

    public boolean isFullyProcessed() {
        if (totalBits == 0) return true;
        int idx = processed.nextClearBit(0);
        return idx >= totalBits;
    }

    public int progressCellIndex(double gx, double gz) {
        // Project golem XZ onto the AB vector to estimate progress along the line
        double ax = a.x, az = a.z;
        double vx = dirX, vz = dirZ;
        double denom = (vx * vx + vz * vz);
        double t = 0.0;
        if (denom > 1e-6) {
            double wx = gx - ax;
            double wz = gz - az;
            t = (wx * vx + wz * vz) / denom;
        }
        t = Mth.clamp(t, 0.0, 1.0);
        int n = Math.max(1, cells.size());
        return Mth.clamp((int) Math.floor(t * (n - 1)), 0, n - 1);
    }

    public Vec3 pointAtIndex(int idx) {
        if (cells.isEmpty()) return b;
        int i = Mth.clamp(idx, 0, cells.size() - 1);
        BlockPos c = cells.get(i);
        double t = cells.size() <= 1 ? 1.0 : (double) i / (double) (cells.size() - 1);
        double y = Mth.lerp(t, a.y, b.y);
        return new Vec3(c.getX() + 0.5, y, c.getZ() + 0.5);
    }

    public void placePendingUpTo(GoldGolemEntity golem, int boundCell, int maxOps) {
        if (processed == null || totalBits == 0) return;
        int boundExclusive = Math.min(totalBits, Math.max(0, (boundCell + 1) * widthSnapshot));
        // Compute perpendicular
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        double px = len > 1e-4 ? (-dirZ / len) : 0.0;
        double pz = len > 1e-4 ? (dirX / len) : 0.0;
        int ops = 0;
        int bit = processed.nextClearBit(scanBit);
        while (ops < maxOps && bit >= 0 && bit < boundExclusive) {
            int cellIndex = bit / widthSnapshot;
            int jIndex = bit % widthSnapshot;
            int j = jIndex - half;
            BlockPos cell = cells.get(cellIndex);
            double t = cells.size() <= 1 ? 1.0 : (double) cellIndex / (double) (cells.size() - 1);
            double y = Mth.lerp(t, a.y, b.y);
            double x = cell.getX() + 0.5;
            double z = cell.getZ() + 0.5;
            boolean xMajor = Math.abs(dirX) >= Math.abs(dirZ);
            net.minecraft.core.Direction travelDir = xMajor
                    ? (dirX >= 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
                    : (dirZ >= 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
            golem.placeOffsetAt(x, y, z, px, pz, widthSnapshot, j, xMajor, travelDir);
            processed.set(bit); // mark attempted (placed or skipped) to avoid thrash
            ops++;
            bit = processed.nextClearBit(bit + 1);
        }
        scanBit = Math.min(Math.max(0, bit), totalBits);
    }

    public BlockPos placeNextBlock(GoldGolemEntity golem, int boundCell) {
        if (processed == null || totalBits == 0) return null;
        int boundExclusive = Math.min(totalBits, Math.max(0, (boundCell + 1) * widthSnapshot));
        // Compute perpendicular
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        double px = len > 1e-4 ? (-dirZ / len) : 0.0;
        double pz = len > 1e-4 ? (dirX / len) : 0.0;

        int bit = processed.nextClearBit(scanBit);
        if (bit >= 0 && bit < boundExclusive) {
            int cellIndex = bit / widthSnapshot;
            int jIndex = bit % widthSnapshot;
            int j = jIndex - half;
            BlockPos cell = cells.get(cellIndex);
            double t = cells.size() <= 1 ? 1.0 : (double) cellIndex / (double) (cells.size() - 1);
            double y = Mth.lerp(t, a.y, b.y);
            double x = cell.getX() + 0.5;
            double z = cell.getZ() + 0.5;
            boolean xMajor = Math.abs(dirX) >= Math.abs(dirZ);
            net.minecraft.core.Direction travelDir = xMajor
                    ? (dirX >= 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
                    : (dirZ >= 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);

            // Find the actual block position where we'll place
            int bx = Mth.floor(x + px * j);
            int bz = Mth.floor(z + pz * j);
            int y0 = Mth.floor(y);

            // Find ground Y
            var world = golem.level();
            Integer groundY = null;
            for (int yy = y0 + 1; yy >= y0 - 6; yy--) {
                BlockPos test = new BlockPos(bx, yy, bz);
                var st = world.getBlockState(test);
                if (!st.isAir() && st.isCollisionShapeFullBlock(world, test)) {
                    groundY = yy;
                    break;
                }
            }

            BlockPos result = null;
            if (groundY != null) {
                // Find the actual placement position
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos rp = new BlockPos(bx, groundY + dy, bz);
                    var rs = world.getBlockState(rp);
                    if (rs.isAir() || !rs.isCollisionShapeFullBlock(world, rp)) continue;
                    BlockPos ap = rp.above();
                    var as = world.getBlockState(ap);
                    if (as.isCollisionShapeFullBlock(world, ap)) continue;
                    result = rp;
                    break;
                }
            }

            golem.placeOffsetAt(x, y, z, px, pz, widthSnapshot, j, xMajor, travelDir);
            processed.set(bit);
            scanBit = Math.min(Math.max(0, bit + 1), totalBits);

            return result != null ? result : new BlockPos(bx, groundY != null ? groundY : y0, bz);
        }
        return null;
    }

    public BlockPos getNextUnplacedBlock(int boundCell) {
        if (processed == null || totalBits == 0) return null;
        int boundExclusive = Math.min(totalBits, Math.max(0, (boundCell + 1) * widthSnapshot));

        // Find the next unplaced block after scanBit
        int bit = processed.nextClearBit(scanBit);
        if (bit >= 0 && bit < boundExclusive) {
            int cellIndex = bit / widthSnapshot;
            int jIndex = bit % widthSnapshot;
            int j = jIndex - half;
            BlockPos cell = cells.get(cellIndex);
            double t = cells.size() <= 1 ? 1.0 : (double) cellIndex / (double) (cells.size() - 1);
            double y = Mth.lerp(t, a.y, b.y);

            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            double px = len > 1e-4 ? (-dirZ / len) : 0.0;
            double pz = len > 1e-4 ? (dirX / len) : 0.0;
            double x = cell.getX() + 0.5;
            double z = cell.getZ() + 0.5;

            int bx = Mth.floor(x + px * j);
            int bz = Mth.floor(z + pz * j);
            int by = Mth.floor(y);

            return new BlockPos(bx, by, bz);
        }
        return null;
    }

    public int suggestFollowIndex(double gx, double gz, int lookAhead) {
        int prog = progressCellIndex(gx, gz);
        int idx = Math.min(Math.max(0, prog + Math.max(1, lookAhead)), Math.max(0, cells.size() - 1));
        return idx;
    }

    private static List<BlockPos> computeCells(BlockPos a, BlockPos b) {
        // Supercover Bresenham: cover corners when both axes change to avoid diagonal gaps
        ArrayList<BlockPos> out = new ArrayList<>();
        int x0 = a.getX();
        int z0 = a.getZ();
        int x1 = b.getX();
        int z1 = b.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = (x0 < x1) ? 1 : -1;
        int sz = (z0 < z1) ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        out.add(new BlockPos(x, 0, z));
        while (x != x1 || z != z1) {
            int e2 = err << 1;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
                out.add(new BlockPos(x, 0, z));
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
                out.add(new BlockPos(x, 0, z));
            }
        }
        return out;
    }
}
