package ninja.trek.mc.goldgolem.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
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

public class GolemHandledScreen extends AbstractContainerScreen<GolemInventoryScreenHandler> {
    private static final Logger LOGGER = LoggerFactory.getLogger(GolemHandledScreen.class);
    private static final Identifier GENERIC_CONTAINER_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/container/generic_54.png");
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
    private int wallScroll = 0; // simple integer rows scrolled

    // Tower mode state
    private java.util.List<String> towerUniqueBlocks = java.util.Collections.emptyList();
    private java.util.Map<String, Integer> towerBlockCounts = new java.util.HashMap<>();
    private java.util.List<Integer> towerBlockGroups = java.util.Collections.emptyList();
    private java.util.List<Float> towerGroupWindows = java.util.Collections.emptyList();
    private java.util.List<Integer> towerGroupNoiseScales = java.util.Collections.emptyList();
    private java.util.List<String> towerGroupFlatSlots = java.util.Collections.emptyList();
    private int towerScroll = 0;
    private int towerLayers = 2; // 1-256 layers (synced from server)
    private TowerLayersRangeSlider towerLayersSlider;
    private EditBox towerLayersField;
    private Button towerOriginResetButton;
    private volatile boolean updatingTowerLayersField = false;
    private boolean hasTowerModeData = false;
    private int pyramidCurvature = 0;
    private PyramidCurvatureSlider pyramidCurvatureSlider;

    // Excavation mode state
    private int excavationHeight = 3; // 1-5
    private int excavationDepth = 16; // 0-64 (0 = infinite)
    private int excavationOreMiningMode = 0; // 0=Always, 1=Never, 2=Silk/Fortune
    private ExcavationHeightSlider excavationHeightSlider;
    private ExcavationDepthSlider excavationDepthSlider;
    private Button excavationOreModeButton;

    // Tunnel mode state
    private int tunnelWidth = 3; // 1-9
    private int tunnelHeight = 3; // 2-6
    private int tunnelOreMiningMode = 0; // 0=Always, 1=Never, 2=Silk/Fortune
    private TunnelWidthSlider tunnelWidthSlider;
    private TunnelHeightSlider tunnelHeightSlider;
    private Button tunnelOreModeButton;

    // Mining mode state
    private int miningOreMiningMode = 0; // 0=Always, 1=Never, 2=Silk/Fortune
    private Button miningOreModeButton;

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
    private int treeTilingPresetOrdinal = 0; // 0 = 3x3, 1 = 5x5

    // Room mode state
    private java.util.List<String> roomUniqueBlocks = java.util.Collections.emptyList();
    private java.util.List<Integer> roomBlockGroups = java.util.Collections.emptyList();
    private java.util.List<Float> roomGroupWindows = java.util.Collections.emptyList();
    private java.util.List<Integer> roomGroupNoiseScales = java.util.Collections.emptyList();
    private java.util.List<String> roomGroupFlatSlots = java.util.Collections.emptyList();
    private int roomMemoryLimit = 100;
    private RoomMemoryLimitSlider roomMemoryLimitSlider;

    // Strategy pattern for group-based modes (Wall, Tower, Tree)
    private GroupModeStrategy groupModeStrategy;
    private final java.util.List<WindowSlider> groupRowSliders = new java.util.ArrayList<>();
    private final java.util.List<NoiseScaleSlider> groupRowScaleSliders = new java.util.ArrayList<>();
    private final int[] groupSliderToGroup = new int[6];
    // Section-based layout system
    private SectionFactory.SectionConfiguration sectionConfig;
    private List<GuiSection> sections = new ArrayList<>();

    // Thread safety for sync methods (C2: GUI Thread Safety)
    private final Object stateLock = new Object();

    // Mode-specific state containers (C3: ModeState consolidation)
    private final Map<BuildMode, ModeState> modeStates = new EnumMap<>(BuildMode.class);

