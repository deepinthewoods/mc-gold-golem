package ninja.trek.mc.goldgolem.client.screen;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Tree mode.
 * Includes tiling preset support.
 */
public class TreeModeStrategy extends AbstractGroupModeStrategy {
    private int tilingPresetOrdinal = 0;

    @Override
    public BuildMode getMode() {
        return BuildMode.TREE;
    }

    @Override
    public List<Integer> getVisibleGroups() {
        List<Integer> out = new ArrayList<>();
        if (groupWindows == null) return out;
        int total = groupWindows.size();
        if (total <= 0) return out;
        for (int g = 0; g < total; g++) {
            out.add(g);
        }
        return out;
    }

    @Override
    public int getIconXOffset() {
        return -20;
    }

    @Override
    public boolean shouldShowBlockCounts() {
        return false;
    }

    /**
     * Get the tiling preset ordinal (0 = 3x3, 1 = 5x5).
     */
    public int getTilingPresetOrdinal() {
        return tilingPresetOrdinal;
    }

    @Override
    public void updateGroupState(List<Float> windows, List<String> flatSlots, Map<String, Object> extraData) {
        super.updateGroupState(windows, flatSlots, extraData);
        // Update tiling preset from extra data
        if (extraData != null) {
            Object preset = extraData.get("tilingPresetOrdinal");
            if (preset instanceof Integer) {
                tilingPresetOrdinal = (Integer) preset;
            }
        }
    }
}
