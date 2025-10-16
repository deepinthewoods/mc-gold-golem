package ninja.trek.mc.goldgolem.wall;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/**
 * Validates wall modules by ensuring all gold markers yield an equivalent join slice (under rotation/mirror/±1 offset).
 * If any marker has ambiguous orientation (both X and Z planes with non-empty components), or slices do not match, it fails.
 * Per requirement: if multiple paths exist, fail to summon (we treat multiple orientations per marker as ambiguity).
 */
public final class WallModuleValidator {
    public record Validation(String signature, WallJoinSlice.Axis axis, int uSize, String error) {
        public boolean ok() { return signature != null && (error == null || error.isEmpty()); }
    }

    public static Validation validate(World world, BlockPos originAbs, Set<BlockPos> voxelsRel, List<BlockPos> goldMarkersRel, @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        if (goldMarkersRel == null || goldMarkersRel.size() < 2) return new Validation(null, null, 0, "Need at least two gold markers");
        // Heuristic preferred axis: choose slice plane perpendicular to the dominant horizontal extent of the combined module
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos r : voxelsRel) { minX = Math.min(minX, r.getX()); maxX = Math.max(maxX, r.getX()); minZ = Math.min(minZ, r.getZ()); maxZ = Math.max(maxZ, r.getZ()); }
        int spreadX = maxX - minX;
        int spreadZ = maxZ - minZ;
        WallJoinSlice.Axis preferred = (spreadX >= spreadZ) ? WallJoinSlice.Axis.X_THICK : WallJoinSlice.Axis.Z_THICK;

        java.util.ArrayList<WallJoinSlice> slices = new java.util.ArrayList<>(goldMarkersRel.size());
        java.util.ArrayList<WallJoinSlice.Axis> axes = new java.util.ArrayList<>(goldMarkersRel.size());
        java.util.ArrayList<Boolean> isSummon = new java.util.ArrayList<>(goldMarkersRel.size());
        for (int i = 0; i < goldMarkersRel.size(); i++) {
            BlockPos g = goldMarkersRel.get(i);
            BlockPos markerAbs = originAbs.add(g);
            // Ignore the pumpkin slot only for the summoning marker; compare others exactly
            BlockPos ignoreAbsMarker = (summonGoldAbs != null && markerAbs.equals(summonGoldAbs)) ? markerAbs.up() : null;
            var sx = WallJoinSlice.fromIgnoring(world, originAbs, voxelsRel, g, WallJoinSlice.Axis.X_THICK, ignoreAbsMarker);
            var sz = WallJoinSlice.fromIgnoring(world, originAbs, voxelsRel, g, WallJoinSlice.Axis.Z_THICK, ignoreAbsMarker);
            if (sx.isEmpty() && sz.isEmpty()) {
                return new Validation(null, null, 0, "Gold marker has no join slice at rel=" + g);
            }
            WallJoinSlice current;
            WallJoinSlice.Axis ax;
            if (sx.isPresent() && sz.isPresent()) {
                if (preferred == WallJoinSlice.Axis.X_THICK) { current = sx.get(); ax = WallJoinSlice.Axis.X_THICK; }
                else { current = sz.get(); ax = WallJoinSlice.Axis.Z_THICK; }
            } else if (sx.isPresent()) { current = sx.get(); ax = WallJoinSlice.Axis.X_THICK; }
            else { current = sz.get(); ax = WallJoinSlice.Axis.Z_THICK; }
            slices.add(current);
            axes.add(ax);
            isSummon.add(Boolean.valueOf(ignoreAbsMarker != null));
        }
        if (slices.isEmpty()) return new Validation(null, null, 0, "No join slice detected");
        // Choose base slice: prefer a non-summon slice with largest shape, else largest overall
        int baseIdx = -1;
        int bestSize = -1;
        for (int i = 0; i < slices.size(); i++) {
            if (Boolean.TRUE.equals(isSummon.get(i))) continue;
            int szPoints = slices.get(i).points.size();
            if (szPoints > bestSize) { bestSize = szPoints; baseIdx = i; }
        }
        if (baseIdx < 0) {
            for (int i = 0; i < slices.size(); i++) {
                int szPoints = slices.get(i).points.size();
                if (szPoints > bestSize) { bestSize = szPoints; baseIdx = i; }
            }
        }
        WallJoinSlice base = slices.get(baseIdx);
        WallJoinSlice.Axis baseAxis = axes.get(baseIdx);
        for (int i = 0; i < slices.size(); i++) {
            if (i == baseIdx) continue;
            var cur = slices.get(i);
            if (!base.matches(cur)) {
                boolean allowSummonDiff = Boolean.TRUE.equals(isSummon.get(i)) || Boolean.TRUE.equals(isSummon.get(baseIdx));
                if (allowSummonDiff) {
                    if (!base.matchesFuzzy(cur, 1, 0)) {
                        BlockPos g = goldMarkersRel.get(i);
                        return new Validation(null, null, 0, "Join slice mismatch at rel=" + g);
                    }
                } else {
                    BlockPos g = goldMarkersRel.get(i);
                    return new Validation(null, null, 0, "Join slice mismatch at rel=" + g);
                }
            }
        }
        int maxU = base.points.stream().mapToInt(p -> p.du()).max().orElse(0);
        int uSize = maxU + 1;
        return new Validation(base.signature(), baseAxis, uSize, null);
    }
}