    private class WindowSlider extends AbstractSliderButton {
        private final int row; // 0 = main, 1 = step
        public WindowSlider(int x, int y, int width, int height, double norm) {
            this(x, y, width, height, norm, 0);
        }
        public WindowSlider(int x, int y, int width, int height, double norm, int row) {
            super(x, y, width, height, Component.literal("Window"), norm);
            this.row = row;
        }
        @Override
        protected void updateMessage() {
            int g = effectiveG(row);
            float w = (g <= 0) ? 0.0f : Math.round(this.value * g * 10.0f) / 10.0f;
            w = Math.max(0.0f, Math.min(g, w));
            this.setMessage(Component.literal("Window: " + w));
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

    private class WidthSlider extends AbstractSliderButton {
        public WidthSlider(int x, int y, int width, int height, int initialWidth) {
            super(x, y, width, height, Component.literal("Width"), toValueInit(initialWidth));
        }
        private static double toValueInit(int w) { return (clampOdd(w) - 1) / 8.0; }
        private static int toWidth(double v) { return clampOdd(1 + (int)Math.round(v * 8.0)); }
        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Width: " + toWidth(this.value)));
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

    private abstract class NoiseScaleSlider extends AbstractSliderButton {
        public NoiseScaleSlider(int x, int y, int width, int height, int initialScale) {
            super(x, y, width, height, Component.literal("Scale"), scaleToValueInit(initialScale));
        }
        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Scale: " + scaleFromValue(this.value)));
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

    private class ExcavationHeightSlider extends AbstractSliderButton {
        public ExcavationHeightSlider(int x, int y, int width, int height, int initialHeight) {
            super(x, y, width, height, Component.literal("Height"), toValueInit(initialHeight));
        }
        private static double toValueInit(int h) { return (h - 1) / 4.0; } // 1-5 range
        private static int toHeight(double v) { return Math.max(1, Math.min(5, 1 + (int)Math.round(v * 4.0))); }
        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Height: " + toHeight(this.value)));
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

    private class ExcavationDepthSlider extends AbstractSliderButton {
        public ExcavationDepthSlider(int x, int y, int width, int height, int initialDepth) {
            super(x, y, width, height, Component.literal("Depth"), toValueInit(initialDepth));
        }
        private static double toValueInit(int d) { return d / 64.0; } // 0-64 range (0 = infinite)
        private static int toDepth(double v) { return Math.max(0, Math.min(64, (int)Math.round(v * 64.0))); }
        @Override
        protected void updateMessage() {
            int d = toDepth(this.value);
            if (d == 0) {
                this.setMessage(Component.literal("Depth: Infinite"));
            } else {
                this.setMessage(Component.literal("Depth: " + d));
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

    private class TunnelWidthSlider extends AbstractSliderButton {
        public TunnelWidthSlider(int x, int y, int width, int height, int initialWidth) {
            super(x, y, width, height, Component.literal("Width"), toValueInit(initialWidth));
        }
        private static double toValueInit(int w) { return (Math.max(1, Math.min(9, w)) - 1) / 8.0; }
        private static int toWidth(double v) { return Math.max(1, Math.min(9, 1 + (int)Math.round(v * 8.0))); }
        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Width: " + toWidth(this.value)));
        }
        @Override
        protected void applyValue() {
            int w = toWidth(this.value);
            if (w != tunnelWidth) {
                tunnelWidth = w;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTunnelWidthC2SPayload(getEntityId(), tunnelWidth));
                updateMessage();
            }
        }
        public void syncTo(int w) {
            this.value = toValueInit(w);
            updateMessage();
        }
    }

    private class TunnelHeightSlider extends AbstractSliderButton {
        public TunnelHeightSlider(int x, int y, int width, int height, int initialHeight) {
            super(x, y, width, height, Component.literal("Height"), toValueInit(initialHeight));
        }
        private static double toValueInit(int h) { return (Math.max(2, Math.min(6, h)) - 2) / 4.0; }
        private static int toHeight(double v) { return Math.max(2, Math.min(6, 2 + (int)Math.round(v * 4.0))); }
        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Height: " + toHeight(this.value)));
        }
        @Override
        protected void applyValue() {
            int h = toHeight(this.value);
            if (h != tunnelHeight) {
                tunnelHeight = h;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTunnelHeightC2SPayload(getEntityId(), tunnelHeight));
                updateMessage();
            }
        }
        public void syncTo(int h) {
            this.value = toValueInit(h);
            updateMessage();
        }
    }

    private class TerraformingScanRadiusSlider extends AbstractSliderButton {
        public TerraformingScanRadiusSlider(int x, int y, int width, int height, int initialRadius) {
            super(x, y, width, height, Component.literal("Scan Radius"), toValueInit(initialRadius));
        }
        private static double toValueInit(int r) { return (r - 1) / 4.0; } // 1-5 range
        private static int toRadius(double v) { return Math.max(1, Math.min(5, 1 + (int)Math.round(v * 4.0))); }
        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Scan Radius: " + toRadius(this.value)));
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

    private String towerHeightLabel() {
        return getCurrentBuildMode() == BuildMode.PYRAMID ? "Height" : "Layers";
    }

    private class TowerLayersRangeSlider extends AbstractSliderButton {
        public TowerLayersRangeSlider(int x, int y, int width, int height, int initialLayers) {
            super(x, y, width, height, Component.literal(GolemHandledScreen.this.towerHeightLabel()),
                    toValueInit(initialLayers));
        }
        private static double toValueInit(int l) {
            return (clampTowerLayersSlider(l) - 2) / 22.0;
        }
        private static int toLayers(double v) {
            return clampTowerLayersSlider(2 + (int)Math.round(v * 22.0));
        }
        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(towerHeightLabel() + ": " + toLayers(this.value)));
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

    private class PyramidCurvatureSlider extends AbstractSliderButton {
        PyramidCurvatureSlider(int x, int y, int width, int height, int initialCurvature) {
            super(x, y, width, height, Component.literal("Curve"), toValue(initialCurvature));
        }

        private static double toValue(int curvature) {
            return (Math.max(-100, Math.min(100, curvature)) + 100) / 200.0;
        }

        private static int fromValue(double value) {
            return Math.max(-100, Math.min(100, (int) Math.round(value * 200.0 - 100.0)));
        }

        @Override
        protected void updateMessage() {
            int curvature = fromValue(value);
            String shape = curvature < -5 ? "Pointy" : curvature > 5 ? "Dome" : "Linear";
            setMessage(Component.literal("Curve: " + shape + " " + curvature));
        }

        @Override
        protected void applyValue() {
            int curvature = fromValue(value);
            if (curvature != pyramidCurvature) {
                pyramidCurvature = curvature;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetPyramidCurvatureC2SPayload(
                        getEntityId(), curvature));
                updateMessage();
            }
        }

        void syncTo(int curvature) {
            value = toValue(curvature);
            updateMessage();
        }
    }

    private class RoomMemoryLimitSlider extends AbstractSliderButton {
        RoomMemoryLimitSlider(int x, int y, int width, int height, int initialLimit) {
            super(x, y, width, height, Component.literal("Remember rooms"), toValue(initialLimit));
        }

        private static double toValue(int limit) {
            return (Math.max(1, Math.min(1000, limit)) - 1) / 999.0;
        }

        private static int fromValue(double value) {
            return Math.max(1, Math.min(1000, 1 + (int) Math.round(value * 999.0)));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Remember rooms: " + fromValue(value)));
        }

        @Override
        protected void applyValue() {
            int limit = fromValue(value);
            if (limit != roomMemoryLimit) {
                roomMemoryLimit = limit;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetRoomMemoryLimitC2SPayload(
                        getEntityId(), limit));
                updateMessage();
            }
        }

        void syncTo(int limit) {
            value = toValue(limit);
            updateMessage();
        }
    }

    private void setTowerLayersFieldText(int layers) {
        if (towerLayersField == null) return;
        String text = Integer.toString(clampTowerLayers(layers));
        if (text.equals(towerLayersField.getValue())) return;
        updatingTowerLayersField = true;
        towerLayersField.setValue(text);
        updatingTowerLayersField = false;
    }

    private void setTowerLayersSliderValue(int layers) {
        if (towerLayersSlider == null) return;
        towerLayersSlider.syncTo(clampTowerLayersSlider(layers));
    }

    private void onTowerLayersChanged(String text) {
        if (updatingTowerLayersField) return;
        if (text == null || text.isEmpty()) return;
        if (!text.chars().allMatch(Character::isDigit)) {
            setTowerLayersFieldText(towerLayers);
            return;
        }
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

    public GolemHandledScreen(GolemInventoryScreenHandler handler, Inventory inventory, Component title) {
        super(handler, inventory, title, 176,
                handler.getControlsMargin() + handler.getGolemRows() * 18 + 94);
    }

    /**
     * Calculate dynamic layout BEFORE super.init() is called.
     * This sets the backgroundHeight based on content and screen size.
     */
    private void calculateDynamicLayout() {
        BuildMode mode = getCurrentBuildMode();

        // Create sections for current mode
        sectionConfig = SectionFactory.createSectionsForMode(
                mode,
                this.menu.getGolemRows(),
                this);
        sections = sectionConfig.sections;

        // imageHeight is correctly set in constructor from controlsMargin
        // Position sections manually to match old layout
        positionSections();
    }

    /**
     * Position sections to match old layout.
     * Content area starts at Y=26 from GUI top. Inventories start at controlsMargin.
     */
    private void positionSections() {
        if (sections == null || sections.isEmpty()) return;
        int contentStartY = 26; // 17px header + 9px gap
        int margin = this.menu.getControlsMargin();

        for (GuiSection section : sections) {
            if (section instanceof InventoriesSection) {
                section.setPosition(margin);
                section.setAllocatedHeight(this.menu.getGolemRows() * 18 + 94);
            } else if (section instanceof GroupModeSection) {
                section.setPosition(contentStartY);
                // Max visible rows = 6 (matches server allocation and slider count)
                // Each row is 24px (ROW_SPACING = 18 + 6)
                section.setAllocatedHeight(6 * 24);
            } else if (section instanceof GradientsSection) {
                section.setPosition(contentStartY);
            } else if (section instanceof SettingsSection) {
                // Settings go after gradients or group rows
                // Position will be set properly in initializeSections
            }
        }
    }

    /**
     * Initialize the section-based layout system widgets.
     * Called AFTER super.init() so x, y are set correctly.
     */
    private void initializeSections() {
        if (sectionConfig != null) {
            for (GuiSection section : sections) {
                if (section instanceof SettingsSection) {
                    ((SettingsSection) section).setGuiCoordinates(this.leftPos, this.topPos);
                }
                section.initializeWidgets(this::addRenderableWidget);
            }
        }

        // Always update gradient sections with current data (for state sync)
        if (sectionConfig != null && sectionConfig.gradientsSection != null) {
            BuildMode mode = getCurrentBuildMode();
            if (mode == BuildMode.PATH || mode == BuildMode.GRADIENT) {
                sectionConfig.gradientsSection.setGradientRow(0, gradientSurfaceBlocks);
                sectionConfig.gradientsSection.setGradientRow(1, gradientMainBlocks);
                sectionConfig.gradientsSection.setGradientRow(2, gradientStepBlocks);
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
        if (sectionConfig == null) return;
        // Re-position sections with current data (group count may have changed)
        positionSections();
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

    public void syncPyramidUniqueBlocks(java.util.List<String> ids) {
        synchronized (stateLock) {
            this.towerUniqueBlocks = ids == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
            getModeState(BuildMode.PYRAMID).setUniqueBlocks(ids);
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
        Minecraft.getInstance().execute(this::refreshLayoutIfNeeded);
    }

    public void syncRoomUniqueBlocks(java.util.List<String> ids) {
        synchronized (stateLock) {
            roomUniqueBlocks = ids == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(ids);
            getModeState(BuildMode.ROOM).setUniqueBlocks(ids);
        }
    }

    public void syncRoomBlockGroups(java.util.List<Integer> groups) {
        synchronized (stateLock) {
            roomBlockGroups = groups == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(groups);
            getModeState(BuildMode.ROOM).setBlockGroups(groups);
        }
    }

    public void syncRoomGroupsState(java.util.List<Float> windows, java.util.List<Integer> noiseScales,
                                    java.util.List<String> flatSlots, int memoryLimit) {
        synchronized (stateLock) {
            roomGroupWindows = windows == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(windows);
            roomGroupNoiseScales = noiseScales == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(noiseScales);
            roomGroupFlatSlots = flatSlots == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(flatSlots);
            roomMemoryLimit = Math.max(1, Math.min(1000, memoryLimit));
            getModeState(BuildMode.ROOM).updateGroupState(windows, noiseScales, flatSlots);
        }
        groupModeStrategy = null;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) syncGroupSliders(strategy);
        if (roomMemoryLimitSlider != null) roomMemoryLimitSlider.syncTo(roomMemoryLimit);
        Minecraft.getInstance().execute(this::refreshLayoutIfNeeded);
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

    public void syncPyramidBlockCounts(java.util.List<String> ids, java.util.List<Integer> counts, int height,
                                        int curvature) {
        synchronized (stateLock) {
            this.towerBlockCounts.clear();
            java.util.Map<String, Integer> countMap = new java.util.HashMap<>();
            if (ids != null && counts != null) {
                for (int i = 0; i < Math.min(ids.size(), counts.size()); i++) {
                    this.towerBlockCounts.put(ids.get(i), counts.get(i));
                    countMap.put(ids.get(i), counts.get(i));
                }
            }
            getModeState(BuildMode.PYRAMID).setBlockCounts(countMap);
            this.towerLayers = Math.max(1, height);
            this.pyramidCurvature = Math.max(-100, Math.min(100, curvature));
            this.hasTowerModeData = true;
        }
        setTowerLayersFieldText(this.towerLayers);
        setTowerLayersSliderValue(this.towerLayers);
        if (pyramidCurvatureSlider != null) pyramidCurvatureSlider.syncTo(this.pyramidCurvature);
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

    public void syncPyramidBlockGroups(java.util.List<Integer> groups) {
        synchronized (stateLock) {
            this.towerBlockGroups = groups == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(groups);
            getModeState(BuildMode.PYRAMID).setBlockGroups(groups);
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
        Minecraft.getInstance().execute(this::refreshLayoutIfNeeded);
    }

    public void syncPyramidGroupsState(java.util.List<Float> windows, java.util.List<Integer> noiseScales,
                                        java.util.List<String> flatSlots) {
        synchronized (stateLock) {
            this.towerGroupWindows = windows == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(windows);
            this.towerGroupNoiseScales = noiseScales == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(noiseScales);
            this.towerGroupFlatSlots = flatSlots == null
                    ? java.util.Collections.emptyList() : new java.util.ArrayList<>(flatSlots);
            getModeState(BuildMode.PYRAMID).updateGroupState(windows, noiseScales, flatSlots);
        }
        groupModeStrategy = null;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) syncGroupSliders(strategy);
        ensureTowerLayersField();
        Minecraft.getInstance().execute(this::refreshLayoutIfNeeded);
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
            this.excavationOreModeButton.setMessage(Component.literal("Ores: " + getOreModeDisplayName(oreMiningMode)));
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
            this.miningOreModeButton.setMessage(Component.literal("Ores: " + getOreModeDisplayName(oreMiningMode)));
        }
    }

    /** @deprecated Use {@link #syncMiningState} instead */
    @Deprecated
    public void setMiningValues(int branchDepth, int branchSpacing, int tunnelHeight, int oreMiningMode) {
        syncMiningState(branchDepth, branchSpacing, tunnelHeight, oreMiningMode);
    }

    // Tunnel mode network sync method
    public void syncTunnelState(int width, int height, int oreMiningMode) {
        synchronized (stateLock) {
            this.tunnelWidth = width;
            this.tunnelHeight = height;
            this.tunnelOreMiningMode = oreMiningMode;
        }
        if (this.tunnelWidthSlider != null) {
            this.tunnelWidthSlider.syncTo(width);
        }
        if (this.tunnelHeightSlider != null) {
            this.tunnelHeightSlider.syncTo(height);
        }
        if (this.tunnelOreModeButton != null) {
            this.tunnelOreModeButton.setMessage(Component.literal("Ores: " + getOreModeDisplayName(oreMiningMode)));
        }
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
        // Update gradient section with synced data
        if (sectionConfig != null && sectionConfig.gradientsSection != null) {
            sectionConfig.gradientsSection.setGradientRow(0, terraformingGradientVertical);
            sectionConfig.gradientsSection.setGradientRow(1, terraformingGradientHorizontal);
            sectionConfig.gradientsSection.setGradientRow(2, terraformingGradientSloped);
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
        Minecraft.getInstance().execute(this::refreshLayoutIfNeeded);
    }

    /** @deprecated Use {@link #syncTreeGroupsState} instead */
    @Deprecated
    public void setTreeGroupsState(int presetOrdinal, java.util.List<Float> windows, java.util.List<Integer> noiseScales, java.util.List<String> flatSlots) {
        syncTreeGroupsState(presetOrdinal, windows, noiseScales, flatSlots);
    }

    private int getTowerLayersFieldY() {
        int startY = this.topPos + 26;
        int rowSpacing = 18 + 6;
        int maxVis = getGroupMaxVisibleRows();
        int rows = 0;
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null && (strategy.getMode() == BuildMode.TOWER
                || strategy.getMode() == BuildMode.PYRAMID)) {
            int total = strategy.getVisibleGroups().size();
            rows = Math.min(Math.max(0, total - strategy.getScroll()), maxVis);
        }
        if (rows <= 0 && towerGroupWindows != null) {
            int total = towerGroupWindows.size();
            rows = Math.min(Math.max(0, total - towerScroll), maxVis);
        }
        if (rows <= 0) rows = 1;
        return startY + rows * rowSpacing;
    }

    private void ensureTowerLayersField() {
        if (this.menu.isSliderEnabled()) return;
        int sliderMode = this.menu.getSliderMode();
        if (sliderMode != 6 && sliderMode != 8) return;
        if (!this.hasTowerModeData) return;
        if (this.minecraft == null || this.width <= 0) return;
        int layersFieldW = 36;
        int layersGap = 6;
        int layersFieldH = 12;
        int resetButtonW = 12;
        int resetButtonGap = 4;
        int left = this.leftPos + 8;
        int totalW = this.imageWidth - 16;
        int layersSliderW = Math.max(40, totalW - layersFieldW - layersGap - resetButtonW - resetButtonGap);
        int layersSliderX = left + resetButtonW + resetButtonGap;
        int layersFieldX = layersSliderX + layersSliderW + layersGap;
        int layersFieldY = getTowerLayersFieldY();
        int resetButtonX = left;
        if (towerLayersSlider == null) {
            towerLayersSlider = new TowerLayersRangeSlider(layersSliderX, layersFieldY, layersSliderW, layersFieldH, towerLayers);
            this.addRenderableWidget(towerLayersSlider);
        } else {
            towerLayersSlider.setSize(layersSliderW, layersFieldH);
            towerLayersSlider.setX(layersSliderX);
            towerLayersSlider.setY(layersFieldY);
        }
        if (towerLayersField == null) {
            towerLayersField = new EditBox(this.font, layersFieldX, layersFieldY, layersFieldW, layersFieldH,
                    Component.literal(towerHeightLabel()));
            towerLayersField.setMaxLength(3);
            towerLayersField.setResponder(this::onTowerLayersChanged);
            setTowerLayersFieldText(towerLayers);
            setTowerLayersSliderValue(towerLayers);
            this.addRenderableWidget(towerLayersField);
        } else {
            towerLayersField.setSize(layersFieldW, layersFieldH);
            towerLayersField.setX(layersFieldX);
            towerLayersField.setY(layersFieldY);
        }
        if (towerOriginResetButton == null) {
            towerOriginResetButton = Button.builder(Component.literal("R"), b ->
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.ResetTowerOriginC2SPayload(getEntityId())))
                .bounds(resetButtonX, layersFieldY, resetButtonW, layersFieldH)
                .build();
            this.addRenderableWidget(towerOriginResetButton);
        } else {
            towerOriginResetButton.setSize(resetButtonW, layersFieldH);
            towerOriginResetButton.setX(resetButtonX);
            towerOriginResetButton.setY(layersFieldY);
        }
        if (sliderMode == 8) {
            int curvatureY = layersFieldY + layersFieldH + 6;
            int curvatureX = this.leftPos + 8;
            int curvatureW = this.imageWidth - 16;
            if (pyramidCurvatureSlider == null) {
                pyramidCurvatureSlider = new PyramidCurvatureSlider(curvatureX, curvatureY, curvatureW,
                        layersFieldH, pyramidCurvature);
                this.addRenderableWidget(pyramidCurvatureSlider);
            } else {
                pyramidCurvatureSlider.setX(curvatureX);
                pyramidCurvatureSlider.setY(curvatureY);
                pyramidCurvatureSlider.setSize(curvatureW, layersFieldH);
            }
        }
    }

    private boolean isExcavationMode() {
        // slider value of 2 indicates excavation mode
        return this.menu.getSliderMode() == 2;
    }

    private boolean isMiningMode() {
        // slider value of 3 indicates mining mode
        return this.menu.getSliderMode() == 3;
    }

    private boolean isTerraformingMode() {
        // slider value of 4 indicates terraforming mode
        return this.menu.getSliderMode() == 4;
    }

    private boolean isTunnelMode() {
        // slider value of 7 indicates tunnel mode
        return this.menu.getSliderMode() == 7;
    }


    /**
     * Get the text renderer for drawing text.
     */
    public net.minecraft.client.gui.Font getFont() {
        return this.font;
    }

    /**
     * Get the player inventory title text.
     */
    public Component getPlayerInventoryTitle() {
        return this.playerInventoryTitle;
    }

    /** Get GUI X position on screen. */
    public int getGuiX() {
        return this.leftPos;
    }

    /** Get GUI Y position on screen. */
    public int getGuiY() {
        return this.topPos;
    }



    /** Called by GroupModeSection after a block group assignment to refresh sliders. */
    public void onGroupDataChanged() {
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            syncGroupSliders(strategy);
        }
    }

    /**
     * Get the current build mode.
     */
    public BuildMode getCurrentBuildMode() {
        if (this.menu.isSliderEnabled()) {
            return BuildMode.PATH;
        }
        return switch (this.menu.getSliderMode()) {
            case 0 -> BuildMode.WALL;
            case 2 -> BuildMode.EXCAVATION;
            case 3 -> BuildMode.MINING;
            case 4 -> BuildMode.TERRAFORMING;
            case 5 -> BuildMode.TREE;
            case 6 -> BuildMode.TOWER;
            case 7 -> BuildMode.TUNNEL;
            case 8 -> BuildMode.PYRAMID;
            case 9 -> BuildMode.ROOM;
            default -> BuildMode.PATH;
        };
    }

    /**
     * Get the current group mode strategy, or null if not in a group mode.
     */
    public GroupModeStrategy getGroupModeStrategy() {
        if (groupModeStrategy != null) {
            return groupModeStrategy;
        }
        BuildMode mode = getCurrentBuildMode();
        if (!mode.isGroupMode()) return null;
        groupModeStrategy = switch (mode) {
            case WALL -> new WallModeStrategy();
            case TOWER -> new TowerModeStrategy();
            case PYRAMID -> new PyramidModeStrategy();
            case TREE -> new TreeModeStrategy();
            case ROOM -> new RoomModeStrategy();
            default -> null;
        };
        if (groupModeStrategy != null) {
            initializeStrategyFromLegacyData();
        }
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
        } else if (groupModeStrategy.getMode() == BuildMode.TOWER
                || groupModeStrategy.getMode() == BuildMode.PYRAMID) {
            groupModeStrategy.updateBlocksAndGroups(towerUniqueBlocks, towerBlockGroups);
            var extraData = new java.util.HashMap<String, Object>();
            extraData.put("blockCounts", towerBlockCounts);
            extraData.put("height", towerLayers);
            extraData.put("curvature", pyramidCurvature);
            groupModeStrategy.updateGroupState(towerGroupWindows, towerGroupNoiseScales, towerGroupFlatSlots, extraData);
        } else if (groupModeStrategy.getMode() == BuildMode.TREE) {
            groupModeStrategy.updateBlocksAndGroups(treeUniqueBlocks, treeBlockGroups);
            var extraData = new java.util.HashMap<String, Object>();
            extraData.put("tilingPresetOrdinal", treeTilingPresetOrdinal);
            groupModeStrategy.updateGroupState(treeGroupWindows, treeGroupNoiseScales, treeGroupFlatSlots, extraData);
        } else if (groupModeStrategy.getMode() == BuildMode.ROOM) {
            groupModeStrategy.updateBlocksAndGroups(roomUniqueBlocks, roomBlockGroups);
            groupModeStrategy.updateGroupState(roomGroupWindows, roomGroupNoiseScales, roomGroupFlatSlots,
                    java.util.Map.of("memoryLimit", roomMemoryLimit));
        }
    }

    /**
     * Generic scroll method for group modes.
     */
    private void scrollGroup(GroupModeStrategy mode, int delta) {
        int rows = mode.getVisibleGroups().size();
        int visRows = getGroupMaxVisibleRows();
        int maxScroll = Math.max(0, rows - visRows);
        int ns = Math.max(0, Math.min(maxScroll, mode.getScroll() + delta));
        if (ns != mode.getScroll()) {
            mode.setScroll(ns);
            syncGroupSliders(mode);
        }
    }

    /** Get the max visible rows from the GroupModeSection, or default to 6. */
    private int getGroupMaxVisibleRows() {
        if (sectionConfig != null && sectionConfig.groupModeSection != null) {
            return sectionConfig.groupModeSection.getMaxVisibleRows();
        }
        return 6;
    }

    /**
     * Generic slider sync method for group modes.
     */
    private void syncGroupSliders(GroupModeStrategy mode) {
        if (mode == null || this.menu.isSliderEnabled()) return;

        java.util.List<Integer> vis = mode.getVisibleGroups();
        int rows = vis.size();
        int maxVis = getGroupMaxVisibleRows();
        for (int i = 0; i < maxVis; i++) {
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

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        // Vanilla chest-style background split into header/body/bottom slices from generic_54.png
        int left = this.leftPos;
        int top = this.topPos;
        int rows = this.menu.getGolemRows();
        int margin = this.menu.getControlsMargin();

        int headerH = 17;            // chest header height
        int bodyH = rows * 18;       // golem rows area
        int bottomH = 96;            // player inventory + hotbar

        float texW = 256f;
        float texH = 256f;

        // Header strip (u:0..176, v:0..17)
        context.blit(GENERIC_CONTAINER_TEXTURE,
                left, top,
                left + this.imageWidth, top + headerH,
                0f / texW, 176f / texW,
                0f / texH, headerH / texH);

        // Filler from header to controls margin using a 1px band (prevents gaps if margin > 17)
        int fillerH = Math.max(0, margin - headerH);
        if (fillerH > 0) {
            float v1 = 10f / texH;
            float v2 = 11f / texH;
            context.blit(GENERIC_CONTAINER_TEXTURE,
                    left, top + headerH,
                    left + this.imageWidth, top + headerH + fillerH,
                    0f / texW, 176f / texW,
                    v1, v2);
        }

        // Body (golem inventory) starts at margin; source v starts at 17
        int bodyY = top + margin;
        if (bodyH > 0) {
            context.blit(GENERIC_CONTAINER_TEXTURE,
                    left, bodyY,
                    left + this.imageWidth, bodyY + bodyH,
                    0f / texW, 176f / texW,
                    17f / texH, (17f + bodyH) / texH);
        }

        // Bottom (player inventory + hotbar) slice at v=126
        int bottomY = bodyY + bodyH;
        context.blit(GENERIC_CONTAINER_TEXTURE,
                left, bottomY,
                left + this.imageWidth, bottomY + bottomH,
                0f / texW, 176f / texW,
                126f / texH, (126f + bottomH) / texH);

        // Delegate to sections for background rendering
        if (sections != null) {
            for (GuiSection section : sections) {
                section.renderBackground(context, this.leftPos, this.topPos);
            }
        }
    }

    // ========== Shared Slot Click Infrastructure ==========

    public java.util.Optional<Identifier> getCursorBlockId() {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || player.containerMenu == null) return java.util.Optional.empty();
        ItemStack cursor = player.containerMenu.getCarried();
        if (cursor.isEmpty()) return java.util.Optional.empty();
        if (cursor.getItem() instanceof BlockItem) {
            return java.util.Optional.of(BuiltInRegistries.BLOCK.getKey(((BlockItem) cursor.getItem()).getBlock()));
        }
        // Detect tools (pickaxe, shovel, axe) → mine action with tool ID encoded
        Identifier toolItemId = BuiltInRegistries.ITEM.getKey(cursor.getItem());
        String toolIdStr = toolItemId.toString();
        if (toolIdStr.contains("_pickaxe") || toolIdStr.contains("_shovel") || toolIdStr.contains("_axe")) {
            return java.util.Optional.of(ninja.trek.mc.goldgolem.util.GradientSlotUtil.mineIdentifier(toolItemId));
        }
        return java.util.Optional.empty();
    }



    @Override
    protected void init() {
        calculateDynamicLayout();

        super.init();

        // Initialize section widgets AFTER super.init() (which sets x, y)
        initializeSections();

        // Place window slider in the controls margin area. Gradient slots are handled via mouse clicks, not buttons.
        int controlsTop = this.topPos + 8; // leave a small header gap
        // int slotsX = this.x + 8; // for reference
        // int slotY = controlsTop + 18; // below title area

        if (this.menu.isSliderEnabled()) {
            // Path mode: window sliders to the right of gradient rows (3 rows: surface, main, step)
            int wx = this.leftPos + 8 + 9 * 18 + 12;
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
            this.addRenderableWidget(windowSliderSurface);
            gradientNoiseScaleSliderSurface = new GradientNoiseScaleSlider(wx + windowW + sliderGap, wy0, scaleW, sliderHeight, gradientNoiseScaleSurface, 0);
            this.addRenderableWidget(gradientNoiseScaleSliderSurface);
            // Main row (row 1)
            int g0 = effectiveG(1);
            double norm0 = g0 <= 0 ? 0.0 : (double) Math.min(gradientWindowMain, g0) / (double) g0;
            windowSliderMain = new WindowSlider(wx, wy1, windowW, sliderHeight, norm0, 1);
            this.addRenderableWidget(windowSliderMain);
            gradientNoiseScaleSliderMain = new GradientNoiseScaleSlider(wx + windowW + sliderGap, wy1, scaleW, sliderHeight, gradientNoiseScaleMain, 1);
            this.addRenderableWidget(gradientNoiseScaleSliderMain);
            // Step row (row 2)
            int g1 = effectiveG(2);
            double norm1 = g1 <= 0 ? 0.0 : (double) Math.min(gradientWindowStep, g1) / (double) g1;
            windowSliderStep = new WindowSlider(wx, wy2, windowW, sliderHeight, norm1, 2);
            this.addRenderableWidget(windowSliderStep);
            gradientNoiseScaleSliderStep = new GradientNoiseScaleSlider(wx + windowW + sliderGap, wy2, scaleW, sliderHeight, gradientNoiseScaleStep, 2);
            this.addRenderableWidget(gradientNoiseScaleSliderStep);

        }

        // Width slider under the gradient row (right-aligned)
        if (this.menu.isSliderEnabled()) {
            int wsliderW = 50;
            int wsliderH = 12;
            int slotTop = this.topPos + 26; // top of first gradient row
            int wsliderY = slotTop + (18 + 6) + (18 + 6) + 18 + 6; // below third row
            int right = this.leftPos + this.imageWidth - 8;
            int widthX = right - wsliderW;
            widthSlider = new WidthSlider(widthX, wsliderY, wsliderW, wsliderH, pathWidth);
            this.addRenderableWidget(widthSlider);
        } else if (!this.menu.isSliderEnabled() && (this.menu.getSliderMode() <= 1
                || this.menu.getSliderMode() == 5 || this.menu.getSliderMode() == 6
                || this.menu.getSliderMode() == 8 || this.menu.getSliderMode() == 9)) {
            // Group Mode UI (Wall, Tower, Tree): create per-row sliders and scroll buttons using strategy pattern
            // Check sliderMode: 0 or 1 indicates Wall or Tower mode, 5 indicates Tree mode
            // Mode-specific data arrives later via network, but we create sliders now
            groupModeStrategy = null; // Reset to force re-initialization
            GroupModeStrategy strategy = getGroupModeStrategy();

            // Create sliders regardless of whether data has arrived yet
            // They will be synced when network data arrives
            groupRowSliders.clear();
            groupRowScaleSliders.clear();
            int gridTop = this.topPos + 26;
            int rowSpacing = 18 + 6;
            int gridX = this.leftPos + 8;
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
                this.addRenderableWidget(s);

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
                this.addRenderableWidget(ns);
            }
            var upBtn = Button.builder(Component.literal("▲"), b -> {
                GroupModeStrategy strat = getGroupModeStrategy();
                if (strat != null) scrollGroup(strat, -1);
            }).bounds(wx2 + w2 + gap2 + s2 + 4, gridTop, 14, 12).build();
            var dnBtn = Button.builder(Component.literal("▼"), b -> {
                GroupModeStrategy strat = getGroupModeStrategy();
                if (strat != null) scrollGroup(strat, 1);
            }).bounds(wx2 + w2 + gap2 + s2 + 4, gridTop + (getGroupMaxVisibleRows() - 1) * rowSpacing, 14, 12).build();
            this.addRenderableWidget(upBtn);
            this.addRenderableWidget(dnBtn);

            // Tower mode: add layers slider on the right side
            if (mode == BuildMode.TOWER || mode == BuildMode.PYRAMID) {
                int layersFieldW = 36;
                int layersGap = 6;
                int layersFieldH = 12;
                int resetButtonW = 12;
                int resetButtonGap = 4;
                int left = this.leftPos + 8;
                int totalW = this.imageWidth - 16;
                int layersSliderW = Math.max(40, totalW - layersFieldW - layersGap - resetButtonW - resetButtonGap);
                int layersSliderX = left + resetButtonW + resetButtonGap;
                int layersFieldX = layersSliderX + layersSliderW + layersGap;
                int layersFieldY = getTowerLayersFieldY();
                int resetButtonX = left;
                towerLayersSlider = new TowerLayersRangeSlider(layersSliderX, layersFieldY, layersSliderW, layersFieldH, towerLayers);
                this.addRenderableWidget(towerLayersSlider);
                towerLayersField = new EditBox(this.font, layersFieldX, layersFieldY, layersFieldW, layersFieldH,
                        Component.literal(towerHeightLabel()));
                towerLayersField.setMaxLength(3);
                towerLayersField.setResponder(this::onTowerLayersChanged);
                setTowerLayersFieldText(towerLayers);
                setTowerLayersSliderValue(towerLayers);
                this.addRenderableWidget(towerLayersField);
                towerOriginResetButton = Button.builder(Component.literal("R"), b ->
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.ResetTowerOriginC2SPayload(getEntityId())))
                    .bounds(resetButtonX, layersFieldY, resetButtonW, layersFieldH)
                    .build();
                this.addRenderableWidget(towerOriginResetButton);
                if (mode == BuildMode.PYRAMID) {
                    pyramidCurvatureSlider = new PyramidCurvatureSlider(left,
                            layersFieldY + layersFieldH + 6, totalW, layersFieldH, pyramidCurvature);
                    this.addRenderableWidget(pyramidCurvatureSlider);
                }
            }

            // Tree mode: add tiling preset button
            if (mode == BuildMode.TREE) {
                String presetText = treeTilingPresetOrdinal == 0 ? "3x3" : "5x5";
                var presetBtn = Button.builder(Component.literal("Preset: " + presetText), b -> {
                    int newPreset = (treeTilingPresetOrdinal == 0) ? 1 : 0;
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTreeTilingPresetC2SPayload(getEntityId(), newPreset));
                    treeTilingPresetOrdinal = newPreset;
                    b.setMessage(Component.literal("Preset: " + (newPreset == 0 ? "3x3" : "5x5")));
                }).bounds(this.leftPos + this.imageWidth - 8 - 70, gridTop, 70, 20).build();
                this.addRenderableWidget(presetBtn);
            }

            if (mode == BuildMode.ROOM) {
                int limitY = this.topPos + 26 + getGroupMaxVisibleRows() * rowSpacing;
                roomMemoryLimitSlider = new RoomMemoryLimitSlider(
                        this.leftPos + 8, limitY, this.imageWidth - 16, 12, roomMemoryLimit);
                this.addRenderableWidget(roomMemoryLimitSlider);
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
            int margin = this.menu.getControlsMargin();
            int sliderW = 120;
            int sliderH = 12;
            int buttonH = 16;
            int gap = 4;
            int sliderX = this.leftPos + this.imageWidth - 8 - sliderW;

            // Calculate positions to fit within margin (leaving some padding)
            // Total height needed: 12 + 4 + 12 + 4 + 16 = 48 pixels
            // Start position: margin - 48 - small_gap = margin - 52
            int startY = this.topPos + margin - 52;
            int sliderY1 = startY;
            int sliderY2 = sliderY1 + sliderH + gap;
            int buttonY = sliderY2 + sliderH + gap;

            excavationHeightSlider = new ExcavationHeightSlider(sliderX, sliderY1, sliderW, sliderH, excavationHeight);
            excavationDepthSlider = new ExcavationDepthSlider(sliderX, sliderY2, sliderW, sliderH, excavationDepth);

            this.addRenderableWidget(excavationHeightSlider);
            this.addRenderableWidget(excavationDepthSlider);

            // Ore mining mode cycling button
            excavationOreModeButton = Button.builder(
                Component.literal("Ores: " + getOreModeDisplayName(excavationOreMiningMode)),
                b -> {
                    excavationOreMiningMode = (excavationOreMiningMode + 1) % 3;
                    b.setMessage(Component.literal("Ores: " + getOreModeDisplayName(excavationOreMiningMode)));
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetOreMiningModeC2SPayload(
                        getEntityId(), 1, excavationOreMiningMode)); // targetMode=1 for excavation
                }
            ).bounds(sliderX, buttonY, sliderW, buttonH).build();
            this.addRenderableWidget(excavationOreModeButton);
        } else if (isMiningMode()) {
            // Mining Mode UI: position ore button within controlsMargin area
            int margin = this.menu.getControlsMargin();
            int sliderW = 120;
            int buttonH = 16;
            int sliderX = this.leftPos + this.imageWidth - 8 - sliderW;
            int buttonY = this.topPos + margin - buttonH - 4; // Position near bottom of margin area

            // Ore mining mode cycling button
            miningOreModeButton = Button.builder(
                Component.literal("Ores: " + getOreModeDisplayName(miningOreMiningMode)),
                b -> {
                    miningOreMiningMode = (miningOreMiningMode + 1) % 3;
                    b.setMessage(Component.literal("Ores: " + getOreModeDisplayName(miningOreMiningMode)));
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetOreMiningModeC2SPayload(
                        getEntityId(), 0, miningOreMiningMode)); // targetMode=0 for mining
                }
            ).bounds(sliderX, buttonY, sliderW, buttonH).build();
            this.addRenderableWidget(miningOreModeButton);
        } else if (isTunnelMode()) {
            // Tunnel Mode UI: width slider, height slider, ore mode button
            int margin = this.menu.getControlsMargin();
            int sliderW = 120;
            int sliderH = 12;
            int buttonH = 16;
            int gap = 4;
            int sliderX = this.leftPos + this.imageWidth - 8 - sliderW;

            // Total height needed: 12 + 4 + 12 + 4 + 16 = 48 pixels
            int startY = this.topPos + margin - 52;
            int sliderY1 = startY;
            int sliderY2 = sliderY1 + sliderH + gap;
            int buttonY = sliderY2 + sliderH + gap;

            tunnelWidthSlider = new TunnelWidthSlider(sliderX, sliderY1, sliderW, sliderH, tunnelWidth);
            tunnelHeightSlider = new TunnelHeightSlider(sliderX, sliderY2, sliderW, sliderH, tunnelHeight);

            this.addRenderableWidget(tunnelWidthSlider);
            this.addRenderableWidget(tunnelHeightSlider);

            // Ore mining mode cycling button
            tunnelOreModeButton = Button.builder(
                Component.literal("Ores: " + getOreModeDisplayName(tunnelOreMiningMode)),
                b -> {
                    tunnelOreMiningMode = (tunnelOreMiningMode + 1) % 3;
                    b.setMessage(Component.literal("Ores: " + getOreModeDisplayName(tunnelOreMiningMode)));
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetOreMiningModeC2SPayload(
                        getEntityId(), 2, tunnelOreMiningMode)); // targetMode=2 for tunnel
                }
            ).bounds(sliderX, buttonY, sliderW, buttonH).build();
            this.addRenderableWidget(tunnelOreModeButton);
        } else if (isTerraformingMode()) {
            // Terraforming mode: 3 gradient rows + window sliders + scan radius slider
            int wx = this.leftPos + 8 + 9 * 18 + 12;
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
            this.addRenderableWidget(terraformingSliderVertical);
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
            this.addRenderableWidget(terraformingScaleVertical);

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
            this.addRenderableWidget(terraformingSliderHorizontal);
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
            this.addRenderableWidget(terraformingScaleHorizontal);

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
            this.addRenderableWidget(terraformingSliderSloped);
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
            this.addRenderableWidget(terraformingScaleSloped);

            // Scan radius slider at the right
            int scanSliderX = this.leftPos + this.imageWidth - 8 - 90;
            int scanSliderY = controlsTop + 18;
            terraformingScanRadiusSlider = new TerraformingScanRadiusSlider(scanSliderX, scanSliderY, 90, 12, terraformingScanRadius);
            this.addRenderableWidget(terraformingScanRadiusSlider);

        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.nextStratum();

        // Draw mode name on top of everything to ensure visibility
        BuildMode currentMode = getCurrentBuildMode();
        String modeName = currentMode.name().charAt(0) + currentMode.name().substring(1).toLowerCase();
        // Clamp to screen so it stays visible even if the GUI is taller than the viewport
        int labelWidth = this.font.width(modeName);
        int iconWidth = currentMode == BuildMode.ROOM ? 18 : 0;
        int labelX = Math.max(2, Math.min(this.leftPos + 8, this.width - labelWidth - iconWidth - 2));
        int labelY = Math.max(2, this.topPos + 6);
        if (currentMode == BuildMode.ROOM) {
            context.item(new ItemStack(net.minecraft.world.level.block.Blocks.GOLD_BLOCK), labelX, labelY - 5);
            labelX += iconWidth;
        }
        context.text(this.font, Component.literal(modeName), labelX, labelY, 0xFF404040, false);

        String jsonName = this.menu.getJsonName();
        if (jsonName != null && !jsonName.isBlank()) {
            int nameWidth = this.font.width(jsonName);
            int nameX = Math.max(2, Math.min(this.leftPos + this.imageWidth - 8 - nameWidth, this.width - nameWidth - 2));
            context.text(this.font, Component.literal(jsonName), nameX, labelY, 0xFF404040, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean traced) {
        int mx = (int) click.x();
        int my = (int) click.y();

        // Delegate to sections (gradient slots, group mode icons/slots)
        if (sections != null) {
            for (GuiSection section : sections) {
                if (section.handleClick(mx, my, click.button())) return true;
            }
        }

        return super.mouseClicked(click, traced);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        int mx = (int) click.x();
        int my = (int) click.y();
        boolean handled = false;

        // Delegate to sections (group mode drag-drop)
        if (sections != null) {
            for (GuiSection section : sections) {
                if (section.handleMouseRelease(mx, my, click.button())) {
                    handled = true;
                    break;
                }
            }
        }

        return handled || super.mouseReleased(click);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // Labels (foreground coordinates are relative to GUI top-left)
        // Player inventory label - position relative to where slots actually are
        // Slots are positioned using controlsMargin from the handler
        int margin = this.menu.getControlsMargin();
        int golemRows = this.menu.getGolemRows();
        int invY = margin + golemRows * 18 + 2; // 2px above player inventory slots (which start at margin + golemRows*18 + 15)
        context.text(this.font, this.playerInventoryTitle, 8, invY, 0xFF404040, false);
        // Width label near the slider
        if (widthSlider != null && this.menu.isSliderEnabled()) {
            int lx = widthSlider.getX() - this.leftPos;
            int ly = widthSlider.getY() - this.topPos - 10;
            context.text(this.font, Component.literal("Width: " + this.pathWidth), lx, ly, 0xFFFFFFFF, false);
        }
        if (gradientNoiseScaleSliderSurface != null && this.menu.isSliderEnabled()) {
            int lx = gradientNoiseScaleSliderSurface.getX() - this.leftPos;
            int ly = gradientNoiseScaleSliderSurface.getY() - this.topPos - 10;
            context.text(this.font, Component.literal("Scale: " + this.gradientNoiseScaleSurface), lx, ly, 0xFFFFFFFF, false);
        }
        if (gradientNoiseScaleSliderMain != null && this.menu.isSliderEnabled()) {
            int lx = gradientNoiseScaleSliderMain.getX() - this.leftPos;
            int ly = gradientNoiseScaleSliderMain.getY() - this.topPos - 10;
            context.text(this.font, Component.literal("Scale: " + this.gradientNoiseScaleMain), lx, ly, 0xFFFFFFFF, false);
        }
        if (gradientNoiseScaleSliderStep != null && this.menu.isSliderEnabled()) {
            int lx = gradientNoiseScaleSliderStep.getX() - this.leftPos;
            int ly = gradientNoiseScaleSliderStep.getY() - this.topPos - 10;
            context.text(this.font, Component.literal("Scale: " + this.gradientNoiseScaleStep), lx, ly, 0xFFFFFFFF, false);
        }

        // Marker dots above each window slider (path mode)
        if (this.menu.isSliderEnabled()) {
            drawSliderMarkers(context, windowSliderSurface, effectiveG(0));
            drawSliderMarkers(context, windowSliderMain, effectiveG(1));
            drawSliderMarkers(context, windowSliderStep, effectiveG(2));
        }

        // Marker dots above group mode sliders (Wall, Tower, Tree)
        GroupModeStrategy strategy = getGroupModeStrategy();
        if (strategy != null) {
            drawGroupSliderMarkers(context, strategy);
        }

        // Delegate to sections for foreground rendering (group mode UI, gradient labels, etc.)
        if (sections != null) {
            for (GuiSection section : sections) {
                section.renderForeground(context, this.leftPos, this.topPos, mouseX, mouseY);
            }
        }
    }

    public int getEntityId() {
        return this.menu.getEntityId();
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
        // Update gradient section with synced data
        if (sectionConfig != null && sectionConfig.gradientsSection != null) {
            sectionConfig.gradientsSection.setGradientRow(0, gradientSurfaceBlocks);
            sectionConfig.gradientsSection.setGradientRow(1, gradientMainBlocks);
            sectionConfig.gradientsSection.setGradientRow(2, gradientStepBlocks);
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

    private static void fillDot(GuiGraphicsExtractor ctx, int cx, int cy, int argb) {
        int r = 1;
        ctx.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, argb);
    }

    private void drawSliderMarkers(GuiGraphicsExtractor context, AbstractSliderButton slider, int g) {
        if (slider == null) return;
        int sx = slider.getX() - this.leftPos;
        int sy = slider.getY() - this.topPos;
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
    private void drawGroupSliderMarkers(GuiGraphicsExtractor context, GroupModeStrategy strategy) {
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
                int sx = slider.getX() - this.leftPos;
                int sy = slider.getY() - this.topPos;
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
