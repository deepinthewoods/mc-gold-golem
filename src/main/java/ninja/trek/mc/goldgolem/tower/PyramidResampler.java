package ninja.trek.mc.goldgolem.tower;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds deterministic, area-weighted horizontal resamples of repeated tower slices. */
public final class PyramidResampler {
    private static final double EPSILON = 1.0e-9;

    private PyramidResampler() {
    }

    public static int defaultHeight(TowerModuleTemplate template) {
        Bounds bounds = bounds(template);
        if (bounds == null) return 1;
        int maxExtent = Math.max(bounds.width(), bounds.depth());
        return Math.max(1, Math.min(256, (int) Math.ceil((maxExtent - 1) / 2.0) + 1));
    }

    public static Map<BlockPos, BlockState> buildLayer(TowerModuleTemplate template, BlockPos origin,
                                                        int layerY, int totalHeight, int curvature,
                                                        List<String> priority) {
        if (template == null || origin == null || template.moduleHeight <= 0 || layerY < 0
                || layerY >= totalHeight) {
            return Map.of();
        }

        Bounds bounds = bounds(template);
        if (bounds == null) return Map.of();

        int yWithinModule = Math.floorMod(layerY, template.moduleHeight);
        int relYTarget = template.minY + yWithinModule;
        int absoluteY = origin.getY() - 1 + layerY;
        double scale = scaleForLayer(layerY, totalHeight, curvature, bounds);
        double centerX = (bounds.minX + bounds.maxX) / 2.0;
        double centerZ = (bounds.minZ + bounds.maxZ) / 2.0;

        Map<Long, Map<BlockState, Double>> votes = new HashMap<>();
        for (TowerModuleTemplate.Voxel voxel : template.voxels) {
            if (voxel.rel.getY() != relYTarget) continue;

            double minX = centerX + (voxel.rel.getX() - 0.5 - centerX) * scale;
            double maxX = centerX + (voxel.rel.getX() + 0.5 - centerX) * scale;
            double minZ = centerZ + (voxel.rel.getZ() - 0.5 - centerZ) * scale;
            double maxZ = centerZ + (voxel.rel.getZ() + 0.5 - centerZ) * scale;
            int startX = (int) Math.floor(minX - 0.5) + 1;
            int endX = (int) Math.ceil(maxX + 0.5) - 1;
            int startZ = (int) Math.floor(minZ - 0.5) + 1;
            int endZ = (int) Math.ceil(maxZ + 0.5) - 1;

            for (int x = startX; x <= endX; x++) {
                double overlapX = Math.min(maxX, x + 0.5) - Math.max(minX, x - 0.5);
                if (overlapX <= EPSILON) continue;
                for (int z = startZ; z <= endZ; z++) {
                    double overlapZ = Math.min(maxZ, z + 0.5) - Math.max(minZ, z - 0.5);
                    double area = overlapX * overlapZ;
                    if (area <= EPSILON) continue;
                    BlockPos destination = new BlockPos(origin.getX() + x, absoluteY, origin.getZ() + z);
                    votes.computeIfAbsent(destination.asLong(), ignored -> new HashMap<>())
                            .merge(voxel.state, area, Double::sum);
                }
            }
        }

        Map<String, Integer> priorityIndex = new HashMap<>();
        if (priority != null) {
            for (int i = 0; i < priority.size(); i++) priorityIndex.putIfAbsent(priority.get(i), i);
        }
        Comparator<Map.Entry<BlockState, Double>> winnerOrder = Comparator
                .<Map.Entry<BlockState, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparingInt(entry -> priorityIndex.getOrDefault(blockId(entry.getKey()), Integer.MAX_VALUE))
                .thenComparing(entry -> entry.getKey().toString());

        List<Long> orderedPositions = new ArrayList<>(votes.keySet());
        orderedPositions.sort(Long::compare);
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (long packed : orderedPositions) {
            Map.Entry<BlockState, Double> winner = votes.get(packed).entrySet().stream()
                    .min(winnerOrder)
                    .orElse(null);
            if (winner != null) result.put(BlockPos.of(packed), winner.getKey());
        }
        return result;
    }

    static double scaleForLayer(int layerY, int totalHeight, int curvature, TowerModuleTemplate template) {
        Bounds bounds = bounds(template);
        return bounds == null ? 1.0 : scaleForLayer(layerY, totalHeight, curvature, bounds);
    }

    private static double scaleForLayer(int layerY, int totalHeight, int curvature, Bounds bounds) {
        int maxExtent = Math.max(bounds.width(), bounds.depth());
        double minimumScale = 1.0 / Math.max(1, maxExtent);
        // A one-layer build can preserve either the captured base or the apex, but not both.
        // Keep the base exact so selecting height 1 never unexpectedly rewrites the template.
        if (totalHeight <= 1) return 1.0;
        double progress = Math.max(0.0, Math.min(1.0, (double) layerY / (totalHeight - 1)));
        double exponent = Math.pow(4.0, Math.max(-100, Math.min(100, curvature)) / 100.0);
        double shrink = Math.pow(progress, exponent);
        return minimumScale + (1.0 - minimumScale) * (1.0 - shrink);
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static Bounds bounds(TowerModuleTemplate template) {
        if (template == null || template.voxels.isEmpty()) return null;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (TowerModuleTemplate.Voxel voxel : template.voxels) {
            minX = Math.min(minX, voxel.rel.getX());
            maxX = Math.max(maxX, voxel.rel.getX());
            minZ = Math.min(minZ, voxel.rel.getZ());
            maxZ = Math.max(maxZ, voxel.rel.getZ());
        }
        return new Bounds(minX, maxX, minZ, maxZ);
    }

    private record Bounds(int minX, int maxX, int minZ, int maxZ) {
        int width() { return maxX - minX + 1; }
        int depth() { return maxZ - minZ + 1; }
    }
}
