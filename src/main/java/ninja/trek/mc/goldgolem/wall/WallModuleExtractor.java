package ninja.trek.mc.goldgolem.wall;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Extracts modules by cutting only the classified local join components. */
public final class WallModuleExtractor {
    public record Module(BlockPos aMarker, BlockPos bMarker, Set<BlockPos> voxels,
                         WallJoinSlice.Axis aAxis, WallJoinSlice.Axis bAxis) {}

    public record ExtractResult(List<Module> modules, List<Integer> chain,
                                WallModuleValidator.Validation validation, String error) {
        public boolean ok() {
            return modules != null && error == null;
        }

        static ExtractResult error(String message) {
            return new ExtractResult(null, null, null, message);
        }
    }

    record ChainResult(List<Integer> chain, List<Map<BlockPos, Integer>> distanceFields,
                       String error) {
        boolean ok() {
            return chain != null && error == null;
        }
    }

    private WallModuleExtractor() {}

    public static ExtractResult extract(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel,
                                        List<BlockPos> goldMarkersRel,
                                        @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        if (goldMarkersRel.size() < 2) return ExtractResult.error("Need at least 2 gold markers");

        ChainResult chainResult = buildMarkerChain(voxelsRel, goldMarkersRel);
        if (!chainResult.ok()) return ExtractResult.error(chainResult.error());
        List<Integer> chain = chainResult.chain();

        WallModuleValidator.Validation validation = WallModuleValidator.validate(
                world, originAbs, voxelsRel, goldMarkersRel, chain, summonGoldAbs);
        if (!validation.ok()) return ExtractResult.error(validation.error());

        List<BlockPos> orderedMarkers = new ArrayList<>(chain.size());
        for (int index : chain) orderedMarkers.add(goldMarkersRel.get(index));
        int segmentCount = orderedMarkers.size() - 1;

        Set<BlockPos> working = new HashSet<>(voxelsRel);
        working.removeAll(new HashSet<>(goldMarkersRel));
        Set<BlockPos> cutVoxels = new HashSet<>();
        for (WallJoinSlice.Capture capture : validation.markerCaptures()) {
            cutVoxels.addAll(capture.componentVoxels());
        }
        working.removeAll(cutVoxels);

        List<Set<BlockPos>> components = connectedComponents(working);
        List<Set<BlockPos>> segmentVoxels = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) segmentVoxels.add(new HashSet<>());

        for (Set<BlockPos> component : components) {
            BitSet touchedMarkers = touchedMarkers(component, validation.markerCaptures());
            int segment = chooseSegment(component, touchedMarkers, orderedMarkers,
                    chain, chainResult.distanceFields());
            if (segment < 0) {
                return ExtractResult.error("A wall component could not be assigned unambiguously "
                        + "to a marker pair (size=" + component.size() + ", touches="
                        + touchedMarkers + ")");
            }
            segmentVoxels.get(segment).addAll(component);
        }

        // Every module owns a copy of both exact join components. Placement already suppresses
        // duplicate world blocks, but retaining both ends is essential for exact input/output joins.
        for (int segment = 0; segment < segmentCount; segment++) {
            segmentVoxels.get(segment).addAll(
                    validation.markerCaptures().get(segment).componentVoxels());
            segmentVoxels.get(segment).addAll(
                    validation.markerCaptures().get(segment + 1).componentVoxels());
        }

        List<Module> modules = new ArrayList<>(segmentCount);
        for (int segment = 0; segment < segmentCount; segment++) {
            Set<BlockPos> voxels = segmentVoxels.get(segment);
            if (voxels.isEmpty()) {
                return ExtractResult.error("Empty module between markers "
                        + orderedMarkers.get(segment) + " and " + orderedMarkers.get(segment + 1));
            }
            if (voxels.size() > 4096) {
                return ExtractResult.error("Module between markers " + orderedMarkers.get(segment)
                        + " and " + orderedMarkers.get(segment + 1) + " exceeds 4096 blocks ("
                        + voxels.size() + ")");
            }
            modules.add(new Module(orderedMarkers.get(segment), orderedMarkers.get(segment + 1),
                    Collections.unmodifiableSet(new HashSet<>(voxels)),
                    validation.markerAxes().get(segment),
                    validation.markerAxes().get(segment + 1)));
        }

