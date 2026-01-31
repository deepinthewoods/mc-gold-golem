package ninja.trek.mc.goldgolem.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Utility for detecting "mine" actions stored in gradient slot arrays.
 * <p>
 * A gradient slot starting with "gold-golem:mine" means the golem should mine (break)
 * the block at that position instead of placing a block.
 * <p>
 * Format: "gold-golem:mine/namespace/item_path" e.g. "gold-golem:mine/minecraft/iron_pickaxe"
 * This encodes which tool was placed so the UI can render the correct icon.
 */
public final class GradientSlotUtil {

    public static final String MINE_PREFIX = "gold-golem:mine";

    private GradientSlotUtil() {}

    /**
     * Check if a gradient slot string represents a mine action.
     */
    public static boolean isMineAction(String slot) {
        return slot != null && slot.startsWith(MINE_PREFIX);
    }

    /**
     * Build an Identifier that encodes a mine action with the tool's item ID.
     * The path format is "mine/namespace/path" so the full string becomes
     * "gold-golem:mine/namespace/path".
     */
    public static Identifier mineIdentifier(Identifier toolItemId) {
        return Identifier.fromNamespaceAndPath("gold-golem", "mine/" + toolItemId.getNamespace() + "/" + toolItemId.getPath());
    }

    /**
     * Extract the tool item from a mine-action slot string.
     * @return the tool Item, or null if the slot is not a mine action or the item is unknown
     */
    public static Item getToolItem(String slot) {
        if (!isMineAction(slot)) return null;
        // Format: "gold-golem:mine/namespace/path"
        String afterPrefix = slot.substring(MINE_PREFIX.length());
        if (afterPrefix.isEmpty() || afterPrefix.charAt(0) != '/') return null;
        afterPrefix = afterPrefix.substring(1); // remove leading '/'
        int sep = afterPrefix.indexOf('/');
        if (sep < 0) return null;
        String namespace = afterPrefix.substring(0, sep);
        String path = afterPrefix.substring(sep + 1);
        Identifier itemId = Identifier.fromNamespaceAndPath(namespace, path);
        return BuiltInRegistries.ITEM.getValue(itemId);
    }
}
