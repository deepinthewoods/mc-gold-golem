package ninja.trek.mc.goldgolem.client.screen;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base implementation of GroupModeStrategy with common functionality.
 */
public abstract class AbstractGroupModeStrategy implements GroupModeStrategy {
    protected final List<String> uniqueBlocks = new ArrayList<>();
    protected final List<Integer> blockGroups = new ArrayList<>();
    protected final List<Float> groupWindows = new ArrayList<>();
    protected final List<String> groupFlatSlots = new ArrayList<>();
    protected Map<String, Object> extraData = Map.of();
    protected int scroll = 0;

    @Override
    public List<String> getUniqueBlocks() {
        return uniqueBlocks;
    }

    @Override
    public List<Integer> getBlockGroups() {
        return blockGroups;
    }

    @Override
    public List<Float> getGroupWindows() {
        return groupWindows;
    }

    @Override
    public List<String> getGroupFlatSlots() {
        return groupFlatSlots;
    }

    @Override
    public Map<String, Object> getExtraData() {
        return extraData;
    }

    @Override
    public int getScroll() {
        return scroll;
    }

    @Override
    public void setScroll(int scroll) {
        this.scroll = scroll;
    }

    @Override
    public int effectiveGroupG(int group) {
        if (groupFlatSlots == null) return 0;
        int start = group * 9;
        int end = Math.min(start + 9, groupFlatSlots.size());
        int G = 0;
        for (int i = end - 1; i >= start; i--) {
            String s = groupFlatSlots.get(i);
            if (s != null && !s.isEmpty()) {
                G = (i - start) + 1;
                break;
            }
        }
        if (G == 0) G = 9;
        return G;
    }

    @Override
    public boolean shouldShowBlockCounts() {
        return false;
    }

    @Override
    public Map<String, Integer> getBlockCounts() {
        return Map.of();
    }

    @Override
    public void updateGroupState(List<Float> windows, List<String> flatSlots, Map<String, Object> extraData) {
        this.groupWindows.clear();
        if (windows != null) {
            this.groupWindows.addAll(windows);
        }
        this.groupFlatSlots.clear();
        if (flatSlots != null) {
            this.groupFlatSlots.addAll(flatSlots);
        }
        this.extraData = extraData != null ? extraData : Map.of();
    }

    @Override
    public void updateBlocksAndGroups(List<String> uniqueBlocks, List<Integer> blockGroups) {
        this.uniqueBlocks.clear();
        if (uniqueBlocks != null) {
            this.uniqueBlocks.addAll(uniqueBlocks);
        }
        this.blockGroups.clear();
        if (blockGroups != null) {
            this.blockGroups.addAll(blockGroups);
        }
    }
}