        if (modules.size() > 64) return ExtractResult.error("Too many modules (" + modules.size() + ")");
        System.out.println("[WallExtractor] geodesic chain=" + chain + ", modules=" + modules.size()
                + ", local join voxels=" + cutVoxels.size());
        return new ExtractResult(List.copyOf(modules), chain, validation, null);
    }

    /**
     * Build marker adjacency from shortest paths through the actual scanned structure. Two markers
     * are adjacent only when no third marker lies on a shortest path between them. The resulting
     * graph must be one simple path; branches and loops are rejected as ambiguous samples.
     */
    static ChainResult buildMarkerChain(Set<BlockPos> voxels, List<BlockPos> markers) {
        int count = markers.size();
        if (count < 2) return new ChainResult(null, List.of(), "Need at least 2 gold markers");

        List<Map<BlockPos, Integer>> distanceFields = new ArrayList<>(count);
        int[][] distances = new int[count][count];
        for (int i = 0; i < count; i++) {
            Map<BlockPos, Integer> field = distancesFrom(markers.get(i), voxels);
            distanceFields.add(field);
            for (int j = 0; j < count; j++) {
                Integer distance = field.get(markers.get(j));
                if (distance == null) {
                    return new ChainResult(null, List.of(), "Gold markers are not connected through "
                            + "the scanned wall: " + markers.get(i) + " to " + markers.get(j));
                }
                distances[i][j] = distance;
            }
        }

        List<Set<Integer>> adjacency = new ArrayList<>(count);
        for (int i = 0; i < count; i++) adjacency.add(new HashSet<>());
        int edgeCount = 0;
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                boolean markerBetween = false;
                for (int k = 0; k < count; k++) {
                    if (k != i && k != j
                            && distances[i][j] == distances[i][k] + distances[k][j]) {
                        markerBetween = true;
                        break;
                    }
                }
                if (!markerBetween) {
                    adjacency.get(i).add(j);
                    adjacency.get(j).add(i);
                    edgeCount++;
                }
            }
        }

        List<Integer> endpoints = new ArrayList<>(2);
        for (int i = 0; i < count; i++) {
            int degree = adjacency.get(i).size();
            if (degree == 1) endpoints.add(i);
            if (degree < 1 || degree > 2) {
                return new ChainResult(null, List.of(), "Gold markers form a branched or ambiguous "
                        + "layout at " + markers.get(i) + " (degree=" + degree + ")");
            }
        }
        if (edgeCount != count - 1 || endpoints.size() != 2) {
            return new ChainResult(null, List.of(), "Gold markers form a loop or ambiguous layout; "
                    + "they must describe one path");
        }

        Comparator<Integer> markerOrder = Comparator
                .comparingInt((Integer i) -> markers.get(i).getX())
                .thenComparingInt(i -> markers.get(i).getY())
                .thenComparingInt(i -> markers.get(i).getZ());
        int current = endpoints.stream().min(markerOrder).orElseThrow();
        int previous = -1;
        List<Integer> chain = new ArrayList<>(count);
        while (current >= 0) {
            chain.add(current);
            int next = -1;
            for (int candidate : adjacency.get(current)) {
                if (candidate != previous) {
                    next = candidate;
                    break;
                }
            }
            previous = current;
            current = next;
        }
        if (chain.size() != count) {
            return new ChainResult(null, List.of(), "Gold marker path is disconnected");
        }
        return new ChainResult(List.copyOf(chain), List.copyOf(distanceFields), null);
    }

    private static Map<BlockPos, Integer> distancesFrom(BlockPos start, Set<BlockPos> voxels) {
        Map<BlockPos, Integer> distances = new HashMap<>();
        if (!voxels.contains(start)) return distances;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        distances.put(start, 0);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            int nextDistance = distances.get(current) + 1;
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (voxels.contains(next) && distances.putIfAbsent(next, nextDistance) == null) {
                    queue.addLast(next);
                }
            }
        }
        return distances;
    }

    private static List<Set<BlockPos>> connectedComponents(Set<BlockPos> voxels) {
        List<Set<BlockPos>> components = new ArrayList<>();
        Set<BlockPos> unvisited = new HashSet<>(voxels);
        while (!unvisited.isEmpty()) {
            BlockPos seed = unvisited.iterator().next();
            Set<BlockPos> component = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            unvisited.remove(seed);
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                component.add(current);
                for (Direction direction : Direction.values()) {
                    BlockPos next = current.relative(direction);
                    if (unvisited.remove(next)) queue.addLast(next);
                }
            }
            components.add(component);
        }
        return components;
    }

    private static BitSet touchedMarkers(Set<BlockPos> component,
                                         List<WallJoinSlice.Capture> captures) {
        BitSet touched = new BitSet(captures.size());
        for (int marker = 0; marker < captures.size(); marker++) {
            Set<BlockPos> join = captures.get(marker).componentVoxels();
            outer:
            for (BlockPos voxel : component) {
                for (Direction direction : Direction.values()) {
                    if (join.contains(voxel.relative(direction))) {
                        touched.set(marker);
                        break outer;
                    }
                }
            }
        }
        return touched;
    }

    private static int chooseSegment(Set<BlockPos> component, BitSet touchedMarkers,
                                     List<BlockPos> orderedMarkers, List<Integer> chain,
                                     List<Map<BlockPos, Integer>> distanceFields) {
        if (touchedMarkers.cardinality() > 2) return -1;
        if (touchedMarkers.cardinality() == 2) {
            int first = touchedMarkers.nextSetBit(0);
            int second = touchedMarkers.nextSetBit(first + 1);
            if (second != first + 1) return -1;
            return first;
        }

        long bestCost = Long.MAX_VALUE;
        int bestSegment = -1;
        boolean tied = false;
        for (int segment = 0; segment < orderedMarkers.size() - 1; segment++) {
            if (!touchedMarkers.isEmpty()
                    && !touchedMarkers.get(segment) && !touchedMarkers.get(segment + 1)) {
                continue;
            }
            Map<BlockPos, Integer> fromA = distanceFields.get(chain.get(segment));
            Map<BlockPos, Integer> fromB = distanceFields.get(chain.get(segment + 1));
            long minA = Long.MAX_VALUE;
            long minB = Long.MAX_VALUE;
            for (BlockPos voxel : component) {
                Integer da = fromA.get(voxel);
                Integer db = fromB.get(voxel);
                if (da != null) minA = Math.min(minA, da);
                if (db != null) minB = Math.min(minB, db);
            }
            if (minA == Long.MAX_VALUE || minB == Long.MAX_VALUE) continue;
            long markerDistance = fromA.getOrDefault(orderedMarkers.get(segment + 1), 0);
            long cost = minA + minB - markerDistance;
            if (cost < bestCost) {
                bestCost = cost;
                bestSegment = segment;
                tied = false;
            } else if (cost == bestCost) {
                tied = true;
            }
        }
        return tied ? -1 : bestSegment;
    }
}
