package ninja.trek.mc.goldgolem.wall;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Extracts wall modules from a chain of gold markers using planar slice cuts.
 *
 * Gold markers form a chain via nearest-neighbor walk. Interior markers define
 * cut planes along the cross-section axis. Removing all voxels at each cut plane
 * separates the structure into connected components — one per module.
 */
public final class WallModuleExtractor {
    public record Module(BlockPos aMarker, BlockPos bMarker, Set<BlockPos> voxels) {}
    public record ExtractResult(List<Module> modules, String error) {
        public boolean ok() { return modules != null && (error == null || error.isEmpty()); }
    }

    public static ExtractResult extract(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel, List<BlockPos> goldMarkersRel, @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        int n = goldMarkersRel.size();
        if (n < 2) return new ExtractResult(null, "Need at least 2 gold markers");

        // Step 1: Build chain order via nearest-neighbor walk
        List<Integer> chain = buildChain(goldMarkersRel);
        if (chain.size() != n) {
            return new ExtractResult(null, "Could not build chain from " + n + " markers (got " + chain.size() + ")");
        }

        int numSegments = chain.size() - 1;

        // Step 2: Collect non-gold voxels
        Set<BlockPos> goldSet = new HashSet<>(goldMarkersRel);
        Set<BlockPos> allNonGold = new HashSet<>();
        for (BlockPos r : voxelsRel) {
            if (!goldSet.contains(r)) allNonGold.add(r);
        }

        // Step 3: For each interior marker, determine the cut plane.
        // A valid cross-section plane will not pass through any other gold marker,
        // since gold blocks are used exclusively as module boundary markers.
        int numCuts = numSegments - 1; // interior markers count
        boolean[] cutIsX = new boolean[numCuts];
        int[] cutCoord = new int[numCuts];
        boolean[] resolved = new boolean[numCuts];

        for (int ci = 0; ci < numCuts; ci++) {
            BlockPos g = goldMarkersRel.get(chain.get(ci + 1)); // interior marker

            // For each candidate plane, BFS from the gold marker within the plane
            // and check if the connected component contains any OTHER gold marker.
            boolean xPlaneHasOtherGold = planeSliceContainsOtherGold(g, true, voxelsRel, goldSet);
            boolean zPlaneHasOtherGold = planeSliceContainsOtherGold(g, false, voxelsRel, goldSet);

            if (!xPlaneHasOtherGold && zPlaneHasOtherGold) {
                cutIsX[ci] = true;
                cutCoord[ci] = g.getX();
                resolved[ci] = true;
            } else if (xPlaneHasOtherGold && !zPlaneHasOtherGold) {
                cutIsX[ci] = false;
                cutCoord[ci] = g.getZ();
                resolved[ci] = true;
            }
            // else: both valid or both invalid — left unresolved for propagation
        }

        // Propagation: use the first resolved cut axis to resolve ambiguous ones.
        // In a straight wall all cuts share the same axis.
        Boolean knownAxis = null;
        for (int ci = 0; ci < numCuts; ci++) {
            if (resolved[ci]) { knownAxis = cutIsX[ci]; break; }
        }
        for (int ci = 0; ci < numCuts; ci++) {
            if (!resolved[ci] && knownAxis != null) {
                BlockPos g = goldMarkersRel.get(chain.get(ci + 1));
                cutIsX[ci] = knownAxis;
                cutCoord[ci] = knownAxis ? g.getX() : g.getZ();
                resolved[ci] = true;
            }
        }

        // Final fallback: if no cuts resolved (e.g. all gold markers collinear on both axes),
        // use heuristic of picking the axis with fewer voxels in the plane
        for (int ci = 0; ci < numCuts; ci++) {
            if (!resolved[ci]) {
                BlockPos g = goldMarkersRel.get(chain.get(ci + 1));
                int xCount = 0, zCount = 0;
                for (BlockPos v : allNonGold) {
                    if (v.getX() == g.getX()) xCount++;
                    if (v.getZ() == g.getZ()) zCount++;
                }
                cutIsX[ci] = xCount <= zCount;
                cutCoord[ci] = cutIsX[ci] ? g.getX() : g.getZ();
            }
        }

        for (int ci = 0; ci < numCuts; ci++) {
            BlockPos g = goldMarkersRel.get(chain.get(ci + 1));
            System.out.println("[WallExtractor] Cut " + ci + " at " + (cutIsX[ci] ? "x" : "z") + "=" + cutCoord[ci]
                    + " (resolved=" + resolved[ci] + ", marker=" + g + ")");
        }

        // Step 4: Remove all voxels at cut planes from the working set
        Set<BlockPos> cutVoxels = new HashSet<>();
        Set<BlockPos> working = new HashSet<>(allNonGold);
        for (int ci = 0; ci < numCuts; ci++) {
            boolean isX = cutIsX[ci];
            int coord = cutCoord[ci];
            Iterator<BlockPos> it = working.iterator();
            while (it.hasNext()) {
                BlockPos v = it.next();
                if ((isX ? v.getX() : v.getZ()) == coord) {
                    cutVoxels.add(v);
                    it.remove();
                }
            }
        }

        // Step 5: Find connected components via 6-neighbor BFS
        List<Set<BlockPos>> components = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos seed : working) {
            if (!visited.add(seed)) continue;
            Set<BlockPos> comp = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            comp.add(seed);
            while (!queue.isEmpty()) {
                BlockPos cur = queue.removeFirst();
                for (Direction dir : Direction.values()) {
                    BlockPos nb = cur.relative(dir);
                    if (working.contains(nb) && visited.add(nb)) {
                        queue.add(nb);
                        comp.add(nb);
                    }
                }
            }
            components.add(comp);
        }

