package ninja.trek.mc.goldgolem.terraforming;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Alpha shape / concave hull generator for 2D point sets.
 * Used to wrap a surface around skeleton points on each Y level.
 */
public class AlphaShape {

    /**
     * Generates a shell around skeleton points on a specific Y level.
     *
     * @param skeletonPoints Points on this Y level (all should have same Y coordinate)
     * @param alpha Alpha parameter (controls concavity, typically 3-5 blocks)
     * @param y The Y level
     * @return Set of positions that form the shell (surface blocks)
     */
    public static Set<BlockPos> generateShell(List<BlockPos> skeletonPoints, int alpha, int y) {
        if (skeletonPoints.isEmpty()) {
            return new HashSet<>();
        }

        // Extract 2D points (X, Z)
        Set<Point2D> points2D = new HashSet<>();
        for (BlockPos pos : skeletonPoints) {
            points2D.add(new Point2D(pos.getX(), pos.getZ()));
        }

        // For small point sets, use convex hull
        if (points2D.size() < 4) {
            return fillConvexHull(points2D, y);
        }

        // Generate concave hull using alpha shape approach
        Set<Point2D> shellPoints2D = generateConcaveHull(points2D, alpha);

        // Convert back to 3D BlockPos and fill interior
        Set<BlockPos> shell = new HashSet<>();
        for (Point2D p : shellPoints2D) {
            shell.add(new BlockPos(p.x, y, p.z));
        }

        return shell;
    }

    /**
     * Simplified concave hull algorithm.
     * Creates a boundary by connecting nearby points and filling the interior.
     */
    private static Set<Point2D> generateConcaveHull(Set<Point2D> points, int alpha) {
        Set<Point2D> result = new HashSet<>();

        // Find bounding box
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (Point2D p : points) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }

        // Expand by alpha to create shell boundary
        int expandedMinX = minX - alpha;
        int expandedMaxX = maxX + alpha;
        int expandedMinZ = minZ - alpha;
        int expandedMaxZ = maxZ + alpha;

        // Scan all positions in expanded bounding box
        for (int x = expandedMinX; x <= expandedMaxX; x++) {
            for (int z = expandedMinZ; z <= expandedMaxZ; z++) {
                Point2D candidate = new Point2D(x, z);

                // Check if this point is within alpha distance of any skeleton point
                boolean nearSkeleton = false;
                for (Point2D skelPoint : points) {
                    if (distanceSquared(candidate, skelPoint) <= alpha * alpha) {
                        nearSkeleton = true;
                        break;
                    }
                }

                if (nearSkeleton) {
                    result.add(candidate);
                }
            }
        }

        return result;
    }

    /**
     * Fallback for small point sets: create a simple filled convex hull.
     */
    private static Set<BlockPos> fillConvexHull(Set<Point2D> points, int y) {
        Set<BlockPos> result = new HashSet<>();

        if (points.isEmpty()) return result;

        // Find bounding box
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (Point2D p : points) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }

        // Simple rectangular fill
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                result.add(new BlockPos(x, y, z));
            }
        }

        return result;
    }

    private static int distanceSquared(Point2D a, Point2D b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /**
     * Simple 2D point for XZ plane calculations.
     */
    private static class Point2D {
        final int x;
        final int z;

        Point2D(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point2D p)) return false;
            return x == p.x && z == p.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
        }
    }
}
