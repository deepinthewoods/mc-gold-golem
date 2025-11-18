package ninja.trek.mc.goldgolem.tower;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Captured tower-mode data from summon-time scan.
 * Contains the module voxel set, unique block ids, block counts per module,
 * and the module height for gradient grouping and building.
 */
public final class TowerDefinition {
    public final BlockPos origin; // bottom gold block pos used as scan origin
    public final Set<BlockPos> voxels; // relative positions from origin
    public final List<String> uniqueBlockIds; // block registry ids (e.g. minecraft:stone)
    public final Map<String, Integer> blockCounts; // count of each block type in one module
    public final int moduleHeight; // Y-height of the module

    public TowerDefinition(BlockPos origin, Set<BlockPos> voxels, List<String> uniqueBlockIds,
                           Map<String, Integer> blockCounts, int moduleHeight) {
        this.origin = origin;
        this.voxels = Collections.unmodifiableSet(new HashSet<>(voxels));
        this.uniqueBlockIds = Collections.unmodifiableList(new ArrayList<>(uniqueBlockIds));
        this.blockCounts = Collections.unmodifiableMap(new HashMap<>(blockCounts));
        this.moduleHeight = moduleHeight;
    }
}
