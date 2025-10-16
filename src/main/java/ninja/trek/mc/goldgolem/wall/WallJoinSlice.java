package ninja.trek.mc.goldgolem.wall;

import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;

/**
 * Represents a 1-wide vertical join slice lying in a plane of thickness 1 along X or Z.
 * Includes the in-plane shape as a set of (dy, du) and the block ids at those positions.
 */
public final class WallJoinSlice {
    public enum Axis { X_THICK, Z_THICK }

    public final Axis axis; // which plane: X_THICK means x = const, Z_THICK means z = const
    public final Set<Point> points; // normalized (dy, du) pairs starting at (0,0)
    public final Map<Point, String> blockIds; // block id per point

    public record Point(int dy, int du) {
        public Point add(int oy, int ou) { return new Point(dy + oy, du + ou); }
    }

    private WallJoinSlice(Axis axis, Set<Point> points, Map<Point, String> blockIds) {
        this.axis = axis;
        this.points = Collections.unmodifiableSet(points);
        this.blockIds = Collections.unmodifiableMap(blockIds);
    }

    /** Build a slice from the plane through goldRel (relative to originAbs) along the given axis. */
    public static Optional<WallJoinSlice> from(World world, BlockPos originAbs, Set<BlockPos> voxelsRel, BlockPos goldRel, Axis axis) {
        return fromIgnoring(world, originAbs, voxelsRel, goldRel, axis, null);
    }

