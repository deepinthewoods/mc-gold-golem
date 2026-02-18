package ninja.trek.mc.goldgolem.wall;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Represents a 1-wide vertical join slice lying in a plane of thickness 1 along X or Z.
 * Includes the in-plane shape as a set of (dy, du) and the block ids at those positions.
 */
public final class WallJoinSlice {
    public enum Axis { X_THICK, Z_THICK }

    public final Axis axis; // which plane: X_THICK means x = const, Z_THICK means z = const
    public final Set<Point> points; // normalized (dy, du) pairs starting at (0,0)
    public final Map<Point, String> blockIds; // block id per point
    public final Map<Point, BlockState> blockStates; // full block state per point (for placement)

    public record Point(int dy, int du) {
        public Point add(int oy, int ou) { return new Point(dy + oy, du + ou); }
    }

    private WallJoinSlice(Axis axis, Set<Point> points, Map<Point, String> blockIds, Map<Point, BlockState> blockStates) {
        this.axis = axis;
        this.points = Collections.unmodifiableSet(points);
        this.blockIds = Collections.unmodifiableMap(blockIds);
        this.blockStates = Collections.unmodifiableMap(blockStates);
    }

    /** Build a slice from the plane through goldRel (relative to originAbs) along the given axis. */
    public static Optional<WallJoinSlice> from(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel, BlockPos goldRel, Axis axis) {
        return fromIgnoring(world, originAbs, voxelsRel, goldRel, axis, null, null);
    }

    /** Backward-compatible delegate: override position used only as BFS bridge, excluded from points. */
    public static Optional<WallJoinSlice> fromIgnoring(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel, BlockPos goldRel, Axis axis, BlockPos overrideAbs) {
        return fromIgnoring(world, originAbs, voxelsRel, goldRel, axis, overrideAbs, null);
    }

    /**
     * Build a slice from the plane through goldRel along the given axis.
     * If overrideAbs and overrideState are both non-null, the override position is included
     * in the slice with the given state (used to fill in the pumpkin slot with the correct block).
     * If overrideAbs is non-null but overrideState is null, the position is used only as a BFS
     * bridge for connectivity but excluded from the final slice points.
     */
    public static Optional<WallJoinSlice> fromIgnoring(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel, BlockPos goldRel, Axis axis, BlockPos overrideAbs, @org.jetbrains.annotations.Nullable BlockState overrideState) {
        int planeCoord = (axis == Axis.X_THICK) ? goldRel.getX() : goldRel.getZ();
        boolean includeOverride = overrideAbs != null && overrideState != null;
        BlockPos overrideRel = overrideAbs != null ? overrideAbs.subtract(originAbs) : null;

        List<BlockPos> plane = new ArrayList<>();
        Set<Long> ignoreKeys = new HashSet<>();
        boolean overrideInPlane = false;
        for (BlockPos r : voxelsRel) {
            if ((axis == Axis.X_THICK && r.getX() == planeCoord) || (axis == Axis.Z_THICK && r.getZ() == planeCoord)) {
                BlockPos abs = originAbs.offset(r);
                var st = world.getBlockState(abs);
                if (st.is(Blocks.SNOW) || st.is(Blocks.GOLD_BLOCK)) continue;
                if (overrideAbs != null && abs.equals(overrideAbs)) {
                    plane.add(r);
                    overrideInPlane = true;
                    if (!includeOverride) {
                        int y = r.getY();
                        int u = (axis == Axis.X_THICK) ? r.getZ() : r.getX();
                        long key = (((long) y) << 32) ^ (u & 0xffffffffL);
                        ignoreKeys.add(key);
                    }
                    continue;
                }
                if (st.isAir()) continue;
                plane.add(r);
            }
        }
        // If override position wasn't found in voxelsRel (e.g., air before pumpkin placement),
        // synthetically add it so the slice stays connected (and optionally included in points)
        if (overrideAbs != null && !overrideInPlane && overrideRel != null) {
            if ((axis == Axis.X_THICK && overrideRel.getX() == planeCoord) || (axis == Axis.Z_THICK && overrideRel.getZ() == planeCoord)) {
                plane.add(overrideRel);
                if (!includeOverride) {
                    int y = overrideRel.getY();
                    int u = (axis == Axis.X_THICK) ? overrideRel.getZ() : overrideRel.getX();
                    long key = (((long) y) << 32) ^ (u & 0xffffffffL);
                    ignoreKeys.add(key);
                }
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
            // Include in component only if not an ignored (pumpkin) position
            if (r != null && !ignoreKeys.contains(ck)) component.add(r);
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
        Map<Point, BlockState> states = new HashMap<>();
        for (BlockPos r : component) {
            int dy = r.getY() - minY;
            int du = ((axis == Axis.X_THICK) ? r.getZ() : r.getX()) - minU;
            Point p = new Point(dy, du);
            pts.add(p);
            BlockState st;
            if (includeOverride && overrideRel != null && r.equals(overrideRel)) {
                st = overrideState;
            } else {
                st = world.getBlockState(originAbs.offset(r));
            }
            ids.put(p, BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
            states.put(p, st);
        }
        return Optional.of(new WallJoinSlice(axis, pts, ids, states));
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

    /** Check if the slice is mirror-symmetric along the du axis (shape and block ids). */
    public boolean isSymmetric() {
        int maxU = points.stream().mapToInt(p -> p.du).max().orElse(0);
        for (Point p : points) {
            Point mirrored = new Point(p.dy, maxU - p.du);
            if (!points.contains(mirrored)) return false;
            String id = blockIds.get(p);
            String mirId = blockIds.get(mirrored);
            if (!Objects.equals(id, mirId)) return false;
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

    /** Serialize a BlockState to a string with properties, e.g. "minecraft:oak_stairs[facing=north,half=bottom]". */
    public static String serializeState(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.getValues().isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id);
        sb.append('[');
        boolean first = true;
        for (var entry : state.getValues().entrySet()) {
            if (!first) sb.append(',');
            sb.append(entry.getKey().getName()).append('=').append(propertyValueName(entry.getKey(), entry.getValue()));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propertyValueName(Property<?> rawProp, Comparable<?> rawVal) {
        Property<T> prop = (Property<T>) rawProp;
        T val = (T) rawVal;
        return prop.getName(val);
    }

    /** Parse a block state string back to a BlockState. Handles both "id[props]" and plain "id" formats. */
    public static BlockState parseState(String str) {
        if (str == null || str.isEmpty()) return null;
        int bracket = str.indexOf('[');
        String blockId = bracket >= 0 ? str.substring(0, bracket) : str;
        var ident = net.minecraft.resources.Identifier.tryParse(blockId);
        if (ident == null) return null;
        var block = BuiltInRegistries.BLOCK.getValue(ident);
        if (block == null) return null;
        BlockState state = block.defaultBlockState();
        if (bracket < 0) return state;
        // Parse properties from "[key=val,key=val,...]"
        int end = str.indexOf(']', bracket);
        if (end < 0) return state;
        String propsStr = str.substring(bracket + 1, end);
        if (propsStr.isEmpty()) return state;
        var stateDefinition = block.getStateDefinition();
        for (String pair : propsStr.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            Property<?> prop = stateDefinition.getProperty(kv[0]);
            if (prop != null) {
                state = applyProperty(state, prop, kv[1]);
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> prop, String valueName) {
        Optional<T> val = prop.getValue(valueName);
        return val.map(v -> state.setValue(prop, v)).orElse(state);
    }
}
