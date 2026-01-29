package ninja.trek.mc.goldgolem.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import ninja.trek.mc.goldgolem.screen.GolemInventoryScreenHandler;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.client.screen.layout.*;
import ninja.trek.mc.goldgolem.client.screen.layout.sections.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GolemHandledScreen extends HandledScreen<GolemInventoryScreenHandler> {
    private static final Logger LOGGER = LoggerFactory.getLogger(GolemHandledScreen.class);
    private static final Identifier GENERIC_CONTAINER_TEXTURE = Identifier.of("minecraft", "textures/gui/container/generic_54.png");
    private float gradientWindowMain = 1.0f; // 0..9 (server synced)
    private float gradientWindowStep = 1.0f; // 0..9 (server synced)
    private float gradientWindowSurface = 1.0f; // 0..9 (server synced)
    private int pathWidth = 3;      // server synced
    private int gradientNoiseScaleMain = 1; // 1-16 (server synced)
    private int gradientNoiseScaleStep = 1; // 1-16 (server synced)
    private int gradientNoiseScaleSurface = 1; // 1-16 (server synced)
    private String[] gradientMainBlocks = new String[9];
    private String[] gradientStepBlocks = new String[9];
    private String[] gradientSurfaceBlocks = new String[9];

    private WindowSlider windowSliderMain;
    private WindowSlider windowSliderStep;
    private WindowSlider windowSliderSurface;
    private WidthSlider widthSlider;
    private GradientNoiseScaleSlider gradientNoiseScaleSliderMain;
    private GradientNoiseScaleSlider gradientNoiseScaleSliderStep;
    private GradientNoiseScaleSlider gradientNoiseScaleSliderSurface;
    private boolean isDragging = false;
    private int dragButton = -1;
    private java.util.Set<Integer> dragVisited = new java.util.HashSet<>();
    private java.util.List<String> wallUniqueBlocks = java.util.Collections.emptyList();
    private java.util.List<Integer> wallBlockGroups = java.util.Collections.emptyList();
    private java.util.List<Float> wallGroupWindows = java.util.Collections.emptyList();
    private java.util.List<Integer> wallGroupNoiseScales = java.util.Collections.emptyList();
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
    private java.util.List<Integer> towerGroupNoiseScales = java.util.Collections.emptyList();
    private java.util.List<String> towerGroupFlatSlots = java.util.Collections.emptyList();
    private int towerScroll = 0;
    private final java.util.List<WindowSlider> towerRowSliders = new java.util.ArrayList<>();
    private final int[] towerSliderToGroup = new int[6];
    private final java.util.List<IconHit> towerIconHits = new java.util.ArrayList<>();
    private String towerDraggingBlockId = null;
    private String towerPendingAssignBlockId = null;
    private int towerLayers = 2; // 1-256 layers (synced from server)
    private TowerLayersRangeSlider towerLayersSlider;
    private TextFieldWidget towerLayersField;
    private ButtonWidget towerOriginResetButton;
    private volatile boolean updatingTowerLayersField = false;
    private boolean hasTowerModeData = false;

    // Excavation mode state
    private int excavationHeight = 3; // 1-5
    private int excavationDepth = 16; // 0-64 (0 = infinite)
    private int excavationOreMiningMode = 0; // 0=Always, 1=Never, 2=Silk/Fortune
    private ExcavationHeightSlider excavationHeightSlider;
    private ExcavationDepthSlider excavationDepthSlider;
    private ButtonWidget excavationOreModeButton;

    // Mining mode state
    private int miningOreMiningMode = 0; // 0=Always, 1=Never, 2=Silk/Fortune
    private ButtonWidget miningOreModeButton;

    // Terraforming mode state
    private int terraformingScanRadius = 2; // 1-5
    private int terraformingGradientVerticalWindow = 1; // 0..9
    private int terraformingGradientHorizontalWindow = 1; // 0..9
    private int terraformingGradientSlopedWindow = 1; // 0..9
    private int terraformingGradientVerticalScale = 1; // 1-16
    private int terraformingGradientHorizontalScale = 1; // 1-16
    private int terraformingGradientSlopedScale = 1; // 1-16
    private String[] terraformingGradientVertical = new String[9];
    private String[] terraformingGradientHorizontal = new String[9];
    private String[] terraformingGradientSloped = new String[9];
    private WindowSlider terraformingSliderVertical;
    private WindowSlider terraformingSliderHorizontal;
    private WindowSlider terraformingSliderSloped;
    private NoiseScaleSlider terraformingScaleVertical;
    private NoiseScaleSlider terraformingScaleHorizontal;
    private NoiseScaleSlider terraformingScaleSloped;
    private TerraformingScanRadiusSlider terraformingScanRadiusSlider;

    // Tree mode state
    private java.util.List<String> treeUniqueBlocks = java.util.Collections.emptyList();
    private java.util.List<Integer> treeBlockGroups = java.util.Collections.emptyList();
    private java.util.List<Float> treeGroupWindows = java.util.Collections.emptyList();
    private java.util.List<Integer> treeGroupNoiseScales = java.util.Collections.emptyList();
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
    private final java.util.List<NoiseScaleSlider> groupRowScaleSliders = new java.util.ArrayList<>();
    private final int[] groupSliderToGroup = new int[6];
    private final java.util.List<IconHit> groupIconHits = new java.util.ArrayList<>();

    // Section-based layout system
    private SectionFactory.SectionConfiguration sectionConfig;
    private List<GuiSection> sections = new ArrayList<>();
    private boolean useNewLayoutSystem = true; // Feature flag for gradual migration

    // Thread safety for sync methods (C2: GUI Thread Safety)
    private final Object stateLock = new Object();

    // Mode-specific state containers (C3: ModeState consolidation)
    private final Map<BuildMode, ModeState> modeStates = new EnumMap<>(BuildMode.class);

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
            float current = (row == 0) ? gradientWindowSurface : (row == 1) ? gradientWindowMain : gradientWindowStep;
            if (Math.abs(w - current) > 0.001f) {
                if (row == 0) gradientWindowSurface = w;
                else if (row == 1) gradientWindowMain = w;
                else gradientWindowStep = w;
                int scale = (row == 0) ? gradientNoiseScaleSurface : (row == 1) ? gradientNoiseScaleMain : gradientNoiseScaleStep;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientWindowC2SPayload(getEntityId(), row, w, scale));
            }
        }
        public void syncTo(int row, int g, float window) {
            double norm = g <= 0 ? 0.0 : (double) Math.min(window, g) / (double) g;
            this.value = norm;
            this.updateMessage();
        }
    }

    private static int clampOdd(int w) {
        w = Math.max(1, Math.min(9, w));
        if ((w & 1) == 0) w = (w < 9) ? (w + 1) : (w - 1);
        return w;
    }

    private static int clampScale(int s) { return Math.max(1, Math.min(16, s)); }
    private static double scaleToValueInit(int s) { return (clampScale(s) - 1) / 15.0; }
    private static int scaleFromValue(double v) { return clampScale(1 + (int)Math.round(v * 15.0)); }

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

    private abstract class NoiseScaleSlider extends SliderWidget {
        public NoiseScaleSlider(int x, int y, int width, int height, int initialScale) {
            super(x, y, width, height, Text.literal("Scale"), scaleToValueInit(initialScale));
        }
        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Scale: " + scaleFromValue(this.value)));
        }
        @Override
        protected void applyValue() {
            applyScale(scaleFromValue(this.value));
        }
        protected abstract void applyScale(int scale);
        public void syncTo(int s) {
            this.value = scaleToValueInit(s);
            updateMessage();
        }
    }

    private class GradientNoiseScaleSlider extends NoiseScaleSlider {
        private final int row;
        public GradientNoiseScaleSlider(int x, int y, int width, int height, int initialScale, int row) {
            super(x, y, width, height, initialScale);
            this.row = row;
        }
        @Override
        protected void applyScale(int scale) {
            if (row == 0) {
                if (scale == gradientNoiseScaleSurface) return;
                gradientNoiseScaleSurface = scale;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientWindowC2SPayload(
                        getEntityId(), 0, gradientWindowSurface, gradientNoiseScaleSurface));
            } else if (row == 1) {
                if (scale == gradientNoiseScaleMain) return;
                gradientNoiseScaleMain = scale;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientWindowC2SPayload(
                        getEntityId(), 1, gradientWindowMain, gradientNoiseScaleMain));
            } else {
                if (scale == gradientNoiseScaleStep) return;
                gradientNoiseScaleStep = scale;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientWindowC2SPayload(
                        getEntityId(), 2, gradientWindowStep, gradientNoiseScaleStep));
            }
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
        private static double toValueInit(int d) { return d / 64.0; } // 0-64 range (0 = infinite)
        private static int toDepth(double v) { return Math.max(0, Math.min(64, (int)Math.round(v * 64.0))); }
        @Override
        protected void updateMessage() {
            int d = toDepth(this.value);
            if (d == 0) {
                this.setMessage(Text.literal("Depth: Infinite"));
            } else {
                this.setMessage(Text.literal("Depth: " + d));
            }
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

    private static int clampTowerLayers(int layers) {
        return Math.max(1, Math.min(256, layers));
    }

    private static int clampTowerLayersSlider(int layers) {
        return Math.max(2, Math.min(24, layers));
    }

    private class TowerLayersRangeSlider extends SliderWidget {
        public TowerLayersRangeSlider(int x, int y, int width, int height, int initialLayers) {
            super(x, y, width, height, Text.literal("Layers"), toValueInit(initialLayers));
        }
        private static double toValueInit(int l) {
            return (clampTowerLayersSlider(l) - 2) / 22.0;
        }
        private static int toLayers(double v) {
            return clampTowerLayersSlider(2 + (int)Math.round(v * 22.0));
        }
        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Layers: " + toLayers(this.value)));
        }
        @Override
        protected void applyValue() {
            int l = toLayers(this.value);
            if (l != towerLayers) {
                towerLayers = l;
                setTowerLayersFieldText(l);
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTowerHeightC2SPayload(getEntityId(), towerLayers));
                updateMessage();
            }
        }
        public void syncTo(int l) {
            this.value = toValueInit(l);
            updateMessage();
        }
    }

    private void setTowerLayersFieldText(int layers) {
        if (towerLayersField == null) return;
        String text = Integer.toString(clampTowerLayers(layers));
        if (text.equals(towerLayersField.getText())) return;
        updatingTowerLayersField = true;
        towerLayersField.setText(text);
        updatingTowerLayersField = false;
    }

    private void setTowerLayersSliderValue(int layers) {
        if (towerLayersSlider == null) return;
        towerLayersSlider.syncTo(clampTowerLayersSlider(layers));
    }

    private void onTowerLayersChanged(String text) {
        if (updatingTowerLayersField) return;
        if (text == null || text.isEmpty()) return;
        int parsed;
        try {
            parsed = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return;
        }
        int clamped = clampTowerLayers(parsed);
        if (clamped != parsed) {
            setTowerLayersFieldText(clamped);
        }
        if (clamped != towerLayers) {
            towerLayers = clamped;
            setTowerLayersSliderValue(clamped);
            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTowerHeightC2SPayload(getEntityId(), towerLayers));
        }
    }

    public GolemHandledScreen(GolemInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 176; // vanilla chest width
        this.backgroundHeight = handler.getControlsMargin() + handler.getGolemRows() * 18 + 94;
    }

    /**
     * Calculate dynamic layout BEFORE super.init() is called.
     * This sets the backgroundHeight based on content and screen size.
     */
    private void calculateDynamicLayout() {
        // Create layout context
        LayoutContext layoutContext = new LayoutContext(this.width, this.height);

        // Get current build mode
        BuildMode mode = getCurrentBuildMode();

        // Create sections for current mode
        sectionConfig = SectionFactory.createSectionsForMode(
                mode,
                this.handler.getGolemRows(),
                this);
        sections = sectionConfig.sections;

        // For modes with manual widget positioning (EXCAVATION, MINING),
        // keep the old backgroundHeight calculation based on controlsMargin
        // to ensure slot positions match
        if (mode == BuildMode.EXCAVATION || mode == BuildMode.MINING) {
            // Don't override backgroundHeight - use the constructor's calculation
            return;
        }

        // Calculate layout
        LayoutManager layoutManager = new LayoutManager(layoutContext, sections);
        LayoutManager.LayoutResult result = layoutManager.calculateLayout();

        // Update GUI height based on layout calculation
        this.backgroundHeight = result.guiHeight;
    }

    /**
     * Initialize the section-based layout system widgets.
     * Called AFTER super.init() so x, y are set correctly.
     */
    private void initializeSections() {
        // If we used the new layout system for height calculation,
        // initialize section widgets now
        if (useNewLayoutSystem && sectionConfig != null) {
            // Initialize section widgets
            for (GuiSection section : sections) {
                if (section instanceof SettingsSection) {
                    ((SettingsSection) section).setGuiCoordinates(this.x, this.y);
                }
                section.initializeWidgets(this::addDrawableChild);
            }
        }

        // Always update gradient sections with current data (for state sync)
        if (sectionConfig != null && sectionConfig.gradientsSection != null) {
            BuildMode mode = getCurrentBuildMode();
            if (mode == BuildMode.PATH || mode == BuildMode.GRADIENT) {
                sectionConfig.gradientsSection.setGradientRow(0, gradientMainBlocks);
                sectionConfig.gradientsSection.setGradientRow(1, gradientStepBlocks);
            } else if (mode == BuildMode.TERRAFORMING) {
                sectionConfig.gradientsSection.setGradientRow(0, terraformingGradientVertical);
                sectionConfig.gradientsSection.setGradientRow(1, terraformingGradientHorizontal);
                sectionConfig.gradientsSection.setGradientRow(2, terraformingGradientSloped);
            }
        }
    }

    /**
     * Refresh the layout when group data changes.
     * This recalculates heights based on actual data and repositions GUI if needed.
     */
    private void refreshLayoutIfNeeded() {
        if (!useNewLayoutSystem || sectionConfig == null) return;

        // Recalculate layout with current data
        LayoutContext layoutContext = new LayoutContext(this.width, this.height);
        LayoutManager layoutManager = new LayoutManager(layoutContext, sections);
        LayoutManager.LayoutResult result = layoutManager.calculateLayout();

        // Check if height changed significantly
        int newHeight = result.guiHeight;
        if (Math.abs(newHeight - this.backgroundHeight) > 5) {
            // Height changed - update GUI dimensions
            this.backgroundHeight = newHeight;
            // Recenter the GUI
            this.x = (this.width - this.backgroundWidth) / 2;
            this.y = (this.height - this.backgroundHeight) / 2;
        }
    }

    /**
     * Get or create the ModeState for a specific build mode.
     * This provides thread-safe access to mode-specific state.
     */
    public ModeState getModeState(BuildMode mode) {
        return modeStates.computeIfAbsent(mode, k -> new ModeState());
    }

    // === SYNC METHODS (C5: Standardized naming with sync* prefix) ===

    public void syncWallUniqueBlocks(java.util.List<String> ids) {
        synchronized (stateLock) {
            this.wallUniqueBlocks = (ids == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
            getModeState(BuildMode.WALL).setUniqueBlocks(ids);
        }
    }

    /** @deprecated Use {@link #syncWallUniqueBlocks} instead */
    @Deprecated
    public void setWallUniqueBlocks(java.util.List<String> ids) {
        syncWallUniqueBlocks(ids);
    }

    public void syncWallBlockGroups(java.util.List<Integer> groups) {
        synchronized (stateLock) {
            this.wallBlockGroups = (groups == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(groups);
            getModeState(BuildMode.WALL).setBlockGroups(groups);
        }
    }

    /** @deprecated Use {@link #syncWallBlockGroups} instead */
    @Deprecated
    public void setWallBlockGroups(java.util.List<Integer> groups) {
        syncWallBlockGroups(groups);
    }

    public void syncTreeUniqueBlocks(java.util.List<String> ids) {
        synchronized (stateLock) {
            this.treeUniqueBlocks = (ids == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
            getModeState(BuildMode.TREE).setUniqueBlocks(ids);
        }
    }

    /** @deprecated Use {@link #syncTreeUniqueBlocks} instead */
    @Deprecated
    public void setTreeUniqueBlocks(java.util.List<String> ids) {
        syncTreeUniqueBlocks(ids);
    }

    public void syncTowerUniqueBlocks(java.util.List<String> ids) {
        synchronized (stateLock) {
            this.towerUniqueBlocks = (ids == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
            getModeState(BuildMode.TOWER).setUniqueBlocks(ids);
            this.hasTowerModeData = true;
        }
        ensureTowerLayersField();
    }

    /** @deprecated Use {@link #syncTowerUniqueBlocks} instead */
    @Deprecated
    public void setTowerUniqueBlocks(java.util.List<String> ids) {
        syncTowerUniqueBlocks(ids);
    }

    public void syncWallGroupsState(java.util.List<Float> windows, java.util.List<Integer> noiseScales, java.util.List<String> flatSlots) {
        synchronized (stateLock) {
            this.wallGroupWindows = (windows == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(windows);
            this.wallGroupNoiseScales = (noiseScales == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(noiseScales);
            this.wallGroupFlatSlots = (flatSlots == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(flatSlots);
            getModeState(BuildMode.WALL).updateGroupState(windows, noiseScales, flatSlots);
        }
        // Invalidate strategy cache to force re-initialization with new data
        groupModeStrategy = null;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            syncGroupSliders(strategy);
        }
        // Schedule UI update on render thread
        MinecraftClient.getInstance().execute(this::refreshLayoutIfNeeded);
    }

    /** @deprecated Use {@link #syncWallGroupsState} instead */
    @Deprecated
    public void setWallGroupsState(java.util.List<Float> windows, java.util.List<Integer> noiseScales, java.util.List<String> flatSlots) {
        syncWallGroupsState(windows, noiseScales, flatSlots);
    }

    // Tower mode network sync methods
    public void syncTowerBlockCounts(java.util.List<String> ids, java.util.List<Integer> counts, int height) {
        synchronized (stateLock) {
            // Note: towerUniqueBlocks is set by UniqueBlocksS2CPayload, not here.
            // Only update if we don't already have unique blocks (backward compatibility).
            if (this.towerUniqueBlocks.isEmpty() && ids != null && !ids.isEmpty()) {
                this.towerUniqueBlocks = new java.util.ArrayList<>(ids);
                getModeState(BuildMode.TOWER).setUniqueBlocks(ids);
            }
            this.towerBlockCounts.clear();
            java.util.Map<String, Integer> countMap = new java.util.HashMap<>();
            if (ids != null && counts != null) {
                for (int i = 0; i < Math.min(ids.size(), counts.size()); i++) {
                    this.towerBlockCounts.put(ids.get(i), counts.get(i));
                    countMap.put(ids.get(i), counts.get(i));
                }
            }
            getModeState(BuildMode.TOWER).setBlockCounts(countMap);
            this.towerLayers = Math.max(1, height);
            this.hasTowerModeData = true;
        }
        setTowerLayersFieldText(this.towerLayers);
        setTowerLayersSliderValue(this.towerLayers);
        ensureTowerLayersField();
    }

    /** @deprecated Use {@link #syncTowerBlockCounts} instead */
    @Deprecated
    public void setTowerBlockCounts(java.util.List<String> ids, java.util.List<Integer> counts, int height) {
        syncTowerBlockCounts(ids, counts, height);
    }

    public void syncTowerBlockGroups(java.util.List<Integer> groups) {
        synchronized (stateLock) {
            this.towerBlockGroups = (groups == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(groups);
            getModeState(BuildMode.TOWER).setBlockGroups(groups);
        }
    }

    /** @deprecated Use {@link #syncTowerBlockGroups} instead */
    @Deprecated
    public void setTowerBlockGroups(java.util.List<Integer> groups) {
        syncTowerBlockGroups(groups);
    }

    public void syncTowerGroupsState(java.util.List<Float> windows, java.util.List<Integer> noiseScales, java.util.List<String> flatSlots) {
        synchronized (stateLock) {
            this.towerGroupWindows = (windows == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(windows);
            this.towerGroupNoiseScales = (noiseScales == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(noiseScales);
            this.towerGroupFlatSlots = (flatSlots == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(flatSlots);
            getModeState(BuildMode.TOWER).updateGroupState(windows, noiseScales, flatSlots);
        }
        // Invalidate strategy cache to force re-initialization with new data
        groupModeStrategy = null;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            syncGroupSliders(strategy);
        }
        ensureTowerLayersField();
        // Schedule UI update on render thread
        MinecraftClient.getInstance().execute(this::refreshLayoutIfNeeded);
    }

    /** @deprecated Use {@link #syncTowerGroupsState} instead */
    @Deprecated
    public void setTowerGroupsState(java.util.List<Float> windows, java.util.List<Integer> noiseScales, java.util.List<String> flatSlots) {
        syncTowerGroupsState(windows, noiseScales, flatSlots);
    }

    // Excavation mode network sync method
    public void syncExcavationState(int height, int depth, int oreMiningMode) {
        synchronized (stateLock) {
            this.excavationHeight = height;
            this.excavationDepth = depth;
            this.excavationOreMiningMode = oreMiningMode;
        }
        if (this.excavationHeightSlider != null) {
            this.excavationHeightSlider.syncTo(height);
        }
        if (this.excavationDepthSlider != null) {
            this.excavationDepthSlider.syncTo(depth);
        }
        if (this.excavationOreModeButton != null) {
            this.excavationOreModeButton.setMessage(Text.literal("Ores: " + getOreModeDisplayName(oreMiningMode)));
        }
    }

    /** @deprecated Use {@link #syncExcavationState} instead */
    @Deprecated
    public void setExcavationValues(int height, int depth, int oreMiningMode) {
        syncExcavationState(height, depth, oreMiningMode);
    }

    // Mining mode network sync method
    public void syncMiningState(int branchDepth, int branchSpacing, int tunnelHeight, int oreMiningMode) {
        synchronized (stateLock) {
            this.miningOreMiningMode = oreMiningMode;
        }
        if (this.miningOreModeButton != null) {
            this.miningOreModeButton.setMessage(Text.literal("Ores: " + getOreModeDisplayName(oreMiningMode)));
        }
    }

    /** @deprecated Use {@link #syncMiningState} instead */
    @Deprecated
    public void setMiningValues(int branchDepth, int branchSpacing, int tunnelHeight, int oreMiningMode) {
        syncMiningState(branchDepth, branchSpacing, tunnelHeight, oreMiningMode);
    }

    private String getOreModeDisplayName(int ordinal) {
        return switch (ordinal) {
            case 0 -> "Always";
            case 1 -> "Never";
            case 2 -> "Silk/Fortune";
            default -> "Always";
        };
    }

    // Terraforming mode network sync method
    public void syncTerraformingState(int scanRadius, int verticalWindow, int horizontalWindow, int slopedWindow,
                                       int verticalScale, int horizontalScale, int slopedScale,
                                       java.util.List<String> verticalGradient, java.util.List<String> horizontalGradient,
                                       java.util.List<String> slopedGradient) {
        synchronized (stateLock) {
            this.terraformingScanRadius = scanRadius;
            this.terraformingGradientVerticalWindow = verticalWindow;
            this.terraformingGradientHorizontalWindow = horizontalWindow;
            this.terraformingGradientSlopedWindow = slopedWindow;
            this.terraformingGradientVerticalScale = verticalScale;
            this.terraformingGradientHorizontalScale = horizontalScale;
            this.terraformingGradientSlopedScale = slopedScale;

            // Sync gradient arrays
            for (int i = 0; i < 9; i++) {
                this.terraformingGradientVertical[i] = (verticalGradient != null && i < verticalGradient.size()) ? verticalGradient.get(i) : "";
                this.terraformingGradientHorizontal[i] = (horizontalGradient != null && i < horizontalGradient.size()) ? horizontalGradient.get(i) : "";
                this.terraformingGradientSloped[i] = (slopedGradient != null && i < slopedGradient.size()) ? slopedGradient.get(i) : "";
            }
        }

        // Sync sliders if they exist (outside lock - UI operations)
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
        if (this.terraformingScaleVertical != null) {
            this.terraformingScaleVertical.syncTo(verticalScale);
        }
        if (this.terraformingScaleHorizontal != null) {
            this.terraformingScaleHorizontal.syncTo(horizontalScale);
        }
        if (this.terraformingScaleSloped != null) {
            this.terraformingScaleSloped.syncTo(slopedScale);
        }
    }

    /** @deprecated Use {@link #syncTerraformingState} instead */
    @Deprecated
    public void setTerraformingValues(int scanRadius, int verticalWindow, int horizontalWindow, int slopedWindow,
                                       int verticalScale, int horizontalScale, int slopedScale,
                                       java.util.List<String> verticalGradient, java.util.List<String> horizontalGradient,
                                       java.util.List<String> slopedGradient) {
        syncTerraformingState(scanRadius, verticalWindow, horizontalWindow, slopedWindow,
                verticalScale, horizontalScale, slopedScale,
                verticalGradient, horizontalGradient, slopedGradient);
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
    public void syncTreeBlockGroups(java.util.List<Integer> groups) {
        synchronized (stateLock) {
            this.treeBlockGroups = (groups == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(groups);
            getModeState(BuildMode.TREE).setBlockGroups(groups);
        }
    }

    /** @deprecated Use {@link #syncTreeBlockGroups} instead */
    @Deprecated
    public void setTreeBlockGroups(java.util.List<Integer> groups) {
        syncTreeBlockGroups(groups);
    }

    public void syncTreeGroupsState(int presetOrdinal, java.util.List<Float> windows, java.util.List<Integer> noiseScales, java.util.List<String> flatSlots) {
        synchronized (stateLock) {
            this.treeTilingPresetOrdinal = presetOrdinal;
            this.treeGroupWindows = (windows == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(windows);
            this.treeGroupNoiseScales = (noiseScales == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(noiseScales);
            this.treeGroupFlatSlots = (flatSlots == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(flatSlots);
            getModeState(BuildMode.TREE).updateGroupState(windows, noiseScales, flatSlots);
        }
        // Invalidate strategy cache to force re-initialization with new data
        groupModeStrategy = null;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            syncGroupSliders(strategy);
        }
        // Schedule UI update on render thread
        MinecraftClient.getInstance().execute(this::refreshLayoutIfNeeded);
    }

    /** @deprecated Use {@link #syncTreeGroupsState} instead */
    @Deprecated
    public void setTreeGroupsState(int presetOrdinal, java.util.List<Float> windows, java.util.List<Integer> noiseScales, java.util.List<String> flatSlots) {
        syncTreeGroupsState(presetOrdinal, windows, noiseScales, flatSlots);
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

    private int getTowerLayersFieldY() {
        int startY = this.y + 26;
        int rowSpacing = 18 + 6;
        int rows = 0;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null && strategy.getMode() == BuildMode.TOWER) {
            int total = strategy.getVisibleGroups().size();
            rows = Math.min(Math.max(0, total - strategy.getScroll()), 6);
        }
        if (rows <= 0 && towerGroupWindows != null) {
            int total = towerGroupWindows.size();
            rows = Math.min(Math.max(0, total - towerScroll), 6);
        }
        if (rows <= 0) rows = 1;
        return startY + rows * rowSpacing;
    }

    private void ensureTowerLayersField() {
        if (this.handler.isSliderEnabled()) return;
        int sliderMode = this.handler.getSliderMode();
        if (sliderMode != 0 && sliderMode != 6) return;
        if (!this.hasTowerModeData) return;
        if (this.client == null || this.width <= 0) return;
        int layersFieldW = 36;
        int layersGap = 6;
        int layersFieldH = 12;
        int resetButtonW = 12;
        int resetButtonGap = 4;
        int left = this.x + 8;
        int totalW = this.backgroundWidth - 16;
        int layersSliderW = Math.max(40, totalW - layersFieldW - layersGap - resetButtonW - resetButtonGap);
        int layersSliderX = left + resetButtonW + resetButtonGap;
        int layersFieldX = layersSliderX + layersSliderW + layersGap;
        int layersFieldY = getTowerLayersFieldY();
        int resetButtonX = left;
        if (towerLayersSlider == null) {
            towerLayersSlider = new TowerLayersRangeSlider(layersSliderX, layersFieldY, layersSliderW, layersFieldH, towerLayers);
            this.addDrawableChild(towerLayersSlider);
        } else {
            towerLayersSlider.setDimensions(layersSliderW, layersFieldH);
            towerLayersSlider.setX(layersSliderX);
            towerLayersSlider.setY(layersFieldY);
        }
        if (towerLayersField == null) {
            towerLayersField = new TextFieldWidget(this.textRenderer, layersFieldX, layersFieldY, layersFieldW, layersFieldH, Text.literal("Layers"));
            towerLayersField.setMaxLength(3);
            towerLayersField.setTextPredicate(input -> input.isEmpty() || input.chars().allMatch(Character::isDigit));
            towerLayersField.setChangedListener(this::onTowerLayersChanged);
            setTowerLayersFieldText(towerLayers);
            setTowerLayersSliderValue(towerLayers);
            this.addDrawableChild(towerLayersField);
        } else {
            towerLayersField.setDimensions(layersFieldW, layersFieldH);
            towerLayersField.setX(layersFieldX);
            towerLayersField.setY(layersFieldY);
        }
        if (towerOriginResetButton == null) {
            towerOriginResetButton = ButtonWidget.builder(Text.literal("R"), b ->
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.ResetTowerOriginC2SPayload(getEntityId())))
                .dimensions(resetButtonX, layersFieldY, resetButtonW, layersFieldH)
                .build();
            this.addDrawableChild(towerOriginResetButton);
        } else {
            towerOriginResetButton.setDimensions(resetButtonW, layersFieldH);
            towerOriginResetButton.setX(resetButtonX);
            towerOriginResetButton.setY(layersFieldY);
        }
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
     * Get the current BuildMode based on the screen state.
     */
    private BuildMode getCurrentMode() {
        if (isWallMode()) {
            return BuildMode.WALL;
        } else if (isTowerMode()) {
            return BuildMode.TOWER;
        } else if (isTreeMode()) {
            return BuildMode.TREE;
        } else if (isExcavationMode()) {
            return BuildMode.EXCAVATION;
        } else if (isTerraformingMode()) {
            return BuildMode.TERRAFORMING;
        } else if (isMiningMode()) {
            return BuildMode.MINING;
        } else if (this.handler.isSliderEnabled()) {
            // Default slider mode is PATH/GRADIENT
            return BuildMode.PATH;
        }
        // Fallback
        return BuildMode.PATH;
    }

    /**
     * Get the text renderer for drawing text.
     */
    public net.minecraft.client.font.TextRenderer getTextRenderer() {
        return this.textRenderer;
    }

    /**
     * Get the player inventory title text.
     */
    public Text getPlayerInventoryTitle() {
        return this.playerInventoryTitle;
    }

    /**
     * Get the current build mode.
     */
    public BuildMode getCurrentBuildMode() {
        // Determine mode from handler's slider mode
        if (this.handler.isSliderEnabled()) {
            return BuildMode.PATH;
        }
        int sliderMode = this.handler.getSliderMode();
        if (sliderMode == 0) return BuildMode.WALL;
        if (sliderMode == 1) return BuildMode.TOWER;
        if (sliderMode == 2) return BuildMode.EXCAVATION;
        if (sliderMode == 3) return BuildMode.MINING;
        if (sliderMode == 4) return BuildMode.TERRAFORMING;
        if (sliderMode == 5) return BuildMode.TREE;
        return BuildMode.PATH;
    }

    /**
     * Get the current group mode strategy, or null if not in a group mode.
     */
    public GroupModeStrategy getGroupModeStrategy() {
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
            groupModeStrategy.updateGroupState(wallGroupWindows, wallGroupNoiseScales, wallGroupFlatSlots, java.util.Map.of());
        } else if (groupModeStrategy.getMode() == BuildMode.TOWER) {
            groupModeStrategy.updateBlocksAndGroups(towerUniqueBlocks, towerBlockGroups);
            var extraData = new java.util.HashMap<String, Object>();
            extraData.put("blockCounts", towerBlockCounts);
            extraData.put("height", towerLayers);
            groupModeStrategy.updateGroupState(towerGroupWindows, towerGroupNoiseScales, towerGroupFlatSlots, extraData);
        } else if (groupModeStrategy.getMode() == BuildMode.TREE) {
            groupModeStrategy.updateBlocksAndGroups(treeUniqueBlocks, treeBlockGroups);
            var extraData = new java.util.HashMap<String, Object>();
            extraData.put("tilingPresetOrdinal", treeTilingPresetOrdinal);
            groupModeStrategy.updateGroupState(treeGroupWindows, treeGroupNoiseScales, treeGroupFlatSlots, extraData);
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
            NoiseScaleSlider ns = i < groupRowScaleSliders.size() ? groupRowScaleSliders.get(i) : null;
            if (s == null) continue;
            boolean visible = idx < rows;
            s.visible = visible;
            if (ns != null) ns.visible = visible;
            if (visible) {
                int G = mode.effectiveGroupG(group);
                float w = (group >= 0 && group < mode.getGroupWindows().size()) ? mode.getGroupWindows().get(group) : 0.0f;
                s.syncTo(0, G, w);
                if (ns != null) {
                    int scale = (group >= 0 && group < mode.getGroupNoiseScales().size()) ? mode.getGroupNoiseScales().get(group) : 1;
                    ns.syncTo(scale);
                }
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

        // Path mode only: draw gradient slot frames and items (three rows: surface, main, step)
        if (this.handler.isSliderEnabled()) {
            int slotsX = this.x + 8;
            int slotY0 = this.y + 26; // first row (surface)
            int slotY1 = slotY0 + 18 + 6; // second row (main)
            int slotY2 = slotY1 + 18 + 6; // third row (step)
            // Frames: 18x18 area with 1px border and darker inner background
            int borderColor = 0xFF555555; // medium-dark border
            int innerColor = 0xFF1C1C1C;  // darker inner background
            for (int row = 0; row < 3; row++) {
                int slotY = slotY0 + row * (18 + 6);
                for (int i = 0; i < 9; i++) {
                    int fx = slotsX + i * 18;
                    context.fill(fx - 1, slotY - 1, fx + 17, slotY + 17, borderColor);
                    context.fill(fx, slotY, fx + 16, slotY + 16, innerColor);
                }
            }
            // Draw items for surface gradient (row 0)
            for (int i = 0; i < 9; i++) {
                String id = (gradientSurfaceBlocks != null && i < gradientSurfaceBlocks.length) ? gradientSurfaceBlocks[i] : "";
                drawGradientSlotItem(context, id, slotsX + i * 18, slotY0);
            }
            // Draw items for main gradient (row 1)
            for (int i = 0; i < 9; i++) {
                String id = (gradientMainBlocks != null && i < gradientMainBlocks.length) ? gradientMainBlocks[i] : "";
                drawGradientSlotItem(context, id, slotsX + i * 18, slotY1);
            }
            // Draw items for step gradient (row 2)
            for (int i = 0; i < 9; i++) {
                String id = (gradientStepBlocks != null && i < gradientStepBlocks.length) ? gradientStepBlocks[i] : "";
                drawGradientSlotItem(context, id, slotsX + i * 18, slotY2);
            }
            // Icons to the left (outside the window), aligned with each row
            ItemStack iconSurface = new ItemStack(net.minecraft.item.Items.SHORT_GRASS);
            ItemStack iconMain = new ItemStack(net.minecraft.item.Items.OAK_PLANKS);
            ItemStack iconStep = new ItemStack(net.minecraft.item.Items.OAK_STAIRS);
            int iconX = this.x - 20;
            context.drawItem(iconSurface, iconX, slotY0);
            context.drawItem(iconMain, iconX, slotY1);
            context.drawItem(iconStep, iconX, slotY2);
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
                drawGradientSlotItem(context, id, slotsX + i * 18, slotY0);
            }

            // Draw items for horizontal gradient
            for (int i = 0; i < 9; i++) {
                String id = (terraformingGradientHorizontal != null && i < terraformingGradientHorizontal.length) ? terraformingGradientHorizontal[i] : "";
                drawGradientSlotItem(context, id, slotsX + i * 18, slotY1);
            }

            // Draw items for sloped gradient
            for (int i = 0; i < 9; i++) {
                String id = (terraformingGradientSloped != null && i < terraformingGradientSloped.length) ? terraformingGradientSloped[i] : "";
                drawGradientSlotItem(context, id, slotsX + i * 18, slotY2);
            }

            // Draw labels to the left of each row
            context.drawText(this.textRenderer, Text.literal("Vertical"), this.x + 8, slotY0 - 10, 0xFFFFFFFF, false);
            context.drawText(this.textRenderer, Text.literal("Horizontal"), this.x + 8, slotY1 - 10, 0xFFFFFFFF, false);
            context.drawText(this.textRenderer, Text.literal("Sloped"), this.x + 8, slotY2 - 10, 0xFFFFFFFF, false);
        }
    }

    // ========== Shared Slot Click Infrastructure ==========

    private java.util.Optional<Identifier> getCursorBlockId() {
        var mc = MinecraftClient.getInstance();
        var player = mc.player;
        if (player == null || player.currentScreenHandler == null) return java.util.Optional.empty();
        ItemStack cursor = player.currentScreenHandler.getCursorStack();
        if (cursor.isEmpty()) return java.util.Optional.empty();
        if (cursor.getItem() instanceof BlockItem) {
            return java.util.Optional.of(Registries.BLOCK.getId(((BlockItem) cursor.getItem()).getBlock()));
        }
        // Detect tools (pickaxe, shovel, axe) → mine action with tool ID encoded
        Identifier toolItemId = Registries.ITEM.getId(cursor.getItem());
        String toolIdStr = toolItemId.toString();
        if (toolIdStr.contains("_pickaxe") || toolIdStr.contains("_shovel") || toolIdStr.contains("_axe")) {
            return java.util.Optional.of(ninja.trek.mc.goldgolem.util.GradientSlotUtil.mineIdentifier(toolItemId));
        }
        return java.util.Optional.empty();
    }

    /**
     * Draw a gradient slot item. Handles mine-action slots (shows tool icon) and normal block slots.
     */
    private void drawGradientSlotItem(DrawContext context, String id, int x, int y) {
        if (id == null || id.isEmpty()) return;
        if (ninja.trek.mc.goldgolem.util.GradientSlotUtil.isMineAction(id)) {
            var toolItem = ninja.trek.mc.goldgolem.util.GradientSlotUtil.getToolItem(id);
            if (toolItem != null) {
                context.drawItem(new ItemStack(toolItem), x, y);
            }
        } else {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident != null) {
                var block = Registries.BLOCK.get(ident);
                if (block != null) {
                    context.drawItem(new ItemStack(block.asItem()), x, y);
                }
            }
        }
    }

    /**
     * Handles slot clicks for group modes (Tower, Wall, Tree).
     * Determines the actual group index based on visual row and scroll position.
     */
    private void handleGroupModeSlotClick(int visualRow, int slot, java.util.Optional<Identifier> blockId) {
        BuildMode mode;
        java.util.List<Integer> visibleGroups;
        int scroll;

        if (isTowerMode()) {
            mode = BuildMode.TOWER;
            visibleGroups = getTowerVisibleGroups();
            scroll = towerScroll;
        } else if (isWallMode()) {
            mode = BuildMode.WALL;
            visibleGroups = getVisibleGroups();
            scroll = wallScroll;
        } else if (isTreeMode()) {
            mode = BuildMode.TREE;
            visibleGroups = getTreeVisibleGroups();
            scroll = treeScroll;
        } else {
            return; // Not in a recognized group mode
        }

        int actualRow = visualRow + scroll;
        if (actualRow >= 0 && actualRow < visibleGroups.size()) {
            int groupIdx = visibleGroups.get(actualRow);
            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeSlotC2SPayload(
                getEntityId(), mode, groupIdx, slot, blockId));
        }
    }

    @Override
    protected void init() {
        // Calculate layout BEFORE super.init() so height is correct for GUI centering
        if (useNewLayoutSystem) {
            calculateDynamicLayout();
        }

        super.init();

        // Initialize section widgets AFTER super.init() (which sets x, y)
        initializeSections();

        // Place window slider in the controls margin area. Gradient slots are handled via mouse clicks, not buttons.
        int controlsTop = this.y + 8; // leave a small header gap
        // int slotsX = this.x + 8; // for reference
        // int slotY = controlsTop + 18; // below title area

        if (this.handler.isSliderEnabled()) {
            // Path mode: window sliders to the right of gradient rows (3 rows: surface, main, step)
            int wx = this.x + 8 + 9 * 18 + 12;
            int wy0 = controlsTop + 18; // align with first gradient row (surface)
            int wy1 = wy0 + 18 + 6;     // second row (main)
            int wy2 = wy1 + 18 + 6;     // third row (step)
            int windowW = 70;
            int scaleW = 50;
            int sliderGap = 6;
            int sliderHeight = 20;
            // Surface row (row 0)
            int gS = effectiveG(0);
            double normS = gS <= 0 ? 0.0 : (double) Math.min(gradientWindowSurface, gS) / (double) gS;
            windowSliderSurface = new WindowSlider(wx, wy0, windowW, sliderHeight, normS, 0);
            this.addDrawableChild(windowSliderSurface);
            gradientNoiseScaleSliderSurface = new GradientNoiseScaleSlider(wx + windowW + sliderGap, wy0, scaleW, sliderHeight, gradientNoiseScaleSurface, 0);
            this.addDrawableChild(gradientNoiseScaleSliderSurface);
            // Main row (row 1)
            int g0 = effectiveG(1);
            double norm0 = g0 <= 0 ? 0.0 : (double) Math.min(gradientWindowMain, g0) / (double) g0;
            windowSliderMain = new WindowSlider(wx, wy1, windowW, sliderHeight, norm0, 1);
            this.addDrawableChild(windowSliderMain);
            gradientNoiseScaleSliderMain = new GradientNoiseScaleSlider(wx + windowW + sliderGap, wy1, scaleW, sliderHeight, gradientNoiseScaleMain, 1);
            this.addDrawableChild(gradientNoiseScaleSliderMain);
            // Step row (row 2)
            int g1 = effectiveG(2);
            double norm1 = g1 <= 0 ? 0.0 : (double) Math.min(gradientWindowStep, g1) / (double) g1;
            windowSliderStep = new WindowSlider(wx, wy2, windowW, sliderHeight, norm1, 2);
            this.addDrawableChild(windowSliderStep);
            gradientNoiseScaleSliderStep = new GradientNoiseScaleSlider(wx + windowW + sliderGap, wy2, scaleW, sliderHeight, gradientNoiseScaleStep, 2);
            this.addDrawableChild(gradientNoiseScaleSliderStep);

        }

        // Width slider under the gradient row (right-aligned)
        if (this.handler.isSliderEnabled()) {
            int wsliderW = 50;
            int wsliderH = 12;
            int slotTop = this.y + 26; // top of first gradient row
            int wsliderY = slotTop + (18 + 6) + (18 + 6) + 18 + 6; // below third row
            int right = this.x + this.backgroundWidth - 8;
            int widthX = right - wsliderW;
            widthSlider = new WidthSlider(widthX, wsliderY, wsliderW, wsliderH, pathWidth);
            this.addDrawableChild(widthSlider);
        } else if (!this.handler.isSliderEnabled() && (this.handler.getSliderMode() <= 1 || this.handler.getSliderMode() == 5 || this.handler.getSliderMode() == 6)) {
            // Group Mode UI (Wall, Tower, Tree): create per-row sliders and scroll buttons using strategy pattern
            // Check sliderMode: 0 or 1 indicates Wall or Tower mode, 5 indicates Tree mode
            // Mode-specific data arrives later via network, but we create sliders now
            groupModeStrategy = null; // Reset to force re-initialization
            GroupModeStrategy strategy = getGroupModeStrategy();

            // Create sliders regardless of whether data has arrived yet
            // They will be synced when network data arrives
            groupRowSliders.clear();
            groupRowScaleSliders.clear();
            int gridTop = this.y + 26;
            int rowSpacing = 18 + 6;
            int gridX = this.x + 8;
            int wx2 = gridX + 9 * 18 + 12;
            int w2 = 70;
            int s2 = 50;
            int gap2 = 6;
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
                        int scale = (group >= 0 && group < strat.getGroupNoiseScales().size()) ? strat.getGroupNoiseScales().get(group) : 1;
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeWindowC2SPayload(getEntityId(), strat.getMode(), group, w, scale));
                    }
                };
                groupRowSliders.add(s);
                this.addDrawableChild(s);

                NoiseScaleSlider ns = new NoiseScaleSlider(wx2 + w2 + gap2, sy, s2, h2, 1) {
                    @Override
                    protected void applyScale(int scale) {
                        int sliderIdx = finalR;
                        if (sliderIdx < 0 || sliderIdx >= 6) return;
                        int group = groupSliderToGroup[sliderIdx];
                        if (group < 0) return;
                        GroupModeStrategy strat = getGroupModeStrategy();
                        if (strat == null) return;
                        float window = (group >= 0 && group < strat.getGroupWindows().size()) ? strat.getGroupWindows().get(group) : 0.0f;
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeWindowC2SPayload(
                                getEntityId(), strat.getMode(), group, window, scale));
                        updateMessage();
                    }
                };
                groupRowScaleSliders.add(ns);
                this.addDrawableChild(ns);
            }
            var upBtn = ButtonWidget.builder(Text.literal("▲"), b -> {
                GroupModeStrategy strat = getGroupModeStrategy();
                if (strat != null) scrollGroup(strat, -1);
            }).dimensions(wx2 + w2 + gap2 + s2 + 4, gridTop, 14, 12).build();
            var dnBtn = ButtonWidget.builder(Text.literal("▼"), b -> {
                GroupModeStrategy strat = getGroupModeStrategy();
                if (strat != null) scrollGroup(strat, 1);
            }).dimensions(wx2 + w2 + gap2 + s2 + 4, gridTop + 5 * rowSpacing, 14, 12).build();
            this.addDrawableChild(upBtn);
            this.addDrawableChild(dnBtn);

            // Tower mode: add layers slider on the right side
            if (mode == BuildMode.TOWER) {
                int layersFieldW = 36;
                int layersGap = 6;
                int layersFieldH = 12;
                int resetButtonW = 12;
                int resetButtonGap = 4;
                int left = this.x + 8;
                int totalW = this.backgroundWidth - 16;
                int layersSliderW = Math.max(40, totalW - layersFieldW - layersGap - resetButtonW - resetButtonGap);
                int layersSliderX = left + resetButtonW + resetButtonGap;
                int layersFieldX = layersSliderX + layersSliderW + layersGap;
                int layersFieldY = getTowerLayersFieldY();
                int resetButtonX = left;
                towerLayersSlider = new TowerLayersRangeSlider(layersSliderX, layersFieldY, layersSliderW, layersFieldH, towerLayers);
                this.addDrawableChild(towerLayersSlider);
                towerLayersField = new TextFieldWidget(this.textRenderer, layersFieldX, layersFieldY, layersFieldW, layersFieldH, Text.literal("Layers"));
                towerLayersField.setMaxLength(3);
                towerLayersField.setTextPredicate(input -> input.isEmpty() || input.chars().allMatch(Character::isDigit));
                towerLayersField.setChangedListener(this::onTowerLayersChanged);
                setTowerLayersFieldText(towerLayers);
                setTowerLayersSliderValue(towerLayers);
                this.addDrawableChild(towerLayersField);
                towerOriginResetButton = ButtonWidget.builder(Text.literal("R"), b ->
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.ResetTowerOriginC2SPayload(getEntityId())))
                    .dimensions(resetButtonX, layersFieldY, resetButtonW, layersFieldH)
                    .build();
                this.addDrawableChild(towerOriginResetButton);
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
            if (hasTowerModeData) {
                ensureTowerLayersField();
            }
        } else if (isExcavationMode()) {
            // Excavation Mode UI: position widgets within controlsMargin area
            // controlsMargin defines where inventory slots start, so widgets must fit above that
            int margin = this.handler.getControlsMargin();
            int sliderW = 120;
            int sliderH = 12;
            int buttonH = 16;
            int gap = 4;
            int sliderX = this.x + this.backgroundWidth - 8 - sliderW;

            // Calculate positions to fit within margin (leaving some padding)
            // Total height needed: 12 + 4 + 12 + 4 + 16 = 48 pixels
            // Start position: margin - 48 - small_gap = margin - 52
            int startY = this.y + margin - 52;
            int sliderY1 = startY;
            int sliderY2 = sliderY1 + sliderH + gap;
            int buttonY = sliderY2 + sliderH + gap;

            excavationHeightSlider = new ExcavationHeightSlider(sliderX, sliderY1, sliderW, sliderH, excavationHeight);
            excavationDepthSlider = new ExcavationDepthSlider(sliderX, sliderY2, sliderW, sliderH, excavationDepth);

            this.addDrawableChild(excavationHeightSlider);
            this.addDrawableChild(excavationDepthSlider);

            // Ore mining mode cycling button
            excavationOreModeButton = ButtonWidget.builder(
                Text.literal("Ores: " + getOreModeDisplayName(excavationOreMiningMode)),
                b -> {
                    excavationOreMiningMode = (excavationOreMiningMode + 1) % 3;
                    b.setMessage(Text.literal("Ores: " + getOreModeDisplayName(excavationOreMiningMode)));
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetOreMiningModeC2SPayload(
                        getEntityId(), 1, excavationOreMiningMode)); // targetMode=1 for excavation
                }
            ).dimensions(sliderX, buttonY, sliderW, buttonH).build();
            this.addDrawableChild(excavationOreModeButton);
        } else if (isMiningMode()) {
            // Mining Mode UI: position ore button within controlsMargin area
            int margin = this.handler.getControlsMargin();
            int sliderW = 120;
            int buttonH = 16;
            int sliderX = this.x + this.backgroundWidth - 8 - sliderW;
            int buttonY = this.y + margin - buttonH - 4; // Position near bottom of margin area

            // Ore mining mode cycling button
            miningOreModeButton = ButtonWidget.builder(
                Text.literal("Ores: " + getOreModeDisplayName(miningOreMiningMode)),
                b -> {
                    miningOreMiningMode = (miningOreMiningMode + 1) % 3;
                    b.setMessage(Text.literal("Ores: " + getOreModeDisplayName(miningOreMiningMode)));
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetOreMiningModeC2SPayload(
                        getEntityId(), 0, miningOreMiningMode)); // targetMode=0 for mining
                }
            ).dimensions(sliderX, buttonY, sliderW, buttonH).build();
            this.addDrawableChild(miningOreModeButton);
        } else if (isTerraformingMode()) {
            // Terraforming mode: 3 gradient rows + window sliders + scan radius slider
            int wx = this.x + 8 + 9 * 18 + 12;
            int wy0 = controlsTop + 18; // First gradient row (vertical)
            int wy1 = wy0 + 18 + 6;     // Second gradient row (horizontal)
            int wy2 = wy1 + 18 + 6;     // Third gradient row (sloped)
            int windowW = 70;
            int scaleW = 50;
            int sliderGap = 6;
            int sliderHeight = 12;

            int g0 = effectiveTerraformingG(0);
            double norm0 = g0 <= 0 ? 0.0 : (double) Math.min(terraformingGradientVerticalWindow, g0) / (double) g0;
            terraformingSliderVertical = new WindowSlider(wx, wy0, windowW, sliderHeight, norm0, 0) {
                @Override
                protected void applyValue() {
                    int G = effectiveTerraformingG(0);
                    int w = (G <= 0) ? 0 : (int)Math.round(this.value * G);
                    w = Math.max(0, Math.min(G, w));
                    if (w != terraformingGradientVerticalWindow) {
                        terraformingGradientVerticalWindow = w;
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientWindowC2SPayload(
                                getEntityId(), 0, w, terraformingGradientVerticalScale));
                    }
                }
            };
            this.addDrawableChild(terraformingSliderVertical);
            terraformingScaleVertical = new NoiseScaleSlider(wx + windowW + sliderGap, wy0, scaleW, sliderHeight, terraformingGradientVerticalScale) {
                @Override
                protected void applyScale(int scale) {
                    if (scale == terraformingGradientVerticalScale) return;
                    terraformingGradientVerticalScale = scale;
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientWindowC2SPayload(
                            getEntityId(), 0, terraformingGradientVerticalWindow, terraformingGradientVerticalScale));
                    updateMessage();
                }
            };
            this.addDrawableChild(terraformingScaleVertical);

            int g1 = effectiveTerraformingG(1);
            double norm1 = g1 <= 0 ? 0.0 : (double) Math.min(terraformingGradientHorizontalWindow, g1) / (double) g1;
            terraformingSliderHorizontal = new WindowSlider(wx, wy1, windowW, sliderHeight, norm1, 1) {
                @Override
                protected void applyValue() {
                    int G = effectiveTerraformingG(1);
                    int w = (G <= 0) ? 0 : (int)Math.round(this.value * G);
                    w = Math.max(0, Math.min(G, w));
                    if (w != terraformingGradientHorizontalWindow) {
                        terraformingGradientHorizontalWindow = w;
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientWindowC2SPayload(
                                getEntityId(), 1, w, terraformingGradientHorizontalScale));
                    }
                }
            };
            this.addDrawableChild(terraformingSliderHorizontal);
            terraformingScaleHorizontal = new NoiseScaleSlider(wx + windowW + sliderGap, wy1, scaleW, sliderHeight, terraformingGradientHorizontalScale) {
                @Override
                protected void applyScale(int scale) {
                    if (scale == terraformingGradientHorizontalScale) return;
                    terraformingGradientHorizontalScale = scale;
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientWindowC2SPayload(
                            getEntityId(), 1, terraformingGradientHorizontalWindow, terraformingGradientHorizontalScale));
                    updateMessage();
                }
            };
            this.addDrawableChild(terraformingScaleHorizontal);

            int g2 = effectiveTerraformingG(2);
            double norm2 = g2 <= 0 ? 0.0 : (double) Math.min(terraformingGradientSlopedWindow, g2) / (double) g2;
            terraformingSliderSloped = new WindowSlider(wx, wy2, windowW, sliderHeight, norm2, 2) {
                @Override
                protected void applyValue() {
                    int G = effectiveTerraformingG(2);
                    int w = (G <= 0) ? 0 : (int)Math.round(this.value * G);
                    w = Math.max(0, Math.min(G, w));
                    if (w != terraformingGradientSlopedWindow) {
                        terraformingGradientSlopedWindow = w;
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientWindowC2SPayload(
                                getEntityId(), 2, w, terraformingGradientSlopedScale));
                    }
                }
            };
            this.addDrawableChild(terraformingSliderSloped);
            terraformingScaleSloped = new NoiseScaleSlider(wx + windowW + sliderGap, wy2, scaleW, sliderHeight, terraformingGradientSlopedScale) {
                @Override
                protected void applyScale(int scale) {
                    if (scale == terraformingGradientSlopedScale) return;
                    terraformingGradientSlopedScale = scale;
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientWindowC2SPayload(
                            getEntityId(), 2, terraformingGradientSlopedWindow, terraformingGradientSlopedScale));
                    updateMessage();
                }
            };
            this.addDrawableChild(terraformingScaleSloped);

            // Scan radius slider at the right
            int scanSliderX = this.x + this.backgroundWidth - 8 - 90;
            int scanSliderY = controlsTop + 18;
            terraformingScanRadiusSlider = new TerraformingScanRadiusSlider(scanSliderX, scanSliderY, 90, 12, terraformingScanRadius);
            this.addDrawableChild(terraformingScanRadiusSlider);

        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(context, mouseX, mouseY);

        // Draw mode name on top of everything to ensure visibility
        BuildMode currentMode = getCurrentMode();
        String modeName = currentMode.name().charAt(0) + currentMode.name().substring(1).toLowerCase();
        // Clamp to screen so it stays visible even if the GUI is taller than the viewport
        int labelWidth = this.textRenderer.getWidth(modeName);
        int labelX = Math.max(2, Math.min(this.x + 8, this.width - labelWidth - 2));
        int labelY = Math.max(2, this.y + 6);
        context.drawText(this.textRenderer, Text.literal(modeName), labelX, labelY, 0xFF404040, false);

        String jsonName = this.handler.getJsonName();
        if (jsonName != null && !jsonName.isBlank()) {
            int nameWidth = this.textRenderer.getWidth(jsonName);
            int nameX = Math.max(2, Math.min(this.x + this.backgroundWidth - 8 - nameWidth, this.width - nameWidth - 2));
            context.drawText(this.textRenderer, Text.literal(jsonName), nameX, labelY, 0xFF404040, false);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean traced) {
        int mx = (int) click.x();
        int my = (int) click.y();
        if (click.button() == 0) {
            if (this.handler.isSliderEnabled()) {
                RowCol rc = gradientIndexAt(mx, my, 3);
                if (rc != null) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(
                        getEntityId(), rc.row, rc.col, getCursorBlockId()));
                    return true;
                }
            } else if (isTerraformingMode()) {
                RowCol rc = gradientIndexAt(mx, my, 3);
                if (rc != null) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientSlotC2SPayload(
                        getEntityId(), rc.row, rc.col, getCursorBlockId()));
                    return true;
                }
            }
        }
        if (this.handler.isSliderEnabled()) {
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
                    if (click.button() == 0) {
                        handleGroupModeSlotClick(rLocal, c, getCursorBlockId());
                        return true;
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
                    if (click.button() == 0) {
                        handleGroupModeSlotClick(rLocal, c, getCursorBlockId());
                        return true;
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
                if (click.button() == 0) {
                    handleGroupModeSlotClick(rLocal, c, getCursorBlockId());
                    return true;
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
    private RowCol gradientIndexAt(int mx, int my, int rows) {
        int slotsX = this.x + 8;
        int slotY0 = this.y + 26; // first row
        int w = 18;
        int h = 18;
        int pad = 18;
        int rowSpacing = 18 + 6;
        int maxRows = Math.max(0, Math.min(3, rows));
        for (int r = 0; r < maxRows; r++) {
            int slotY = slotY0 + r * rowSpacing;
            if (my >= slotY && my < slotY + h) {
                int dx = mx - slotsX;
                if (dx >= 0) {
                    int col = dx / pad;
                    if (col >= 0 && col < 9) {
                        int colX = slotsX + col * pad;
                        if (mx >= colX && mx < colX + w) return new RowCol(r, col);
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Labels (foreground coordinates are relative to GUI top-left)
        // Player inventory label - position relative to where slots actually are
        // Slots are positioned using controlsMargin from the handler
        int margin = this.handler.getControlsMargin();
        int golemRows = this.handler.getGolemRows();
        int invY = margin + golemRows * 18 + 2; // 2px above player inventory slots (which start at margin + golemRows*18 + 15)
        context.drawText(this.textRenderer, this.playerInventoryTitle, 8, invY, 0xFF404040, false);
        // Width label near the slider
        if (widthSlider != null && this.handler.isSliderEnabled()) {
            int lx = widthSlider.getX() - this.x;
            int ly = widthSlider.getY() - this.y - 10;
            context.drawText(this.textRenderer, Text.literal("Width: " + this.pathWidth), lx, ly, 0xFFFFFFFF, false);
        }
        if (gradientNoiseScaleSliderSurface != null && this.handler.isSliderEnabled()) {
            int lx = gradientNoiseScaleSliderSurface.getX() - this.x;
            int ly = gradientNoiseScaleSliderSurface.getY() - this.y - 10;
            context.drawText(this.textRenderer, Text.literal("Scale: " + this.gradientNoiseScaleSurface), lx, ly, 0xFFFFFFFF, false);
        }
        if (gradientNoiseScaleSliderMain != null && this.handler.isSliderEnabled()) {
            int lx = gradientNoiseScaleSliderMain.getX() - this.x;
            int ly = gradientNoiseScaleSliderMain.getY() - this.y - 10;
            context.drawText(this.textRenderer, Text.literal("Scale: " + this.gradientNoiseScaleMain), lx, ly, 0xFFFFFFFF, false);
        }
        if (gradientNoiseScaleSliderStep != null && this.handler.isSliderEnabled()) {
            int lx = gradientNoiseScaleSliderStep.getX() - this.x;
            int ly = gradientNoiseScaleSliderStep.getY() - this.y - 10;
            context.drawText(this.textRenderer, Text.literal("Scale: " + this.gradientNoiseScaleStep), lx, ly, 0xFFFFFFFF, false);
        }

        // Marker dots above each window slider (path mode)
        if (this.handler.isSliderEnabled()) {
            drawSliderMarkers(context, windowSliderSurface, effectiveG(0));
            drawSliderMarkers(context, windowSliderMain, effectiveG(1));
            drawSliderMarkers(context, windowSliderStep, effectiveG(2));
        }

        // Marker dots above group mode sliders (Wall, Tower, Tree)
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            drawGroupSliderMarkers(context, strategy);
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
                // C4: Bounds checking - use min of both sizes to avoid IndexOutOfBoundsException
                int n = Math.min(uniqueBlocks.size(), blockGroups.size());
                int maxGroups = vis.isEmpty() ? 0 : vis.stream().max(Integer::compareTo).orElse(0) + 1;
                for (int i = 0; i < n; i++) {
                    int g = blockGroups.get(i);
                    // C4: Validate group index before use
                    if (g < 0 || g >= maxGroups) {
                        LOGGER.warn("Invalid group index {} for block {} (maxGroups={})", g, uniqueBlocks.get(i), maxGroups);
                        continue;
                    }
                    groupToBlocks.computeIfAbsent(g, k -> new java.util.ArrayList<>()).add(uniqueBlocks.get(i));
                }
                for (int r = 0; r < drawRows; r++) {
                    // C4: Bounds check for vis access
                    int visIndex = r + strategy.getScroll();
                    if (visIndex < 0 || visIndex >= vis.size()) {
                        LOGGER.warn("Invalid vis index {} (vis.size={})", visIndex, vis.size());
                        continue;
                    }
                    int groupIdx = vis.get(visIndex);
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
                            int textX = ix + 24;  // Shifted right to avoid overlap
                            int textY = y + 4;
                            context.drawText(this.textRenderer, countText, textX, textY, 0xFFFFFFFF, true);
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
                // C4: Bounds check for vis access
                int visIndex = r + strategy.getScroll();
                if (visIndex < 0 || visIndex >= vis.size()) {
                    LOGGER.warn("Invalid vis index {} in group rows (vis.size={})", visIndex, vis.size());
                    continue;
                }
                int groupIdx = vis.get(visIndex);
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
                    // C4: Bounds check for flatIndex (already present but add logging for invalid state)
                    if (flatIndex < 0) {
                        LOGGER.warn("Negative flatIndex {} for group {} col {}", flatIndex, groupIdx, c);
                        continue;
                    }
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

            // Draw total blocks preview for Tower mode (to the right of window/scale sliders)
            if (showBlockCounts && strategy.shouldShowBlockCounts()) {
                Map<String, Integer> blockCounts = strategy.getBlockCounts();
                int totalBlocks = 0;

                // Sum all blocks that have non-zero counts
                for (Integer count : blockCounts.values()) {
                    if (count > 0) {
                        totalBlocks += count;
                    }
                }

                // Calculate stacks (64 blocks per stack)
                int totalStacks = (int) Math.ceil((double) totalBlocks / 64.0);

                // Build the preview text
                StringBuilder previewText = new StringBuilder();
                previewText.append(totalStacks).append("st");

                // If more than 27 stacks, show shulker boxes
                if (totalStacks > 27) {
                    double shulkerBoxes = Math.ceil((double) totalStacks / 27.0 * 10.0) / 10.0; // Round up to 1 decimal place
                    previewText.append(" ").append(String.format("%.1f", shulkerBoxes)).append("sb");
                }

                // Position to the right of window/scale sliders
                gridX = this.x + 8;
                int wx2 = gridX + 9 * 18 + 12;
                int w2 = 70;  // window slider width
                int gap2 = 6;
                int s2 = 50;  // scale slider width
                int gridTop = this.y + 26;

                String preview = previewText.toString();
                int previewX = wx2 + w2 + gap2 + s2 + 8; // 8 pixels to the right of scale slider
                int previewY = gridTop; // Align with top of first row
                context.drawText(this.textRenderer, Text.literal(preview), previewX, previewY, 0xFFFFFFFF, true);
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

    public void applyServerSync(int width, int noiseScaleMain, int noiseScaleStep, int noiseScaleSurface, float windowMain, float windowStep, float windowSurface, String[] blocksMain, String[] blocksStep, String[] blocksSurface) {
        this.pathWidth = width;
        this.gradientNoiseScaleMain = noiseScaleMain;
        this.gradientNoiseScaleStep = noiseScaleStep;
        this.gradientNoiseScaleSurface = noiseScaleSurface;
        this.gradientWindowMain = windowMain;
        this.gradientWindowStep = windowStep;
        this.gradientWindowSurface = windowSurface;
        if (blocksMain != null) {
            if (blocksMain.length != 9) this.gradientMainBlocks = new String[9];
            System.arraycopy(blocksMain, 0, this.gradientMainBlocks, 0, Math.min(9, blocksMain.length));
        }
        if (blocksStep != null) {
            if (blocksStep.length != 9) this.gradientStepBlocks = new String[9];
            System.arraycopy(blocksStep, 0, this.gradientStepBlocks, 0, Math.min(9, blocksStep.length));
        }
        if (blocksSurface != null) {
            if (blocksSurface.length != 9) this.gradientSurfaceBlocks = new String[9];
            System.arraycopy(blocksSurface, 0, this.gradientSurfaceBlocks, 0, Math.min(9, blocksSurface.length));
        }
        if (this.windowSliderSurface != null) {
            int g = effectiveG(0);
            this.windowSliderSurface.syncTo(0, g, gradientWindowSurface);
        }
        if (this.windowSliderMain != null) {
            int g = effectiveG(1);
            this.windowSliderMain.syncTo(1, g, gradientWindowMain);
        }
        if (this.windowSliderStep != null) {
            int g = effectiveG(2);
            this.windowSliderStep.syncTo(2, g, gradientWindowStep);
        }
        if (this.widthSlider != null) {
            this.widthSlider.syncTo(this.pathWidth);
        }
        if (this.gradientNoiseScaleSliderSurface != null) {
            this.gradientNoiseScaleSliderSurface.syncTo(this.gradientNoiseScaleSurface);
        }
        if (this.gradientNoiseScaleSliderMain != null) {
            this.gradientNoiseScaleSliderMain.syncTo(this.gradientNoiseScaleMain);
        }
        if (this.gradientNoiseScaleSliderStep != null) {
            this.gradientNoiseScaleSliderStep.syncTo(this.gradientNoiseScaleStep);
        }
    }

    private int effectiveG(int row) {
        String[] arr = row == 0 ? gradientSurfaceBlocks : row == 1 ? gradientMainBlocks : gradientStepBlocks;
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
