package ninja.trek.mc.goldgolem.wall;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Validates and classifies the local join component at every gold marker. */
public final class WallModuleValidator {
    public record Validation(String signature, WallJoinSlice.Axis axis, int uSize, boolean symmetric,
                             WallJoinSlice referenceSlice,
                             List<WallJoinSlice.Axis> markerAxes,
                             List<WallJoinSlice.Capture> markerCaptures, String error) {
        public Validation {
            markerAxes = markerAxes == null ? List.of() : List.copyOf(markerAxes);
            markerCaptures = markerCaptures == null ? List.of() : List.copyOf(markerCaptures);
        }

        public boolean ok() {
            return signature != null && error == null;
        }

        static Validation error(String message) {
            return new Validation(null, null, 0, false, null, List.of(), List.of(), message);
        }
    }

    private record Selection(List<WallJoinSlice.Axis> axes, List<WallJoinSlice.Capture> captures,
                             int score) {}

    private WallModuleValidator() {}

    public static Validation validate(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel,
                                      List<BlockPos> goldMarkersRel,
                                      @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        List<Integer> identityChain = new ArrayList<>();
        for (int i = 0; i < goldMarkersRel.size(); i++) identityChain.add(i);
        return validate(world, originAbs, voxelsRel, goldMarkersRel, identityChain, summonGoldAbs);
    }

    /**
     * Validate joins in chain order. Axis ambiguity is resolved from local connectivity and marker
     * direction; an exact tie is rejected instead of silently choosing the wrong cut orientation.
     */
    public static Validation validate(Level world, BlockPos originAbs, Set<BlockPos> voxelsRel,
                                      List<BlockPos> goldMarkersRel, List<Integer> chain,
                                      @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        if (goldMarkersRel == null || goldMarkersRel.size() < 2) {
            return Validation.error("Need at least two gold markers");
        }
        if (chain == null || chain.size() != goldMarkersRel.size()) {
            return Validation.error("Marker chain does not contain every gold marker");
        }

        List<BlockPos> orderedMarkers = new ArrayList<>(chain.size());
        for (int index : chain) orderedMarkers.add(goldMarkersRel.get(index));
        BlockState pumpkinOverride = inferPumpkinOverride(
                world, originAbs, orderedMarkers, summonGoldAbs);

        List<List<WallJoinSlice.Capture>> candidates = new ArrayList<>(orderedMarkers.size());
        for (BlockPos marker : orderedMarkers) {
            BlockPos markerAbs = originAbs.offset(marker);
            boolean isSummon = summonGoldAbs != null && markerAbs.equals(summonGoldAbs);
            BlockPos overrideAbs = isSummon ? markerAbs.above() : null;
            BlockState overrideState = isSummon ? pumpkinOverride : null;
            List<WallJoinSlice.Capture> markerCandidates = new ArrayList<>(2);
            for (WallJoinSlice.Axis axis : WallJoinSlice.Axis.values()) {
                WallJoinSlice.captureIgnoring(world, originAbs, voxelsRel, marker, axis,
                                overrideAbs, overrideState)
                        .ifPresent(markerCandidates::add);
            }
            if (markerCandidates.isEmpty()) {
                return Validation.error("Gold marker has no connected join slice at rel=" + marker);
            }
            candidates.add(markerCandidates);
        }

        Set<BlockPos> nonGold = new HashSet<>(voxelsRel);
        nonGold.removeAll(new HashSet<>(goldMarkersRel));
        Selection best = null;
        WallJoinSlice bestReference = null;
        boolean bestIsAmbiguous = false;

        for (List<WallJoinSlice.Capture> baseCandidates : candidates) {
            for (WallJoinSlice.Capture baseCapture : baseCandidates) {
                WallJoinSlice reference = baseCapture.slice();
                List<WallJoinSlice.Axis> selectedAxes = new ArrayList<>(orderedMarkers.size());
                List<WallJoinSlice.Capture> selectedCaptures = new ArrayList<>(orderedMarkers.size());
                int totalScore = 0;
                boolean valid = true;

                for (int markerIndex = 0; markerIndex < orderedMarkers.size(); markerIndex++) {
                    List<WallJoinSlice.Capture> matches = candidates.get(markerIndex).stream()
                            .filter(candidate -> reference.matches(candidate.slice()))
                            .toList();
                    if (matches.isEmpty()) {
                        valid = false;
                        break;
                    }

                    WallJoinSlice.Capture chosen = null;
                    int chosenScore = Integer.MAX_VALUE;
                    boolean tied = false;
                    for (WallJoinSlice.Capture candidate : matches) {
                        int score = axisScore(candidate, markerIndex, orderedMarkers, nonGold);
                        if (score < chosenScore) {
                            chosen = candidate;
                            chosenScore = score;
                            tied = false;
                        } else if (score == chosenScore && chosen != null
                                && chosen.slice().axis != candidate.slice().axis) {
                            tied = true;
                        }
                    }
                    if (tied) {
                        valid = false;
                        break;
                    }
                    selectedAxes.add(chosen.slice().axis);
                    selectedCaptures.add(chosen);
                    totalScore += chosenScore;
                }

                if (valid && (best == null || totalScore < best.score())) {
                    best = new Selection(selectedAxes, selectedCaptures, totalScore);
                    bestReference = reference;
                    bestIsAmbiguous = false;
                } else if (valid && best != null && totalScore == best.score()
                        && !best.axes().equals(selectedAxes)) {
                    bestIsAmbiguous = true;
                }
            }
        }

        if (best == null || bestReference == null || bestIsAmbiguous) {
            return Validation.error(buildFailureMessage(orderedMarkers, candidates));
        }

        int maxU = bestReference.points.stream().mapToInt(WallJoinSlice.Point::du).max().orElse(0);
        return new Validation(bestReference.signature(), bestReference.axis, maxU + 1,
                bestReference.isSymmetric(), bestReference, best.axes(), best.captures(), null);
    }

