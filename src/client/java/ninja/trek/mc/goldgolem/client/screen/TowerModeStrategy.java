package ninja.trek.mc.goldgolem.client.screen;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Tower mode.
 * Includes block counts display.
 */
public class TowerModeStrategy extends AbstractGroupModeStrategy {
    private final Map<String, Integer> blockCounts = new HashMap<>();

    @Override
    public BuildMode getMode() {
        return BuildMode.TOWER;
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
        return -50; // Shifted left to make room for block count text
    }

    @Override
    public boolean shouldShowBlockCounts() {
        return true;
    }

    @Override
    public Map<String, Integer> getBlockCounts() {
        return blockCounts;
    }

    @Override
    public void updateBlocksAndGroups(List<String> uniqueBlocks, List<Integer> blockGroups) {
        super.updateBlocksAndGroups(uniqueBlocks, blockGroups);
        // Block counts are updated separately via extraData
    }

    @Override
    public void updateGroupState(List<Float> windows, List<Integer> noiseScales, List<String> flatSlots, Map<String, Object> extraData) {
        super.updateGroupState(windows, noiseScales, flatSlots, extraData);
        // Update block counts from extra data
        blockCounts.clear();
        if (extraData != null) {
            Object counts = extraData.get("blockCounts");
            if (counts instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Integer> typedCounts = (Map<String, Integer>) counts;
                blockCounts.putAll(typedCounts);
            }
        }
    }
}
