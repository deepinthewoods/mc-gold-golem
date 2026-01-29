package ninja.trek.mc.goldgolem.util;

import net.minecraft.nbt.NbtCompound;

import java.util.*;

/**
 * Manages gradient groups for block replacement in building modes.
 * Each group has slots (String[9]), a window size, and a noise scale.
 * Blocks are mapped to groups for dynamic material substitution.
 */
public class GradientGroupManager {

    private static final int GRADIENT_SIZE = 9;

    private final List<String[]> groupSlots = new ArrayList<>();
    private final List<Float> groupWindows = new ArrayList<>();
    private final List<Integer> groupNoiseScales = new ArrayList<>();
    private final Map<String, Integer> blockGroups = new HashMap<>();

    /**
     * Initialize groups from a list of unique block IDs.
     * Creates one group per unique block by default.
     * If gold block is present, merges it into the first non-gold group.
     */
    public void initializeFromUniqueBlocks(List<String> uniqueBlocks) {
        clear();
        if (uniqueBlocks == null) return;

        int idx = 0;
        int firstNonGold = -1;
        for (String id : uniqueBlocks) {
            String[] arr = new String[GRADIENT_SIZE];
            groupSlots.add(arr);
            groupWindows.add(1.0f);
            groupNoiseScales.add(1);
            blockGroups.put(id, idx);
            if (!"minecraft:gold_block".equals(id) && firstNonGold < 0) {
                firstNonGold = idx;
            }
            idx++;
        }

        // Merge gold block into first non-gold group
        Integer goldIdx = blockGroups.get("minecraft:gold_block");
        if (goldIdx != null && firstNonGold >= 0 && goldIdx != firstNonGold) {
            blockGroups.put("minecraft:gold_block", firstNonGold);
        }
    }

    /**
     * Clear all groups and mappings.
     */
    public void clear() {
        groupSlots.clear();
        groupWindows.clear();
        groupNoiseScales.clear();
        blockGroups.clear();
    }

    /**
     * Set the window size for a group.
     * @param group Group index
     * @param window Window size (clamped to 0.0-9.0)
     */
    public void setGroupWindow(int group, float window) {
        if (group < 0 || group >= groupWindows.size()) return;
        groupWindows.set(group, Math.max(0.0f, Math.min(9.0f, window)));
    }

    /**
     * Set the noise scale for a group.
     * @param group Group index
     * @param scale Noise scale (clamped to 1-16)
     */
    public void setGroupNoiseScale(int group, int scale) {
        if (group < 0 || group >= groupNoiseScales.size()) return;
        groupNoiseScales.set(group, Math.max(1, Math.min(16, scale)));
    }

    /**
     * Set a slot value for a group.
     * @param group Group index
     * @param slot Slot index (0-8)
     * @param id Block ID for the slot
     */
    public void setGroupSlot(int group, int slot, String id) {
        if (group < 0 || group >= groupSlots.size()) return;
        if (slot < 0 || slot >= GRADIENT_SIZE) return;
        String[] arr = groupSlots.get(group);
        arr[slot] = (id == null) ? "" : id;
    }

    /**
     * Assign a block to a group.
     * @param blockId Block ID to assign
     * @param group Target group index, or -1 to create a new group
     */
    public void setBlockGroup(String blockId, int group) {
        if (group < 0) {
            // Create new group
            groupSlots.add(new String[GRADIENT_SIZE]);
            groupWindows.add(1.0f);
            groupNoiseScales.add(1);
            group = groupSlots.size() - 1;
        } else if (group >= groupSlots.size()) {
            return;
        }
        blockGroups.put(blockId, group);
    }

    /**
     * Get the group index for a block ID.
     */
    public int getGroupForBlock(String blockId) {
        return blockGroups.getOrDefault(blockId, 0);
    }

    /**
     * Get a list of group indices for a list of block IDs.
     */
    public List<Integer> getBlockGroupMap(List<String> uniqueBlocks) {
        List<Integer> out = new ArrayList<>(uniqueBlocks.size());
        for (String id : uniqueBlocks) {
            out.add(blockGroups.getOrDefault(id, 0));
        }
        return out;
    }

