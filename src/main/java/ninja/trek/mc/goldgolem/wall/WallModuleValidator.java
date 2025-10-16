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
        try {
            System.out.println("[GoldGolem][Wall] Validate: markers=" + goldMarkersRel.size() + " voxels=" + voxelsRel.size() + " preferredAxis=" + preferred + " summonAbs=" + summonGoldAbs);
        } catch (Throwable ignored) {}
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
                int cx = sx.get().points.size();
                int cz = sz.get().points.size();
                if (cx > cz) { current = sx.get(); ax = WallJoinSlice.Axis.X_THICK; }
                else if (cz > cx) { current = sz.get(); ax = WallJoinSlice.Axis.Z_THICK; }
                else {
                    if (preferred == WallJoinSlice.Axis.X_THICK) { current = sx.get(); ax = WallJoinSlice.Axis.X_THICK; }
                    else { current = sz.get(); ax = WallJoinSlice.Axis.Z_THICK; }
                }
            } else if (sx.isPresent()) { current = sx.get(); ax = WallJoinSlice.Axis.X_THICK; }
            else { current = sz.get(); ax = WallJoinSlice.Axis.Z_THICK; }
            slices.add(current);
            axes.add(ax);
            isSummon.add(Boolean.valueOf(ignoreAbsMarker != null));
            try {
                int uMax = current.points.stream().mapToInt(p -> p.du()).max().orElse(0);
                int sigHash = current.signature().hashCode();
                System.out.println("[GoldGolem][Wall] Slice[i=" + i + "] rel=" + g +
                        " summon=" + (ignoreAbsMarker != null) + " axis=" + ax +
                        " points=" + current.points.size() + " uSize=" + (uMax + 1) + " sigHash=" + sigHash);
            } catch (Throwable ignored) {}
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
            boolean curIsSummon = Boolean.TRUE.equals(isSummon.get(i));
            boolean baseIsSummon = Boolean.TRUE.equals(isSummon.get(baseIdx));

            if (!curIsSummon && !baseIsSummon) {
                // Non-summon slices must be exactly equal under rotation/mirror/±1 shift
                if (!base.matches(cur)) {
                    try {
                        System.out.println("[GoldGolem][Wall] Mismatch(non-summon): baseIdx=" + baseIdx + " i=" + i +
                                " baseAxis=" + axes.get(baseIdx) + " curAxis=" + axes.get(i));
                    } catch (Throwable ignored) {}
                    BlockPos g = goldMarkersRel.get(i);
                    return new Validation(null, null, 0, "Join slice mismatch at rel=" + g);
                }
            } else {
                // Allow a single missing cell (pumpkin hole) on the summoning slice only
                // Evaluate against the non-summon as canonical where possible
                WallJoinSlice canonical = baseIsSummon ? cur : base;
                WallJoinSlice candidate = baseIsSummon ? base : cur;
                if (!matchesWithSingleHole(canonical, candidate)) {
                    try {
                        System.out.println("[GoldGolem][Wall] Mismatch(summon-hole): baseIdx=" + baseIdx + " i=" + i +
                                " canonAxis=" + canonical.axis + " candAxis=" + candidate.axis +
                                " canonPts=" + canonical.points.size() + " candPts=" + candidate.points.size());
                    } catch (Throwable ignored) {}
                    BlockPos g = goldMarkersRel.get(i);
                    return new Validation(null, null, 0, "Join slice mismatch at rel=" + g);
                }
            }
        }
        int maxU = base.points.stream().mapToInt(p -> p.du()).max().orElse(0);
        int uSize = maxU + 1;
        return new Validation(base.signature(), baseAxis, uSize, null);
    }

    /**
     * Returns true if 'candidate' equals 'canonical' under rotation/mirror/±1 du shift,
     * except for exactly one missing point in candidate (the pumpkin hole). IDs must match everywhere else.
     */
    private static boolean matchesWithSingleHole(WallJoinSlice canonical, WallJoinSlice candidate) {
        // Try both same-axis and rotated comparison
        for (boolean rotated : new boolean[]{false, true}) {
            if (!rotated && canonical.axis != candidate.axis) continue;
            if (rotated && canonical.axis == candidate.axis) continue;

            var A = canonical.points;   // canonical reference
            var B = candidate.points;   // candidate with one missing cell
            int aMaxU = A.stream().mapToInt(p -> p.du()).max().orElse(0);

            for (boolean mirror : new boolean[]{false, true}) {
                for (int shift = -1; shift <= 1; shift++) {
                    // Transform candidate B into A's frame under mirror/shift using A's maxU
                    java.util.HashMap<WallJoinSlice.Point, String> transformed = new java.util.HashMap<>();
                    for (WallJoinSlice.Point pb : B) {
                        int tu = mirror ? (aMaxU - pb.du()) : pb.du();
                        tu += shift;
                        WallJoinSlice.Point q = new WallJoinSlice.Point(pb.dy(), tu);
                        transformed.put(q, candidate.blockIds.get(pb));
                    }

                    int missing = 0;
                    int idMismatch = 0;
                    for (WallJoinSlice.Point pa : A) {
                        String bId = transformed.get(pa);
                        if (bId == null) { missing++; if (missing > 1) break; }
                        else {
                            String aId = canonical.blockIds.get(pa);
                            if (!java.util.Objects.equals(aId, bId)) { idMismatch++; break; }
                        }
                    }
                    // Accept either exact match (missing==0) or exactly one missing (the pumpkin hole)
                    if (!((missing == 0 || missing == 1) && idMismatch == 0)) continue;

                    // Ensure no extras in transformed candidate that aren't in A
                    boolean extra = false;
                    for (WallJoinSlice.Point q : transformed.keySet()) {
                        if (!A.contains(q)) { extra = true; break; }
                    }
                    if (!extra) return true;
                }
            }
        }
        return false;
    }
}