        System.out.println("[WallExtractor] After cuts: " + components.size() + " components, " + cutVoxels.size() + " cut voxels, expected " + numSegments + " segments");

        // Step 6: Assign each component to its nearest segment, then re-add cut voxels
        List<Set<BlockPos>> segmentVoxels = new ArrayList<>(numSegments);
        for (int i = 0; i < numSegments; i++) segmentVoxels.add(new HashSet<>());

        for (Set<BlockPos> comp : components) {
            // Centroid of the component
            double cx = 0, cy = 0, cz = 0;
            for (BlockPos p : comp) { cx += p.getX(); cy += p.getY(); cz += p.getZ(); }
            cx /= comp.size(); cy /= comp.size(); cz /= comp.size();

            double bestCost = Double.MAX_VALUE;
            int bestSeg = 0;
            for (int seg = 0; seg < numSegments; seg++) {
                BlockPos a = goldMarkersRel.get(chain.get(seg));
                BlockPos b = goldMarkersRel.get(chain.get(seg + 1));
                double da = Math.sqrt(sq(cx - a.getX()) + sq(cy - a.getY()) + sq(cz - a.getZ()));
                double db = Math.sqrt(sq(cx - b.getX()) + sq(cy - b.getY()) + sq(cz - b.getZ()));
                if (da + db < bestCost) { bestCost = da + db; bestSeg = seg; }
            }
            segmentVoxels.get(bestSeg).addAll(comp);
        }

        // Re-add cut voxels: each goes to the nearest segment
        for (BlockPos cv : cutVoxels) {
            double bestCost = Double.MAX_VALUE;
            int bestSeg = 0;
            for (int seg = 0; seg < numSegments; seg++) {
                BlockPos a = goldMarkersRel.get(chain.get(seg));
                BlockPos b = goldMarkersRel.get(chain.get(seg + 1));
                double cost = dist(cv, a) + dist(cv, b);
                if (cost < bestCost) { bestCost = cost; bestSeg = seg; }
            }
            segmentVoxels.get(bestSeg).add(cv);
        }

        // Step 7: Build modules
        List<Module> modules = new ArrayList<>();
        for (int seg = 0; seg < numSegments; seg++) {
            int ia = chain.get(seg);
            int ib = chain.get(seg + 1);
            Set<BlockPos> voxels = segmentVoxels.get(seg);
            if (voxels.isEmpty()) {
                return new ExtractResult(null, "Empty module between markers " + ia + " and " + ib);
            }
            if (voxels.size() > 4096) {
                return new ExtractResult(null, "Module between markers " + ia + " and " + ib + " exceeds 4096 blocks (" + voxels.size() + ")");
            }
            modules.add(new Module(goldMarkersRel.get(ia), goldMarkersRel.get(ib), voxels));
        }

