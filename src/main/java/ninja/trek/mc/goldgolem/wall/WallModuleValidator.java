package ninja.trek.mc.goldgolem.wall;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Validates wall modules by ensuring all gold markers yield an equivalent join slice
 * (under rotation/mirror/±1 offset). Each marker may have slices on both axes —
 * we try all combinations to find a consistent set.
 */
public final class WallModuleValidator {
    public record Validation(String signature, WallJoinSlice.Axis axis, int uSize, boolean symmetric, String error) {
        public boolean ok() { return signature != null && (error == null || error.isEmpty()); }
    }

    public static Validation validate(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel, List<BlockPos> goldMarkersRel, @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        if (goldMarkersRel == null || goldMarkersRel.size() < 2) return new Validation(null, null, 0, false, "Need at least two gold markers");

        int n = goldMarkersRel.size();

        // Infer the block state for the pumpkin position (above summon gold) from a non-summon marker.
        // The pumpkin hasn't been placed yet during validation, so we look at the equivalent position
        // (directly above) on another gold marker to determine what block belongs there.
        BlockState pumpkinOverride = null;
        if (summonGoldAbs != null) {
            for (BlockPos g : goldMarkersRel) {
                BlockPos gAbs = originAbs.offset(g);
                if (gAbs.equals(summonGoldAbs)) continue;
                BlockState candidate = world.getBlockState(gAbs.above());
                if (!candidate.isAir() && !candidate.is(Blocks.SNOW) && !candidate.is(Blocks.GOLD_BLOCK)) {
                    pumpkinOverride = candidate;
                    break;
                }
            }
        }

        // For each gold marker, compute both X_THICK and Z_THICK slices.
        // For the summon marker, include the pumpkin position with the inferred block so all
        // slices are complete and can be compared with plain matches().
        List<WallJoinSlice> xSlices = new ArrayList<>(n);
        List<WallJoinSlice> zSlices = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            BlockPos g = goldMarkersRel.get(i);
            BlockPos markerAbs = originAbs.offset(g);
            boolean isSummon = summonGoldAbs != null && markerAbs.equals(summonGoldAbs);
            BlockPos overrideAbs = isSummon ? markerAbs.above() : null;
            BlockState overrideState = isSummon ? pumpkinOverride : null;
            var sx = WallJoinSlice.fromIgnoring(world, originAbs, voxelsRel, g, WallJoinSlice.Axis.X_THICK, overrideAbs, overrideState);
            var sz = WallJoinSlice.fromIgnoring(world, originAbs, voxelsRel, g, WallJoinSlice.Axis.Z_THICK, overrideAbs, overrideState);
            if (sx.isEmpty() && sz.isEmpty()) {
                return new Validation(null, null, 0, false, "Gold marker has no join slice at rel=" + g);
            }
            xSlices.add(sx.orElse(null));
            zSlices.add(sz.orElse(null));
        }

        // Try every marker as potential base and every axis slice as reference.
        for (int baseIdx = 0; baseIdx < n; baseIdx++) {
            List<WallJoinSlice> baseCandidates = new ArrayList<>(2);
            if (xSlices.get(baseIdx) != null) baseCandidates.add(xSlices.get(baseIdx));
            if (zSlices.get(baseIdx) != null) baseCandidates.add(zSlices.get(baseIdx));

            for (WallJoinSlice base : baseCandidates) {
                boolean allMatch = true;

                for (int i = 0; i < n; i++) {
                    if (i == baseIdx) continue;

                    boolean matched = false;
                    for (WallJoinSlice candidate : new WallJoinSlice[]{xSlices.get(i), zSlices.get(i)}) {
                        if (candidate != null && base.matches(candidate)) {
                            matched = true;
                            break;
                        }
                    }

                    if (!matched) {
                        allMatch = false;
                        break;
                    }
                }

                if (allMatch) {
                    int maxU = base.points.stream().mapToInt(p -> p.du()).max().orElse(0);
                    int uSize = maxU + 1;
                    boolean symmetric = base.isSymmetric();
                    return new Validation(base.signature(), base.axis, uSize, symmetric, null);
                }
            }
        }

        // No consistent combination found — build a debug message
        StringBuilder dbg = new StringBuilder("No matching slice combination found. Per-marker slices: ");
        for (int i = 0; i < n; i++) {
            BlockPos g = goldMarkersRel.get(i);
            dbg.append("\n  marker[").append(i).append("] rel=").append(g);
            if (xSlices.get(i) != null) dbg.append(" X(").append(xSlices.get(i).points.size()).append("pts)");
            if (zSlices.get(i) != null) dbg.append(" Z(").append(zSlices.get(i).points.size()).append("pts)");
            BlockPos markerAbs = originAbs.offset(g);
            if (summonGoldAbs != null && markerAbs.equals(summonGoldAbs)) dbg.append(" [summon]");
        }

        // Log full structure to console for debugging
        System.out.println("[WallValidator] " + dbg);
        System.out.println("[WallValidator] origin=" + originAbs + " voxels=" + voxelsRel.size() + " markers=" + n);
        for (int i = 0; i < n; i++) {
            BlockPos g = goldMarkersRel.get(i);
            BlockPos markerAbs = originAbs.offset(g);
            boolean isSummon = summonGoldAbs != null && markerAbs.equals(summonGoldAbs);
            System.out.println("[WallValidator]   marker[" + i + "] rel=" + g + " abs=" + markerAbs
                    + (isSummon ? " [summon]" : ""));
            if (xSlices.get(i) != null) {
                System.out.println("[WallValidator]     X slice (" + xSlices.get(i).points.size() + " pts): " + xSlices.get(i).signature());
            }
            if (zSlices.get(i) != null) {
                System.out.println("[WallValidator]     Z slice (" + zSlices.get(i).points.size() + " pts): " + zSlices.get(i).signature());
            }
        }
        System.out.println("[WallValidator] All voxels (rel to origin):");
        List<BlockPos> sortedVoxels = new ArrayList<>(voxelsRel);
        sortedVoxels.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for (BlockPos v : sortedVoxels) {
            BlockPos abs = originAbs.offset(v);
            System.out.println("[WallValidator]   " + v + " abs=" + abs + " block=" + world.getBlockState(abs).getBlock());
        }

        return new Validation(null, null, 0, false, dbg.toString());
    }
}
