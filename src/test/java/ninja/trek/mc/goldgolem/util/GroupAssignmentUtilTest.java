package ninja.trek.mc.goldgolem.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupAssignmentUtilTest {
    @Test
    void splittingACombinedGroupReusesTheVacantRow() {
        List<String> uniqueBlocks = List.of("minecraft:stone", "minecraft:dirt");
        List<String[]> slots = groups(2);
        List<Float> windows = new ArrayList<>(List.of(2.0f, 3.0f));
        List<Integer> scales = new ArrayList<>(List.of(4, 5));
        Map<String, Integer> assignments = new HashMap<>();
        assignments.put("minecraft:stone", 1);
        assignments.put("minecraft:dirt", 1);

        assertTrue(GroupAssignmentUtil.assign("minecraft:stone", -1, uniqueBlocks,
                slots, windows, scales, assignments));

        assertEquals(2, slots.size());
        assertEquals(0, assignments.get("minecraft:stone"));
        assertEquals(1.0f, windows.get(0));
        assertEquals(1, scales.get(0));
    }

    @Test
    void repeatedNewGroupRequestsCannotGrowPastCapturedBlockCount() {
        List<String> uniqueBlocks = List.of("minecraft:stone");
        List<String[]> slots = groups(1);
        List<Float> windows = new ArrayList<>(List.of(1.0f));
        List<Integer> scales = new ArrayList<>(List.of(1));
        Map<String, Integer> assignments = new HashMap<>(Map.of("minecraft:stone", 0));

        for (int i = 0; i < 100; i++) {
            assertTrue(GroupAssignmentUtil.assign("minecraft:stone", -1, uniqueBlocks,
                    slots, windows, scales, assignments));
        }

        assertEquals(1, slots.size());
    }

    @Test
    void rejectsUnknownBlocksAndOutOfRangeGroups() {
        List<String> uniqueBlocks = List.of("minecraft:stone");
        List<String[]> slots = groups(1);
        List<Float> windows = new ArrayList<>(List.of(1.0f));
        List<Integer> scales = new ArrayList<>(List.of(1));
        Map<String, Integer> assignments = new HashMap<>(Map.of("minecraft:stone", 0));

        assertFalse(GroupAssignmentUtil.assign("minecraft:dirt", -1, uniqueBlocks,
                slots, windows, scales, assignments));
        assertFalse(GroupAssignmentUtil.assign("minecraft:stone", 1, uniqueBlocks,
                slots, windows, scales, assignments));
        assertEquals(1, slots.size());
    }

    private static List<String[]> groups(int count) {
        List<String[]> groups = new ArrayList<>();
        for (int i = 0; i < count; i++) groups.add(new String[9]);
        return groups;
    }
}