    public static Optional<WallJoinSlice> fromIgnoring(World world, BlockPos originAbs, Set<BlockPos> voxelsRel, BlockPos goldRel, Axis axis, BlockPos ignoreAbs) {
        int planeCoord = (axis == Axis.X_THICK) ? goldRel.getX() : goldRel.getZ();

        // Collect all rel voxels lying in the plane
        List<BlockPos> plane = new ArrayList<>();
        for (BlockPos r : voxelsRel) {
            if ((axis == Axis.X_THICK && r.getX() == planeCoord) || (axis == Axis.Z_THICK && r.getZ() == planeCoord)) {
                BlockPos abs = originAbs.add(r);
                if (ignoreAbs != null && abs.equals(ignoreAbs)) continue;
                var st = world.getBlockState(abs);
                // exclude snow layers and gold blocks from slice
                if (st.isOf(Blocks.SNOW) || st.isOf(Blocks.GOLD_BLOCK)) continue;
                if (st.isAir()) continue;
                plane.add(r);
            }
        }
        if (plane.isEmpty()) return Optional.empty();

        // Build a 2D grid for BFS in-plane to extract the connected component containing the gold's in-plane coordinate
        // In-plane coordinates: (y, u) where u is z for X_THICK, or x for Z_THICK
        int gy = goldRel.getY();
        int gu = (axis == Axis.X_THICK) ? goldRel.getZ() : goldRel.getX();
        Map<Long, BlockPos> index = new HashMap<>();
        for (BlockPos r : plane) {
            int y = r.getY();
            int u = (axis == Axis.X_THICK) ? r.getZ() : r.getX();
            long key = (((long) y) << 32) ^ (u & 0xffffffffL);
            index.put(key, r);
        }
        // BFS from (gy, gu)
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        long startKey = (((long) gy) << 32) ^ (gu & 0xffffffffL);
        if (!index.containsKey(startKey)) {
            // If the gold cell itself is not in the plane set (due to exclusion), try neighbors in-plane
            boolean seeded = false;
            for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
                long nk = (((long)(gy + d[0])) << 32) ^ ((gu + d[1]) & 0xffffffffL);
                if (index.containsKey(nk)) { startKey = nk; seeded = true; break; }
            }
            if (!seeded) return Optional.empty();
        }
        q.add(new int[]{(int)(startKey >> 32), (int)startKey});
        seen.add(startKey);
        List<BlockPos> component = new ArrayList<>();
        while (!q.isEmpty()) {
            int[] cur = q.removeFirst();
            long ck = (((long) cur[0]) << 32) ^ (cur[1] & 0xffffffffL);
            BlockPos r = index.get(ck);
            if (r != null) component.add(r);
            for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
                int ny = cur[0] + d[0];
                int nu = cur[1] + d[1];
                long nk = (((long) ny) << 32) ^ (nu & 0xffffffffL);
                if (index.containsKey(nk) && seen.add(nk)) q.add(new int[]{ny, nu});
            }
        }
        if (component.isEmpty()) return Optional.empty();

        // Normalize component to (dy, du) with min y and min u as origin
        int minY = Integer.MAX_VALUE, minU = Integer.MAX_VALUE;
        for (BlockPos r : component) {
            minY = Math.min(minY, r.getY());
            int u = (axis == Axis.X_THICK) ? r.getZ() : r.getX();
            minU = Math.min(minU, u);
        }
        Set<Point> pts = new HashSet<>();
        Map<Point, String> ids = new HashMap<>();
        for (BlockPos r : component) {
            int dy = r.getY() - minY;
            int du = ((axis == Axis.X_THICK) ? r.getZ() : r.getX()) - minU;
            Point p = new Point(dy, du);
            pts.add(p);
            var st = world.getBlockState(originAbs.add(r));
            ids.put(p, Registries.BLOCK.getId(st.getBlock()).toString());
        }
        return Optional.of(new WallJoinSlice(axis, pts, ids));
    }

    /**
     * Check if two slices match under rotation (X<->Z), mirroring in-plane (even mirroring supported via offset),
     * and small in-plane offsets (du in [-1,0,1]). Vertical (Y) offsets are normalized away.
     */
    public boolean matches(WallJoinSlice other) {
        // Try both same-axis and rotated comparison: if axes differ, treat as rotated
        for (boolean rotated : new boolean[]{false, true}) {
            if (!rotated && this.axis != other.axis) continue;
            if (rotated && this.axis == other.axis) continue;

            // Build normalized representations for both
            var A = this.points;
            var B = other.points;

            // Compute bounds for mirror
            int aMaxU = A.stream().mapToInt(p -> p.du).max().orElse(0);
            int bMaxU = B.stream().mapToInt(p -> p.du).max().orElse(0);

            for (boolean mirror : new boolean[]{false, true}) {
                for (int shift = -1; shift <= 1; shift++) {
                    if (equalUnder(A, this.blockIds, aMaxU, B, other.blockIds, bMaxU, mirror, shift)) return true;
                }
            }
        }
        return false;
    }

    /** Fuzzy match allowing small shape or id differences (to tolerate summon interior or filtered ground types). */
    public boolean matchesFuzzy(WallJoinSlice other, int maxShapeDiff, int maxIdMismatch) {
        for (boolean rotated : new boolean[]{false, true}) {
            if (!rotated && this.axis != other.axis) continue;
            if (rotated && this.axis == other.axis) continue;

            var A = this.points;
            var B = other.points;
            int aMaxU = A.stream().mapToInt(p -> p.du).max().orElse(0);
            int bMaxU = B.stream().mapToInt(p -> p.du).max().orElse(0);

            for (boolean mirror : new boolean[]{false, true}) {
                for (int shift = -1; shift <= 1; shift++) {
                    // Build transformed B set and compare with tolerance
                    java.util.HashSet<Point> bset = new java.util.HashSet<>();
                    for (Point pb : B) {
                        int bu = mirror ? (bMaxU - pb.du) : pb.du;
                        bu += shift;
                        bset.add(new Point(pb.dy, bu));
                    }
                    int shapeDiff = 0;
                    int idMismatch = 0;
                    // Count A not in B and mismatched ids for A∩B
                    for (Point pa : A) {
                        if (!bset.contains(pa)) { shapeDiff++; if (shapeDiff > maxShapeDiff) break; }
                        else {
                            String aId = this.blockIds.get(pa);
                            String bId = other.blockIds.get(new Point(pa.dy, mirror ? (bMaxU - pa.du) + shift : (pa.du + shift)));
                            if (!java.util.Objects.equals(aId, bId)) { idMismatch++; if (idMismatch > maxIdMismatch) break; }
                        }
                    }
                    if (shapeDiff > maxShapeDiff || idMismatch > maxIdMismatch) continue;
                    // Count extras in B not in A
                    java.util.HashSet<Point> aset = new java.util.HashSet<>(A);
                    for (Point pb : B) {
                        int bu = mirror ? (bMaxU - pb.du) : pb.du;
                        bu += shift;
                        Point q = new Point(pb.dy, bu);
                        if (!aset.contains(q)) { shapeDiff++; if (shapeDiff > maxShapeDiff) break; }
                    }
                    if (shapeDiff <= maxShapeDiff && idMismatch <= maxIdMismatch) return true;
                }
            }
        }
        return false;
    }

    private static boolean equalUnder(Set<Point> A, Map<Point, String> idA, int aMaxU,
                                      Set<Point> B, Map<Point, String> idB, int bMaxU,
                                      boolean mirror, int shift) {
        if (A.size() != B.size()) return false;
        // Build transformed B set into A's frame
        for (Point p : A) {
            int bu = mirror ? (bMaxU - p.du) : p.du;
            bu += shift;
            Point q = new Point(p.dy, bu);
            if (!B.contains(q)) return false;
            String aId = idA.get(p);
            String bId = idB.get(q);
            if (!Objects.equals(aId, bId)) return false;
        }
        return true;
    }

    /** A compact signature string for persistence and debugging. */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        sb.append(axis == Axis.X_THICK ? 'X' : 'Z');
        sb.append('|');
        // Sort points for stable signature
        List<Point> list = new ArrayList<>(points);
        list.sort(Comparator.<Point>comparingInt(p -> p.dy).thenComparingInt(p -> p.du));
        for (Point p : list) {
            sb.append(p.dy).append(':').append(p.du).append('#');
            String id = blockIds.get(p);
            sb.append(id == null ? "" : id).append(';');
        }
        return sb.toString();
    }
}
