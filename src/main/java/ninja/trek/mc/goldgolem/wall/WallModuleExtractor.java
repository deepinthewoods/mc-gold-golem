package ninja.trek.mc.goldgolem.wall;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;

/**
 * Extracts module pairings and volumes between validated join slices.
 * Rules:
 * - Build union of all join slices (each marker must have exactly one orientation already ensured by validation).
 * - Create a graph where vertices are markers and an undirected edge (i,j) exists if
 *   there is a path in (voxels - allSliceVoxels) between any neighbor of slice i and any neighbor of slice j.
 * - Require each vertex to have degree exactly 1; otherwise, ambiguity → fail.
 * - For each edge, the module volume is the connected component in (voxels - allSliceVoxels)
 *   reachable from the fringe of i (it will also touch the fringe of j). Enforce size ≤ 2048.
 */
public final class WallModuleExtractor {
    public record Module(BlockPos aMarker, BlockPos bMarker, Set<BlockPos> voxels) {}
    public record ExtractResult(List<Module> modules, String error) {
        public boolean ok() { return modules != null && (error == null || error.isEmpty()); }
    }

    private static final Direction[] DIRS = new Direction[]{
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public static ExtractResult extract(World world, BlockPos originAbs, Set<BlockPos> voxelsRel, List<BlockPos> goldMarkersRel, @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        // Compute slice for each marker; if both orientations exist, choose a preferred plane
        int n = goldMarkersRel.size();
        if (n % 2 != 0) return new ExtractResult(null, "Odd number of gold markers; cannot pair");

        // Preferred axis: perpendicular to dominant horizontal extent of combined module
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos r : voxelsRel) { minX = Math.min(minX, r.getX()); maxX = Math.max(maxX, r.getX()); minZ = Math.min(minZ, r.getZ()); maxZ = Math.max(maxZ, r.getZ()); }
        int spreadX = maxX - minX;
        int spreadZ = maxZ - minZ;
        WallJoinSlice.Axis preferred = (spreadX >= spreadZ) ? WallJoinSlice.Axis.X_THICK : WallJoinSlice.Axis.Z_THICK;

        List<Set<BlockPos>> sliceSets = new ArrayList<>(n);
        Set<BlockPos> allSlices = new HashSet<>();
        for (int i = 0; i < n; i++) {
            BlockPos g = goldMarkersRel.get(i);
            BlockPos markerAbs = originAbs.add(g);
            BlockPos ignoreAbsMarker = (summonGoldAbs != null && markerAbs.equals(summonGoldAbs)) ? markerAbs.up() : null;
            var sx = WallJoinSlice.fromIgnoring(world, originAbs, voxelsRel, g, WallJoinSlice.Axis.X_THICK, ignoreAbsMarker);
            var sz = WallJoinSlice.fromIgnoring(world, originAbs, voxelsRel, g, WallJoinSlice.Axis.Z_THICK, ignoreAbsMarker);
            WallJoinSlice s;
            if (sx.isPresent() && sz.isPresent()) {
                s = (preferred == WallJoinSlice.Axis.X_THICK) ? sx.get() : sz.get();
            } else if (sx.isPresent()) {
                s = sx.get();
            } else if (sz.isPresent()) {
                s = sz.get();
            } else {
                return new ExtractResult(null, "Missing join slice at rel=" + g);
            }
            if (s == null) return new ExtractResult(null, "Missing join slice at rel=" + g);
            // Build rel positions of points back from normalized points; we need actual rel positions composing the slice
            // Reconstruct by scanning voxelsRel in the plane again (cheaper: reuse WallJoinSlice.from path which already filtered plane)
            // Here, reconstruct plane membership from voxelsRel using axis and matching plane coordinate
            int planeCoord = (s.axis == WallJoinSlice.Axis.X_THICK) ? g.getX() : g.getZ();
            Set<BlockPos> planeSet = new HashSet<>();
            for (BlockPos r : voxelsRel) {
                if ((s.axis == WallJoinSlice.Axis.X_THICK && r.getX() == planeCoord) || (s.axis == WallJoinSlice.Axis.Z_THICK && r.getZ() == planeCoord)) {
                    // We need to decide if r belongs to the component; test membership via normalized set
                    int y = r.getY();
                    int u = (s.axis == WallJoinSlice.Axis.X_THICK) ? r.getZ() : r.getX();
                    int minY = s.points.stream().mapToInt(p -> p.dy()).min().orElse(0); // normalized start at 0; minY=0
                    int minU = s.points.stream().mapToInt(p -> p.du()).min().orElse(0); // 0
                    // Since s.points already normalized to min, we need to relocate it back to match continuous region.
                    // We cannot directly compute original offsets, so rely on connectivity filter:
                }
            }
            // To avoid reconstructing via normalization, fetch component again and store positions
            Set<BlockPos> comp = sliceComponentPositions(world, originAbs, voxelsRel, g, s.axis);
            if (comp.isEmpty()) return new ExtractResult(null, "Internal error: empty slice component");
            sliceSets.add(comp);
            allSlices.addAll(comp);
        }

        // Precompute adjacency for BFS: remaining voxels = voxelsRel - allSlices
        Set<BlockPos> passable = new HashSet<>(voxelsRel);
        passable.removeAll(allSlices);

        // Build fringe neighbors for each marker: voxels in passable that are 6-neighbor-adjacent to its slice component
        List<Set<BlockPos>> fringes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Set<BlockPos> fringe = new HashSet<>();
            for (BlockPos s : sliceSets.get(i)) {
                for (Direction d : DIRS) {
                    BlockPos nb = s.offset(d);
                    if (passable.contains(nb)) fringe.add(nb);
                }
            }
            fringes.add(fringe);
            if (fringe.isEmpty()) return new ExtractResult(null, "Join slice has no adjacent interior at marker index " + i);
        }

