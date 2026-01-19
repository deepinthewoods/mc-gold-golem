package ninja.trek.mc.goldgolem.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import ninja.trek.mc.goldgolem.screen.GolemInventoryScreenHandler;
import ninja.trek.mc.goldgolem.BuildMode;

import java.util.Map;

public class GolemHandledScreen extends HandledScreen<GolemInventoryScreenHandler> {
    private static final Identifier GENERIC_CONTAINER_TEXTURE = Identifier.of("minecraft", "textures/gui/container/generic_54.png");
    private float gradientWindowMain = 1.0f; // 0..9 (server synced)
    private float gradientWindowStep = 1.0f; // 0..9 (server synced)
    private int pathWidth = 3;      // server synced
    private String[] gradientMainBlocks = new String[9];
    private String[] gradientStepBlocks = new String[9];

    private WindowSlider windowSliderMain;
    private WindowSlider windowSliderStep;
    private WidthSlider widthSlider;
    private boolean isDragging = false;
    private int dragButton = -1;
    private java.util.Set<Integer> dragVisited = new java.util.HashSet<>();
    private java.util.List<String> wallUniqueBlocks = java.util.Collections.emptyList();
    private java.util.List<Integer> wallBlockGroups = java.util.Collections.emptyList();
    private java.util.List<Float> wallGroupWindows = java.util.Collections.emptyList();
    private java.util.List<String> wallGroupFlatSlots = java.util.Collections.emptyList();
    private String pendingAssignBlockId = null; // click icon then click row to assign
    private int wallScroll = 0; // simple integer rows scrolled
    private final java.util.List<WindowSlider> wallRowSliders = new java.util.ArrayList<>();
    private final int[] wallSliderToGroup = new int[6];
    // Drag state for Wall mode icon -> group assignment
    private String draggingBlockId = null;
    private int draggingStartX = 0;
    private int draggingStartY = 0;
    private boolean draggingFromIcon = false;
    private final java.util.List<IconHit> wallIconHits = new java.util.ArrayList<>();

    // Tower mode state
    private java.util.List<String> towerUniqueBlocks = java.util.Collections.emptyList();
    private java.util.Map<String, Integer> towerBlockCounts = new java.util.HashMap<>();
    private java.util.List<Integer> towerBlockGroups = java.util.Collections.emptyList();
    private java.util.List<Float> towerGroupWindows = java.util.Collections.emptyList();
    private java.util.List<String> towerGroupFlatSlots = java.util.Collections.emptyList();
    private int towerScroll = 0;
    private final java.util.List<WindowSlider> towerRowSliders = new java.util.ArrayList<>();
    private final int[] towerSliderToGroup = new int[6];
    private final java.util.List<IconHit> towerIconHits = new java.util.ArrayList<>();
    private String towerDraggingBlockId = null;
    private String towerPendingAssignBlockId = null;
    private int towerLayers = 1; // 1-256 layers (synced from server)
    private TowerLayersSlider towerLayersSlider;

    // Excavation mode state
    private int excavationHeight = 3; // 1-5
    private int excavationDepth = 16; // 1-64
    private ExcavationHeightSlider excavationHeightSlider;
    private ExcavationDepthSlider excavationDepthSlider;

    // Terraforming mode state
    private int terraformingScanRadius = 2; // 1-5
    private int terraformingGradientVerticalWindow = 1; // 0..9
    private int terraformingGradientHorizontalWindow = 1; // 0..9
    private int terraformingGradientSlopedWindow = 1; // 0..9
    private String[] terraformingGradientVertical = new String[9];
    private String[] terraformingGradientHorizontal = new String[9];
    private String[] terraformingGradientSloped = new String[9];
    private WindowSlider terraformingSliderVertical;
    private WindowSlider terraformingSliderHorizontal;
    private WindowSlider terraformingSliderSloped;
    private TerraformingScanRadiusSlider terraformingScanRadiusSlider;

    // Tree mode state
    private java.util.List<String> treeUniqueBlocks = java.util.Collections.emptyList();
    private java.util.List<Integer> treeBlockGroups = java.util.Collections.emptyList();
    private java.util.List<Float> treeGroupWindows = java.util.Collections.emptyList();
    private java.util.List<String> treeGroupFlatSlots = java.util.Collections.emptyList();
    private int treeScroll = 0;
    private final java.util.List<WindowSlider> treeRowSliders = new java.util.ArrayList<>();
    private final int[] treeSliderToGroup = new int[6];
    private final java.util.List<IconHit> treeIconHits = new java.util.ArrayList<>();
    private String treeDraggingBlockId = null;
    private String treePendingAssignBlockId = null;
    private int treeTilingPresetOrdinal = 0; // 0 = 3x3, 1 = 5x5

    // Strategy pattern for group-based modes (Wall, Tower, Tree)
    private GroupModeStrategy groupModeStrategy;
    private final java.util.List<WindowSlider> groupRowSliders = new java.util.ArrayList<>();
    private final int[] groupSliderToGroup = new int[6];
    private final java.util.List<IconHit> groupIconHits = new java.util.ArrayList<>();

    private static final class IconHit {
        final String blockId; final int group; final int x; final int y; final int w; final int h;
        IconHit(String blockId, int group, int x, int y, int w, int h) { this.blockId = blockId; this.group = group; this.x = x; this.y = y; this.w = w; this.h = h; }
        boolean contains(int mx, int my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private class WindowSlider extends SliderWidget {
        private final int row; // 0 = main, 1 = step
        public WindowSlider(int x, int y, int width, int height, double norm) {
            this(x, y, width, height, norm, 0);
        }
        public WindowSlider(int x, int y, int width, int height, double norm, int row) {
            super(x, y, width, height, Text.literal("Window"), norm);
            this.row = row;
        }
        @Override
        protected void updateMessage() {
            int g = effectiveG(row);
            float w = (g <= 0) ? 0.0f : Math.round(this.value * g * 10.0f) / 10.0f;
            w = Math.max(0.0f, Math.min(g, w));
            this.setMessage(Text.literal("Window: " + w));
        }
        @Override
        protected void applyValue() {
            int g = effectiveG(row);
            float w = (g <= 0) ? 0.0f : Math.round(this.value * g * 10.0f) / 10.0f;
            w = Math.max(0.0f, Math.min(g, w));
            float current = (row == 0) ? gradientWindowMain : gradientWindowStep;
            if (Math.abs(w - current) > 0.001f) {
                if (row == 0) gradientWindowMain = w; else gradientWindowStep = w;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientWindowC2SPayload(getEntityId(), row, w));
            }
        }
        public void syncTo(int row, int g, float window) {
            double norm = g <= 0 ? 0.0 : (double) Math.min(window, g) / (double) g;
            this.value = norm;
            this.updateMessage();
            this.applyValue();
        }
    }

    private static int clampOdd(int w) {
        w = Math.max(1, Math.min(9, w));
        if ((w & 1) == 0) w = (w < 9) ? (w + 1) : (w - 1);
        return w;
    }

