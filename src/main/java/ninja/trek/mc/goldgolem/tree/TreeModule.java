package ninja.trek.mc.goldgolem.tree;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Represents a single input module captured during Tree Mode scanning.
 * A module is a connected region of blocks separated from other modules by gold blocks.
 * Stores relative block positions from the scan origin.
 */
public final class TreeModule {
    public final Set<BlockPos> voxels; // relative positions from origin
    public final BlockPos centroid; // approximate center for reference

    public TreeModule(Set<BlockPos> voxels) {
        this.voxels = Collections.unmodifiableSet(new HashSet<>(voxels));
        this.centroid = calculateCentroid(voxels);
    }

    private static BlockPos calculateCentroid(Set<BlockPos> voxels) {
        if (voxels.isEmpty()) {
            return BlockPos.ORIGIN;
        }
        long sumX = 0, sumY = 0, sumZ = 0;
        for (BlockPos pos : voxels) {
            sumX += pos.getX();
            sumY += pos.getY();
            sumZ += pos.getZ();
        }
        int count = voxels.size();
        return new BlockPos((int)(sumX / count), (int)(sumY / count), (int)(sumZ / count));
    }

    public int size() {
        return voxels.size();
    }
}