        // Connectivity graph by BFS reachability between fringes
        List<Set<Integer>> graph = new ArrayList<>(n);
        for (int i = 0; i < n; i++) graph.add(new HashSet<>());

        // Precompute a map from rel positions to small integer ids for compact BFS
        Map<BlockPos, Integer> idMap = new HashMap<>();
        List<BlockPos> rev = new ArrayList<>(passable.size());
        int idx = 0;
        for (BlockPos p : passable) { idMap.put(p, idx++); rev.add(p); }

        // Build adjacency lists for passable voxels
        List<int[]> adj = new ArrayList<>(rev.size());
        for (int i = 0; i < rev.size(); i++) adj.add(new int[0]);
        for (int i = 0; i < rev.size(); i++) {
            BlockPos p = rev.get(i);
            int deg = 0;
            for (Direction d : DIRS) if (idMap.containsKey(p.offset(d))) deg++;
            int[] list = new int[deg];
            int k = 0;
            for (Direction d : DIRS) {
                Integer j = idMap.get(p.offset(d));
                if (j != null) list[k++] = j;
            }
            adj.set(i, list);
        }

        // For each i, BFS once from its fringe to mark reachable nodes; then see which j fringes are touched
        for (int i = 0; i < n; i++) {
            boolean[] vis = new boolean[rev.size()];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (BlockPos f : fringes.get(i)) {
                Integer id = idMap.get(f);
                if (id != null && !vis[id]) { vis[id] = true; q.add(id); }
            }
            while (!q.isEmpty()) {
                int a = q.removeFirst();
                for (int b : adj.get(a)) if (!vis[b]) { vis[b] = true; q.add(b); }
            }
            // Test reachability to other fringes
            for (int j = 0; j < n; j++) if (j != i) {
                boolean hit = false;
                for (BlockPos f : fringes.get(j)) {
                    Integer id = idMap.get(f);
                    if (id != null && vis[id]) { hit = true; break; }
                }
                if (hit) {
                    graph.get(i).add(j);
                }
            }
        }

        // Degree must be exactly 1 for all nodes for unique pairing
        for (int i = 0; i < n; i++) {
            if (graph.get(i).size() != 1) {
                return new ExtractResult(null, "Ambiguous or missing pairing at marker index " + i + " (degree=" + graph.get(i).size() + ")");
            }
        }

