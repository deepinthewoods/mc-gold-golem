package ninja.trek.mc.goldgolem.world.entity.strategy;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Cache for tool slot positions in an inventory.
 * Avoids scanning the entire inventory every tick when looking for tools.
 *
 * The cache is invalidated when onInventoryChanged() is called, and
 * will be rebuilt on the next getToolSlots() call.
 */
public class ToolCache {

    private int[] toolSlots = null;
    private int inventoryVersion = -1;

    /**
     * Invalidate the cache. Should be called when the inventory changes.
     */
    public void invalidate() {
        toolSlots = null;
    }

    /**
     * Get the cached tool slots, rebuilding the cache if necessary.
     *
     * @param inventory The inventory to scan
     * @param currentVersion A version number that changes when inventory is modified
     * @return Array of slot indices containing tools
     */
    public int[] getToolSlots(Container inventory, int currentVersion) {
        if (toolSlots == null || currentVersion != inventoryVersion) {
            toolSlots = scanForTools(inventory);
            inventoryVersion = currentVersion;
        }
        return toolSlots;
    }

    /**
     * Scan the inventory for tools and return their slot indices.
     */
    private int[] scanForTools(Container inventory) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isTool(stack)) {
                slots.add(i);
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Check if an ItemStack is a tool.
     * Uses item ID pattern matching for standard tools and mining speed check as fallback.
     */
    private boolean isTool(ItemStack stack) {
        if (stack.isEmpty()) return false;

        var item = stack.getItem();

        // Check for shears explicitly
        if (item == Items.SHEARS) {
            return true;
        }

        // Check for tools by item ID pattern (covers vanilla and modded tools)
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        if (itemId.contains("_pickaxe") || itemId.contains("_shovel") ||
            itemId.contains("_axe") || itemId.contains("_hoe") || itemId.contains("_sword")) {
            return true;
        }

        // Fallback: check if item has mining speed bonus on stone (catches unconventional tools)
        float miningSpeed = stack.getDestroySpeed(Blocks.STONE.defaultBlockState());
        if (miningSpeed > 1.0f) {
            return true;
        }

        return false;
    }
}
