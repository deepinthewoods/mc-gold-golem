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
        // The cross-section axis is the one with FEWER voxels in the plane (the thin direction).
        int numCuts = numSegments - 1; // interior markers count
        boolean[] cutIsX = new boolean[numCuts];
        int[] cutCoord = new int[numCuts];
        for (int ci = 0; ci < numCuts; ci++) {
            BlockPos g = goldMarkersRel.get(chain.get(ci + 1)); // interior marker
            int xCount = 0, zCount = 0;
            for (BlockPos v : allNonGold) {
                if (v.getX() == g.getX()) xCount++;
                if (v.getZ() == g.getZ()) zCount++;
            }
            cutIsX[ci] = xCount <= zCount;
            cutCoord[ci] = cutIsX[ci] ? g.getX() : g.getZ();
            System.out.println("[WallExtractor] Cut " + ci + " at " + (cutIsX[ci] ? "x" : "z") + "=" + cutCoord[ci]
                    + " (xCount=" + xCount + " zCount=" + zCount + ")");
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
}
