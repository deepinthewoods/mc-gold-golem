package ninja.trek.mc.goldgolem.wall;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel;

/**
 * Represents a 1-wide vertical join slice lying in a plane of thickness 1 along X or Z.
 * Includes the in-plane shape as a set of (dy, du) and the block ids at those positions.
 */
public final class WallJoinSlice {
    public enum Axis { X_THICK, Z_THICK }

    /** The normalized join profile together with the exact structure voxels that formed it. */
    public record Capture(WallJoinSlice slice, Set<BlockPos> componentVoxels) {}

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
        return captureIgnoring(world, originAbs, voxelsRel, goldRel, axis, overrideAbs, overrideState)
                .map(Capture::slice);
    }

    /**
     * Capture the local, connected join component. Unlike a plane cut, this records only the
     * voxels belonging to the marker's join profile, so unrelated geometry in the same plane is
     * never removed during module extraction.
     */
    public static Optional<Capture> captureIgnoring(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel,
                                                     BlockPos goldRel, Axis axis, BlockPos overrideAbs,
                                                     @org.jetbrains.annotations.Nullable BlockState overrideState) {
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
        return Optional.of(new Capture(
                new WallJoinSlice(axis, pts, ids, states),
                Collections.unmodifiableSet(new HashSet<>(component))));
    }

    /** Check whether two complete profiles are equivalent under a horizontal D4 transform. */
    public boolean matches(WallJoinSlice other) {
        if (other == null) return false;
        for (int rot = 0; rot < 4; rot++) {
            for (boolean mirror : new boolean[]{false, true}) {
                if (profileEquals(other.transformed(rot, mirror))) return true;
            }
        }
        return false;
    }

    /** Check if the slice is mirror-symmetric along du, including block-state orientation. */
    public boolean isSymmetric() {
        int maxU = points.stream().mapToInt(p -> p.du).max().orElse(0);
        for (Point p : points) {
            Point mirrored = new Point(p.dy, maxU - p.du);
            if (!points.contains(mirrored)) return false;
            BlockState state = blockStates.get(p);
            BlockState mirroredState = blockStates.get(mirrored);
            if (state != null && mirroredState != null) {
                Mirror sliceMirror = axis == Axis.X_THICK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
                if (!state.mirror(sliceMirror).equals(mirroredState)) return false;
            } else if (!Objects.equals(blockIds.get(p), blockIds.get(mirrored))) {
                return false;
            }
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
            BlockState state = blockStates.get(p);
            String value = state != null ? serializeState(state) : blockIds.get(p);
            sb.append(value == null ? "" : value).append(';');
        }
        return sb.toString();
    }

    /** Serialize a BlockState to a string with properties, e.g. "minecraft:oak_stairs[facing=north,half=bottom]". */
    public static String serializeState(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var values = state.getValues().toList();
        if (values.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id);
        sb.append('[');
        boolean first = true;
        for (var value : values) {
            if (!first) sb.append(',');
            sb.append(value.property().getName()).append('=').append(value.valueName());
            first = false;
        }
        sb.append(']');
        return sb.toString();
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

    /** Build a slice from template voxels at a given marker's plane along the given axis. */
    public static Optional<WallJoinSlice> fromTemplateVoxels(List<Voxel> voxels, BlockPos markerRel, Axis axis) {
        int planeCoord = (axis == Axis.X_THICK) ? markerRel.getX() : markerRel.getZ();

        // Build map from position to state
        Map<BlockPos, BlockState> stateMap = new HashMap<>();
        for (var v : voxels) {
            stateMap.put(v.rel, v.state);
        }

        // Filter voxels in the plane
        List<BlockPos> plane = new ArrayList<>();
        for (var v : voxels) {
            BlockPos r = v.rel;
            if ((axis == Axis.X_THICK && r.getX() == planeCoord) || (axis == Axis.Z_THICK && r.getZ() == planeCoord)) {
                if (v.state.is(Blocks.SNOW) || v.state.is(Blocks.GOLD_BLOCK)) continue;
                if (v.state.isAir()) continue;
                plane.add(r);
            }
        }
        if (plane.isEmpty()) return Optional.empty();

        // Build 2D grid for BFS: (y, u)
        int gy = markerRel.getY();
        int gu = (axis == Axis.X_THICK) ? markerRel.getZ() : markerRel.getX();
        Map<Long, BlockPos> index = new HashMap<>();
        for (BlockPos r : plane) {
            int y = r.getY();
            int u = (axis == Axis.X_THICK) ? r.getZ() : r.getX();
            long key = (((long) y) << 32) ^ (u & 0xffffffffL);
            index.put(key, r);
        }

        // BFS from marker position (marker itself likely not in voxels — it's the gold block)
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        long startKey = (((long) gy) << 32) ^ (gu & 0xffffffffL);
        if (!index.containsKey(startKey)) {
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

        // Normalize to (dy, du)
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
            BlockState st = stateMap.get(r);
            if (st != null) {
                ids.put(p, BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
                states.put(p, st);
            }
        }
        return Optional.of(new WallJoinSlice(axis, pts, ids, states));
    }

    /**
     * Return a new slice with axis rotated (for rot 1,3) and du values mirrored
     * if the placement transform flips the du direction.
     */
    public WallJoinSlice transformed(int rot, boolean mirror) {
        Axis newAxis = this.axis;
        if (rot == 1 || rot == 3) {
            newAxis = (this.axis == Axis.X_THICK) ? Axis.Z_THICK : Axis.X_THICK;
        }

        boolean duMirrored = isDuMirrored(this.axis, rot, mirror);

        int maxDu = this.points.stream().mapToInt(p -> p.du).max().orElse(0);
        Set<Point> newPts = new HashSet<>();
        Map<Point, String> newIds = new HashMap<>();
        Map<Point, BlockState> newStates = new HashMap<>();
        for (Point p : this.points) {
            Point np = new Point(p.dy, duMirrored ? maxDu - p.du : p.du);
            newPts.add(np);
            BlockState st = this.blockStates.get(p);
            if (st != null) {
                BlockState transformedState = transformState(st, rot, mirror);
                newStates.put(np, transformedState);
                newIds.put(np, BuiltInRegistries.BLOCK.getKey(transformedState.getBlock()).toString());
            } else {
                String id = this.blockIds.get(p);
                if (id != null) newIds.put(np, id);
            }
        }
        return new WallJoinSlice(newAxis, newPts, newIds, newStates);
    }

    /** Backward-compatible name used by placement code. */
    public WallJoinSlice transformedDu(int rot, boolean mirror) {
        return transformed(rot, mirror);
    }

    private static BlockState transformState(BlockState state, int rot, boolean mirror) {
        Rotation rotation = switch (rot & 3) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        BlockState transformed = state.rotate(rotation);
        return mirror ? transformed.mirror(Mirror.FRONT_BACK) : transformed;
    }

    /**
     * Check if a placement transform (rot, mirror) flips the du direction of a slice.
     */
    public static boolean isDuMirrored(Axis origAxis, int rot, boolean mirror) {
        // du unit vector in 3D: X_THICK → du along Z → (0,0,1), Z_THICK → du along X → (1,0,0)
        int dx = (origAxis == Axis.Z_THICK) ? 1 : 0;
        int dz = (origAxis == Axis.X_THICK) ? 1 : 0;
        // Apply rotation (same logic as ModulePlacement.rotateAndMirror)
        int rx = dx, rz = dz;
        switch (rot & 3) {
            case 1 -> { int ox = rx; rx = -rz; rz = ox; }
            case 2 -> { rx = -rx; rz = -rz; }
            case 3 -> { int ox = rx; rx = rz; rz = -ox; }
        }
        if (mirror) rx = -rx;
        Axis newAxis = origAxis;
        if (rot == 1 || rot == 3) {
            newAxis = (origAxis == Axis.X_THICK) ? Axis.Z_THICK : Axis.X_THICK;
        }
        // Check du component in new axis frame: Z_THICK → du is X, X_THICK → du is Z
        int comp = (newAxis == Axis.Z_THICK) ? rx : rz;
        return comp < 0;
    }

    /** Exact match of axis, points set, and blockIds at each point. */
    public boolean profileEquals(WallJoinSlice other) {
        if (other == null) return false;
        if (this.axis != other.axis) return false;
        if (!this.points.equals(other.points)) return false;
        for (Point p : this.points) {
            BlockState thisState = this.blockStates.get(p);
            BlockState otherState = other.blockStates.get(p);
            if (thisState != null && otherState != null) {
                if (!thisState.equals(otherState)) return false;
            } else if (!Objects.equals(this.blockIds.get(p), other.blockIds.get(p))) {
                return false;
            }
        }
        return true;
    }

    /** Construct a slice from deserialized state strings, while accepting legacy plain block ids. */
    public static WallJoinSlice fromData(Axis axis, Set<Point> points, Map<Point, String> blockIds) {
        Map<Point, String> ids = new HashMap<>();
        Map<Point, BlockState> states = new HashMap<>();
        for (Point point : points) {
            String serialized = blockIds.get(point);
            // A plain id is legacy data that did not preserve properties. Keep it id-only so
            // profileEquals can remain backward compatible instead of treating a default state
            // as if it had actually been saved.
            BlockState state = serialized != null && serialized.indexOf('[') >= 0
                    ? parseState(serialized) : null;
            if (state != null) {
                states.put(point, state);
                ids.put(point, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            } else if (serialized != null) {
                ids.put(point, serialized);
            }
        }
        return new WallJoinSlice(axis, new HashSet<>(points), ids, states);
    }
}