    /**
     * Get a copy of group windows.
     */
    public List<Float> getGroupWindows() {
        return new ArrayList<>(groupWindows);
    }

    /**
     * Get a copy of group noise scales.
     */
    public List<Integer> getGroupNoiseScales() {
        return new ArrayList<>(groupNoiseScales);
    }

    /**
     * Get the group slots list (direct reference for iteration).
     */
    public List<String[]> getGroupSlots() {
        return groupSlots;
    }

    /**
     * Get the block-to-group map (direct reference).
     */
    public Map<String, Integer> getBlockGroupMap() {
        return blockGroups;
    }

    /**
     * Get the number of groups.
     */
    public int getGroupCount() {
        return groupSlots.size();
    }

    /**
     * Get all slots flattened into a single list.
     */
    public List<String> getFlatSlots() {
        List<String> out = new ArrayList<>(groupSlots.size() * GRADIENT_SIZE);
        for (String[] arr : groupSlots) {
            for (int i = 0; i < GRADIENT_SIZE; i++) {
                out.add(arr[i] == null ? "" : arr[i]);
            }
        }
        return out;
    }

    /**
     * Get the slots array for a specific group.
     */
    public String[] getGroupSlotArray(int group) {
        if (group < 0 || group >= groupSlots.size()) return null;
        return groupSlots.get(group);
    }

    /**
     * Get the window for a specific group.
     */
    public float getGroupWindow(int group) {
        if (group < 0 || group >= groupWindows.size()) return 1.0f;
        return groupWindows.get(group);
    }

    /**
     * Get the noise scale for a specific group.
     */
    public int getGroupNoiseScale(int group) {
        if (group < 0 || group >= groupNoiseScales.size()) return 1;
        return groupNoiseScales.get(group);
    }

    /**
     * Write group data to NBT with a prefix.
     */
    public void writeToNbt(NbtCompound nbt, String prefix) {
        // Save group count
        nbt.putInt(prefix + "GroupCount", groupSlots.size());

        // Save each group's data
        for (int g = 0; g < groupSlots.size(); g++) {
            String gPrefix = prefix + "Group" + g;

            // Save window and noise scale
            nbt.putFloat(gPrefix + "Window", groupWindows.get(g));
            nbt.putInt(gPrefix + "NoiseScale", groupNoiseScales.get(g));

            // Save slots
            String[] slots = groupSlots.get(g);
            for (int s = 0; s < GRADIENT_SIZE; s++) {
                nbt.putString(gPrefix + "Slot" + s, slots[s] == null ? "" : slots[s]);
            }
        }

        // Save block-to-group mappings
        nbt.putInt(prefix + "BlockMapCount", blockGroups.size());
        int i = 0;
        for (Map.Entry<String, Integer> entry : blockGroups.entrySet()) {
            nbt.putString(prefix + "BlockMapKey" + i, entry.getKey());
            nbt.putInt(prefix + "BlockMapVal" + i, entry.getValue());
            i++;
        }
    }

    /**
     * Read group data from NBT with a prefix.
     */
    public void readFromNbt(NbtCompound nbt, String prefix) {
        clear();

        // Read group count
        int groupCount = nbt.getInt(prefix + "GroupCount", 0);

        // Read each group's data
        for (int g = 0; g < groupCount; g++) {
            String gPrefix = prefix + "Group" + g;

            String[] slots = new String[GRADIENT_SIZE];
            groupSlots.add(slots);
            groupWindows.add(nbt.getFloat(gPrefix + "Window", 1.0f));
            groupNoiseScales.add(nbt.getInt(gPrefix + "NoiseScale", 1));

            // Read slots
            for (int s = 0; s < GRADIENT_SIZE; s++) {
                slots[s] = nbt.getString(gPrefix + "Slot" + s, "");
            }
        }

        // Read block-to-group mappings
        int mapCount = nbt.getInt(prefix + "BlockMapCount", 0);
        for (int i = 0; i < mapCount; i++) {
            String key = nbt.getString(prefix + "BlockMapKey" + i, "");
            int val = nbt.getInt(prefix + "BlockMapVal" + i, 0);
            if (!key.isEmpty()) {
                blockGroups.put(key, val);
            }
        }
    }
}
