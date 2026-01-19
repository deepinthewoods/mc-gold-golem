package ninja.trek.mc.goldgolem.client.screen;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for group-based build modes (Wall, Tower, Tree).
 * Encapsulates mode-specific behavior and state for the UI.
 */
public interface GroupModeStrategy {
    /**
     * Get the build mode this strategy handles.
     */
    BuildMode getMode();

    /**
     * Get the list of visible (non-empty) group indices.
     */
    List<Integer> getVisibleGroups();

    /**
     * Get the current scroll position.
     */
    int getScroll();

    /**
     * Set the scroll position.
     */
    void setScroll(int scroll);

    /**
     * Get the list of unique block IDs for this mode.
     */
    List<String> getUniqueBlocks();

    /**
     * Get the group assignment for each unique block.
     */
    List<Integer> getBlockGroups();

    /**
     * Get the window size for each group.
     */
    List<Float> getGroupWindows();

    /**
     * Get the flat slot list (group * 9 + col -> block ID).
     */
    List<String> getGroupFlatSlots();

    /**
     * Get extra data specific to this mode.
     */
    Map<String, Object> getExtraData();

    /**
     * Calculate the effective gradient size (G) for a group.
     */
    int effectiveGroupG(int group);

    /**
     * Get the X offset for icon rendering.
     * Wall/Tree use -20, Tower uses -50 (for block count text).
     */
    int getIconXOffset();

    /**
     * Whether to show block counts next to icons.
     */
    boolean shouldShowBlockCounts();

    /**
     * Get block counts for display (Tower mode only).
     */
    Map<String, Integer> getBlockCounts();

    /**
     * Update the strategy with new group state from server.
     */
    void updateGroupState(List<Float> windows, List<String> flatSlots, Map<String, Object> extraData);

    /**
     * Update the strategy with new unique blocks and group assignments.
     */
    void updateBlocksAndGroups(List<String> uniqueBlocks, List<Integer> blockGroups);
}