        // Construct pair list ensuring i<j unique
        boolean[] used = new boolean[n];
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (used[i]) continue;
            int j = graph.get(i).iterator().next();
            if (used[j]) return new ExtractResult(null, "Pairing cycle detected");
            pairs.add(new int[]{Math.min(i,j), Math.max(i,j)});
            used[i] = used[j] = true;
        }

        // For each pair, extract the module volume by BFS from i's fringe within passable, collecting visited
        List<Module> modules = new ArrayList<>();
        for (int[] pr : pairs) {
            int ia = pr[0], ib = pr[1];
            boolean[] vis = new boolean[rev.size()];
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (BlockPos f : fringes.get(ia)) {
                Integer id = idMap.get(f);
                if (id != null && !vis[id]) { vis[id] = true; q.add(id); }
            }
            Set<BlockPos> comp = new HashSet<>();
            while (!q.isEmpty()) {
                int a = q.removeFirst();
                BlockPos p = rev.get(a);
                comp.add(p);
                if (comp.size() > 2048) {
                    return new ExtractResult(null, "Module between markers exceeds 2048 blocks");
                }
                for (int b : adj.get(a)) if (!vis[b]) { vis[b] = true; q.add(b); }
            }
            modules.add(new Module(goldMarkersRel.get(ia), goldMarkersRel.get(ib), comp));
        }

        if (modules.size() > 64) return new ExtractResult(null, "Too many modules (" + modules.size() + ")");
        return new ExtractResult(modules, null);
    }

    private static Set<BlockPos> sliceComponentPositions(World world, BlockPos originAbs, Set<BlockPos> voxelsRel, BlockPos goldRel, WallJoinSlice.Axis axis) {
        // Recompute in-plane component positions similar to WallJoinSlice.from, but return rel positions
        int planeCoord = (axis == WallJoinSlice.Axis.X_THICK) ? goldRel.getX() : goldRel.getZ();
        Map<Long, BlockPos> index = new HashMap<>();
        for (BlockPos r : voxelsRel) {
            if ((axis == WallJoinSlice.Axis.X_THICK && r.getX() == planeCoord) || (axis == WallJoinSlice.Axis.Z_THICK && r.getZ() == planeCoord)) {
                var st = world.getBlockState(originAbs.add(r));
                if (st.isAir() || st.isOf(net.minecraft.block.Blocks.SNOW) || st.isOf(net.minecraft.block.Blocks.GOLD_BLOCK)) continue;
                int y = r.getY();
                int u = (axis == WallJoinSlice.Axis.X_THICK) ? r.getZ() : r.getX();
                long key = (((long) y) << 32) ^ (u & 0xffffffffL);
                index.put(key, r);
            }
        }
        int gy = goldRel.getY();
        int gu = (axis == WallJoinSlice.Axis.X_THICK) ? goldRel.getZ() : goldRel.getX();
        long startKey = (((long) gy) << 32) ^ (gu & 0xffffffffL);
        if (!index.containsKey(startKey)) {
            boolean seeded = false;
            for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
                long nk = (((long)(gy + d[0])) << 32) ^ ((gu + d[1]) & 0xffffffffL);
                if (index.containsKey(nk)) { startKey = nk; seeded = true; break; }
            }
            if (!seeded) return java.util.Collections.emptySet();
        }
        ArrayDeque<long[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(new long[]{startKey});
        seen.add(startKey);
        Set<BlockPos> out = new HashSet<>();
        while (!q.isEmpty()) {
            long k = q.removeFirst()[0];
            BlockPos r = index.get(k);
            if (r != null) out.add(r);
            int y = (int)(k >> 32);
            int u = (int)k;
            for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
                long nk = (((long)(y + d[0])) << 32) ^ ((u + d[1]) & 0xffffffffL);
                if (index.containsKey(nk) && seen.add(nk)) q.add(new long[]{nk});
            }
        }
        return out;
    }
}
