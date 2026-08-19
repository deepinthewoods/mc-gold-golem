package ninja.trek.mc.goldgolem.util;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Safely assigns captured block types to bounded gradient groups. */
public final class GroupAssignmentUtil {
    private static final int GRADIENT_SIZE = 9;

    private GroupAssignmentUtil() {}

    /**
     * Assigns {@code blockId} to an existing group, or to a reset vacant group when
     * {@code requestedGroup} is negative. The number of groups can never exceed the
     * number of captured block types.
     *
     * @return whether the assignment was accepted
     */
    public static boolean assign(
            String blockId,
            int requestedGroup,
            List<String> uniqueBlocks,
            List<String[]> groupSlots,
            List<Float> groupWindows,
            List<Integer> groupNoiseScales,
            Map<String, Integer> blockGroups) {
        if (blockId == null || uniqueBlocks == null || !uniqueBlocks.contains(blockId)) return false;

        int maxGroups = uniqueBlocks.size();
        if (maxGroups == 0) return false;

        int group = requestedGroup;
        if (group < 0) {
            group = findVacantGroup(blockId, maxGroups, groupSlots.size(), blockGroups);
            if (group >= 0) {
                resetGroup(group, groupSlots, groupWindows, groupNoiseScales);
            } else if (groupSlots.size() < maxGroups) {
                group = groupSlots.size();
                groupSlots.add(new String[GRADIENT_SIZE]);
                groupWindows.add(1.0f);
                groupNoiseScales.add(1);
            } else {
                return false;
            }
        } else if (group >= maxGroups || group >= groupSlots.size()) {
            return false;
        }

        blockGroups.put(blockId, group);
        return true;
    }

    private static int findVacantGroup(
            String blockId, int maxGroups, int existingGroups, Map<String, Integer> blockGroups) {
        Set<Integer> groupsUsedByOtherBlocks = new HashSet<>();
        for (Map.Entry<String, Integer> entry : blockGroups.entrySet()) {
            if (!blockId.equals(entry.getKey())) groupsUsedByOtherBlocks.add(entry.getValue());
        }
        int reusableRange = Math.min(maxGroups, existingGroups);
        for (int group = 0; group < reusableRange; group++) {
            if (!groupsUsedByOtherBlocks.contains(group)) return group;
        }
        return -1;
    }

    private static void resetGroup(
            int group,
            List<String[]> groupSlots,
            List<Float> groupWindows,
            List<Integer> groupNoiseScales) {
        groupSlots.set(group, new String[GRADIENT_SIZE]);
        groupWindows.set(group, 1.0f);
        groupNoiseScales.set(group, 1);
    }
}
