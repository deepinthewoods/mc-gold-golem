package ninja.trek.mc.goldgolem.client.screen;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Wall mode.
 */
public class WallModeStrategy extends AbstractGroupModeStrategy {
    @Override
    public BuildMode getMode() {
        return BuildMode.WALL;
    }

    @Override
    public List<Integer> getVisibleGroups() {
        List<Integer> out = new ArrayList<>();
        if (groupWindows == null) return out;
        int total = groupWindows.size();
        if (total <= 0) return out;
        boolean[] used = new boolean[total];
        if (uniqueBlocks != null && blockGroups != null) {
            int n = Math.min(uniqueBlocks.size(), blockGroups.size());
            for (int i = 0; i < n; i++) {
                int g = blockGroups.get(i);
                if (g >= 0 && g < total) used[g] = true;
            }
        }
        for (int g = 0; g < total; g++) {
            if (used[g]) out.add(g);
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
}
