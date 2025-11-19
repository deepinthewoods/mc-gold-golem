package ninja.trek.mc.goldgolem.tree;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Captured Tree Mode data from summon-time scan.
 * Contains multiple input modules separated by gold blocks,
 * along with the list of unique block IDs for gradient grouping.
 */
public final class TreeDefinition {
    public final BlockPos origin; // second gold block position used as scan origin
    public final List<TreeModule> modules; // individual input modules
    public final List<String> uniqueBlockIds; // block registry IDs across all modules (e.g. minecraft:oak_log)

    public TreeDefinition(BlockPos origin, List<TreeModule> modules, List<String> uniqueBlockIds) {
        this.origin = origin.toImmutable();
        this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
        this.uniqueBlockIds = Collections.unmodifiableList(new ArrayList<>(uniqueBlockIds));
    }

    public int getTotalVoxelCount() {
        int count = 0;
        for (TreeModule module : modules) {
            count += module.size();
        }
        return count;
    }
}
