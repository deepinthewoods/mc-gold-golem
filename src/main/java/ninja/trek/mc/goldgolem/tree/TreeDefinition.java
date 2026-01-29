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

    /**
     * E7: Constructor with validation to ensure all required fields are present and valid.
     * @throws IllegalArgumentException if any argument is null or empty
     */
    public TreeDefinition(BlockPos origin, List<TreeModule> modules, List<String> uniqueBlockIds) {
        // E7: Validate all parameters
        if (origin == null) {
            throw new IllegalArgumentException("Tree origin cannot be null");
        }
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("Tree must have at least one module");
        }
        if (uniqueBlockIds == null || uniqueBlockIds.isEmpty()) {
            throw new IllegalArgumentException("Tree must have at least one unique block ID");
        }

        // Validate modules don't contain null entries
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i) == null) {
                throw new IllegalArgumentException("Tree module at index " + i + " cannot be null");
            }
        }

        // Validate uniqueBlockIds don't contain null or empty entries
        for (int i = 0; i < uniqueBlockIds.size(); i++) {
            String blockId = uniqueBlockIds.get(i);
            if (blockId == null || blockId.isEmpty()) {
                throw new IllegalArgumentException("Tree block ID at index " + i + " cannot be null or empty");
            }
        }

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