    private class WidthSlider extends SliderWidget {
        public WidthSlider(int x, int y, int width, int height, int initialWidth) {
            super(x, y, width, height, Text.literal("Width"), toValueInit(initialWidth));
        }
        private static double toValueInit(int w) { return (clampOdd(w) - 1) / 8.0; }
        private static int toWidth(double v) { return clampOdd(1 + (int)Math.round(v * 8.0)); }
        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Width: " + toWidth(this.value)));
        }
        @Override
        protected void applyValue() {
            int w = toWidth(this.value);
            if (w != pathWidth) {
                pathWidth = w;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetPathWidthC2SPayload(getEntityId(), pathWidth));
                updateMessage();
            }
        }
        public void syncTo(int w) {
            this.value = toValueInit(w);
            updateMessage();
        }
    }

    private class ExcavationHeightSlider extends SliderWidget {
        public ExcavationHeightSlider(int x, int y, int width, int height, int initialHeight) {
            super(x, y, width, height, Text.literal("Height"), toValueInit(initialHeight));
        }
        private static double toValueInit(int h) { return (h - 1) / 4.0; } // 1-5 range
        private static int toHeight(double v) { return Math.max(1, Math.min(5, 1 + (int)Math.round(v * 4.0))); }
        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Height: " + toHeight(this.value)));
        }
        @Override
        protected void applyValue() {
            int h = toHeight(this.value);
            if (h != excavationHeight) {
                excavationHeight = h;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetExcavationHeightC2SPayload(getEntityId(), excavationHeight));
                updateMessage();
            }
        }
        public void syncTo(int h) {
            this.value = toValueInit(h);
            updateMessage();
        }
    }

    private class ExcavationDepthSlider extends SliderWidget {
        public ExcavationDepthSlider(int x, int y, int width, int height, int initialDepth) {
            super(x, y, width, height, Text.literal("Depth"), toValueInit(initialDepth));
        }
        private static double toValueInit(int d) { return (d - 1) / 63.0; } // 1-64 range
        private static int toDepth(double v) { return Math.max(1, Math.min(64, 1 + (int)Math.round(v * 63.0))); }
        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Depth: " + toDepth(this.value)));
        }
        @Override
        protected void applyValue() {
            int d = toDepth(this.value);
            if (d != excavationDepth) {
                excavationDepth = d;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetExcavationDepthC2SPayload(getEntityId(), excavationDepth));
                updateMessage();
            }
        }
        public void syncTo(int d) {
            this.value = toValueInit(d);
            updateMessage();
        }
    }

    private class TerraformingScanRadiusSlider extends SliderWidget {
        public TerraformingScanRadiusSlider(int x, int y, int width, int height, int initialRadius) {
            super(x, y, width, height, Text.literal("Scan Radius"), toValueInit(initialRadius));
        }
        private static double toValueInit(int r) { return (r - 1) / 4.0; } // 1-5 range
        private static int toRadius(double v) { return Math.max(1, Math.min(5, 1 + (int)Math.round(v * 4.0))); }
        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Scan Radius: " + toRadius(this.value)));
        }
        @Override
        protected void applyValue() {
            int r = toRadius(this.value);
            if (r != terraformingScanRadius) {
                terraformingScanRadius = r;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingScanRadiusC2SPayload(getEntityId(), terraformingScanRadius));
                updateMessage();
            }
        }
        public void syncTo(int r) {
            this.value = toValueInit(r);
            updateMessage();
        }
    }

    private class TowerLayersSlider extends SliderWidget {
        public TowerLayersSlider(int x, int y, int width, int height, int initialLayers) {
            super(x, y, width, height, Text.literal("Layers"), toValueInit(initialLayers));
        }
        private static double toValueInit(int l) { return (l - 1) / 255.0; } // 1-256 range
        private static int toLayers(double v) { return Math.max(1, Math.min(256, 1 + (int)Math.round(v * 255.0))); }
        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Layers: " + toLayers(this.value)));
        }
        @Override
        protected void applyValue() {
            int l = toLayers(this.value);
            if (l != towerLayers) {
                towerLayers = l;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTowerHeightC2SPayload(getEntityId(), towerLayers));
                updateMessage();
            }
        }
        public void syncTo(int l) {
            this.value = toValueInit(l);
            updateMessage();
        }
    }

    public GolemHandledScreen(GolemInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 176; // vanilla chest width
        this.backgroundHeight = handler.getControlsMargin() + handler.getGolemRows() * 18 + 94;
    }

    public void setWallUniqueBlocks(java.util.List<String> ids) {
        this.wallUniqueBlocks = (ids == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
    }
    public void setWallBlockGroups(java.util.List<Integer> groups) {
        this.wallBlockGroups = (groups == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(groups);
    }
    public void setTreeUniqueBlocks(java.util.List<String> ids) {
        this.treeUniqueBlocks = (ids == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
    }
    public void setTowerUniqueBlocks(java.util.List<String> ids) {
        this.towerUniqueBlocks = (ids == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
    }
    public void setWallGroupsState(java.util.List<Float> windows, java.util.List<String> flatSlots) {
        this.wallGroupWindows = (windows == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(windows);
        this.wallGroupFlatSlots = (flatSlots == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(flatSlots);
        // Invalidate strategy cache to force re-initialization with new data
        groupModeStrategy = null;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            syncGroupSliders(strategy);
        }
    }

    // Tower mode network sync methods
    public void setTowerBlockCounts(java.util.List<String> ids, java.util.List<Integer> counts, int height) {
        this.towerUniqueBlocks = (ids == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
        this.towerBlockCounts.clear();
        if (ids != null && counts != null) {
            for (int i = 0; i < Math.min(ids.size(), counts.size()); i++) {
                this.towerBlockCounts.put(ids.get(i), counts.get(i));
            }
        }
        this.towerLayers = Math.max(1, height);
        if (this.towerLayersSlider != null) {
            this.towerLayersSlider.syncTo(this.towerLayers);
        }
    }
    public void setTowerBlockGroups(java.util.List<Integer> groups) {
        this.towerBlockGroups = (groups == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(groups);
    }
    public void setTowerGroupsState(java.util.List<Float> windows, java.util.List<String> flatSlots) {
        this.towerGroupWindows = (windows == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(windows);
        this.towerGroupFlatSlots = (flatSlots == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(flatSlots);
        // Invalidate strategy cache to force re-initialization with new data
        groupModeStrategy = null;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            syncGroupSliders(strategy);
        }
    }

    // Excavation mode network sync method
    public void setExcavationValues(int height, int depth) {
        this.excavationHeight = height;
        this.excavationDepth = depth;
        if (this.excavationHeightSlider != null) {
            this.excavationHeightSlider.syncTo(height);
        }
        if (this.excavationDepthSlider != null) {
            this.excavationDepthSlider.syncTo(depth);
        }
    }

    // Terraforming mode network sync method
    public void setTerraformingValues(int scanRadius, int verticalWindow, int horizontalWindow, int slopedWindow,
                                       java.util.List<String> verticalGradient, java.util.List<String> horizontalGradient,
                                       java.util.List<String> slopedGradient) {
        this.terraformingScanRadius = scanRadius;
        this.terraformingGradientVerticalWindow = verticalWindow;
        this.terraformingGradientHorizontalWindow = horizontalWindow;
        this.terraformingGradientSlopedWindow = slopedWindow;

        // Sync gradient arrays
        for (int i = 0; i < 9; i++) {
            this.terraformingGradientVertical[i] = (verticalGradient != null && i < verticalGradient.size()) ? verticalGradient.get(i) : "";
            this.terraformingGradientHorizontal[i] = (horizontalGradient != null && i < horizontalGradient.size()) ? horizontalGradient.get(i) : "";
            this.terraformingGradientSloped[i] = (slopedGradient != null && i < slopedGradient.size()) ? slopedGradient.get(i) : "";
        }

        // Sync sliders if they exist
        if (this.terraformingScanRadiusSlider != null) {
            this.terraformingScanRadiusSlider.syncTo(scanRadius);
        }
        if (this.terraformingSliderVertical != null) {
            int g = effectiveTerraformingG(0);
            this.terraformingSliderVertical.syncTo(0, g, verticalWindow);
        }
        if (this.terraformingSliderHorizontal != null) {
            int g = effectiveTerraformingG(1);
            this.terraformingSliderHorizontal.syncTo(0, g, horizontalWindow);
        }
        if (this.terraformingSliderSloped != null) {
            int g = effectiveTerraformingG(2);
            this.terraformingSliderSloped.syncTo(0, g, slopedWindow);
        }
    }

    private int effectiveTerraformingG(int gradientType) {
        String[] arr = switch (gradientType) {
            case 0 -> terraformingGradientVertical;
            case 1 -> terraformingGradientHorizontal;
            case 2 -> terraformingGradientSloped;
            default -> null;
        };
        if (arr == null) return 0;
        int g = 0;
        for (int i = arr.length - 1; i >= 0; i--) {
            if (arr[i] != null && !arr[i].isEmpty()) {
                g = i + 1;
                break;
            }
        }
        return g;
    }

    // Tree mode network sync methods
    public void setTreeBlockGroups(java.util.List<Integer> groups) {
        this.treeBlockGroups = (groups == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(groups);
    }
    public void setTreeGroupsState(int presetOrdinal, java.util.List<Float> windows, java.util.List<String> flatSlots) {
        this.treeTilingPresetOrdinal = presetOrdinal;
        this.treeGroupWindows = (windows == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(windows);
        this.treeGroupFlatSlots = (flatSlots == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(flatSlots);
        // Invalidate strategy cache to force re-initialization with new data
        groupModeStrategy = null;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            syncGroupSliders(strategy);
        }
    }

    private void scrollWall(int delta) {
        int rows = getVisibleGroups().size();
        int maxScroll = Math.max(0, rows - 6);
        int ns = Math.max(0, Math.min(maxScroll, wallScroll + delta));
        if (ns != wallScroll) {
            wallScroll = ns;
            syncWallSliders();
        }
    }

    private int effectiveGroupG(int group) {
        if (wallGroupFlatSlots == null) return 0;
        int start = group * 9;
        int end = Math.min(start + 9, wallGroupFlatSlots.size());
        int G = 0;
        for (int i = end - 1; i >= start; i--) {
            String s = wallGroupFlatSlots.get(i);
            if (s != null && !s.isEmpty()) { G = (i - start) + 1; break; }
        }
        if (G == 0) G = 9;
        return G;
    }

    private void syncWallSliders() {
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null && strategy.getMode() == BuildMode.WALL) {
            syncGroupSliders(strategy);
        }
    }

    // Tower mode helper methods
    private void scrollTower(int delta) {
        int rows = getTowerVisibleGroups().size();
        int maxScroll = Math.max(0, rows - 6);
        int ns = Math.max(0, Math.min(maxScroll, towerScroll + delta));
        if (ns != towerScroll) {
            towerScroll = ns;
            syncTowerSliders();
        }
    }

    private int effectiveTowerGroupG(int group) {
        if (towerGroupFlatSlots == null) return 0;
        int start = group * 9;
        int end = Math.min(start + 9, towerGroupFlatSlots.size());
        int G = 0;
        for (int i = end - 1; i >= start; i--) {
            String s = towerGroupFlatSlots.get(i);
            if (s != null && !s.isEmpty()) { G = (i - start) + 1; break; }
        }
        if (G == 0) G = 9;
        return G;
    }

    private void syncTowerSliders() {
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null && strategy.getMode() == BuildMode.TOWER) {
            syncGroupSliders(strategy);
        }
    }

    private int indexOfTowerBlockId(String id) {
        if (towerUniqueBlocks == null) return -1;
        for (int i = 0; i < towerUniqueBlocks.size(); i++) {
            if (towerUniqueBlocks.get(i).equals(id)) return i;
        }
        return -1;
    }

    private java.util.List<Integer> getTowerVisibleGroups() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        if (towerGroupWindows == null) return out;
        int total = towerGroupWindows.size();
        if (total <= 0) return out;
        for (int g = 0; g < total; g++) out.add(g);
        return out;
    }

    private boolean isTowerMode() {
        return !this.handler.isSliderEnabled() && !towerUniqueBlocks.isEmpty();
    }

    // Tree mode helper methods
    private void scrollTree(int delta) {
        int rows = getTreeVisibleGroups().size();
        int maxScroll = Math.max(0, rows - 6);
        int ns = Math.max(0, Math.min(maxScroll, treeScroll + delta));
        if (ns != treeScroll) {
            treeScroll = ns;
            syncTreeSliders();
        }
    }

    private int effectiveTreeGroupG(int group) {
        if (treeGroupFlatSlots == null) return 0;
        int start = group * 9;
        int end = Math.min(start + 9, treeGroupFlatSlots.size());
        int G = 0;
        for (int i = end - 1; i >= start; i--) {
            String s = treeGroupFlatSlots.get(i);
            if (s != null && !s.isEmpty()) { G = (i - start) + 1; break; }
        }
        if (G == 0) G = 9;
        return G;
    }

    private void syncTreeSliders() {
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null && strategy.getMode() == BuildMode.TREE) {
            syncGroupSliders(strategy);
        }
    }

    private int indexOfTreeBlockId(String id) {
        if (treeUniqueBlocks == null) return -1;
        for (int i = 0; i < treeUniqueBlocks.size(); i++) {
            if (treeUniqueBlocks.get(i).equals(id)) return i;
        }
        return -1;
    }

    private java.util.List<Integer> getTreeVisibleGroups() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        if (treeGroupWindows == null) return out;
        int total = treeGroupWindows.size();
        if (total <= 0) return out;
        for (int g = 0; g < total; g++) out.add(g);
        return out;
    }

    private boolean isWallMode() {
        return !this.handler.isSliderEnabled() && towerUniqueBlocks.isEmpty() && !wallUniqueBlocks.isEmpty();
    }

    private boolean isExcavationMode() {
        // slider value of 2 indicates excavation mode
        return this.handler.getSliderMode() == 2;
    }

    private boolean isMiningMode() {
        // slider value of 3 indicates mining mode
        return this.handler.getSliderMode() == 3;
    }

    private boolean isTerraformingMode() {
        // slider value of 4 indicates terraforming mode
        return this.handler.getSliderMode() == 4;
    }

    private boolean isTreeMode() {
        // slider value of 5 indicates tree mode
        return this.handler.getSliderMode() == 5;
    }

    /**
     * Get the current group mode strategy, or null if not in a group mode.
     */
    private GroupModeStrategy getGroupModeStrategy() {
        if (groupModeStrategy != null) {
            return groupModeStrategy;
        }
        // Initialize strategy based on current mode
        if (isWallMode()) {
            groupModeStrategy = new WallModeStrategy();
        } else if (isTowerMode()) {
            groupModeStrategy = new TowerModeStrategy();
        } else if (isTreeMode()) {
            groupModeStrategy = new TreeModeStrategy();
        } else {
            return null;
        }
        // Initialize with existing data
        initializeStrategyFromLegacyData();
        return groupModeStrategy;
    }

    /**
     * Initialize the strategy with existing legacy data (for backward compatibility during refactor).
     */
    private void initializeStrategyFromLegacyData() {
        if (groupModeStrategy == null) return;
        if (groupModeStrategy.getMode() == BuildMode.WALL) {
            groupModeStrategy.updateBlocksAndGroups(wallUniqueBlocks, wallBlockGroups);
            groupModeStrategy.updateGroupState(wallGroupWindows, wallGroupFlatSlots, java.util.Map.of());
        } else if (groupModeStrategy.getMode() == BuildMode.TOWER) {
            groupModeStrategy.updateBlocksAndGroups(towerUniqueBlocks, towerBlockGroups);
            var extraData = new java.util.HashMap<String, Object>();
            extraData.put("blockCounts", towerBlockCounts);
            extraData.put("height", towerLayers);
            groupModeStrategy.updateGroupState(towerGroupWindows, towerGroupFlatSlots, extraData);
        } else if (groupModeStrategy.getMode() == BuildMode.TREE) {
            groupModeStrategy.updateBlocksAndGroups(treeUniqueBlocks, treeBlockGroups);
            var extraData = new java.util.HashMap<String, Object>();
            extraData.put("tilingPresetOrdinal", treeTilingPresetOrdinal);
            groupModeStrategy.updateGroupState(treeGroupWindows, treeGroupFlatSlots, extraData);
        }
    }

    /**
     * Generic scroll method for group modes.
     */
    private void scrollGroup(GroupModeStrategy mode, int delta) {
        int rows = mode.getVisibleGroups().size();
        int maxScroll = Math.max(0, rows - 6);
        int ns = Math.max(0, Math.min(maxScroll, mode.getScroll() + delta));
        if (ns != mode.getScroll()) {
            mode.setScroll(ns);
            syncGroupSliders(mode);
        }
    }

    /**
     * Generic slider sync method for group modes.
     */
    private void syncGroupSliders(GroupModeStrategy mode) {
        if (mode == null || this.handler.isSliderEnabled()) return;

        java.util.List<Integer> vis = mode.getVisibleGroups();
        int rows = vis.size();
        for (int i = 0; i < 6; i++) {
            int idx = i + mode.getScroll();
            int group = (idx < rows) ? vis.get(idx) : -1;
            groupSliderToGroup[i] = group;
            WindowSlider s = i < groupRowSliders.size() ? groupRowSliders.get(i) : null;
            if (s == null) continue;
            boolean visible = idx < rows;
            s.visible = visible;
            if (visible) {
                int G = mode.effectiveGroupG(group);
                float w = (group >= 0 && group < mode.getGroupWindows().size()) ? mode.getGroupWindows().get(group) : 0.0f;
                s.syncTo(0, G, w);
            }
        }
    }

    private int indexOfBlockId(String id) {
        if (wallUniqueBlocks == null) return -1;
        for (int i = 0; i < wallUniqueBlocks.size(); i++) {
            if (id.equals(wallUniqueBlocks.get(i))) return i;
        }
        return -1;
    }

    private java.util.List<Integer> getVisibleGroups() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        if (wallGroupWindows == null) return out;
        int total = wallGroupWindows.size();
        if (total <= 0) return out;
        boolean[] used = new boolean[total];
        if (wallUniqueBlocks != null && wallBlockGroups != null) {
            int n = Math.min(wallUniqueBlocks.size(), wallBlockGroups.size());
            for (int i = 0; i < n; i++) {
                int g = wallBlockGroups.get(i);
                if (g >= 0 && g < total) used[g] = true;
            }
        }
        for (int g = 0; g < total; g++) if (used[g]) out.add(g);
        return out;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        // Vanilla chest-style background split into header/body/bottom slices from generic_54.png
        int left = this.x;
        int top = this.y;
        int rows = this.handler.getGolemRows();
        int margin = this.handler.getControlsMargin();

        int headerH = 17;            // chest header height
        int bodyH = rows * 18;       // golem rows area
        int bottomH = 96;            // player inventory + hotbar

        float texW = 256f;
        float texH = 256f;

        // Header strip (u:0..176, v:0..17)
        context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                left, top,
                left + this.backgroundWidth, top + headerH,
                0f / texW, 176f / texW,
                0f / texH, headerH / texH);

        // Filler from header to controls margin using a 1px band (prevents gaps if margin > 17)
        int fillerH = Math.max(0, margin - headerH);
        if (fillerH > 0) {
            float v1 = 10f / texH;
            float v2 = 11f / texH;
            context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                    left, top + headerH,
                    left + this.backgroundWidth, top + headerH + fillerH,
                    0f / texW, 176f / texW,
                    v1, v2);
        }

        // Body (golem inventory) starts at margin; source v starts at 17
        int bodyY = top + margin;
        if (bodyH > 0) {
            context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                    left, bodyY,
                    left + this.backgroundWidth, bodyY + bodyH,
                    0f / texW, 176f / texW,
                    17f / texH, (17f + bodyH) / texH);
        }

        // Bottom (player inventory + hotbar) slice at v=126
        int bottomY = bodyY + bodyH;
        context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                left, bottomY,
                left + this.backgroundWidth, bottomY + bottomH,
                0f / texW, 176f / texW,
                126f / texH, (126f + bottomH) / texH);

        // Path mode only: draw gradient slot frames and items (two rows)
        if (this.handler.isSliderEnabled()) {
            int slotsX = this.x + 8;
            int slotY0 = this.y + 26; // first row
            int slotY1 = slotY0 + 18 + 6; // second row below with small gap
            // Frames: 18x18 area with 1px border and darker inner background
            int borderColor = 0xFF555555; // medium-dark border
            int innerColor = 0xFF1C1C1C;  // darker inner background
            for (int i = 0; i < 9; i++) {
                int fx = slotsX + i * 18;
                int fy = slotY0;
                context.fill(fx - 1, fy - 1, fx + 17, fy + 17, borderColor);
                context.fill(fx, fy, fx + 16, fy + 16, innerColor);
            }
            for (int i = 0; i < 9; i++) {
                int fx = slotsX + i * 18;
                int fy = slotY1;
                context.fill(fx - 1, fy - 1, fx + 17, fy + 17, borderColor);
                context.fill(fx, fy, fx + 16, fy + 16, innerColor);
            }
            for (int i = 0; i < 9; i++) {
                String id = (gradientMainBlocks != null && i < gradientMainBlocks.length) ? gradientMainBlocks[i] : "";
                if (id != null && !id.isEmpty()) {
                    var ident = net.minecraft.util.Identifier.tryParse(id);
                    if (ident != null) {
                        var block = Registries.BLOCK.get(ident);
                        if (block != null) {
                            ItemStack stack = new ItemStack(block.asItem());
                            context.drawItem(stack, slotsX + i * 18, slotY0);
                        }
                    }
                }
            }
            for (int i = 0; i < 9; i++) {
                String id = (gradientStepBlocks != null && i < gradientStepBlocks.length) ? gradientStepBlocks[i] : "";
                if (id != null && !id.isEmpty()) {
                    var ident = net.minecraft.util.Identifier.tryParse(id);
                    if (ident != null) {
                        var block = Registries.BLOCK.get(ident);
                        if (block != null) {
                            ItemStack stack = new ItemStack(block.asItem());
                            context.drawItem(stack, slotsX + i * 18, slotY1);
                        }
                    }
                }
            }
            // Icons to the left (outside the window), aligned with each row
            ItemStack iconMain = new ItemStack(net.minecraft.item.Items.OAK_PLANKS);
            ItemStack iconStep = new ItemStack(net.minecraft.item.Items.OAK_STAIRS);
            int iconX = this.x - 20;
            context.drawItem(iconMain, iconX, slotY0);
            context.drawItem(iconStep, iconX, slotY1);
        } else if (isTerraformingMode()) {
            // Terraforming mode: draw 3 gradient slot rows (vertical, horizontal, sloped)
            int slotsX = this.x + 8;
            int slotY0 = this.y + 26; // First row: vertical
            int slotY1 = slotY0 + 18 + 6; // Second row: horizontal
            int slotY2 = slotY1 + 18 + 6; // Third row: sloped

            int borderColor = 0xFF555555;
            int innerColor = 0xFF1C1C1C;

            // Draw frames for all three rows
            for (int row = 0; row < 3; row++) {
                int slotY = slotY0 + row * (18 + 6);
                for (int i = 0; i < 9; i++) {
                    int fx = slotsX + i * 18;
                    context.fill(fx - 1, slotY - 1, fx + 17, slotY + 17, borderColor);
                    context.fill(fx, slotY, fx + 16, slotY + 16, innerColor);
                }
            }

            // Draw items for vertical gradient
            for (int i = 0; i < 9; i++) {
                String id = (terraformingGradientVertical != null && i < terraformingGradientVertical.length) ? terraformingGradientVertical[i] : "";
                if (id != null && !id.isEmpty()) {
                    var ident = net.minecraft.util.Identifier.tryParse(id);
                    if (ident != null) {
                        var block = Registries.BLOCK.get(ident);
                        if (block != null) {
                            ItemStack stack = new ItemStack(block.asItem());
                            context.drawItem(stack, slotsX + i * 18, slotY0);
                        }
                    }
                }
            }

            // Draw items for horizontal gradient
            for (int i = 0; i < 9; i++) {
                String id = (terraformingGradientHorizontal != null && i < terraformingGradientHorizontal.length) ? terraformingGradientHorizontal[i] : "";
                if (id != null && !id.isEmpty()) {
                    var ident = net.minecraft.util.Identifier.tryParse(id);
                    if (ident != null) {
                        var block = Registries.BLOCK.get(ident);
                        if (block != null) {
                            ItemStack stack = new ItemStack(block.asItem());
                            context.drawItem(stack, slotsX + i * 18, slotY1);
                        }
                    }
                }
            }

            // Draw items for sloped gradient
            for (int i = 0; i < 9; i++) {
                String id = (terraformingGradientSloped != null && i < terraformingGradientSloped.length) ? terraformingGradientSloped[i] : "";
                if (id != null && !id.isEmpty()) {
                    var ident = net.minecraft.util.Identifier.tryParse(id);
                    if (ident != null) {
                        var block = Registries.BLOCK.get(ident);
                        if (block != null) {
                            ItemStack stack = new ItemStack(block.asItem());
                            context.drawItem(stack, slotsX + i * 18, slotY2);
                        }
                    }
                }
            }

            // Draw labels to the left of each row
            context.drawText(this.textRenderer, Text.literal("Vertical"), this.x + 8, slotY0 - 10, 0xFFFFFF, false);
            context.drawText(this.textRenderer, Text.literal("Horizontal"), this.x + 8, slotY1 - 10, 0xFFFFFF, false);
            context.drawText(this.textRenderer, Text.literal("Sloped"), this.x + 8, slotY2 - 10, 0xFFFFFF, false);
        }
    }

    @Override
    protected void init() {
        super.init();
        // Place window slider in the controls margin area. Gradient slots are handled via mouse clicks, not buttons.
        int controlsTop = this.y + 8; // leave a small header gap
        // int slotsX = this.x + 8; // for reference
        // int slotY = controlsTop + 18; // below title area

        if (this.handler.isSliderEnabled()) {
            // Path mode: window sliders to the right of gradient rows
            int wx = this.x + 8 + 9 * 18 + 12;
            int wy0 = controlsTop + 18; // align with first gradient row
            int wy1 = wy0 + 18 + 6;     // second row
            int sliderWidth = 90;
            int sliderHeight = 20;
            int g0 = effectiveG(0);
            double norm0 = g0 <= 0 ? 0.0 : (double) Math.min(gradientWindowMain, g0) / (double) g0;
            windowSliderMain = new WindowSlider(wx, wy0, sliderWidth, sliderHeight, norm0, 0);
            this.addDrawableChild(windowSliderMain);
            int g1 = effectiveG(1);
            double norm1 = g1 <= 0 ? 0.0 : (double) Math.min(gradientWindowStep, g1) / (double) g1;
            windowSliderStep = new WindowSlider(wx, wy1, sliderWidth, sliderHeight, norm1, 1);
            this.addDrawableChild(windowSliderStep);

            // Transparent buttons over gradient slots for reliable clicks (both rows)
            int slotsX = this.x + 8;
            int slotY0 = this.y + 26;
            int slotY1 = slotY0 + 18 + 6;
            for (int i = 0; i < 9; i++) {
                final int idx = i;
                int gx = slotsX + i * 18;
                var btn0 = ButtonWidget.builder(Text.empty(), b -> {
                    var mc = MinecraftClient.getInstance();
                    var player = mc.player;
                    if (player == null) return;
                    // Always use the item on the mouse cursor; never fall back to hotbar
                    ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                    ItemStack held = cursor;
                    boolean clear = held.isEmpty() || !(held.getItem() instanceof BlockItem);
                    if (clear) {
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), 0, idx, java.util.Optional.empty()));
                    } else {
                        BlockItem bi = (BlockItem) held.getItem();
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), 0, idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                    }
                }).dimensions(gx, slotY0, 18, 18).build();
                btn0.setAlpha(0f);
                this.addDrawableChild(btn0);

                var btn1 = ButtonWidget.builder(Text.empty(), b -> {
                    var mc = MinecraftClient.getInstance();
                    var player = mc.player;
                    if (player == null) return;
                    ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                    ItemStack held = cursor;
                    boolean clear = held.isEmpty() || !(held.getItem() instanceof BlockItem);
                    if (clear) {
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), 1, idx, java.util.Optional.empty()));
                    } else {
                        BlockItem bi = (BlockItem) held.getItem();
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), 1, idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                    }
                }).dimensions(gx, slotY1, 18, 18).build();
                btn1.setAlpha(0f);
                this.addDrawableChild(btn1);
            }
        }

        // Width slider under the gradient row (right-aligned)
        if (this.handler.isSliderEnabled()) {
            int wsliderW = 90;
            int wsliderH = 12;
            int slotTop = this.y + 26; // top of first gradient row
            int wsliderY = slotTop + (18 + 6) + 18 + 6; // below second row
            int wsliderX = this.x + this.backgroundWidth - 8 - wsliderW;
            widthSlider = new WidthSlider(wsliderX, wsliderY, wsliderW, wsliderH, pathWidth);
            this.addDrawableChild(widthSlider);
        } else if (!this.handler.isSliderEnabled() && (this.handler.getSliderMode() <= 1 || this.handler.getSliderMode() == 5)) {
            // Group Mode UI (Wall, Tower, Tree): create per-row sliders and scroll buttons using strategy pattern
            // Check sliderMode: 0 or 1 indicates Wall or Tower mode, 5 indicates Tree mode
            // Mode-specific data arrives later via network, but we create sliders now
            groupModeStrategy = null; // Reset to force re-initialization
            GroupModeStrategy strategy = getGroupModeStrategy();

            // Create sliders regardless of whether data has arrived yet
            // They will be synced when network data arrives
            groupRowSliders.clear();
            int gridTop = this.y + 26;
            int rowSpacing = 18 + 6;
            int gridX = this.x + 8;
            int wx2 = gridX + 9 * 18 + 12;
            int w2 = 90;
            int h2 = 10;
            BuildMode mode = strategy != null ? strategy.getMode() : BuildMode.WALL; // Default to WALL, will be corrected when data arrives

            for (int r = 0; r < 6; r++) {
                int finalR = r;
                int sy = gridTop + r * rowSpacing + 3;
                WindowSlider s = new WindowSlider(wx2, sy, w2, h2, 0.0, 0) {
                    @Override
                    protected void applyValue() {
                        int sliderIdx = finalR;
                        if (sliderIdx < 0 || sliderIdx >= 6) return;
                        int group = groupSliderToGroup[sliderIdx];
                        if (group < 0) return;
                        GroupModeStrategy strat = getGroupModeStrategy();
                        if (strat == null) return;
                        int G = strat.effectiveGroupG(group);
                        float w = (G <= 0) ? 0.0f : Math.round(this.value * G * 10.0f) / 10.0f;
                        w = Math.max(0.0f, Math.min(G, w));
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeWindowC2SPayload(getEntityId(), strat.getMode(), group, w));
                    }
                };
                groupRowSliders.add(s);
                this.addDrawableChild(s);
            }
            var upBtn = ButtonWidget.builder(Text.literal("▲"), b -> {
                GroupModeStrategy strat = getGroupModeStrategy();
                if (strat != null) scrollGroup(strat, -1);
            }).dimensions(wx2 + w2 + 4, gridTop, 14, 12).build();
            var dnBtn = ButtonWidget.builder(Text.literal("▼"), b -> {
                GroupModeStrategy strat = getGroupModeStrategy();
                if (strat != null) scrollGroup(strat, 1);
            }).dimensions(wx2 + w2 + 4, gridTop + 5 * rowSpacing, 14, 12).build();
            this.addDrawableChild(upBtn);
            this.addDrawableChild(dnBtn);

            // Tower mode: add layers slider on the right side
            if (mode == BuildMode.TOWER) {
                int layersSliderW = 90;
                int layersSliderH = 12;
                int layersSliderX = this.x + this.backgroundWidth - 8 - layersSliderW;
                int layersSliderY = this.y + 26;
                towerLayersSlider = new TowerLayersSlider(layersSliderX, layersSliderY, layersSliderW, layersSliderH, towerLayers);
                this.addDrawableChild(towerLayersSlider);
            }

            // Tree mode: add tiling preset button
            if (mode == BuildMode.TREE) {
                String presetText = treeTilingPresetOrdinal == 0 ? "3x3" : "5x5";
                var presetBtn = ButtonWidget.builder(Text.literal("Preset: " + presetText), b -> {
                    int newPreset = (treeTilingPresetOrdinal == 0) ? 1 : 0;
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTreeTilingPresetC2SPayload(getEntityId(), newPreset));
                    treeTilingPresetOrdinal = newPreset;
                    b.setMessage(Text.literal("Preset: " + (newPreset == 0 ? "3x3" : "5x5")));
                }).dimensions(this.x + this.backgroundWidth - 8 - 70, gridTop, 70, 20).build();
                this.addDrawableChild(presetBtn);
            }

            // Sync sliders if strategy is available, otherwise they'll be synced when data arrives
            if (strategy != null) {
                syncGroupSliders(strategy);
            }
        } else if (isExcavationMode()) {
            // Excavation Mode UI: simple sliders for height and depth
            int sliderW = 120;
            int sliderH = 12;
            int sliderX = this.x + this.backgroundWidth - 8 - sliderW;
            int sliderY1 = this.y + 26; // First slider position
            int sliderY2 = sliderY1 + sliderH + 14; // Second slider below with gap

            excavationHeightSlider = new ExcavationHeightSlider(sliderX, sliderY1, sliderW, sliderH, excavationHeight);
            excavationDepthSlider = new ExcavationDepthSlider(sliderX, sliderY2, sliderW, sliderH, excavationDepth);

            this.addDrawableChild(excavationHeightSlider);
            this.addDrawableChild(excavationDepthSlider);
        } else if (isTerraformingMode()) {
            // Terraforming mode: 3 gradient rows + window sliders + scan radius slider
            int wx = this.x + 8 + 9 * 18 + 12;
            int wy0 = controlsTop + 18; // First gradient row (vertical)
            int wy1 = wy0 + 18 + 6;     // Second gradient row (horizontal)
            int wy2 = wy1 + 18 + 6;     // Third gradient row (sloped)
            int sliderWidth = 90;
            int sliderHeight = 12;

            int g0 = effectiveTerraformingG(0);
            double norm0 = g0 <= 0 ? 0.0 : (double) Math.min(terraformingGradientVerticalWindow, g0) / (double) g0;
            terraformingSliderVertical = new WindowSlider(wx, wy0, sliderWidth, sliderHeight, norm0, 0);
            this.addDrawableChild(terraformingSliderVertical);

            int g1 = effectiveTerraformingG(1);
            double norm1 = g1 <= 0 ? 0.0 : (double) Math.min(terraformingGradientHorizontalWindow, g1) / (double) g1;
            terraformingSliderHorizontal = new WindowSlider(wx, wy1, sliderWidth, sliderHeight, norm1, 1);
            this.addDrawableChild(terraformingSliderHorizontal);

            int g2 = effectiveTerraformingG(2);
            double norm2 = g2 <= 0 ? 0.0 : (double) Math.min(terraformingGradientSlopedWindow, g2) / (double) g2;
            terraformingSliderSloped = new WindowSlider(wx, wy2, sliderWidth, sliderHeight, norm2, 2);
            this.addDrawableChild(terraformingSliderSloped);

            // Scan radius slider at the right
            int scanSliderX = this.x + this.backgroundWidth - 8 - 90;
            int scanSliderY = controlsTop + 18;
            terraformingScanRadiusSlider = new TerraformingScanRadiusSlider(scanSliderX, scanSliderY, 90, 12, terraformingScanRadius);
            this.addDrawableChild(terraformingScanRadiusSlider);

            // Transparent buttons over gradient slots for all three rows
            int slotsX = this.x + 8;
            int slotY0 = this.y + 26;
            int slotY1 = slotY0 + 18 + 6;
            int slotY2 = slotY1 + 18 + 6;
            for (int i = 0; i < 9; i++) {
                final int idx = i;
                int gx = slotsX + i * 18;

                // Vertical gradient row
                var btn0 = ButtonWidget.builder(Text.empty(), b -> {
                    var mc = MinecraftClient.getInstance();
                    var player = mc.player;
                    if (player == null) return;
                    ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                    boolean clear = cursor.isEmpty() || !(cursor.getItem() instanceof BlockItem);
                    if (clear) {
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientSlotC2SPayload(getEntityId(), 0, idx, java.util.Optional.empty()));
                    } else {
                        BlockItem bi = (BlockItem) cursor.getItem();
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientSlotC2SPayload(getEntityId(), 0, idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                    }
                }).dimensions(gx, slotY0, 18, 18).build();
                btn0.setAlpha(0f);
                this.addDrawableChild(btn0);

                // Horizontal gradient row
                var btn1 = ButtonWidget.builder(Text.empty(), b -> {
                    var mc = MinecraftClient.getInstance();
                    var player = mc.player;
                    if (player == null) return;
                    ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                    boolean clear = cursor.isEmpty() || !(cursor.getItem() instanceof BlockItem);
                    if (clear) {
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientSlotC2SPayload(getEntityId(), 1, idx, java.util.Optional.empty()));
                    } else {
                        BlockItem bi = (BlockItem) cursor.getItem();
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientSlotC2SPayload(getEntityId(), 1, idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                    }
                }).dimensions(gx, slotY1, 18, 18).build();
                btn1.setAlpha(0f);
                this.addDrawableChild(btn1);

                // Sloped gradient row
                var btn2 = ButtonWidget.builder(Text.empty(), b -> {
                    var mc = MinecraftClient.getInstance();
                    var player = mc.player;
                    if (player == null) return;
                    ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                    boolean clear = cursor.isEmpty() || !(cursor.getItem() instanceof BlockItem);
                    if (clear) {
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientSlotC2SPayload(getEntityId(), 2, idx, java.util.Optional.empty()));
                    } else {
                        BlockItem bi = (BlockItem) cursor.getItem();
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientSlotC2SPayload(getEntityId(), 2, idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                    }
                }).dimensions(gx, slotY2, 18, 18).build();
                btn2.setAlpha(0f);
                this.addDrawableChild(btn2);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean traced) {
        int mx = (int) click.x();
        int my = (int) click.y();
        if (this.handler.isSliderEnabled()) {
            // Pathing mode: right-click clears ghost slot
            if (click.button() == 1) {
                RowCol rc = gradientIndexAt(mx, my);
                if (rc != null) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), rc.row, rc.col, java.util.Optional.empty()));
                    return true;
                }
            }
            return super.mouseClicked(click, traced);
        }

        // Tree Mode: click/drag icons to assign groups; click slots to set/clear
        if (isTreeMode() && click.button() == 0 && !treeIconHits.isEmpty()) {
            for (IconHit ih : treeIconHits) {
                if (ih.contains(mx, my)) {
                    treePendingAssignBlockId = ih.blockId;
                    treeDraggingBlockId = ih.blockId;
                    draggingFromIcon = true;
                    draggingStartX = mx;
                    draggingStartY = my;
                    return true;
                }
            }
        }
        if (isTreeMode()) {
            int startY = this.y + 26;
            int rowSpacing = 18 + 6;
            int gridX = this.x + 8;
            java.util.List<Integer> vis = getTreeVisibleGroups();
            int rows = vis.size();
            int rLocal = (my - startY) / rowSpacing;
            int rIdx = rLocal + treeScroll;
            if (rLocal >= 0 && rLocal < 6 && rIdx >= 0 && rIdx < rows) {
                int c = (mx - gridX) / 18;
                if (c >= 0 && c < 9) {
                    if (treePendingAssignBlockId != null) {
                        int groupIdx = vis.get(rIdx);
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TREE, treePendingAssignBlockId, groupIdx));
                        int bi = indexOfTreeBlockId(treePendingAssignBlockId);
                        if (bi >= 0 && bi < treeBlockGroups.size()) { treeBlockGroups.set(bi, groupIdx); syncTreeSliders(); }
                        treePendingAssignBlockId = null;
                        return true;
                    }
                    if (click.button() == 1) {
                        int groupIdx = vis.get(rIdx);
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeSlotC2SPayload(getEntityId(), BuildMode.TREE, groupIdx, c, java.util.Optional.empty()));
                        return true;
                    } else {
                        var mc = MinecraftClient.getInstance();
                        var player = mc.player;
                        if (player != null) {
                            ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                            if (!cursor.isEmpty() && cursor.getItem() instanceof BlockItem bi) {
                                int groupIdx = vis.get(rIdx);
                                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeSlotC2SPayload(getEntityId(), BuildMode.TREE, groupIdx, c, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                                return true;
                            }
                        }
                    }
                }
            }
            if (treePendingAssignBlockId != null) {
                int bottomY = startY + Math.min(6, Math.max(0, rows - treeScroll)) * rowSpacing;
                int iconAreaRight = this.x - 20 + 16;
                int iconAreaLeft = 0;
                if (my >= bottomY && mx >= iconAreaLeft && mx < iconAreaRight) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TREE, treePendingAssignBlockId, -1));
                    treePendingAssignBlockId = null;
                    return true;
                }
            }
            return super.mouseClicked(click, traced);
        }

        // Tower Mode: click/drag icons to assign groups; click slots to set/clear
        if (isTowerMode() && click.button() == 0 && !towerIconHits.isEmpty()) {
            for (IconHit ih : towerIconHits) {
                if (ih.contains(mx, my)) {
                    towerPendingAssignBlockId = ih.blockId;
                    towerDraggingBlockId = ih.blockId;
                    draggingFromIcon = true;
                    draggingStartX = mx;
                    draggingStartY = my;
                    return true;
                }
            }
        }
        if (isTowerMode()) {
            int startY = this.y + 26;
            int rowSpacing = 18 + 6;
            int gridX = this.x + 8;
            java.util.List<Integer> vis = getTowerVisibleGroups();
            int rows = vis.size();
            int rLocal = (my - startY) / rowSpacing;
            int rIdx = rLocal + towerScroll;
            if (rLocal >= 0 && rLocal < 6 && rIdx >= 0 && rIdx < rows) {
                int c = (mx - gridX) / 18;
                if (c >= 0 && c < 9) {
                    if (towerPendingAssignBlockId != null) {
                        int groupIdx = vis.get(rIdx);
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TOWER, towerPendingAssignBlockId, groupIdx));
                        int bi = indexOfTowerBlockId(towerPendingAssignBlockId);
                        if (bi >= 0 && bi < towerBlockGroups.size()) { towerBlockGroups.set(bi, groupIdx); syncTowerSliders(); }
                        towerPendingAssignBlockId = null;
                        return true;
                    }
                    if (click.button() == 1) {
                        int groupIdx = vis.get(rIdx);
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeSlotC2SPayload(getEntityId(), BuildMode.TOWER, groupIdx, c, java.util.Optional.empty()));
                        return true;
                    } else {
                        var mc = MinecraftClient.getInstance();
                        var player = mc.player;
                        if (player != null) {
                            ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                            if (!cursor.isEmpty() && cursor.getItem() instanceof BlockItem bi) {
                                int groupIdx = vis.get(rIdx);
                                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeSlotC2SPayload(getEntityId(), BuildMode.TOWER, groupIdx, c, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                                return true;
                            }
                        }
                    }
                }
            }
            if (towerPendingAssignBlockId != null) {
                int bottomY = startY + Math.min(6, Math.max(0, rows - towerScroll)) * rowSpacing;
                int iconAreaRight = this.x - 50 + 16;
                int iconAreaLeft = 0;
                if (my >= bottomY && mx >= iconAreaLeft && mx < iconAreaRight) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TOWER, towerPendingAssignBlockId, -1));
                    towerPendingAssignBlockId = null;
                    return true;
                }
            }
            return super.mouseClicked(click, traced);
        }

        // Wall Mode: click/drag icons to assign groups; click slots to set/clear
        int startY = this.y + 26;
        int rowSpacing = 18 + 6;
        // Start drag if clicking on any label icon (icon positions are updated each frame)
        if (!this.handler.isSliderEnabled() && click.button() == 0 && !wallIconHits.isEmpty()) {
            for (IconHit ih : wallIconHits) {
                if (ih.contains(mx, my)) {
                    pendingAssignBlockId = ih.blockId; // also support click-then-row
                    draggingFromIcon = true;
                    draggingBlockId = ih.blockId;
                    draggingStartX = mx;
                    draggingStartY = my;
                    return true;
                }
            }
        }
        int gridX = this.x + 8;
        java.util.List<Integer> vis = getVisibleGroups();
        int rows = vis.size();
        int rLocal = (my - startY) / rowSpacing;
        int rIdx = rLocal + wallScroll;
        if (rLocal >= 0 && rLocal < 6 && rIdx >= 0 && rIdx < rows) {
            int c = (mx - gridX) / 18;
            if (c >= 0 && c < 9) {
                if (pendingAssignBlockId != null) {
                    int groupIdx = vis.get(rIdx);
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.WALL, pendingAssignBlockId, groupIdx));
                    int bi = indexOfBlockId(pendingAssignBlockId);
                    if (bi >= 0 && bi < wallBlockGroups.size()) { wallBlockGroups.set(bi, groupIdx); syncWallSliders(); }
                    pendingAssignBlockId = null;
                    return true;
                }
                if (click.button() == 1) {
                    int groupIdx = vis.get(rIdx);
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeSlotC2SPayload(getEntityId(), BuildMode.WALL, groupIdx, c, java.util.Optional.empty()));
                    return true;
                } else {
                    var mc = MinecraftClient.getInstance();
                    var player = mc.player;
                    if (player != null) {
                        ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                        if (!cursor.isEmpty() && cursor.getItem() instanceof BlockItem bi) {
                            int groupIdx = vis.get(rIdx);
                            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeSlotC2SPayload(getEntityId(), BuildMode.WALL, groupIdx, c, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                            return true;
                        }
                    }
                }
            }
        }
        if (pendingAssignBlockId != null) {
            int bottomY = startY + Math.min(6, Math.max(0, rows - wallScroll)) * rowSpacing;
            // New group only if under the icon area on the left (beneath existing icons),
            // spanning all the way to the left side of the screen.
            int iconAreaRight = this.x - 20 + 16; // first icon right edge
            int iconAreaLeft = 0; // extend to the left edge of the screen
            if (my >= bottomY && mx >= iconAreaLeft && mx < iconAreaRight) {
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.WALL, pendingAssignBlockId, -1));
                pendingAssignBlockId = null;
                return true;
            }
        }
        return super.mouseClicked(click, traced);
    }

    @Override
    public boolean mouseReleased(Click click) {
        int mx = (int) click.x();
        int my = (int) click.y();
        boolean handled = false;

        // Tree Mode drag and drop handling
        if (isTreeMode() && draggingFromIcon && treeDraggingBlockId != null) {
            int iconX = this.x - 20;
            int startY = this.y + 26;
            int rowSpacing = 18 + 6;
            int gridX = this.x + 8;
            java.util.List<Integer> vis = getTreeVisibleGroups();
            int rows = vis.size();

            int dx = Math.abs(mx - draggingStartX);
            int dy = Math.abs(my - draggingStartY);
            boolean moved = (dx + dy) > 4;

            if (moved) {
                // Prefer drop onto another label icon to combine groups
                if (!treeIconHits.isEmpty()) {
                    for (IconHit ih : treeIconHits) {
                        if (ih.contains(mx, my)) {
                            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TREE, treeDraggingBlockId, ih.group));
                            int bi = indexOfTreeBlockId(treeDraggingBlockId);
                            if (bi >= 0 && bi < treeBlockGroups.size()) { treeBlockGroups.set(bi, ih.group); syncTreeSliders(); }
                            handled = true;
                            treePendingAssignBlockId = null;
                            break;
                        }
                    }
                }
                if (!handled) {
                    int rLocal = (my - startY) / rowSpacing;
                    int rIdx = rLocal + treeScroll;
                    if (rLocal >= 0 && rLocal < 6 && rIdx >= 0 && rIdx < rows) {
                        // Dropped over a visible group row -> assign to that group
                        int groupIdx = vis.get(rIdx);
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TREE, treeDraggingBlockId, groupIdx));
                        int bi = indexOfTreeBlockId(treeDraggingBlockId);
                        if (bi >= 0 && bi < treeBlockGroups.size()) { treeBlockGroups.set(bi, groupIdx); syncTreeSliders(); }
                        handled = true;
                        treePendingAssignBlockId = null;
                    } else {
                        // If dropped below the last visible row within the icon area on the left, create a new group
                        int bottomY = startY + Math.min(6, Math.max(0, rows - treeScroll)) * rowSpacing;
                        int iconAreaRight = this.x - 20 + 16;
                        int iconAreaLeft = 0;
                        if (my >= bottomY && mx >= iconAreaLeft && mx < iconAreaRight) {
                            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TREE, treeDraggingBlockId, -1));
                            handled = true;
                            treePendingAssignBlockId = null;
                        }
                    }
                }
            }
            // Clear drag state regardless of handled
            draggingFromIcon = false;
            treeDraggingBlockId = null;
            return handled || super.mouseReleased(click);
        }

        // Tower Mode drag and drop handling
        if (isTowerMode() && draggingFromIcon && towerDraggingBlockId != null) {
            int iconX = this.x - 50;
            int startY = this.y + 26;
            int rowSpacing = 18 + 6;
            int gridX = this.x + 8;
            java.util.List<Integer> vis = getTowerVisibleGroups();
            int rows = vis.size();

            int dx = Math.abs(mx - draggingStartX);
            int dy = Math.abs(my - draggingStartY);
            boolean moved = (dx + dy) > 4;

            if (moved) {
                // Prefer drop onto another label icon to combine groups
                if (!towerIconHits.isEmpty()) {
                    for (IconHit ih : towerIconHits) {
                        if (ih.contains(mx, my)) {
                            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TOWER, towerDraggingBlockId, ih.group));
                            int bi = indexOfTowerBlockId(towerDraggingBlockId);
                            if (bi >= 0 && bi < towerBlockGroups.size()) { towerBlockGroups.set(bi, ih.group); syncTowerSliders(); }
                            handled = true;
                            towerPendingAssignBlockId = null;
                            break;
                        }
                    }
                }
                if (!handled) {
                    int rLocal = (my - startY) / rowSpacing;
                    int rIdx = rLocal + towerScroll;
                    if (rLocal >= 0 && rLocal < 6 && rIdx >= 0 && rIdx < rows) {
                        // Dropped over a visible group row -> assign to that group
                        int groupIdx = vis.get(rIdx);
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TOWER, towerDraggingBlockId, groupIdx));
                        int bi = indexOfTowerBlockId(towerDraggingBlockId);
                        if (bi >= 0 && bi < towerBlockGroups.size()) { towerBlockGroups.set(bi, groupIdx); syncTowerSliders(); }
                        handled = true;
                        towerPendingAssignBlockId = null;
                    } else {
                        // If dropped below the last visible row within the icon area on the left, create a new group
                        int bottomY = startY + Math.min(6, Math.max(0, rows - towerScroll)) * rowSpacing;
                        int iconAreaRight = this.x - 50 + 16;
                        int iconAreaLeft = 0;
                        if (my >= bottomY && mx >= iconAreaLeft && mx < iconAreaRight) {
                            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.TOWER, towerDraggingBlockId, -1));
                            handled = true;
                            towerPendingAssignBlockId = null;
                        }
                    }
                }
            }
            // Clear drag state regardless of handled
            draggingFromIcon = false;
            towerDraggingBlockId = null;
            return handled || super.mouseReleased(click);
        }

        if (!this.handler.isSliderEnabled() && draggingFromIcon && draggingBlockId != null) {
            int iconX = this.x - 20;
            int startY = this.y + 26;
            int rowSpacing = 18 + 6;
            int gridX = this.x + 8;
            java.util.List<Integer> vis = getVisibleGroups();
            int rows = vis.size();

            // Consider it a drag if release is not within the original icon column or moved sufficiently
            int dx = Math.abs(mx - draggingStartX);
            int dy = Math.abs(my - draggingStartY);
            boolean moved = (dx + dy) > 4;

            if (moved) {
                // Prefer drop onto another label icon to combine groups
                if (!wallIconHits.isEmpty()) {
                    for (IconHit ih : wallIconHits) {
                        if (ih.contains(mx, my)) {
                            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.WALL, draggingBlockId, ih.group));
                            int bi = indexOfBlockId(draggingBlockId);
                            if (bi >= 0 && bi < wallBlockGroups.size()) { wallBlockGroups.set(bi, ih.group); syncWallSliders(); }
                            handled = true;
                            pendingAssignBlockId = null;
                            break;
                        }
                    }
                }
                if (!handled) {
                    int rLocal = (my - startY) / rowSpacing;
                    int rIdx = rLocal + wallScroll;
                    if (rLocal >= 0 && rLocal < 6 && rIdx >= 0 && rIdx < rows) {
                        // Dropped over a visible group row -> assign to that group
                        int groupIdx = vis.get(rIdx);
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.WALL, draggingBlockId, groupIdx));
                        int bi = indexOfBlockId(draggingBlockId);
                        if (bi >= 0 && bi < wallBlockGroups.size()) { wallBlockGroups.set(bi, groupIdx); syncWallSliders(); }
                        handled = true;
                        pendingAssignBlockId = null; // complete the action
                    } else {
                        // If dropped below the last visible row within the icon area on the left, create a new group
                        int bottomY = startY + Math.min(6, Math.max(0, rows - wallScroll)) * rowSpacing;
                        int iconAreaRight = this.x - 20 + 16;
                        int iconAreaLeft = 0; // extend to left screen edge
                        if (my >= bottomY && mx >= iconAreaLeft && mx < iconAreaRight) {
                            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(getEntityId(), BuildMode.WALL, draggingBlockId, -1));
                            handled = true;
                            pendingAssignBlockId = null;
                        }
                    }
                }
            }
            // Clear drag state regardless of handled
            draggingFromIcon = false;
            draggingBlockId = null;
        }
        return handled || super.mouseReleased(click);
    }

    private static class RowCol { final int row; final int col; RowCol(int r, int c){row=r;col=c;} }
    private RowCol gradientIndexAt(int mx, int my) {
        int slotsX = this.x + 8;
        int slotY0 = this.y + 26; // first row
        int slotY1 = slotY0 + 18 + 6; // second row
        int w = 18, h = 18, pad = 18;
        // First row
        if (my >= slotY0 && my < slotY0 + h) {
            int dx = mx - slotsX;
            if (dx >= 0) {
                int col = dx / pad;
                if (col >= 0 && col < 9) {
                    int colX = slotsX + col * pad;
                    if (mx >= colX && mx < colX + w) return new RowCol(0, col);
                }
            }
        }
        // Second row
        if (my >= slotY1 && my < slotY1 + h) {
            int dx = mx - slotsX;
            if (dx >= 0) {
                int col = dx / pad;
                if (col >= 0 && col < 9) {
                    int colX = slotsX + col * pad;
                    if (mx >= colX && mx < colX + w) return new RowCol(1, col);
                }
            }
        }
        return null;
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Labels (foreground coordinates are relative to screen top-left)
        context.drawText(this.textRenderer, this.title, 8, 6, 0xFFFFFF, false);
        // Player inventory label (match vanilla placement)
        int invY = this.backgroundHeight - 96 + 2;
        context.drawText(this.textRenderer, this.playerInventoryTitle, 8, invY, 0x404040, false);
        if (this.handler.isSliderEnabled()) {
            context.drawText(this.textRenderer, Text.literal("Gradient"), 8, 18, 0xA0A0A0, false);
        }
        // Width label near the slider
        if (widthSlider != null && this.handler.isSliderEnabled()) {
            int lx = widthSlider.getX() - this.x;
            int ly = widthSlider.getY() - this.y - 10;
            context.drawText(this.textRenderer, Text.literal("Width: " + this.pathWidth), lx, ly, 0xFFFFFF, false);
        }

        // Marker dots above each window slider (path mode)
        if (this.handler.isSliderEnabled()) {
            drawSliderMarkers(context, windowSliderMain, effectiveG(0));
            drawSliderMarkers(context, windowSliderStep, effectiveG(1));
        }

        // Marker dots above group mode sliders (Wall, Tower, Tree)
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            drawGroupSliderMarkers(context, strategy);
        }

        // Excavation Mode UI: labels for sliders
        if (isExcavationMode()) {
            context.drawText(this.textRenderer, Text.literal("Excavation Settings"), 8, 18, 0xA0A0A0, false);
        }

        // Group Mode UI (Wall, Tower, Tree): label icons aligned to group rows + group rows
        if (strategy != null) {
            int iconX = strategy.getIconXOffset(); // relative to GUI left
            int startY = 26; // relative to GUI top
            int rowSpacing = 18 + 6;
            boolean showBlockCounts = strategy.shouldShowBlockCounts();

            // Draw icons per visible group row; stack multiple icons leftward
            groupIconHits.clear();
            java.util.List<Integer> vis = strategy.getVisibleGroups();
            int rows = vis.size();
            int drawRows = Math.min(Math.max(0, rows - strategy.getScroll()), 6);
            java.util.List<String> uniqueBlocks = strategy.getUniqueBlocks();
            java.util.List<Integer> blockGroups = strategy.getBlockGroups();

            if (uniqueBlocks != null && blockGroups != null && !uniqueBlocks.isEmpty() && !blockGroups.isEmpty()) {
                java.util.Map<Integer, java.util.List<String>> groupToBlocks = new java.util.HashMap<>();
                int n = Math.min(uniqueBlocks.size(), blockGroups.size());
                for (int i = 0; i < n; i++) {
                    int g = blockGroups.get(i);
                    groupToBlocks.computeIfAbsent(g, k -> new java.util.ArrayList<>()).add(uniqueBlocks.get(i));
                }
                for (int r = 0; r < drawRows; r++) {
                    int groupIdx = vis.get(r + strategy.getScroll());
                    java.util.List<String> list = groupToBlocks.getOrDefault(groupIdx, java.util.Collections.emptyList());
                    int y = startY + r * rowSpacing;
                    for (int i = 0; i < list.size(); i++) {
                        String id = list.get(i);
                        var ident = Identifier.tryParse(id);
                        if (ident == null) continue;
                        var block = Registries.BLOCK.get(ident);
                        if (block == null) continue;
                        ItemStack icon = new ItemStack(block.asItem());
                        int ix = iconX - i * 18; // stack leftward
                        context.drawItem(icon, ix, y);

                        // Draw block count next to icon if enabled (Tower mode)
                        if (showBlockCounts) {
                            Map<String, Integer> blockCounts = strategy.getBlockCounts();
                            int count = blockCounts.getOrDefault(id, 0);
                            String countText = "x" + count;
                            int textX = ix + 18;
                            int textY = y + 4;
                            context.drawText(this.textRenderer, countText, textX, textY, 0xFFFFFF, true);
                        }

                        groupIconHits.add(new IconHit(id, groupIdx, this.x + ix, this.y + y, 16, 16));
                    }
                }
            }

            // Group rows (ghost slots + items)
            vis = strategy.getVisibleGroups();
            rows = vis.size();
            drawRows = Math.min(Math.max(0, rows - strategy.getScroll()), 6);
            int gridX = 8; // relative to GUI left, align with path mode
            java.util.List<String> groupFlatSlots = strategy.getGroupFlatSlots();

            for (int r = 0; r < drawRows; r++) {
                int groupIdx = vis.get(r + strategy.getScroll());
                int y = startY + r * rowSpacing;
                for (int c = 0; c < 9; c++) {
                    int x = gridX + c * 18;
                    int col = 0xFF404040;
                    int ix1 = x, iy1 = y, ix2 = x + 16, iy2 = y + 16;
                    context.fill(ix1, iy1, ix2, iy2, 0x80000000);
                    // border 1px
                    context.fill(ix1 - 1, iy1 - 1, ix2 + 1, iy1, col); // top
                    context.fill(ix1 - 1, iy2, ix2 + 1, iy2 + 1, col); // bottom
                    context.fill(ix1 - 1, iy1, ix1, iy2, col); // left
                    context.fill(ix2, iy1, ix2 + 1, iy2, col); // right
                    int flatIndex = groupIdx * 9 + c;
                    if (flatIndex >= 0 && flatIndex < groupFlatSlots.size()) {
                        String bid = groupFlatSlots.get(flatIndex);
                        if (bid != null && !bid.isEmpty()) {
                            var ident = Identifier.tryParse(bid);
                            if (ident != null) {
                                var block = Registries.BLOCK.get(ident);
                                if (block != null) {
                                    ItemStack st = new ItemStack(block.asItem());
                                    context.drawItem(st, x, y);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Cursor-following visual when dragging a label icon
        if ((isWallMode() || isTowerMode() || isTreeMode()) && draggingFromIcon && (draggingBlockId != null || towerDraggingBlockId != null || treeDraggingBlockId != null)) {
            String dragId = draggingBlockId != null ? draggingBlockId : (towerDraggingBlockId != null ? towerDraggingBlockId : treeDraggingBlockId);
            var ident = Identifier.tryParse(dragId);
            if (ident != null) {
                var block = Registries.BLOCK.get(ident);
                if (block != null) {
                    ItemStack icon = new ItemStack(block.asItem());
                    int relX = mouseX - this.x - 8; // center roughly under cursor
                    int relY = mouseY - this.y - 8;
                    context.drawItem(icon, relX, relY);
                }
            }
        }
    }

    public int getEntityId() {
        return this.handler.getEntityId();
    }

    public void applyServerSync(int width, float windowMain, float windowStep, String[] blocksMain, String[] blocksStep) {
        this.pathWidth = width;
        this.gradientWindowMain = windowMain;
        this.gradientWindowStep = windowStep;
        if (blocksMain != null) {
            if (blocksMain.length != 9) this.gradientMainBlocks = new String[9];
            System.arraycopy(blocksMain, 0, this.gradientMainBlocks, 0, Math.min(9, blocksMain.length));
        }
        if (blocksStep != null) {
            if (blocksStep.length != 9) this.gradientStepBlocks = new String[9];
            System.arraycopy(blocksStep, 0, this.gradientStepBlocks, 0, Math.min(9, blocksStep.length));
        }
        if (this.windowSliderMain != null) {
            int g = effectiveG(0);
            this.windowSliderMain.syncTo(0, g, gradientWindowMain);
        }
        if (this.windowSliderStep != null) {
            int g = effectiveG(1);
            this.windowSliderStep.syncTo(1, g, gradientWindowStep);
        }
        if (this.widthSlider != null) {
            this.widthSlider.syncTo(this.pathWidth);
        }
    }

    private int effectiveG(int row) {
        String[] arr = row == 0 ? gradientMainBlocks : gradientStepBlocks;
        int G = 0;
        for (int i = arr.length - 1; i >= 0; i--) {
            String s = arr[i];
            if (s != null && !s.isEmpty()) { G = i + 1; break; }
        }
        if (G == 0) G = 9; // fallback
        return G;
    }

    private static void fillDot(DrawContext ctx, int cx, int cy, int argb) {
        int r = 1;
        ctx.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, argb);
    }

    private void drawSliderMarkers(DrawContext context, SliderWidget slider, int g) {
        if (slider == null) return;
        int sx = slider.getX() - this.x;
        int sy = slider.getY() - this.y;
        int sw = slider.getWidth();
        int dotY = sy - 4;
        if (g > 0) {
            int gold = 0xFFFFCC00;
            for (int k = 0; k <= g; k++) {
                double t = (double) k / (double) g;
                int dx = (int) Math.round(sx + t * sw);
                fillDot(context, dx, dotY, gold);
            }
            double deltaS = (double) (g - 1) / (double) Math.max(1, pathWidth - 1);
            if (deltaS > 1e-6) {
                int cyan = 0xFF00FFFF;
                for (int n = 1; ; n++) {
                    double w = n * deltaS;
                    if (w > g) break;
                    double t = w / (double) g;
                    int dx = (int) Math.round(sx + t * sw);
                    fillDot(context, dx, dotY - 4, cyan);
                }
            }
        }
    }

    /**
     * Draw slider markers for group mode sliders.
     */
    private void drawGroupSliderMarkers(DrawContext context, GroupModeStrategy strategy) {
        if (strategy == null || groupRowSliders == null || groupRowSliders.isEmpty()) return;

        java.util.List<Integer> vis = strategy.getVisibleGroups();
        int rows = vis.size();

        for (int i = 0; i < Math.min(6, groupRowSliders.size()); i++) {
            int idx = i + strategy.getScroll();
            if (idx >= rows) continue;

            WindowSlider slider = groupRowSliders.get(i);
            if (slider == null || !slider.visible) continue;

            int group = vis.get(idx);
            int g = strategy.effectiveGroupG(group);

            if (g > 0) {
                int sx = slider.getX() - this.x;
                int sy = slider.getY() - this.y;
                int sw = slider.getWidth();
                int dotY = sy - 2;

                // Gold dots for gradient positions
                int gold = 0xFFFFCC00;
                for (int k = 0; k <= g; k++) {
                    double t = (double) k / (double) g;
                    int dx = (int) Math.round(sx + t * sw);
                    fillDot(context, dx, dotY, gold);
                }
            }
        }
    }
}
