package ninja.trek.mc.goldgolem.client.screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe container for mode-specific GUI state.
 * Each BuildMode has its own ModeState instance to hold blocks, groups,
 * sliders, and other mode-specific data.
 */
public class ModeState {
    // Block and group data (synced from server)
    private List<String> uniqueBlocks = Collections.emptyList();
    private List<Integer> blockGroups = Collections.emptyList();
    private List<Float> groupWindows = Collections.emptyList();
    private List<Integer> groupNoiseScales = Collections.emptyList();
    private List<String> groupFlatSlots = Collections.emptyList();
    private Map<String, Integer> blockCounts = Collections.emptyMap();

    // UI scroll and drag state
    private int scroll = 0;
    private String draggingBlockId = null;
    private String pendingAssignBlockId = null;

    // Slider tracking
    private final List<WindowSliderRef> rowSliders = new ArrayList<>();
    private final int[] sliderToGroup = new int[6];
    private final List<IconHitRef> iconHits = new ArrayList<>();

    /**
     * Reference to a window slider (lightweight reference to avoid holding widget directly).
     */
    public static class WindowSliderRef {
        public int group;
        public float window;
        public int noiseScale;

        public WindowSliderRef(int group, float window, int noiseScale) {
            this.group = group;
            this.window = window;
            this.noiseScale = noiseScale;
        }
    }

    /**
     * Reference to an icon hit area for drag-and-drop.
     */
    public static class IconHitRef {
        public final String blockId;
        public final int group;
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        public IconHitRef(String blockId, int group, int x, int y, int w, int h) {
            this.blockId = blockId;
            this.group = group;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    // Synchronized getters and setters

    public synchronized void setUniqueBlocks(List<String> blocks) {
        this.uniqueBlocks = (blocks == null) ? Collections.emptyList() : new ArrayList<>(blocks);
    }

    public synchronized List<String> getUniqueBlocks() {
        return new ArrayList<>(uniqueBlocks);
    }

    public synchronized void setBlockGroups(List<Integer> groups) {
        this.blockGroups = (groups == null) ? Collections.emptyList() : new ArrayList<>(groups);
    }

    public synchronized List<Integer> getBlockGroups() {
        return new ArrayList<>(blockGroups);
    }

    public synchronized void setGroupWindows(List<Float> windows) {
        this.groupWindows = (windows == null) ? Collections.emptyList() : new ArrayList<>(windows);
    }

    public synchronized List<Float> getGroupWindows() {
        return new ArrayList<>(groupWindows);
    }

    public synchronized void setGroupNoiseScales(List<Integer> scales) {
        this.groupNoiseScales = (scales == null) ? Collections.emptyList() : new ArrayList<>(scales);
    }

    public synchronized List<Integer> getGroupNoiseScales() {
        return new ArrayList<>(groupNoiseScales);
    }

    public synchronized void setGroupFlatSlots(List<String> slots) {
        this.groupFlatSlots = (slots == null) ? Collections.emptyList() : new ArrayList<>(slots);
    }

    public synchronized List<String> getGroupFlatSlots() {
        return new ArrayList<>(groupFlatSlots);
    }

    public synchronized void setBlockCounts(Map<String, Integer> counts) {
        this.blockCounts = (counts == null) ? Collections.emptyMap() : new HashMap<>(counts);
    }

    public synchronized Map<String, Integer> getBlockCounts() {
        return new HashMap<>(blockCounts);
    }

    public synchronized int getScroll() {
        return scroll;
    }

    public synchronized void setScroll(int scroll) {
        this.scroll = scroll;
    }

    public synchronized String getDraggingBlockId() {
        return draggingBlockId;
    }

    public synchronized void setDraggingBlockId(String blockId) {
        this.draggingBlockId = blockId;
    }

    public synchronized String getPendingAssignBlockId() {
        return pendingAssignBlockId;
    }

    public synchronized void setPendingAssignBlockId(String blockId) {
        this.pendingAssignBlockId = blockId;
    }

    public synchronized List<WindowSliderRef> getRowSliders() {
        return new ArrayList<>(rowSliders);
    }

    public synchronized void clearRowSliders() {
        rowSliders.clear();
    }

    public synchronized void addRowSlider(WindowSliderRef slider) {
        rowSliders.add(slider);
    }

    public synchronized int getSliderToGroup(int index) {
        if (index < 0 || index >= sliderToGroup.length) {
            return -1;
        }
        return sliderToGroup[index];
    }

    public synchronized void setSliderToGroup(int index, int group) {
        if (index >= 0 && index < sliderToGroup.length) {
            sliderToGroup[index] = group;
        }
    }

    public synchronized List<IconHitRef> getIconHits() {
        return new ArrayList<>(iconHits);
    }

    public synchronized void clearIconHits() {
        iconHits.clear();
    }

    public synchronized void addIconHit(IconHitRef hit) {
        iconHits.add(hit);
    }

    /**
     * Bulk update for group state (windows, scales, slots).
     * This is more efficient than setting each field separately.
     */
    public synchronized void updateGroupState(List<Float> windows, List<Integer> noiseScales, List<String> flatSlots) {
        this.groupWindows = (windows == null) ? Collections.emptyList() : new ArrayList<>(windows);
        this.groupNoiseScales = (noiseScales == null) ? Collections.emptyList() : new ArrayList<>(noiseScales);
        this.groupFlatSlots = (flatSlots == null) ? Collections.emptyList() : new ArrayList<>(flatSlots);
    }

    /**
     * Reset all state to defaults.
     */
    public synchronized void reset() {
        uniqueBlocks = Collections.emptyList();
        blockGroups = Collections.emptyList();
        groupWindows = Collections.emptyList();
        groupNoiseScales = Collections.emptyList();
        groupFlatSlots = Collections.emptyList();
        blockCounts = Collections.emptyMap();
        scroll = 0;
        draggingBlockId = null;
        pendingAssignBlockId = null;
        rowSliders.clear();
        iconHits.clear();
        for (int i = 0; i < sliderToGroup.length; i++) {
            sliderToGroup[i] = 0;
        }
    }
}