        if (modules.size() > 64) return new ExtractResult(null, "Too many modules (" + modules.size() + ")");

        System.out.println("[WallExtractor] Chain: " + chain + ", modules: " + modules.size());
        for (int i = 0; i < modules.size(); i++) {
            var m = modules.get(i);
            System.out.println("[WallExtractor]   module " + i + ": a=" + m.aMarker() + " b=" + m.bMarker() + " voxels=" + m.voxels().size());
        }

        return new ExtractResult(modules, null);
    }

    private static double sq(double v) { return v * v; }

    /**
     * Build a chain (path) through gold markers using nearest-neighbor heuristic.
     * Start from the marker whose nearest neighbor is farthest away (likely an endpoint).
     */
    private static List<Integer> buildChain(List<BlockPos> markers) {
        int n = markers.size();
        if (n <= 1) return List.of(0);

        int startIdx = 0;
        double maxMinDist = -1;
        for (int i = 0; i < n; i++) {
            double minDist = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double d = dist(markers.get(i), markers.get(j));
                minDist = Math.min(minDist, d);
            }
            if (minDist > maxMinDist) {
                maxMinDist = minDist;
                startIdx = i;
            }
        }

        List<Integer> chain = new ArrayList<>();
        boolean[] used = new boolean[n];
        int cur = startIdx;
        while (true) {
            chain.add(cur);
            used[cur] = true;
            int next = -1;
            double bestDist = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (used[j]) continue;
                double d = dist(markers.get(cur), markers.get(j));
                if (d < bestDist) { bestDist = d; next = j; }
            }
            if (next < 0) break;
            cur = next;
        }
        return chain;
    }

    private static double dist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * BFS within a plane slice from the given gold marker and check if the connected
     * component contains any other gold block. The plane is defined by either
     * x = marker.x (isX=true) or z = marker.z (isX=false). Connectivity is 4-neighbor
     * within the 2D plane (y + the other horizontal axis).
     */
    private static boolean planeSliceContainsOtherGold(BlockPos marker, boolean isX,
                                                        Set<BlockPos> allVoxels, Set<BlockPos> goldSet) {
        int planeCoord = isX ? marker.getX() : marker.getZ();

        // Index all voxels in this plane by their 2D (y, u) key
        // u = z when isX, u = x when !isX
        Map<Long, BlockPos> planeIndex = new HashMap<>();
        for (BlockPos v : allVoxels) {
            if ((isX ? v.getX() : v.getZ()) == planeCoord) {
                int y = v.getY();
                int u = isX ? v.getZ() : v.getX();
                long key = (((long) y) << 32) ^ (u & 0xffffffffL);
                planeIndex.put(key, v);
            }
        }

        // BFS from the marker position within the plane
        int startY = marker.getY();
        int startU = isX ? marker.getZ() : marker.getX();
        long startKey = (((long) startY) << 32) ^ (startU & 0xffffffffL);
        if (!planeIndex.containsKey(startKey)) return false;

        Set<Long> visited = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        visited.add(startKey);
        queue.add(new long[]{startY, startU});

        while (!queue.isEmpty()) {
            long[] cur = queue.removeFirst();
            int cy = (int) cur[0];
            int cu = (int) cur[1];
            long ck = (((long) cy) << 32) ^ (cu & 0xffffffffL);
            BlockPos v = planeIndex.get(ck);
            if (v != null && !v.equals(marker) && goldSet.contains(v)) {
                return true;
            }
            for (int[] d : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) {
                int ny = cy + d[0];
                int nu = cu + d[1];
                long nk = (((long) ny) << 32) ^ (nu & 0xffffffffL);
                if (planeIndex.containsKey(nk) && visited.add(nk)) {
                    queue.add(new long[]{ny, nu});
                }
            }
        }
        return false;
    }
}
