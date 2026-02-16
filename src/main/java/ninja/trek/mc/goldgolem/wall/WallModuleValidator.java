package ninja.trek.mc.goldgolem.wall;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Validates wall modules by ensuring all gold markers yield an equivalent join slice
 * (under rotation/mirror/±1 offset). Each marker may have slices on both axes —
 * we try all combinations to find a consistent set.
 */
public final class WallModuleValidator {
    public record Validation(String signature, WallJoinSlice.Axis axis, int uSize, String error) {
        public boolean ok() { return signature != null && (error == null || error.isEmpty()); }
    }

    public static Validation validate(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel, List<BlockPos> goldMarkersRel, @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        if (goldMarkersRel == null || goldMarkersRel.size() < 2) return new Validation(null, null, 0, "Need at least two gold markers");

        int n = goldMarkersRel.size();

        // For each gold marker, compute both X_THICK and Z_THICK slices (either may be absent)
        List<WallJoinSlice> xSlices = new ArrayList<>(n);
        List<WallJoinSlice> zSlices = new ArrayList<>(n);
        List<Boolean> isSummon = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            BlockPos g = goldMarkersRel.get(i);
            BlockPos markerAbs = originAbs.offset(g);
            BlockPos ignoreAbsMarker = (summonGoldAbs != null && markerAbs.equals(summonGoldAbs)) ? markerAbs.above() : null;
            var sx = WallJoinSlice.fromIgnoring(world, originAbs, voxelsRel, g, WallJoinSlice.Axis.X_THICK, ignoreAbsMarker);
            var sz = WallJoinSlice.fromIgnoring(world, originAbs, voxelsRel, g, WallJoinSlice.Axis.Z_THICK, ignoreAbsMarker);
            if (sx.isEmpty() && sz.isEmpty()) {
                return new Validation(null, null, 0, "Gold marker has no join slice at rel=" + g);
            }
            xSlices.add(sx.orElse(null));
            zSlices.add(sz.orElse(null));
            isSummon.add(Boolean.valueOf(ignoreAbsMarker != null));
        }

        // Pick a base marker: prefer a non-summon marker that has at least one slice
        int baseIdx = -1;
        for (int i = 0; i < n; i++) {
            if (!Boolean.TRUE.equals(isSummon.get(i))) {
                baseIdx = i;
                break;
            }
        }
        if (baseIdx < 0) baseIdx = 0; // fallback to first

        // Collect candidate base slices (up to 2: X and Z)
        List<WallJoinSlice> baseCandidates = new ArrayList<>(2);
        if (xSlices.get(baseIdx) != null) baseCandidates.add(xSlices.get(baseIdx));
        if (zSlices.get(baseIdx) != null) baseCandidates.add(zSlices.get(baseIdx));

        // Try each base candidate and see if all other markers have a matching slice
        for (WallJoinSlice base : baseCandidates) {
            boolean allMatch = true;
            // Track which slice was chosen per marker for the result
            WallJoinSlice[] chosen = new WallJoinSlice[n];
            chosen[baseIdx] = base;

            for (int i = 0; i < n; i++) {
                if (i == baseIdx) continue;

                boolean curIsSummon = Boolean.TRUE.equals(isSummon.get(i));
                boolean baseIsSummon = Boolean.TRUE.equals(isSummon.get(baseIdx));

                // Try both X and Z slices for this marker
                WallJoinSlice matchedSlice = null;
                for (WallJoinSlice candidate : new WallJoinSlice[]{xSlices.get(i), zSlices.get(i)}) {
                    if (candidate == null) continue;

                    if (!curIsSummon && !baseIsSummon) {
                        if (base.matches(candidate)) {
                            matchedSlice = candidate;
                            break;
                        }
                    } else {
                        WallJoinSlice canonical = baseIsSummon ? candidate : base;
                        WallJoinSlice cand = baseIsSummon ? base : candidate;
                        if (matchesWithSingleHole(canonical, cand)) {
                            matchedSlice = candidate;
                            break;
                        }
                    }
                }

                if (matchedSlice == null) {
                    allMatch = false;
                    break;
                }
                chosen[i] = matchedSlice;
            }

            if (allMatch) {
                int maxU = base.points.stream().mapToInt(p -> p.du()).max().orElse(0);
                int uSize = maxU + 1;
                return new Validation(base.signature(), base.axis, uSize, null);
            }
        }

        // No consistent combination found — build a debug message
        StringBuilder dbg = new StringBuilder("No matching slice combination found. Per-marker slices: ");
        for (int i = 0; i < n; i++) {
            BlockPos g = goldMarkersRel.get(i);
            dbg.append("\n  marker[").append(i).append("] rel=").append(g);
            if (xSlices.get(i) != null) dbg.append(" X(").append(xSlices.get(i).points.size()).append("pts)");
            if (zSlices.get(i) != null) dbg.append(" Z(").append(zSlices.get(i).points.size()).append("pts)");
            if (Boolean.TRUE.equals(isSummon.get(i))) dbg.append(" [summon]");
        }
        return new Validation(null, null, 0, dbg.toString());
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