    private static BlockState inferPumpkinOverride(Level world, BlockPos originAbs,
                                                   List<BlockPos> markers,
                                                   @org.jetbrains.annotations.Nullable BlockPos summonGoldAbs) {
        if (summonGoldAbs == null) return null;
        for (BlockPos marker : markers) {
            BlockPos markerAbs = originAbs.offset(marker);
            if (markerAbs.equals(summonGoldAbs)) continue;
            BlockState candidate = world.getBlockState(markerAbs.above());
            if (!candidate.isAir() && !candidate.is(Blocks.SNOW)
                    && !candidate.is(Blocks.GOLD_BLOCK)) {
                return candidate;
            }
        }
        return null;
    }

    private static int axisScore(WallJoinSlice.Capture capture, int markerIndex,
                                 List<BlockPos> markers, Set<BlockPos> nonGold) {
        WallJoinSlice.Axis axis = capture.slice().axis;
        Direction negative = axis == WallJoinSlice.Axis.X_THICK ? Direction.WEST : Direction.NORTH;
        Direction positive = axis == WallJoinSlice.Axis.X_THICK ? Direction.EAST : Direction.SOUTH;
        boolean touchesNegative = touchesOutside(capture.componentVoxels(), nonGold, negative);
        boolean touchesPositive = touchesOutside(capture.componentVoxels(), nonGold, positive);
        int sideCount = (touchesNegative ? 1 : 0) + (touchesPositive ? 1 : 0);
        int expectedSides = markerIndex == 0 || markerIndex == markers.size() - 1 ? 1 : 2;

        BlockPos marker = markers.get(markerIndex);
        int normalStrength = 0;
        if (markerIndex > 0) {
            normalStrength += normalDistance(marker, markers.get(markerIndex - 1), axis);
        }
        if (markerIndex + 1 < markers.size()) {
            normalStrength += normalDistance(marker, markers.get(markerIndex + 1), axis);
        }
        return Math.abs(sideCount - expectedSides) * 10_000 - normalStrength;
    }

    private static boolean touchesOutside(Set<BlockPos> component, Set<BlockPos> nonGold,
                                          Direction direction) {
        for (BlockPos voxel : component) {
            BlockPos neighbor = voxel.relative(direction);
            if (!component.contains(neighbor) && nonGold.contains(neighbor)) return true;
        }
        return false;
    }

    private static int normalDistance(BlockPos a, BlockPos b, WallJoinSlice.Axis axis) {
        return axis == WallJoinSlice.Axis.X_THICK
                ? Math.abs(a.getX() - b.getX())
                : Math.abs(a.getZ() - b.getZ());
    }

    private static String buildFailureMessage(List<BlockPos> markers,
                                              List<List<WallJoinSlice.Capture>> candidates) {
        StringBuilder message = new StringBuilder(
                "Could not choose one unambiguous, matching local join at every gold marker:");
        for (int i = 0; i < markers.size(); i++) {
            message.append("\n  marker ").append(markers.get(i)).append(':');
            for (WallJoinSlice.Capture candidate : candidates.get(i)) {
                message.append(' ').append(candidate.slice().axis)
                        .append('(').append(candidate.slice().points.size()).append(" blocks)");
            }
        }
        return message.toString();
    }
}
