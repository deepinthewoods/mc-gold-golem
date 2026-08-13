package ninja.trek.mc.goldgolem.world.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import ninja.trek.mc.goldgolem.screen.GolemScreens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.util.GradientGroupManager;
import ninja.trek.mc.goldgolem.util.GroupAssignmentUtil;
import ninja.trek.mc.goldgolem.world.entity.strategy.BuildStrategy;
import ninja.trek.mc.goldgolem.world.entity.strategy.BuildStrategyRegistry;

public class GoldGolemEntity extends PathfinderMob {
    private static final Logger LOGGER = LoggerFactory.getLogger(GoldGolemEntity.class);

    // Animation constants
    private static final int ANIMATION_DURATION_TICKS = 12;
    private static final float ARM_SWING_MIN_ANGLE = 15.0f;
    private static final float ARM_SWING_MAX_ANGLE = 70.0f;

    // Inventory constants
    private static final int GRADIENT_SIZE = 9;
    public static final int INVENTORY_SIZE = 27;

    // Ring buffer constants
    private static final int PLACED_RING_BUFFER_SIZE = 8192;

    // Timing constants
    private static final int STUCK_TICK_THRESHOLD = 20;
    private static final int EYE_UPDATE_COOLDOWN_MIN = 5;
    private static final int EYE_UPDATE_COOLDOWN_MAX = 10;
    private static final int FIRE_HAZARD_CHECK_INTERVAL_TICKS = 10;

    // Owner cache constants
    private static final int OWNER_CACHE_DURATION = 100; // 5 seconds (100 ticks)

    private static final String SNAPSHOT_FOLDER = "GoldGolemModules";
    private static final int SNAPSHOT_VERSION = 2;
    private static final String GOLEM_COUNTER_FILE = "golem_counters.json";

    // Golem counter system for sequential naming (thread-safe for multiplayer)
    private static final java.util.Map<BuildMode, Integer> golemCounters = new java.util.HashMap<>();
    private static boolean countersLoaded = false;
    private static final Object counterLock = new Object();

    // Data trackers for client-server sync
    private static final EntityDataAccessor<Integer> LEFT_HAND_ANIMATION_TICK = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> RIGHT_HAND_ANIMATION_TICK = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> LEFT_ARM_HAS_TARGET = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> RIGHT_ARM_HAS_TARGET = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<BlockPos>> LEFT_HAND_TARGET_POS = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Optional<BlockPos>> RIGHT_HAND_TARGET_POS = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Optional<BlockPos>> LEFT_HAND_NEXT_POS = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Optional<BlockPos>> RIGHT_HAND_NEXT_POS = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Boolean> BUILDING_PATHS = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BUILD_MODE = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> LEFT_MINING_TOOL = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> RIGHT_MINING_TOOL = SynchedEntityData.defineId(GoldGolemEntity.class, EntityDataSerializers.ITEM_STACK);

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private final String[] gradient = new String[GRADIENT_SIZE];
    private final String[] stepGradient = new String[GRADIENT_SIZE];
    private final String[] surfaceGradient = new String[GRADIENT_SIZE];
    // Gradient copy caching
    private String[] cachedGradientCopy = null;
    private boolean gradientCopyDirty = true;
    private float gradientWindow = 1.0f; // window width in slot units (0..9)
    private float stepGradientWindow = 1.0f; // window width for step gradient
    private float surfaceGradientWindow = 1.0f; // window width for surface gradient
    private int gradientNoiseScaleMain = 1; // simplex noise scale (1..16)
    private int gradientNoiseScaleStep = 1; // simplex noise scale (1..16)
    private int gradientNoiseScaleSurface = 1; // simplex noise scale (1..16)
    private int pathWidth = 3;
    private boolean buildingPaths = false;
    private long gradientNoiseSeedCache = Long.MIN_VALUE;
    private SimplexNoise gradientNoiseSampler;
    private int fireHazardCheckCooldown = 0;

    // Strategy pattern for build modes
    private BuildStrategy activeStrategy = null;
    private BlockPos buildStartPosition = null;
    private BlockPos resourceWaitAnchor = null;

    // Wall-mode captured data (scaffold)
    private java.util.List<String> wallUniqueBlockIds = java.util.Collections.emptyList();
    private net.minecraft.core.BlockPos wallOrigin = null; // absolute origin of capture
    private String wallJsonFile = null; // saved snapshot path (relative to game dir)
    private String wallJoinSignature = null; // common join-slice signature
    private ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis wallJoinAxis = null;
    private int wallJoinUSize = 1;
    private int wallModuleCount = 0;
    private int wallLongestModule = 0; // by voxel count for now
    private boolean wallSliceSymmetric = true; // whether join slice is mirror-symmetric
    private java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> wallTemplates = java.util.Collections.emptyList();
    // Join-slice inferred template: points in (dy,du) with ids; uses wallJoinAxis
    private java.util.List<ninja.trek.mc.goldgolem.world.entity.strategy.wall.JoinEntry> wallJoinTemplate = java.util.Collections.emptyList();
    // Wall UI state: dynamic gradient groups
    private final java.util.List<String[]> wallGroupSlots = new java.util.ArrayList<>(); // each String[9]
    private final java.util.List<Float> wallGroupWindows = new java.util.ArrayList<>();
    private final java.util.List<Integer> wallGroupNoiseScales = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> wallBlockGroup = new java.util.HashMap<>();

    // Tower-mode captured data
    private java.util.List<String> towerUniqueBlockIds = java.util.Collections.emptyList();
    private java.util.Map<String, Integer> towerBlockCounts = java.util.Collections.emptyMap();
    private net.minecraft.core.BlockPos towerOrigin = null; // absolute origin (bottom gold block)
    private String towerJsonFile = null; // saved snapshot path (relative to game dir)
    private int towerHeight = 0; // total height to build (in blocks)
    private ninja.trek.mc.goldgolem.tower.TowerModuleTemplate towerTemplate = null;
    // Tower UI state: dynamic gradient groups (same as wall mode)
    private final java.util.List<String[]> towerGroupSlots = new java.util.ArrayList<>(); // each String[9]
    private final java.util.List<Float> towerGroupWindows = new java.util.ArrayList<>();
    private final java.util.List<Integer> towerGroupNoiseScales = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> towerBlockGroup = new java.util.HashMap<>();
    // Tower building state
    private int towerCurrentY = 0; // current Y layer being placed (0 = bottom)
    private int towerPlacementCursor = 0; // cursor within current Y layer

    // Mining-mode state is now managed by MiningBuildStrategy

    // Excavation-mode state is now managed by ExcavationBuildStrategy

    // Terraforming-mode state is now managed by TerraformingBuildStrategy
    // UI settings remain here
    private int terraformingScanRadius = 2; // slope detection radius (1-5)
    private int terraformingAlpha = 3; // alpha shape parameter (concavity control, 1-10)
    // Three gradients for terraforming mode
    private final String[] terraformingGradientVertical = new String[GRADIENT_SIZE]; // steep/cliff surfaces
    private final String[] terraformingGradientHorizontal = new String[GRADIENT_SIZE]; // flat surfaces
    private final String[] terraformingGradientSloped = new String[GRADIENT_SIZE]; // diagonal surfaces
    private int terraformingGradientVerticalWindow = 1; // window for vertical gradient (0..9)
    private int terraformingGradientHorizontalWindow = 1; // window for horizontal gradient (0..9)
    private int terraformingGradientSlopedWindow = 1; // window for sloped gradient (0..9)
    private int terraformingGradientVerticalScale = 1; // noise scale for vertical gradient (1..16)
    private int terraformingGradientHorizontalScale = 1; // noise scale for horizontal gradient (1..16)
    private int terraformingGradientSlopedScale = 1; // noise scale for sloped gradient (1..16)

    // Tree-mode captured data (UI fields stay in entity, state fields moved to TreeBuildStrategy)
    private java.util.List<ninja.trek.mc.goldgolem.tree.TreeModule> treeModules = java.util.Collections.emptyList();
    private java.util.List<String> treeUniqueBlockIds = java.util.Collections.emptyList();
    private net.minecraft.core.BlockPos treeOrigin = null; // second gold block position
    private String treeJsonFile = null; // saved snapshot path (relative to game dir)
    private String treeGroundBlockId = null; // registry ID of the ground block detected at scan time
    private ninja.trek.mc.goldgolem.tree.TilingPreset treeTilingPreset = ninja.trek.mc.goldgolem.tree.TilingPreset.SMALL_3x3;
    // Tree UI state: dynamic gradient groups (same pattern as wall/tower)
    private final java.util.List<String[]> treeGroupSlots = new java.util.ArrayList<>(); // each String[9]
    private final java.util.List<Float> treeGroupWindows = new java.util.ArrayList<>();
    private final java.util.List<Integer> treeGroupNoiseScales = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> treeBlockGroup = new java.util.HashMap<>();

    // Shared tracking fields (used by PATH and WALL modes)
    private Vec3 trackStart = null;
    private java.util.ArrayDeque<ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg> pendingLines = new java.util.ArrayDeque<>();
    private ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg currentLine = null;

    // Path-mode pending mine queue and helper (for gradient mine actions in placeOffsetAt)
    private final java.util.ArrayDeque<BlockPos> pathPendingMines = new java.util.ArrayDeque<>();
    private final ninja.trek.mc.goldgolem.world.entity.strategy.GradientMiningHelper pathGradientMiner = new ninja.trek.mc.goldgolem.world.entity.strategy.GradientMiningHelper();
    private final LongOpenHashSet recentPlaced = new LongOpenHashSet(PLACED_RING_BUFFER_SIZE);
    private final long[] placedRing = new long[PLACED_RING_BUFFER_SIZE];
    private int placedHead = 0;
    private int placedSize = 0;
    private final Object ringBufferLock = new Object();

    private int stuckTicks = 0;
    private double wheelRotation = 0.0;
    private double prevX = 0.0;
    private double prevZ = 0.0;
    // Eye look directions (independent for each eye)
    private float leftEyeYaw = 0.0f;
    private float leftEyePitch = 0.0f;
    private float rightEyeYaw = 0.0f;
    private float rightEyePitch = 0.0f;
    private int eyeUpdateCooldown = 0;
    // Arm swing animation
    private static final int ARM_SWING_DURATION_TICKS = 15;
    private float leftArmRotation = 0.0f;  // Pitch rotation in degrees (up/down)
    private float rightArmRotation = 0.0f; // Pitch rotation in degrees (up/down)
    private float leftArmYaw = 0.0f;       // Yaw rotation in degrees (left/right, relative to body)
    private float rightArmYaw = 0.0f;      // Yaw rotation in degrees (left/right, relative to body)
    private float leftArmTarget = 0.0f;    // Target rotation for this swing
    private float rightArmTarget = 0.0f;   // Target rotation for this swing
    private int armSwingTimer = 0;         // Timer counting down from SWING_DURATION_TICKS

    // Block placement animation (new system)
    private int placementTickCounter = 0;  // 0-1 tick counter (places every 2 ticks)
    private boolean leftHandActive = true; // Which hand places next
    private Vec3 leftArmTargetBlock = null;  // Block position left arm points at
    private Vec3 rightArmTargetBlock = null; // Block position right arm points at
    private int leftHandAnimationTick = -1;   // -1 = idle, 0-3 = animation cycle
    private int rightHandAnimationTick = -1;  // -1 = idle, 0-3 = animation cycle
    private BlockPos nextLeftBlock = null;    // Next block for left hand
    private BlockPos nextRightBlock = null;   // Next block for right hand
    private boolean leftHandJustActivated = false;
    private boolean rightHandJustActivated = false;

    // GUI viewer tracking - golem stays in place when a player has GUI open
    private java.util.UUID guiViewerUuid = null;
    private boolean suppressSnapshotWrite = false;

    // Tree-mode stored block states for resurrection (per module, relative positions)
    private java.util.List<java.util.Map<BlockPos, BlockState>> treeModuleBlockStates = new java.util.ArrayList<>();

    public boolean hasGuiViewer() { return guiViewerUuid != null; }
    public void setGuiViewer(java.util.UUID uuid) { this.guiViewerUuid = uuid; }
    public void clearGuiViewer() { this.guiViewerUuid = null; }

    public boolean isBuildingPaths() { return this.entityData.get(BUILDING_PATHS); }
    public float getLeftEyeYaw() { return leftEyeYaw; }
    public float getLeftEyePitch() { return leftEyePitch; }
    public float getRightEyeYaw() { return rightEyeYaw; }
    public float getRightEyePitch() { return rightEyePitch; }
    public double getWheelRotation() { return wheelRotation; }
    public float getLeftArmRotation() { return leftArmRotation; }
    public float getRightArmRotation() { return rightArmRotation; }
    public float getLeftArmYaw() { return leftArmYaw; }
    public float getRightArmYaw() { return rightArmYaw; }
    public int getLeftHandAnimationTick() { return this.entityData.get(LEFT_HAND_ANIMATION_TICK); }
    public int getRightHandAnimationTick() { return this.entityData.get(RIGHT_HAND_ANIMATION_TICK); }
    public boolean shouldShowLeftHandItem() {
        int tick = getLeftHandAnimationTick();
        // Show item for first half of animation (ticks 0-5 of 12)
        return tick >= 0 && tick <= 5;
    }
    public boolean shouldShowRightHandItem() {
        int tick = getRightHandAnimationTick();
        // Show item for first half of animation (ticks 0-5 of 12)
        return tick >= 0 && tick <= 5;
    }
    public ItemStack getLeftHandItem() {
        BuildMode mode = getBuildMode();
        // Mining/Excavation: show tool continuously while active
        if ((mode == BuildMode.MINING || mode == BuildMode.EXCAVATION) && isBuildingPaths()) {
            ItemStack tool = getLeftMiningTool();
            if (!tool.isEmpty()) {
                return tool;
            }
        }
        // Building modes: show block during placement animation
        if (!shouldShowLeftHandItem()) return ItemStack.EMPTY;
        // Return the first block item from inventory
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
    public ItemStack getRightHandItem() {
        BuildMode mode = getBuildMode();
        // Mining/Excavation: show tool continuously while active
        if ((mode == BuildMode.MINING || mode == BuildMode.EXCAVATION) && isBuildingPaths()) {
            ItemStack tool = getRightMiningTool();
            if (!tool.isEmpty()) {
                return tool;
            }
        }
        // Building modes: show block during placement animation
        if (!shouldShowRightHandItem()) return ItemStack.EMPTY;
        // Return the first block item from inventory
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
    public BuildMode getBuildMode() {
        int ordinal = this.entityData.get(BUILD_MODE);
        BuildMode[] values = BuildMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BuildMode.PATH;
    }
    public void setBuildMode(BuildMode mode) {
        this.entityData.set(BUILD_MODE, (mode == null ? BuildMode.PATH : mode).ordinal());
    }

    public ItemStack getLeftMiningTool() {
        return this.entityData.get(LEFT_MINING_TOOL);
    }

    public ItemStack getRightMiningTool() {
        return this.entityData.get(RIGHT_MINING_TOOL);
    }

    public void setLeftMiningTool(ItemStack tool) {
        this.entityData.set(LEFT_MINING_TOOL, tool == null ? ItemStack.EMPTY : tool);
    }

    public void setRightMiningTool(ItemStack tool) {
        this.entityData.set(RIGHT_MINING_TOOL, tool == null ? ItemStack.EMPTY : tool);
    }

    /** @deprecated Use getLeftMiningTool() or getRightMiningTool() instead */
    @Deprecated
    public ItemStack getCurrentMiningTool() {
        // For backwards compatibility, return left tool if active, else right
        ItemStack left = getLeftMiningTool();
        return !left.isEmpty() ? left : getRightMiningTool();
    }

    /** @deprecated Use setLeftMiningTool() or setRightMiningTool() instead */
    @Deprecated
    public void setCurrentMiningTool(ItemStack tool) {
        // For backwards compatibility, set both tools to empty when called with empty
        if (tool == null || tool.isEmpty()) {
            setLeftMiningTool(ItemStack.EMPTY);
            setRightMiningTool(ItemStack.EMPTY);
        } else {
            setLeftMiningTool(tool);
        }
    }

    // Strategy pattern methods
    public BuildStrategy getActiveStrategy() {
        return activeStrategy;
    }

    public void setActiveStrategy(BuildStrategy strategy) {
        if (activeStrategy != null) {
            activeStrategy.cleanup(this);
        }
        activeStrategy = strategy;
        if (activeStrategy != null) {
            activeStrategy.initialize(this);
            setBuildMode(strategy.getMode());
        }
    }

    /**
     * Create and set a strategy for the current build mode.
     * Called when loading from NBT or when the golem starts building.
     */
    public void initializeStrategyForCurrentMode() {
        BuildMode mode = getBuildMode();
        if (activeStrategy == null || activeStrategy.getMode() != mode) {
            BuildStrategy newStrategy = BuildStrategyRegistry.create(mode);
            setActiveStrategy(newStrategy);
        }
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy wall) {
            configureWallStrategy(wall);
        }
    }

    /**
     * Transfer the entity's wall capture data into the given WallBuildStrategy.
     */
    private void configureWallStrategy(ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy wall) {
        wall.setConfig(wallOrigin, getJsonFileForMode(BuildMode.WALL), wallUniqueBlockIds, wallJoinSignature,
                wallJoinAxis, wallJoinUSize, wallModuleCount, wallLongestModule, wallSliceSymmetric, getWallTemplates(), wallJoinTemplate);
    }

    /**
     * Stop building and clean up the active strategy.
     */
    public void stopBuilding() {
        this.buildingPaths = false;
        this.entityData.set(BUILDING_PATHS, false);
        if (activeStrategy != null) {
            activeStrategy.stop(this);
        }
        this.getNavigation().stop();
        clearBuildReturnState();
    }

    /**
     * Start building with the current strategy.
     */
    public void startBuilding() {
        prepareBuildReturnState(true);
        this.buildingPaths = true;
        this.entityData.set(BUILDING_PATHS, true);
        initializeStrategyForCurrentMode();
    }

    /**
     * Set the building state (called by strategies).
     */
    public void setBuildingPaths(boolean building) {
        this.buildingPaths = building;
        this.entityData.set(BUILDING_PATHS, building);
    }

    public boolean isWaitingForResources() {
        return activeStrategy != null && activeStrategy.isWaitingForResources();
    }

    public BlockPos getResourceWaitAnchor() {
        return resourceWaitAnchor;
    }

    private void clearBuildReturnState() {
        buildStartPosition = null;
        resourceWaitAnchor = null;
    }

    private void prepareBuildReturnState(boolean resetStartPosition) {
        if (!getBuildMode().returnsToBuildStartWhenOutOfBlocks()) {
            clearBuildReturnState();
            return;
        }
        if (resetStartPosition || buildStartPosition == null) {
            buildStartPosition = this.blockPosition();
        }
        resourceWaitAnchor = null;
    }

    public void setWallCapture(java.util.List<String> uniqueIds, net.minecraft.core.BlockPos origin, String jsonPath) {
        this.wallUniqueBlockIds = uniqueIds == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(uniqueIds);
        this.wallOrigin = origin;
        this.wallJsonFile = jsonPath;
    }
    public java.util.List<String> getWallUniqueBlockIds() { return java.util.Collections.unmodifiableList(this.wallUniqueBlockIds); }
    public void setWallJoinSignature(String sig) { this.wallJoinSignature = sig; }
    public String getWallJoinSignature() { return wallJoinSignature; }
    public void setWallJoinMeta(ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis axis, int uSize) { this.wallJoinAxis = axis; this.wallJoinUSize = Math.max(1, uSize); }
    public void setWallModulesMeta(int count, int longest) { this.wallModuleCount = count; this.wallLongestModule = longest; }
    public int getWallModuleCount() { return wallModuleCount; }
    public int getWallLongestModule() { return wallLongestModule; }
    public void setWallSliceSymmetric(boolean symmetric) { this.wallSliceSymmetric = symmetric; }
    public boolean isWallSliceSymmetric() { return wallSliceSymmetric; }
    public void setWallTemplates(java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> tpls) {
        this.wallTemplates = tpls == null ? java.util.Collections.emptyList() : tpls;
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy wall) {
            configureWallStrategy(wall);
        }
    }
    /**
     * Get wall templates, lazy-loading from snapshot JSON if not yet loaded.
     * Mirrors the getTowerTemplate() pattern for persistence across world reloads.
     */
    public java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> getWallTemplates() {
        if ((wallTemplates == null || wallTemplates.isEmpty()) && wallJsonFile != null && !wallJsonFile.isEmpty()) {
            if (this.level() instanceof net.minecraft.server.level.ServerLevel serverWorld) {
                try {
                    java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(wallJsonFile);
                    if (java.nio.file.Files.exists(path)) {
                        SnapshotData data = readSnapshot(serverWorld, path);
                        if (data != null && data.wallTemplates() != null && !data.wallTemplates().isEmpty()) {
                            this.wallTemplates = data.wallTemplates();
                            LOGGER.info("Lazy-loaded wall templates from {}", wallJsonFile);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to lazy-load wall templates from {}: {}", wallJsonFile, e.getMessage());
                }
            }
        }
        return wallTemplates;
    }
    public void setWallJoinTemplate(java.util.List<int[]> pointsDyDuAndIdIndex, java.util.List<String> idLut) {
        java.util.ArrayList<ninja.trek.mc.goldgolem.world.entity.strategy.wall.JoinEntry> list = new java.util.ArrayList<>();
        for (int[] p : pointsDyDuAndIdIndex) {
            int dy = p[0], du = p[1], idx = p[2];
            String id = (idx >= 0 && idx < idLut.size()) ? idLut.get(idx) : "";
            list.add(new ninja.trek.mc.goldgolem.world.entity.strategy.wall.JoinEntry(dy, du, id));
        }
        this.wallJoinTemplate = list;
    }
    public void initWallGroups(java.util.List<String> uniqueBlocks) {
        wallGroupSlots.clear(); wallGroupWindows.clear(); wallGroupNoiseScales.clear(); wallBlockGroup.clear();
        // default: one group per unique; if gold present, merge it with first non-gold
        int idx = 0;
        int firstNonGold = -1;
        for (String id : uniqueBlocks) {
            String[] arr = new String[9];
            wallGroupSlots.add(arr);
            wallGroupWindows.add(1.0f);
            wallGroupNoiseScales.add(1);
            wallBlockGroup.put(id, idx);
            if (!"minecraft:gold_block".equals(id) && firstNonGold < 0) firstNonGold = idx;
            idx++;
        }
        Integer goldIdx = wallBlockGroup.get("minecraft:gold_block");
        if (goldIdx != null && firstNonGold >= 0 && goldIdx != firstNonGold) {
            // merge gold into first non-gold group by remapping only; keep arrays as-is
            wallBlockGroup.put("minecraft:gold_block", firstNonGold);
        }
    }
    public java.util.List<Integer> getWallBlockGroupMap(java.util.List<String> uniqueBlocks) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>(uniqueBlocks.size());
        for (String id : uniqueBlocks) out.add(wallBlockGroup.getOrDefault(id, 0));
        return out;
    }
    public java.util.List<Float> getWallGroupWindows() { return new java.util.ArrayList<>(wallGroupWindows); }
    public java.util.List<Integer> getWallGroupNoiseScales() { return new java.util.ArrayList<>(wallGroupNoiseScales); }
    public java.util.List<String[]> getWallGroupSlots() { return wallGroupSlots; }
    public java.util.Map<String, Integer> getWallBlockGroup() { return wallBlockGroup; }
    public java.util.List<String> getWallGroupFlatSlots() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(wallGroupSlots.size() * 9);
        for (String[] arr : wallGroupSlots) {
            for (int i = 0; i < 9; i++) out.add(arr[i] == null ? "" : arr[i]);
        }
        return out;
    }
    public boolean setWallBlockGroup(String blockId, int group) {
        return GroupAssignmentUtil.assign(blockId, group, wallUniqueBlockIds,
                wallGroupSlots, wallGroupWindows, wallGroupNoiseScales, wallBlockGroup);
    }
    public void setWallGroupWindow(int group, float window) {
        if (group < 0 || group >= wallGroupWindows.size()) return;
        wallGroupWindows.set(group, Math.max(0.0f, Math.min(9.0f, window)));
    }
    public void setWallGroupNoiseScale(int group, int scale) {
        if (group < 0 || group >= wallGroupNoiseScales.size()) return;
        wallGroupNoiseScales.set(group, Math.max(1, Math.min(16, scale)));
    }
    public void setWallGroupSlot(int group, int slot, String id) {
        if (group < 0 || group >= wallGroupSlots.size()) return;
        if (slot < 0 || slot >= 9) return;
        String[] arr = wallGroupSlots.get(group);
        arr[slot] = (id == null) ? "" : id;
    }

    // Tower mode methods
    public void setTowerCapture(java.util.List<String> uniqueIds, java.util.Map<String, Integer> counts,
                                net.minecraft.core.BlockPos origin, String jsonPath, int height,
                                ninja.trek.mc.goldgolem.tower.TowerModuleTemplate template) {
        this.towerUniqueBlockIds = uniqueIds == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(uniqueIds);
        this.towerBlockCounts = counts == null ? java.util.Collections.emptyMap() : new java.util.HashMap<>(counts);
        this.towerOrigin = origin;
        this.towerJsonFile = jsonPath;
        int minHeight = (template != null) ? template.moduleHeight : 1;
        this.towerHeight = Math.max(height, minHeight);
        this.towerTemplate = template;
        // Initialize tower groups
        initTowerGroups(uniqueIds);
    }
    public java.util.List<String> getTowerUniqueBlockIds() { return java.util.Collections.unmodifiableList(this.towerUniqueBlockIds); }
    public java.util.Map<String, Integer> getTowerBlockCounts() { return java.util.Collections.unmodifiableMap(this.towerBlockCounts); }
    public int getTowerHeight() { return towerHeight; }
    public void setTowerHeight(int height) {
        int clampedHeight = Math.max(1, Math.min(256, height));
        if (this.towerHeight == clampedHeight) return;
        this.towerHeight = clampedHeight;
        notifyTowerConfigurationChanged("towerHeight");
    }
    public ninja.trek.mc.goldgolem.tower.TowerModuleTemplate getTowerTemplate() {
        // Lazy load from JSON file if template is null but file path is set
        if (towerTemplate == null && towerJsonFile != null && !towerJsonFile.isEmpty()) {
            if (this.level() instanceof net.minecraft.server.level.ServerLevel serverWorld) {
                try {
                    java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(towerJsonFile);
                    if (java.nio.file.Files.exists(path)) {
                        SnapshotData data = readSnapshot(serverWorld, path);
                        if (data != null && data.towerTemplate() != null) {
                            this.towerTemplate = data.towerTemplate();
                            LOGGER.info("Lazy-loaded tower template from {}", towerJsonFile);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to lazy-load tower template from {}: {}", towerJsonFile, e.getMessage());
                }
            }
        }
        return towerTemplate;
    }

    public void initTowerGroups(java.util.List<String> uniqueBlocks) {
        towerGroupSlots.clear(); towerGroupWindows.clear(); towerGroupNoiseScales.clear(); towerBlockGroup.clear();
        // Default: one group per unique block type
        int idx = 0;
        for (String id : uniqueBlocks) {
            String[] arr = new String[9];
            towerGroupSlots.add(arr);
            towerGroupWindows.add(1.0f);
            towerGroupNoiseScales.add(1);
            towerBlockGroup.put(id, idx);
            idx++;
        }
    }
    public java.util.List<Integer> getTowerBlockGroupMap(java.util.List<String> uniqueBlocks) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>(uniqueBlocks.size());
        for (String id : uniqueBlocks) out.add(towerBlockGroup.getOrDefault(id, 0));
        return out;
    }
    public java.util.List<Float> getTowerGroupWindows() { return new java.util.ArrayList<>(towerGroupWindows); }
    public java.util.List<Integer> getTowerGroupNoiseScales() { return new java.util.ArrayList<>(towerGroupNoiseScales); }
    public java.util.List<String[]> getTowerGroupSlots() { return towerGroupSlots; }
    public java.util.Map<String, Integer> getTowerBlockGroup() { return towerBlockGroup; }
    public BlockPos getTowerOrigin() { return towerOrigin; }
    public void setTowerOrigin(BlockPos origin) {
        if (Objects.equals(this.towerOrigin, origin)) return;
        this.towerOrigin = origin;
        notifyTowerConfigurationChanged("towerOrigin");
    }
    public java.util.List<String> getTowerGroupFlatSlots() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(towerGroupSlots.size() * 9);
        for (String[] arr : towerGroupSlots) {
            for (int i = 0; i < 9; i++) out.add(arr[i] == null ? "" : arr[i]);
        }
        return out;
    }
    public boolean setTowerBlockGroup(String blockId, int group) {
        boolean assigned = GroupAssignmentUtil.assign(blockId, group, towerUniqueBlockIds,
                towerGroupSlots, towerGroupWindows, towerGroupNoiseScales, towerBlockGroup);
        if (assigned) notifyTowerConfigurationChanged("towerGradient");
        return assigned;
    }
    public void setTowerGroupWindow(int group, float window) {
        if (group < 0 || group >= towerGroupWindows.size()) return;
        float clampedWindow = Math.max(0.0f, Math.min(9.0f, window));
        if (Float.compare(towerGroupWindows.get(group), clampedWindow) == 0) return;
        towerGroupWindows.set(group, clampedWindow);
        notifyTowerConfigurationChanged("towerGradient");
    }
    public void setTowerGroupNoiseScale(int group, int scale) {
        if (group < 0 || group >= towerGroupNoiseScales.size()) return;
        int clampedScale = Math.max(1, Math.min(16, scale));
        if (towerGroupNoiseScales.get(group) == clampedScale) return;
        towerGroupNoiseScales.set(group, clampedScale);
        notifyTowerConfigurationChanged("towerGradient");
    }
    public void setTowerGroupSlot(int group, int slot, String id) {
        if (group < 0 || group >= towerGroupSlots.size()) return;
        if (slot < 0 || slot >= 9) return;
        String[] arr = towerGroupSlots.get(group);
        String normalizedId = (id == null) ? "" : id;
        if (Objects.equals(arr[slot], normalizedId)) return;
        arr[slot] = normalizedId;
        notifyTowerConfigurationChanged("towerGradient");
    }

    private void notifyTowerConfigurationChanged(String configKey) {
        if (activeStrategy != null) {
            activeStrategy.onConfigurationChanged(configKey);
        }
    }

    // Tree mode configuration
    public void setTreeCapture(java.util.List<ninja.trek.mc.goldgolem.tree.TreeModule> modules,
                              java.util.List<String> uniqueIds, net.minecraft.core.BlockPos origin, String jsonPath,
                              String groundBlockId) {
        this.treeModules = modules == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(modules);
        this.treeUniqueBlockIds = uniqueIds == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(uniqueIds);
        this.treeOrigin = origin;
        this.treeJsonFile = jsonPath;
        this.treeGroundBlockId = groundBlockId;
        // Initialize tree groups
        initTreeGroups(uniqueIds);
    }

    public void setTreeModuleBlockStates(java.util.List<java.util.Map<BlockPos, BlockState>> states) {
        this.treeModuleBlockStates = states == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(states);
    }

    /**
     * Get tree module block states, lazy-loading from snapshot JSON if not yet loaded.
     * Mirrors the getTowerTemplate() / getWallTemplates() pattern for persistence across world reloads.
     */
    public java.util.List<java.util.Map<BlockPos, BlockState>> getTreeModuleBlockStates() {
        if ((treeModuleBlockStates == null || treeModuleBlockStates.isEmpty()) && treeJsonFile != null && !treeJsonFile.isEmpty()) {
            if (this.level() instanceof net.minecraft.server.level.ServerLevel serverWorld) {
                try {
                    java.nio.file.Path path = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(treeJsonFile);
                    if (java.nio.file.Files.exists(path)) {
                        SnapshotData data = readSnapshot(serverWorld, path);
                        if (data != null && data.treeModuleStates() != null && !data.treeModuleStates().isEmpty()) {
                            this.treeModuleBlockStates = new java.util.ArrayList<>(data.treeModuleStates());
                            LOGGER.info("Lazy-loaded tree module block states from {}", treeJsonFile);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to lazy-load tree module block states from {}: {}", treeJsonFile, e.getMessage());
                }
            }
        }
        return treeModuleBlockStates;
    }

    public String getCurrentJsonName() {
        String jsonRel = getJsonFileForMode(getBuildMode());
        if (jsonRel == null || jsonRel.isBlank()) return "";
        try {
            Path fileName = Path.of(jsonRel).getFileName();
            return fileName == null ? jsonRel : fileName.toString();
        } catch (Exception ignored) {
            return jsonRel;
        }
    }

    private String getJsonFileForMode(BuildMode mode) {
        if (mode == null) return null;
        return switch (mode) {
            case WALL -> wallJsonFile;
            case TOWER -> towerJsonFile;
            case TREE -> treeJsonFile;
            default -> null;
        };
    }

    private void setJsonFileForMode(BuildMode mode, String jsonRel) {
        if (mode == null) return;
        switch (mode) {
            case WALL -> this.wallJsonFile = jsonRel;
            case TOWER -> this.towerJsonFile = jsonRel;
            case TREE -> this.treeJsonFile = jsonRel;
            default -> {
            }
        }
    }

    private static String sanitizeJsonBaseName(String name) {
        if (name == null || name.isEmpty()) return "golem";

        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // Only allow letters, digits, underscore, hyphen, space, period
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ' ' || c == '.') {
                sb.append(c);
            }
        }

        // Trim trailing dots and spaces manually
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == '.' || sb.charAt(end - 1) == ' ')) {
            end--;
        }

        // Also trim leading spaces
        int start = 0;
        while (start < end && sb.charAt(start) == ' ') {
            start++;
        }

        if (start >= end) return "golem";
        String result = sb.substring(start, end);
        return result.isEmpty() ? "golem" : result;
    }

    private static Path resolveSnapshotPath(Path folder, String baseName) throws IOException {
        Files.createDirectories(folder);
        Path targetPath = folder.resolve(baseName + ".json");
        int suffix = 2;
        while (Files.exists(targetPath)) {
            targetPath = folder.resolve(baseName + "_" + suffix + ".json");
            suffix++;
        }
        return targetPath;
    }

    public static Path findSnapshotPath(String desiredName) {
        String baseName = sanitizeJsonBaseName(desiredName);
        if (baseName == null) return null;
        Path folder = FabricLoader.getInstance().getGameDir().resolve(SNAPSHOT_FOLDER);
        Path direct = folder.resolve(baseName + ".json");
        if (Files.exists(direct)) return direct;
        if (!Files.isDirectory(folder)) return null;
        long bestTime = Long.MIN_VALUE;
        Path best = null;
        try (var stream = Files.list(folder)) {
            for (Path p : stream.filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")).toList()) {
                try {
                    String json = Files.readString(p);
                    JsonElement parsed = JsonParser.parseString(json);
                    if (!parsed.isJsonObject()) continue;
                    JsonObject root = parsed.getAsJsonObject();
                    String name = root.has("golemName") ? root.get("golemName").getAsString() : "";
                    if (!baseName.equals(sanitizeJsonBaseName(name))) continue;
                    long savedAt = root.has("savedAt") ? root.get("savedAt").getAsLong() : 0L;
                    if (savedAt > bestTime) {
                        bestTime = savedAt;
                        best = p;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to read snapshot file {}: {}", p, e.getMessage());
                } catch (com.google.gson.JsonSyntaxException e) {
                    LOGGER.warn("Invalid JSON in snapshot file {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to list snapshot folder {}: {}", folder, e.getMessage());
        }
        return best;
    }

    /**
     * Load golem counters from persistent storage (must be called within synchronized block)
     */
    private static void loadGolemCounters() {
        if (countersLoaded) return;
        countersLoaded = true;

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path counterFile = gameDir.resolve(SNAPSHOT_FOLDER).resolve(GOLEM_COUNTER_FILE);

        if (!Files.exists(counterFile)) {
            // Initialize all counters to 1
            for (BuildMode mode : BuildMode.values()) {
                golemCounters.put(mode, 1);
            }
            return;
        }

        try {
            String json = Files.readString(counterFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            for (BuildMode mode : BuildMode.values()) {
                String key = mode.name().toLowerCase();
                if (root.has(key)) {
                    golemCounters.put(mode, root.get(key).getAsInt());
                } else {
                    golemCounters.put(mode, 1);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load golem counters, resetting to 1", e);
            for (BuildMode mode : BuildMode.values()) {
                golemCounters.put(mode, 1);
            }
        }
    }

    /**
     * Save golem counters to persistent storage
     */
    private static void saveGolemCounters() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path folder = gameDir.resolve(SNAPSHOT_FOLDER);
        Path counterFile = folder.resolve(GOLEM_COUNTER_FILE);

        try {
            Files.createDirectories(folder);

            JsonObject root = new JsonObject();
            for (java.util.Map.Entry<BuildMode, Integer> entry : golemCounters.entrySet()) {
                root.addProperty(entry.getKey().name().toLowerCase(), entry.getValue());
            }

            Files.writeString(counterFile, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException e) {
            LOGGER.error("Failed to save golem counters", e);
        }
    }

    /**
     * Get the next sequential golem name for a given build mode (thread-safe for multiplayer)
     */
    public static String getNextGolemName(BuildMode mode) {
        synchronized (counterLock) {
            loadGolemCounters();

            int counter = golemCounters.getOrDefault(mode, 1);
            golemCounters.put(mode, counter + 1);
            saveGolemCounters();

            String modeStr = mode.name().toLowerCase();
            return "gg_" + modeStr + "_" + counter;
        }
    }

    private static JsonObject serializeBlockState(BlockState state) {
        JsonObject out = new JsonObject();
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        out.addProperty("id", id);
        JsonObject props = new JsonObject();
        for (Property<?> prop : state.getProperties()) {
            Comparable<?> value = state.getValue(prop);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Property raw = (Property) prop;
            props.addProperty(prop.getName(), raw.getName(value));
        }
        out.add("props", props);
        return out;
    }

    private static BlockState deserializeBlockState(JsonObject obj) {
        if (obj == null) return null;
        String id = obj.has("id") ? obj.get("id").getAsString() : "";
        if (id.isEmpty()) return null;
        var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        BlockState state = block.defaultBlockState();
        if (obj.has("props") && obj.get("props").isJsonObject()) {
            JsonObject props = obj.getAsJsonObject("props");
            for (var entry : props.entrySet()) {
                String propName = entry.getKey();
                String propValue = entry.getValue().getAsString();
                Property<?> prop = block.getStateDefinition().getProperty(propName);
                if (prop == null) continue;
                @SuppressWarnings({"rawtypes", "unchecked"})
                Property raw = (Property) prop;
                Optional parsed = raw.getValue(propValue);
                if (parsed.isPresent()) {
                    state = state.setValue(raw, (Comparable) parsed.get());
                }
            }
        }
        return state;
    }

    private static JsonArray serializeVec(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    private static BlockPos deserializeVec(JsonArray arr) {
        if (arr == null || arr.size() < 3) return BlockPos.ZERO;
        return new BlockPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
    }

    private CompoundTag buildSnapshotNbt(ServerLevel world) {
        TagValueOutput view = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess());
        addAdditionalSaveData(view);
        view.discard("Owner");
        NonNullList<ItemStack> empty = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        ContainerHelper.saveAllItems(view.child("Inventory"), empty, true);
        return view.buildResult();
    }

    private JsonObject buildSnapshotJson(ServerLevel world, String desiredName, CompoundTag nbt) {
        JsonObject root = new JsonObject();
        root.addProperty("version", SNAPSHOT_VERSION);
        root.addProperty("savedAt", System.currentTimeMillis());
        root.addProperty("golemName", desiredName == null ? "" : desiredName);
        root.addProperty("mode", getBuildMode().name());
        root.addProperty("nbt", NbtUtils.structureToSnbt(nbt));

        JsonArray wallTemplatesJson = new JsonArray();
        for (var tpl : getWallTemplates()) {
            JsonObject t = new JsonObject();
            t.add("a", serializeVec(tpl.aMarker));
            t.add("b", serializeVec(tpl.bMarker));
            t.addProperty("minY", tpl.minY);
            if (tpl.aSliceAxis != null) t.addProperty("aSliceAxis", tpl.aSliceAxis.name());
            if (tpl.bSliceAxis != null) t.addProperty("bSliceAxis", tpl.bSliceAxis.name());
            JsonArray voxels = new JsonArray();
            for (var v : tpl.voxels) {
                JsonObject vj = new JsonObject();
                vj.add("rel", serializeVec(v.rel));
                vj.add("state", serializeBlockState(v.state));
                voxels.add(vj);
            }
            t.add("voxels", voxels);
            wallTemplatesJson.add(t);
        }
        root.add("wallTemplates", wallTemplatesJson);

        if (towerTemplate != null) {
            JsonObject tower = new JsonObject();
            tower.addProperty("minY", towerTemplate.minY);
            tower.addProperty("maxY", towerTemplate.maxY);
            JsonArray voxels = new JsonArray();
            for (var v : towerTemplate.voxels) {
                JsonObject vj = new JsonObject();
                vj.add("rel", serializeVec(v.rel));
                vj.add("state", serializeBlockState(v.state));
                voxels.add(vj);
            }
            tower.add("voxels", voxels);
            root.add("towerTemplate", tower);
        }

        JsonArray treeModulesJson = new JsonArray();
        for (var module : getTreeModuleBlockStates()) {
            JsonArray voxels = new JsonArray();
            for (var entry : module.entrySet()) {
                JsonObject vj = new JsonObject();
                vj.add("rel", serializeVec(entry.getKey()));
                vj.add("state", serializeBlockState(entry.getValue()));
                voxels.add(vj);
            }
            treeModulesJson.add(voxels);
        }
        root.add("treeModuleStates", treeModulesJson);

        return root;
    }

    private static SnapshotData readSnapshot(ServerLevel world, Path path) throws IOException {
        String json = Files.readString(path);
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) return null;
        JsonObject root = parsed.getAsJsonObject();
        String nbtStr = root.has("nbt") ? root.get("nbt").getAsString() : "";
        if (nbtStr.isEmpty()) return null;
        CompoundTag nbt;
        try {
            nbt = NbtUtils.snbtToStructure(nbtStr);
        } catch (Exception e) {
            return null;
        }

        java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> wallTemplates = new java.util.ArrayList<>();
        if (root.has("wallTemplates") && root.get("wallTemplates").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("wallTemplates");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject t = el.getAsJsonObject();
                BlockPos a = deserializeVec(t.getAsJsonArray("a"));
                BlockPos b = deserializeVec(t.getAsJsonArray("b"));
                int minY = t.has("minY") ? t.get("minY").getAsInt() : 0;
                java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel> voxels = new java.util.ArrayList<>();
                if (t.has("voxels") && t.get("voxels").isJsonArray()) {
                    for (JsonElement ve : t.getAsJsonArray("voxels")) {
                        if (!ve.isJsonObject()) continue;
                        JsonObject vj = ve.getAsJsonObject();
                        BlockPos rel = deserializeVec(vj.getAsJsonArray("rel"));
                        BlockState state = deserializeBlockState(vj.getAsJsonObject("state"));
                        if (state != null) {
                            voxels.add(new ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel(rel, state));
                        }
                    }
                }
                ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis aSliceAxis = null;
                ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis bSliceAxis = null;
                if (t.has("aSliceAxis")) {
                    try { aSliceAxis = ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.valueOf(t.get("aSliceAxis").getAsString()); }
                    catch (IllegalArgumentException ignored) {}
                }
                if (t.has("bSliceAxis")) {
                    try { bSliceAxis = ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.valueOf(t.get("bSliceAxis").getAsString()); }
                    catch (IllegalArgumentException ignored) {}
                }
                wallTemplates.add(new ninja.trek.mc.goldgolem.wall.WallModuleTemplate(a, b, voxels, minY, aSliceAxis, bSliceAxis));
            }
        }

        ninja.trek.mc.goldgolem.tower.TowerModuleTemplate towerTemplate = null;
        if (root.has("towerTemplate") && root.get("towerTemplate").isJsonObject()) {
            JsonObject tower = root.getAsJsonObject("towerTemplate");
            int minY = tower.has("minY") ? tower.get("minY").getAsInt() : 0;
            int maxY = tower.has("maxY") ? tower.get("maxY").getAsInt() : 0;
            java.util.List<ninja.trek.mc.goldgolem.tower.TowerModuleTemplate.Voxel> voxels = new java.util.ArrayList<>();
            if (tower.has("voxels") && tower.get("voxels").isJsonArray()) {
                for (JsonElement ve : tower.getAsJsonArray("voxels")) {
                    if (!ve.isJsonObject()) continue;
                    JsonObject vj = ve.getAsJsonObject();
                    BlockPos rel = deserializeVec(vj.getAsJsonArray("rel"));
                    BlockState state = deserializeBlockState(vj.getAsJsonObject("state"));
                    if (state != null) {
                        voxels.add(new ninja.trek.mc.goldgolem.tower.TowerModuleTemplate.Voxel(rel, state));
                    }
                }
            }
            towerTemplate = new ninja.trek.mc.goldgolem.tower.TowerModuleTemplate(voxels, minY, maxY);
        }

        java.util.List<java.util.Map<BlockPos, BlockState>> treeModuleStates = new java.util.ArrayList<>();
        if (root.has("treeModuleStates") && root.get("treeModuleStates").isJsonArray()) {
            for (JsonElement moduleEl : root.getAsJsonArray("treeModuleStates")) {
                if (!moduleEl.isJsonArray()) continue;
                java.util.Map<BlockPos, BlockState> module = new java.util.HashMap<>();
                for (JsonElement ve : moduleEl.getAsJsonArray()) {
                    if (!ve.isJsonObject()) continue;
                    JsonObject vj = ve.getAsJsonObject();
                    BlockPos rel = deserializeVec(vj.getAsJsonArray("rel"));
                    BlockState state = deserializeBlockState(vj.getAsJsonObject("state"));
                    if (state != null) {
                        module.put(rel, state);
                    }
                }
                treeModuleStates.add(module);
            }
        }

        return new SnapshotData(nbt, wallTemplates, towerTemplate, treeModuleStates);
    }

    private Path writeSnapshotForName(String desiredName) {
        String baseName = sanitizeJsonBaseName(desiredName);
        if (baseName == null) return null;
        if (!(level() instanceof ServerLevel world)) return null;
        Path folder = FabricLoader.getInstance().getGameDir().resolve(SNAPSHOT_FOLDER);
        try {
            CompoundTag nbt = buildSnapshotNbt(world);
            JsonObject root = buildSnapshotJson(world, desiredName, nbt);
            Path out = resolveSnapshotPath(folder, baseName);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(out, gson.toJson(root));
            String rel = FabricLoader.getInstance().getGameDir().relativize(out).toString();
            setJsonFileForMode(getBuildMode(), rel);
            return out;
        } catch (IOException e) {
            LOGGER.error("Failed to write snapshot {}: {}", desiredName, e.getMessage());
            return null;
        } catch (Exception e) {
            LOGGER.error("Unexpected error writing snapshot {}", desiredName, e);
            return null;
        }
    }

    public boolean applySnapshotFromPath(ServerLevel world, Path path, BlockPos summonOrigin, Player owner, String displayName) {
        SnapshotData data;
        try {
            data = readSnapshot(world, path);
        } catch (IOException e) {
            return false;
        }
        if (data == null) return false;
        ValueInput view = TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), data.nbt());
        readAdditionalSaveData(view);
        setOwner(owner);
        if (displayName != null && !displayName.isBlank()) {
            setCustomNameNoSnapshot(Component.literal(displayName));
        }
        setJsonFileForMode(getBuildMode(), FabricLoader.getInstance().getGameDir().relativize(path).toString());
        if (data.wallTemplates() != null && !data.wallTemplates().isEmpty()) {
            setWallTemplates(data.wallTemplates());
        }
        if (data.towerTemplate() != null) {
            this.towerTemplate = data.towerTemplate();
        }
        if (data.treeModuleStates() != null) {
            setTreeModuleBlockStates(data.treeModuleStates());
        }
        if (getBuildMode() == BuildMode.TREE) {
            this.treeOrigin = summonOrigin;
        } else if (getBuildMode() == BuildMode.TOWER) {
            if (this.towerOrigin == null) {
                this.towerOrigin = summonOrigin;
            }
        } else if (getBuildMode() == BuildMode.WALL) {
            this.wallOrigin = summonOrigin;
        }
        return true;
    }

    private record SnapshotData(
            CompoundTag nbt,
            java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> wallTemplates,
            ninja.trek.mc.goldgolem.tower.TowerModuleTemplate towerTemplate,
            java.util.List<java.util.Map<BlockPos, BlockState>> treeModuleStates
    ) {}
    public java.util.List<ninja.trek.mc.goldgolem.tree.TreeModule> getTreeModules() { return java.util.Collections.unmodifiableList(this.treeModules); }
    public java.util.List<String> getTreeUniqueBlockIds() { return java.util.Collections.unmodifiableList(this.treeUniqueBlockIds); }
    public ninja.trek.mc.goldgolem.tree.TilingPreset getTreeTilingPreset() { return treeTilingPreset; }
    public void setTreeTilingPreset(ninja.trek.mc.goldgolem.tree.TilingPreset preset) {
        if (preset != null && preset != this.treeTilingPreset) {
            this.treeTilingPreset = preset;
            // Invalidate tile cache via polymorphic dispatch
            if (activeStrategy != null) {
                activeStrategy.onConfigurationChanged("tilingPreset");
            }
        }
    }

    public void initTreeGroups(java.util.List<String> uniqueBlocks) {
        treeGroupSlots.clear(); treeGroupWindows.clear(); treeGroupNoiseScales.clear(); treeBlockGroup.clear();
        // Default: one group per unique block type
        int idx = 0;
        for (String id : uniqueBlocks) {
            String[] arr = new String[9];
            treeGroupSlots.add(arr);
            treeGroupWindows.add(1.0f);
            treeGroupNoiseScales.add(1);
            treeBlockGroup.put(id, idx);
            idx++;
        }
    }
    public java.util.List<Integer> getTreeBlockGroupMap(java.util.List<String> uniqueBlocks) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>(uniqueBlocks.size());
        for (String id : uniqueBlocks) out.add(treeBlockGroup.getOrDefault(id, 0));
        return out;
    }
    public java.util.List<Float> getTreeGroupWindows() { return new java.util.ArrayList<>(treeGroupWindows); }
    public java.util.List<Integer> getTreeGroupNoiseScales() { return new java.util.ArrayList<>(treeGroupNoiseScales); }
    public java.util.List<String> getTreeGroupFlatSlots() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(treeGroupSlots.size() * 9);
        for (String[] arr : treeGroupSlots) {
            for (int i = 0; i < 9; i++) out.add(arr[i] == null ? "" : arr[i]);
        }
        return out;
    }
    public boolean setTreeBlockGroup(String blockId, int group) {
        return GroupAssignmentUtil.assign(blockId, group, treeUniqueBlockIds,
                treeGroupSlots, treeGroupWindows, treeGroupNoiseScales, treeBlockGroup);
    }
    public void setTreeGroupWindow(int group, float window) {
        if (group < 0 || group >= treeGroupWindows.size()) return;
        treeGroupWindows.set(group, Math.max(0.0f, Math.min(9.0f, window)));
    }
    public void setTreeGroupNoiseScale(int group, int scale) {
        if (group < 0 || group >= treeGroupNoiseScales.size()) return;
        treeGroupNoiseScales.set(group, Math.max(1, Math.min(16, scale)));
    }
    public void setTreeGroupSlot(int group, int slot, String id) {
        if (group < 0 || group >= treeGroupSlots.size()) return;
        if (slot < 0 || slot >= 9) return;
        String[] arr = treeGroupSlots.get(group);
        arr[slot] = (id == null) ? "" : id;
    }
    public BlockPos getTreeOrigin() { return treeOrigin; }
    public String getTreeGroundBlockId() { return treeGroundBlockId; }
    public java.util.Map<String, Integer> getTreeBlockGroup() { return treeBlockGroup; }
    public java.util.List<String[]> getTreeGroupSlots() { return treeGroupSlots; }

    // Strategy-based group manager accessors (for future migration to strategy-owned groups)
    /**
     * Get the GradientGroupManager for wall mode via the strategy.
     * Returns null if strategy is not a WallBuildStrategy.
     */
    public GradientGroupManager getWallGroupManager() {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy wallStrategy) {
            return wallStrategy.getGroups();
        }
        return null;
    }

    /**
     * Get the GradientGroupManager for tower mode via the strategy.
     * Returns null if strategy is not a TowerBuildStrategy.
     */
    public GradientGroupManager getTowerGroupManager() {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TowerBuildStrategy towerStrategy) {
            return towerStrategy.getGroups();
        }
        return null;
    }

    /**
     * Get the GradientGroupManager for tree mode via the strategy.
     * Returns null if strategy is not a TreeBuildStrategy.
     */
    public GradientGroupManager getTreeGroupManager() {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TreeBuildStrategy treeStrategy) {
            return treeStrategy.getGroups();
        }
        return null;
    }

    // Tree waiting state accessors (using polymorphic dispatch)
    public boolean isTreeWaitingForInventory() {
        return activeStrategy != null && activeStrategy.isWaitingForResources();
    }
    public void setTreeWaitingForInventory(boolean waiting) {
        if (activeStrategy != null) {
            activeStrategy.setWaitingForResources(waiting);
            // When resuming (waiting=false), the strategy's setWaitingForResources handles the reset
        }
    }

    // Animation setters for strategy access
    public void setLeftHandTargetPos(java.util.Optional<BlockPos> pos) {
        this.entityData.set(LEFT_HAND_TARGET_POS, pos);
    }
    public void setLeftArmHasTarget(boolean hasTarget) {
        this.entityData.set(LEFT_ARM_HAS_TARGET, hasTarget);
    }
    public void setLeftHandAnimationTick(int tick) {
        this.entityData.set(LEFT_HAND_ANIMATION_TICK, tick);
    }

    // Mining mode configuration
    public void setMiningConfig(BlockPos chestPos, net.minecraft.core.Direction miningDir, BlockPos startPos) {
        // Ensure strategy is initialized for mining mode
        if (activeStrategy == null || !(activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.MiningBuildStrategy)) {
            setBuildMode(BuildMode.MINING);
            initializeStrategyForCurrentMode();
        }
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.MiningBuildStrategy miningStrategy) {
            miningStrategy.setConfig(chestPos, miningDir, startPos);
        }
    }
    public void setMiningSliders(int branchDepth, int branchSpacing, int tunnelHeight) {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.MiningBuildStrategy miningStrategy) {
            miningStrategy.setSliders(branchDepth, branchSpacing, tunnelHeight);
        }
    }
    public int getMiningBranchDepth() {
        return activeStrategy != null ? activeStrategy.getConfigInt("branchDepth", 16) : 16;
    }
    public int getMiningBranchSpacing() {
        return activeStrategy != null ? activeStrategy.getConfigInt("branchSpacing", 3) : 3;
    }
    public int getMiningTunnelHeight() {
        return activeStrategy != null ? activeStrategy.getConfigInt("tunnelHeight", 2) : 2;
    }

    // Excavation mode configuration
    public void setExcavationConfig(BlockPos chest1, BlockPos chest2, net.minecraft.core.Direction dir1, net.minecraft.core.Direction dir2, BlockPos startPos) {
        // Ensure strategy is initialized for excavation mode
        if (activeStrategy == null || !(activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.ExcavationBuildStrategy)) {
            setBuildMode(BuildMode.EXCAVATION);
            initializeStrategyForCurrentMode();
        }
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.ExcavationBuildStrategy excavationStrategy) {
            excavationStrategy.setConfig(chest1, chest2, dir1, dir2, startPos);
        }
    }
    public void setExcavationSliders(int height, int depth) {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.ExcavationBuildStrategy excavationStrategy) {
            excavationStrategy.setSliders(height, depth);
        }
    }
    public int getExcavationHeight() {
        return activeStrategy != null ? activeStrategy.getConfigInt("height", 3) : 3;
    }
    public int getExcavationDepth() {
        return activeStrategy != null ? activeStrategy.getConfigInt("depth", 16) : 16;
    }

    // Ore Mining Mode helpers for Mining strategy
    public void setMiningOreMiningMode(ninja.trek.mc.goldgolem.OreMiningMode mode) {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.MiningBuildStrategy miningStrategy) {
            miningStrategy.setOreMiningMode(mode);
        }
    }
    public ninja.trek.mc.goldgolem.OreMiningMode getMiningOreMiningMode() {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.MiningBuildStrategy miningStrategy) {
            return miningStrategy.getOreMiningMode();
        }
        return ninja.trek.mc.goldgolem.OreMiningMode.ALWAYS;
    }

    // Tunnel mode configuration
    public void setTunnelConfig(BlockPos c1, BlockPos c2, BlockPos c3, net.minecraft.core.Direction dir, BlockPos start) {
        if (activeStrategy == null || !(activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TunnelBuildStrategy)) {
            setBuildMode(BuildMode.TUNNEL);
            initializeStrategyForCurrentMode();
        }
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TunnelBuildStrategy tunnelStrategy) {
            tunnelStrategy.setConfig(c1, c2, c3, dir, start);
        }
    }
    public void setTunnelWidth(int width) {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TunnelBuildStrategy tunnelStrategy) {
            tunnelStrategy.setWidth(width);
        }
    }
    public void setTunnelHeight(int height) {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TunnelBuildStrategy tunnelStrategy) {
            tunnelStrategy.setHeight(height);
        }
    }
    public int getTunnelWidth() {
        return activeStrategy != null ? activeStrategy.getConfigInt("width", 3) : 3;
    }
    public int getTunnelHeight() {
        return activeStrategy != null ? activeStrategy.getConfigInt("height", 3) : 3;
    }
    public ninja.trek.mc.goldgolem.OreMiningMode getTunnelOreMiningMode() {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TunnelBuildStrategy tunnelStrategy) {
            return tunnelStrategy.getOreMiningMode();
        }
        return ninja.trek.mc.goldgolem.OreMiningMode.ALWAYS;
    }
    public void setTunnelOreMiningMode(ninja.trek.mc.goldgolem.OreMiningMode mode) {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TunnelBuildStrategy tunnelStrategy) {
            tunnelStrategy.setOreMiningMode(mode);
        }
    }

    // Ore Mining Mode helpers for Excavation strategy
    public void setExcavationOreMiningMode(ninja.trek.mc.goldgolem.OreMiningMode mode) {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.ExcavationBuildStrategy excavationStrategy) {
            excavationStrategy.setOreMiningMode(mode);
        }
    }
    public ninja.trek.mc.goldgolem.OreMiningMode getExcavationOreMiningMode() {
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.ExcavationBuildStrategy excavationStrategy) {
            return excavationStrategy.getOreMiningMode();
        }
        return ninja.trek.mc.goldgolem.OreMiningMode.ALWAYS;
    }

    public void setTerraformingConfig(ninja.trek.mc.goldgolem.terraforming.TerraformingDefinition def, BlockPos startPos) {
        // Ensure we're in TERRAFORMING mode with an active strategy
        if (activeStrategy == null || !(activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TerraformingBuildStrategy)) {
            setBuildMode(BuildMode.TERRAFORMING);
            initializeStrategyForCurrentMode();
        }
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TerraformingBuildStrategy terraformingStrategy) {
            terraformingStrategy.setConfig(def, startPos);
        }
    }

    public void setTerraformingScanRadius(int radius) {
        this.terraformingScanRadius = Math.max(1, Math.min(5, radius));
    }

    public int getTerraformingScanRadius() {
        return terraformingScanRadius;
    }

    public void setTerraformingAlpha(int alpha) {
        this.terraformingAlpha = Math.max(1, Math.min(10, alpha));
        // Regenerate shell in strategy if already initialized
        if (activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.TerraformingBuildStrategy terraformingStrategy) {
            terraformingStrategy.rebuildShell();
        }
    }

    public int getTerraformingAlpha() {
        return terraformingAlpha;
    }

    public GoldGolemEntity(EntityType<? extends PathfinderMob> type, Level world) {
        super(type, world);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(LEFT_HAND_ANIMATION_TICK, -1);
        builder.define(RIGHT_HAND_ANIMATION_TICK, -1);
        builder.define(LEFT_ARM_HAS_TARGET, false);
        builder.define(RIGHT_ARM_HAS_TARGET, false);
        builder.define(LEFT_HAND_TARGET_POS, Optional.empty());
        builder.define(RIGHT_HAND_TARGET_POS, Optional.empty());
        builder.define(LEFT_HAND_NEXT_POS, Optional.empty());
        builder.define(RIGHT_HAND_NEXT_POS, Optional.empty());
        builder.define(BUILDING_PATHS, false);
        builder.define(BUILD_MODE, BuildMode.PATH.ordinal());
        builder.define(LEFT_MINING_TOOL, ItemStack.EMPTY);
        builder.define(RIGHT_MINING_TOOL, ItemStack.EMPTY);
    }

    // UUID conversion helpers for NBT (still used by mining mode)
    private static int[] uuidToIntArray(java.util.UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new int[]{
            (int)(most >> 32),
            (int)most,
            (int)(least >> 32),
            (int)least
        };
    }

    private static java.util.UUID intArrayToUuid(int[] array) {
        long most = ((long)array[0] << 32) | (array[1] & 0xFFFFFFFFL);
        long least = ((long)array[2] << 32) | (array[3] & 0xFFFFFFFFL);
        return new java.util.UUID(most, least);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AttributeSupplier.builder()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MAX_ABSORPTION, 0.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 0.0)
                .add(Attributes.ARMOR_TOUGHNESS, 0.0)
                .add(Attributes.WAYPOINT_TRANSMIT_RANGE, 0.0)
                .add(Attributes.STEP_HEIGHT, 0.6)
                .add(Attributes.WATER_MOVEMENT_EFFICIENCY, 1.0)
                .add(Attributes.MOVEMENT_EFFICIENCY, 1.0)
                .add(Attributes.GRAVITY, 0.08)
                .add(Attributes.SAFE_FALL_DISTANCE, 128.0)
                .add(Attributes.FALL_DAMAGE_MULTIPLIER, 0.0)
                .add(Attributes.JUMP_STRENGTH, 0.42)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.15)
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 0.15)
                .add(Attributes.BURNING_TIME, 10f)
                .add(Attributes.SCALE, 1.0);
    }

    @Override
    protected void registerGoals() {
        // Follow players holding gold nuggets (approach within 1.5 blocks)
        this.goalSelector.addGoal(3, new FollowGoldNuggetHolderGoal(this, 1.1, 1.75));
        this.goalSelector.addGoal(5, new PathingAwareWanderGoal(this, 0.8));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    protected Component getTypeName() {
        return Component.translatable("entity.gold_golem.gold_golem");
    }

    @Override
    public void tick() {
        super.tick();

        // Decrement owner cache counter
        if (ownerCacheTicksRemaining > 0) {
            ownerCacheTicksRemaining--;
        }

        // Update wheel rotation based on movement (both client and server for smooth animation)
        double wheelDx = this.getX() - prevX;
        double wheelDz = this.getZ() - prevZ;
        double distanceTraveled = Math.sqrt(wheelDx * wheelDx + wheelDz * wheelDz);
        // Rotate wheels based on distance traveled (assuming wheel radius of ~0.5 blocks)
        wheelRotation += distanceTraveled * 2.0; // 2.0 = 1/(π*radius) approximately for visual effect
        wheelRotation %= (Math.PI * 2.0); // Keep rotation within 0-2π
        prevX = this.getX();
        prevZ = this.getZ();

        // Update hand animation ticks (both client and server)
        // Read from data tracker
        leftHandAnimationTick = getLeftHandAnimationTick();
        rightHandAnimationTick = getRightHandAnimationTick();

        if (this.level().isClientSide()) {
            updateClientHandTargetsFromTracker();
        }

        // Read buildingPaths from data tracker BEFORE using it for animation decisions
        buildingPaths = isBuildingPaths();

        // Determine which animation system to use
        boolean leftAnimating = (leftHandAnimationTick >= 0 && (leftArmTargetBlock != null || this.entityData.get(LEFT_ARM_HAS_TARGET)));
        boolean rightAnimating = (rightHandAnimationTick >= 0 && (rightArmTargetBlock != null || this.entityData.get(RIGHT_ARM_HAS_TARGET)));
        boolean anyAnimating = leftAnimating || rightAnimating;

        // When building and actively placing blocks, use block placement animation
        // Otherwise use walking animation when moving
        if (buildingPaths && anyAnimating) {
            // Block placement animation - update arms/eyes based on targets
            updateArmAndEyePositions();
        } else if (distanceTraveled > 0.001) {
            // Walking animation (whether building or not, if not actively placing)
            if (armSwingTimer <= 0) {
                leftArmTarget = ARM_SWING_MIN_ANGLE + this.getRandom().nextFloat() * (ARM_SWING_MAX_ANGLE - ARM_SWING_MIN_ANGLE);
                rightArmTarget = ARM_SWING_MIN_ANGLE + this.getRandom().nextFloat() * (ARM_SWING_MAX_ANGLE - ARM_SWING_MIN_ANGLE);
                if (leftArmRotation >= 0) {
                    leftArmTarget = -leftArmTarget;
                } else {
                    rightArmTarget = -rightArmTarget;
                }
                armSwingTimer = ARM_SWING_DURATION_TICKS;
            }
            float progress = 1.0f - (armSwingTimer / (float) ARM_SWING_DURATION_TICKS);
            float prevLeftTarget = -leftArmTarget;
            float prevRightTarget = -rightArmTarget;
            leftArmRotation = Mth.lerp(progress, prevLeftTarget, leftArmTarget);
            rightArmRotation = Mth.lerp(progress, prevRightTarget, rightArmTarget);
            // Reset yaw to forward during walking
            leftArmYaw = Mth.lerp(0.2f, leftArmYaw, 0.0f);
            rightArmYaw = Mth.lerp(0.2f, rightArmYaw, 0.0f);
            armSwingTimer--;
            // Update eyes randomly when not placing blocks
            updateRandomEyeMovement();
        } else {
            // Idle - return arms to neutral
            leftArmRotation = Mth.lerp(0.1f, leftArmRotation, 0.0f);
            rightArmRotation = Mth.lerp(0.1f, rightArmRotation, 0.0f);
            leftArmYaw = Mth.lerp(0.1f, leftArmYaw, 0.0f);
            rightArmYaw = Mth.lerp(0.1f, rightArmYaw, 0.0f);
            armSwingTimer = 0;
            // Update eyes randomly when idle
            updateRandomEyeMovement();
        }

        if (this.level().isClientSide()) return;
        tickFireResistancePotion();
        tickResourceWaitReturn();
        if (buildingPaths) {
            // Increment placement tick counter (2-tick cycle)
            placementTickCounter = (placementTickCounter + 1) % 2;

            // Initialize strategy if needed
            if (activeStrategy == null || activeStrategy.getMode() != getBuildMode()) {
                initializeStrategyForCurrentMode();
            }

            // Use strategy for building logic
            if (activeStrategy != null) {
                Player owner = null;
                if (activeStrategy.usesPlayerTracking()) {
                    owner = getOwnerPlayer();
                    if (owner != null) {
                        this.getLookControl().setLookAt(owner, 30.0f, 30.0f);
                    }
                }
                activeStrategy.tick(this, owner);

                // Check if strategy has completed its work
                if (activeStrategy.isComplete()) {
                    stopBuilding();
                }
            }
        }

        advanceHandAnimationTicks();
    }

    private void tickFireResistancePotion() {
        if (this.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) {
            fireHazardCheckCooldown = 0;
            return;
        }
        if (fireHazardCheckCooldown-- > 0) return;
        fireHazardCheckCooldown = FIRE_HAZARD_CHECK_INTERVAL_TICKS - 1;

        int potionSlot = GoldGolemFireSafety.findFireResistancePotionSlot(inventory);
        if (potionSlot < 0 || !hasNearbyFireOrLava()) return;

        ItemStack potion = inventory.getItem(potionSlot);
        if (GoldGolemFireSafety.isSplashPotion(potion)) {
            throwSplashPotionAtFeet(potionSlot, potion);
            return;
        }

        ItemStack remainder = potion.finishUsingItem(this.level(), this);
        inventory.setItem(potionSlot, remainder);
        this.playSound(SoundEvents.GENERIC_DRINK.value(), 1.0f, 0.9f + this.getRandom().nextFloat() * 0.1f);
    }

    private void throwSplashPotionAtFeet(int potionSlot, ItemStack potion) {
        ServerLevel serverLevel = (ServerLevel) this.level();
        ItemStack thrownPotion = potion.copyWithCount(1);
        ThrownSplashPotion projectile = new ThrownSplashPotion(serverLevel, this, thrownPotion);
        Projectile.spawnProjectileUsingShoot(
                projectile, serverLevel, thrownPotion, 0.0, -1.0, 0.0, 0.5f, 0.0f);

        potion.shrink(1);
        inventory.setItem(potionSlot, potion);
        this.playSound(
                SoundEvents.SPLASH_POTION_THROW,
                0.5f,
                0.8f + this.getRandom().nextFloat() * 0.4f);
    }

    private boolean hasNearbyFireOrLava() {
        var area = this.getBoundingBox().inflate(1.0);
        int minX = Mth.floor(area.minX);
        int minY = Mth.floor(area.minY);
        int minZ = Mth.floor(area.minZ);
        int maxX = Mth.floor(area.maxX);
        int maxY = Mth.floor(area.maxY);
        int maxZ = Mth.floor(area.maxZ);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (this.level().hasChunkAt(cursor)
                            && GoldGolemFireSafety.isFireOrLava(this.level().getBlockState(cursor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void tickResourceWaitReturn() {
        if (!getBuildMode().returnsToBuildStartWhenOutOfBlocks()
                || buildingPaths || !isWaitingForResources() || resourceWaitAnchor == null) return;

        double targetX = resourceWaitAnchor.getX() + 0.5;
        double targetZ = resourceWaitAnchor.getZ() + 0.5;
        double dx = this.getX() - targetX;
        double dz = this.getZ() - targetZ;
        if (dx * dx + dz * dz <= 16.0 || !this.getNavigation().isDone()) return;

        // Retry periodically if terrain changes or the first path search fails.
        if (this.tickCount % 20 == 0) {
            this.getNavigation().moveTo(targetX, resourceWaitAnchor.getY(), targetZ, 0.8);
        }
    }

    private void updateRandomEyeMovement() {
        // Update eye look directions randomly every 5-10 ticks
        if (eyeUpdateCooldown <= 0) {
            // Random look direction for left eye (within a reasonable range)
            leftEyeYaw = (this.getRandom().nextFloat() - 0.5f) * 120.0f; // ±60 degrees from center
            leftEyePitch = (this.getRandom().nextFloat() - 0.5f) * 60.0f; // ±30 degrees from center

            // Random look direction for right eye (independent)
            rightEyeYaw = (this.getRandom().nextFloat() - 0.5f) * 120.0f;
            rightEyePitch = (this.getRandom().nextFloat() - 0.5f) * 60.0f;

            // Set next update time
            eyeUpdateCooldown = EYE_UPDATE_COOLDOWN_MIN + this.getRandom().nextInt(EYE_UPDATE_COOLDOWN_MAX - EYE_UPDATE_COOLDOWN_MIN + 1);
        } else {
            eyeUpdateCooldown--;
        }
    }

    private void updateArmAndEyePositions() {
        float bodyYawRad = (float) Math.toRadians(this.getVisualRotationYInDegrees());

        // Update left arm and eye
        if (leftArmTargetBlock != null) {
            // SERVER: Has exact block position, calculate precise angle
            Vec3 armPos = new Vec3(this.getX() - 0.3, this.getY() + 1.0, this.getZ()); // Left arm position (approx)
            Vec3 targetPos = leftArmTargetBlock;

            // For later ticks, transition to looking at next block
            if (leftHandAnimationTick >= 6 && nextLeftBlock != null) {
                targetPos = new Vec3(nextLeftBlock.getX() + 0.5, nextLeftBlock.getY() + 0.5, nextLeftBlock.getZ() + 0.5);
            }

            // Calculate direction to target in world space
            double dx = targetPos.x - armPos.x;
            double dy = targetPos.y - armPos.y;
            double dz = targetPos.z - armPos.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            // Calculate world-space yaw to target, then make it relative to body yaw
            // Use -dx because Minecraft yaw convention: 0=South, -90=East, 90=West
            // Add 180° because arm default is down, pitch rotates to backward (-Z), so yaw=180 is forward
            float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            leftArmYaw = worldYaw - this.getVisualRotationYInDegrees() + 180.0f;
            // Normalize to -180 to 180
            while (leftArmYaw > 180) leftArmYaw -= 360;
            while (leftArmYaw < -180) leftArmYaw += 360;

            // Calculate pitch (vertical angle from horizontal)
            float pitch = (float) Math.toDegrees(Math.atan2(dy, horizontalDist));
            // Arm model points DOWN by default (0° = down, 90° = forward/horizontal)
            leftArmRotation = 90.0f + pitch;

            // Update left eye to look at same target (relative to head)
            double eyeDx = targetPos.x - this.getX();
            double eyeDy = targetPos.y - (this.getY() + 1.5); // Eye height approx
            double eyeDz = targetPos.z - this.getZ();
            double eyeHorizontalDist = Math.sqrt(eyeDx * eyeDx + eyeDz * eyeDz);

            leftEyeYaw = (float) Math.toDegrees(Math.atan2(-eyeDx, eyeDz)); // Relative to forward
            leftEyePitch = (float) Math.toDegrees(Math.atan2(-eyeDy, eyeHorizontalDist));
        } else if (leftHandAnimationTick >= 0) {
            // CLIENT: Animation is active but no block position - use default "placing" pose
            leftArmRotation = 70.0f; // Mostly forward, slightly down
            leftArmYaw = 0.0f;       // Straight ahead
            leftEyeYaw = 0.0f;
            leftEyePitch = 15.0f;
        } else {
            // Idle - neutral
            leftArmYaw = 0.0f;
            leftEyeYaw = 0.0f;
            leftEyePitch = 0.0f;
        }

        // Update right arm and eye
        if (rightArmTargetBlock != null) {
            // SERVER: Has exact block position, calculate precise angle
            Vec3 armPos = new Vec3(this.getX() + 0.3, this.getY() + 1.0, this.getZ()); // Right arm position (approx)
            Vec3 targetPos = rightArmTargetBlock;

            // For later ticks, transition to looking at next block
            if (rightHandAnimationTick >= 6 && nextRightBlock != null) {
                targetPos = new Vec3(nextRightBlock.getX() + 0.5, nextRightBlock.getY() + 0.5, nextRightBlock.getZ() + 0.5);
            }

            // Calculate direction to target in world space
            double dx = targetPos.x - armPos.x;
            double dy = targetPos.y - armPos.y;
            double dz = targetPos.z - armPos.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            // Calculate world-space yaw to target, then make it relative to body yaw
            // Use -dx because Minecraft yaw convention: 0=South, -90=East, 90=West
            // Add 180° because arm default is down, pitch rotates to backward (-Z), so yaw=180 is forward
            float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            rightArmYaw = worldYaw - this.getVisualRotationYInDegrees() + 180.0f;
            // Normalize to -180 to 180
            while (rightArmYaw > 180) rightArmYaw -= 360;
            while (rightArmYaw < -180) rightArmYaw += 360;

            // Calculate pitch (vertical angle from horizontal)
            float pitch = (float) Math.toDegrees(Math.atan2(dy, horizontalDist));
            // Arm model points DOWN by default, need 90° to reach horizontal
            rightArmRotation = 90.0f + pitch;

            // Update right eye to look at same target
            double eyeDx = targetPos.x - this.getX();
            double eyeDy = targetPos.y - (this.getY() + 1.5);
            double eyeDz = targetPos.z - this.getZ();
            double eyeHorizontalDist = Math.sqrt(eyeDx * eyeDx + eyeDz * eyeDz);

            rightEyeYaw = (float) Math.toDegrees(Math.atan2(-eyeDx, eyeDz));
            rightEyePitch = (float) Math.toDegrees(Math.atan2(-eyeDy, eyeHorizontalDist));
        } else if (rightHandAnimationTick >= 0) {
            // CLIENT: Animation is active but no block position - use default "placing" pose
            rightArmRotation = 70.0f; // Mostly forward, slightly down
            rightArmYaw = 0.0f;       // Straight ahead
            rightEyeYaw = 0.0f;
            rightEyePitch = 15.0f;
        } else {
            // Idle - neutral
            rightArmYaw = 0.0f;
            rightEyeYaw = 0.0f;
            rightEyePitch = 0.0f;
        }
    }

    private static Vec3 blockCenter(BlockPos pos) {
        return pos == null ? null : new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private void updateClientHandTargetsFromTracker() {
        if (!this.level().isClientSide()) return;

        if (this.entityData.get(LEFT_ARM_HAS_TARGET)) {
            Optional<BlockPos> current = this.entityData.get(LEFT_HAND_TARGET_POS);
            leftArmTargetBlock = current.map(GoldGolemEntity::blockCenter).orElse(null);
            nextLeftBlock = this.entityData.get(LEFT_HAND_NEXT_POS).orElse(null);
        } else {
            leftArmTargetBlock = null;
            nextLeftBlock = null;
        }

        if (this.entityData.get(RIGHT_ARM_HAS_TARGET)) {
            Optional<BlockPos> current = this.entityData.get(RIGHT_HAND_TARGET_POS);
            rightArmTargetBlock = current.map(GoldGolemEntity::blockCenter).orElse(null);
            nextRightBlock = this.entityData.get(RIGHT_HAND_NEXT_POS).orElse(null);
        } else {
            rightArmTargetBlock = null;
            nextRightBlock = null;
        }
    }

    public void beginHandAnimation(boolean isLeft, BlockPos placedBlock, BlockPos previewBlock) {
        if (placedBlock == null) return;
        Vec3 center = blockCenter(placedBlock);

        if (isLeft) {
            leftArmTargetBlock = center;
            nextLeftBlock = previewBlock;
            leftHandAnimationTick = 0;
            leftHandJustActivated = true;
        } else {
            rightArmTargetBlock = center;
            nextRightBlock = previewBlock;
            rightHandAnimationTick = 0;
            rightHandJustActivated = true;
        }

        this.entityData.set(isLeft ? LEFT_HAND_ANIMATION_TICK : RIGHT_HAND_ANIMATION_TICK, 0);
        this.entityData.set(isLeft ? LEFT_ARM_HAS_TARGET : RIGHT_ARM_HAS_TARGET, true);
        this.entityData.set(isLeft ? LEFT_HAND_TARGET_POS : RIGHT_HAND_TARGET_POS, Optional.ofNullable(placedBlock));
        this.entityData.set(isLeft ? LEFT_HAND_NEXT_POS : RIGHT_HAND_NEXT_POS, Optional.ofNullable(previewBlock));
    }

    private void clearHandAnimation(boolean isLeft) {
        if (isLeft) {
            leftArmTargetBlock = null;
            nextLeftBlock = null;
            leftHandAnimationTick = -1;
            leftHandJustActivated = false;
        } else {
            rightArmTargetBlock = null;
            nextRightBlock = null;
            rightHandAnimationTick = -1;
            rightHandJustActivated = false;
        }

        this.entityData.set(isLeft ? LEFT_HAND_ANIMATION_TICK : RIGHT_HAND_ANIMATION_TICK, -1);
        this.entityData.set(isLeft ? LEFT_ARM_HAS_TARGET : RIGHT_ARM_HAS_TARGET, false);
        this.entityData.set(isLeft ? LEFT_HAND_TARGET_POS : RIGHT_HAND_TARGET_POS, Optional.empty());
        this.entityData.set(isLeft ? LEFT_HAND_NEXT_POS : RIGHT_HAND_NEXT_POS, Optional.empty());
    }

    private void advanceHandAnimationTicks() {
        if (this.level().isClientSide()) return;
        advanceHandAnimationTick(true);
        advanceHandAnimationTick(false);
    }

    private void advanceHandAnimationTick(boolean isLeft) {
        int tick = isLeft ? leftHandAnimationTick : rightHandAnimationTick;
        if (tick < 0) return;

        boolean justActivated = isLeft ? leftHandJustActivated : rightHandJustActivated;
        if (justActivated) {
            if (isLeft) {
                leftHandJustActivated = false;
            } else {
                rightHandJustActivated = false;
            }
            return;
        }

        int next = tick + 1;
        // Extended animation duration from 4 to 12 ticks to keep arms/eyes animated during movement
        if (next >= 12) {
            clearHandAnimation(isLeft);
        } else {
            this.entityData.set(isLeft ? LEFT_HAND_ANIMATION_TICK : RIGHT_HAND_ANIMATION_TICK, next);
            if (isLeft) {
                leftHandAnimationTick = next;
            } else {
                rightHandAnimationTick = next;
            }
        }
    }

    public double computeGroundTargetY(Vec3 pos) {
        int bx = Mth.floor(pos.x);
        int bz = Mth.floor(pos.z);
        int y0 = Mth.floor(pos.y);
        var world = this.level();
        Integer groundY = null;
        for (int yy = y0 + 3; yy >= y0 - 8; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isCollisionShapeFullBlock(world, test)) { groundY = yy; break; }
        }
        if (groundY == null) return pos.y;
        // ensure stand space (two blocks of air above ground)
        int ty = groundY + 1;
        for (int up = 0; up <= 3; up++) {
            BlockPos p1 = new BlockPos(bx, ty + up, bz);
            BlockPos p2 = new BlockPos(bx, ty + up + 1, bz);
            var s1 = world.getBlockState(p1);
            var s2 = world.getBlockState(p2);
            boolean passable = s1.isAir() && s2.isAir();
            if (passable) return ty + up;
        }
        return groundY + 1.0;
    }

    /**
     * Teleport the golem to a target position with portal particle effects.
     * Adds a small Y offset (0.1) to prevent clipping into ground blocks.
     */
    public void teleportWithParticles(BlockPos target) {
        if (this.level() instanceof ServerLevel sw) {
            sw.sendParticles(ParticleTypes.PORTAL,
                    this.getX(), this.getY() + 0.5, this.getZ(),
                    40, 0.5, 0.5, 0.5, 0.2);
            sw.sendParticles(ParticleTypes.PORTAL,
                    target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                    40, 0.5, 0.5, 0.5, 0.2);
        }
        // Add small Y offset (0.1) to ensure golem spawns clearly above the floor
        // and doesn't clip into the ground block causing brief suffocation
        this.snapTo(
                target.getX() + 0.5,
                target.getY() + 0.1,
                target.getZ() + 0.5,
                this.getYRot(),
                this.getXRot()
        );
        this.setDeltaMovement(0, 0, 0);  // Clear velocity to prevent unexpected movement
        this.getNavigation().stop();
    }

    // Tower mode logic has been moved to TowerBuildStrategy

    // Tree mode logic has been moved to TreeBuildStrategy

    // Mining methods have been moved to MiningBuildStrategy

    private ItemStack transferToInventory(ItemStack stack, net.minecraft.world.Container targetInv) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

        // Try to merge with existing stacks first
        for (int i = 0; i < targetInv.getContainerSize(); i++) {
            ItemStack targetStack = targetInv.getItem(i);
            if (targetStack.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(stack, targetStack)) {
                int space = targetStack.getMaxStackSize() - targetStack.getCount();
                if (space > 0) {
                    int toTransfer = Math.min(space, stack.getCount());
                    targetStack.setCount(targetStack.getCount() + toTransfer);
                    targetInv.setItem(i, targetStack);
                    stack.shrink(toTransfer);
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                }
            }
        }

        // Place in empty slots
        for (int i = 0; i < targetInv.getContainerSize(); i++) {
            if (targetInv.getItem(i).isEmpty()) {
                targetInv.setItem(i, stack.copy());
                return ItemStack.EMPTY;
            }
        }

        // Chest is full, return remainder
        return stack;
    }

    private boolean shouldMineBlock(BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        return !state.isAir() && state.getDestroySpeed(this.level(), pos) >= 0;
    }

    /**
     * Check if placing a block at the given position would overlap with this golem's bounding box.
     * This prevents the golem from placing blocks inside itself and taking suffocation damage.
     */
    private boolean wouldBlockOverlapSelf(BlockPos pos) {
        var golemBox = this.getBoundingBox();
        // Create a box for the block position (1x1x1 cube)
        var blockBox = new net.minecraft.world.phys.AABB(
            pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0
        );
        return golemBox.intersects(blockBox);
    }

    private boolean isOreBlock(String blockId) {
        return blockId.contains("_ore") || blockId.contains("ancient_debris") ||
               blockId.equals("minecraft:gilded_blackstone");
    }

    private boolean isGravityBlock(net.minecraft.world.level.block.Block block) {
        return block instanceof net.minecraft.world.level.block.FallingBlock;
    }

    private String getBlockIdFromStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
            return null;
        }
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
    }

    private ItemStack findBestTool(BlockState state) {
        ItemStack bestTool = ItemStack.EMPTY;
        float bestSpeed = 1.0f;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) continue;

            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestTool = stack;
            }
        }

        return bestTool;
    }

    private void addToInventory(ItemStack stack) {
        if (stack.isEmpty()) return;

        // Try to merge with existing stacks
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()) continue;

            if (ItemStack.isSameItemSameComponents(stack, slot)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    int toAdd = Math.min(space, stack.getCount());
                    slot.setCount(slot.getCount() + toAdd);
                    inventory.setItem(i, slot);
                    stack.shrink(toAdd);
                    if (stack.isEmpty()) return;
                }
            }
        }

        // Place in empty slots
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack.copy());
                return;
            }
        }

        // Inventory full, drop on ground
        if (this.level() instanceof ServerLevel sw) {
            this.spawnAtLocation(sw, stack);
        }
    }

    public boolean placeBlockFromInventory(BlockPos pos, BlockState state, BlockPos nextPos) {
        boolean placed = placeBlockFromInventory(pos, state, nextPos, leftHandActive);
        if (placed) {
            leftHandActive = !leftHandActive;
        }
        return placed;
    }

    public boolean placeBlockFromInventory(BlockPos pos, BlockState state, BlockPos nextPos, boolean isLeft) {
        return placeBlockFromInventoryWithTemplate(pos, state, state, nextPos, isLeft);
    }

    public boolean placeBlockFromInventoryWithTemplate(BlockPos pos, BlockState templateState, BlockState gradientState, BlockPos nextPos, boolean isLeft) {
        // Determine the final block state to place based on template and gradient
        BlockState finalState = getPlacementStateForBlock(pos, gradientState.getBlock(), templateState, 0, false);
        BlockState replacedState = this.level().getBlockState(pos);

        // Check if block already exists at position
        if (replacedState.equals(finalState)) return true;

        // Prevent placing blocks inside self to avoid suffocation damage
        if (wouldBlockOverlapSelf(pos)) return false;

        boolean replacingDifferentBlock = replacedState.getBlock() != finalState.getBlock();
        if (replacingDifferentBlock) {
            // Consume only when the material changes. Correcting the state of an existing block
            // (for example a connected wall) must not waste an identical inventory block.
            String blockId = BuiltInRegistries.BLOCK.getKey(finalState.getBlock()).toString();
            if (!consumeBlockFromInventory(blockId)) {
                LOGGER.warn("GoldGolem placement failed: missing blockId={} at pos={}", blockId, pos);
                handleMissingBuildingBlock();
                return false;
            }
        }

        this.level().setBlockAndUpdate(pos, finalState);

        if (replacingDifferentBlock && !replacedState.isAir()) {
            // Consuming the replacement first may free the slot needed for the old block.
            // If no room exists, the old material is intentionally discarded.
            tryAddToInventory(new ItemStack(replacedState.getBlock().asItem()));
        }

        // Explicitly update the block state to ensure proper connections (e.g. walls/fences)
        // This fixes issues where simulatePlayerPlacement might miss connections or when replacing blocks
        BlockState placedState = this.level().getBlockState(pos);
        BlockState correctedState = placedState;
        for (Direction dir : Direction.values()) {
            correctedState = correctedState.updateShape(
                this.level(),
                this.level(),
                pos,
                dir,
                pos.relative(dir),
                this.level().getBlockState(pos.relative(dir)),
                this.level().getRandom()
            );
        }
        if (correctedState != placedState) {
            this.level().setBlockAndUpdate(pos, correctedState);
        }

        // Set hand animation with current and next block positions
        beginHandAnimation(isLeft, pos, nextPos);
        return true;
    }

    private boolean tryAddToInventory(ItemStack stack) {
        if (stack.isEmpty()) return false;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(stack, slot)) continue;

            int space = slot.getMaxStackSize() - slot.getCount();
            if (space <= 0) continue;

            int toAdd = Math.min(space, stack.getCount());
            slot.setCount(slot.getCount() + toAdd);
            stack.shrink(toAdd);
            inventory.setItem(i, slot);
            if (stack.isEmpty()) return true;
        }

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) continue;
            inventory.setItem(i, stack.copy());
            return true;
        }

        return false;
    }

    /**
     * Determines the appropriate block state for placement based on template and target block.
     * Three-tier logic:
     * 1. Same block type → copy exact state
     * 2. Same property set → copy all properties
     * 3. Different block family → simulate player placement
     */
    public BlockState getPlacementStateForBlock(BlockPos pos, net.minecraft.world.level.block.Block targetBlock, BlockState templateState, int rotation, boolean mirror) {
        net.minecraft.world.level.block.Block templateBlock = templateState.getBlock();

        // Case A: Exact same block type - copy state directly
        if (templateBlock == targetBlock) {
            BlockState result = templateState;
            result = applyRotationAndMirror(result, rotation, mirror);
            return result;
        }

        // Case B: Different blocks - check if they have the same property set
        java.util.Collection<Property<?>> templatePropsCollection = templateState.getProperties();
        java.util.Collection<Property<?>> targetPropsCollection = targetBlock.defaultBlockState().getProperties();

        java.util.Set<Property<?>> templateProps = new java.util.HashSet<>(templatePropsCollection);
        java.util.Set<Property<?>> targetProps = new java.util.HashSet<>(targetPropsCollection);

        if (templateProps.equals(targetProps)) {
            // Same property set - copy all properties from template
            BlockState result = targetBlock.defaultBlockState();
            for (Property<?> prop : templateProps) {
                result = copyProperty(templateState, result, prop);
            }
            result = applyRotationAndMirror(result, rotation, mirror);
            return result;
        }

        // Case C: Different block family - simulate player placement
        BlockState result = simulatePlayerPlacement(pos, targetBlock);
        result = applyRotationAndMirror(result, rotation, mirror);
        return result;
    }

    /**
     * Safely copies a property value from source to target BlockState.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState copyProperty(BlockState source, BlockState target, Property<T> property) {
        try {
            if (target.hasProperty(property)) {
                T value = source.getValue(property);
                return target.setValue(property, value);
            }
        } catch (Exception e) {
            // Property incompatible or other error - skip silently
        }
        return target;
    }

    /**
     * Applies rotation and mirroring to a block state (used in wall mode).
     */
    private BlockState applyRotationAndMirror(BlockState state, int rotation, boolean mirror) {
        if (rotation == 0 && !mirror) {
            return state;
        }

        try {
            net.minecraft.world.level.block.Rotation blockRotation = switch (rotation & 3) {
                case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
                case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
                case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
                default -> net.minecraft.world.level.block.Rotation.NONE;
            };
            state = state.rotate(blockRotation);
        } catch (Throwable ignored) {}

        try {
            if (mirror) {
                net.minecraft.world.level.block.Mirror blockMirror = net.minecraft.world.level.block.Mirror.LEFT_RIGHT;
                state = state.mirror(blockMirror);
            }
        } catch (Throwable ignored) {}

        return state;
    }

    /**
     * Simulates player placement with deterministic randomness.
     * Uses world seed + position for consistent results.
     */
    private BlockState simulatePlayerPlacement(BlockPos pos, net.minecraft.world.level.block.Block block) {
        // Create deterministic random from world seed + position
        long seed = pos.asLong();
        Level world = this.level();
        if (world instanceof ServerLevel serverWorld) {
            seed = serverWorld.getSeed() + pos.asLong();
        }
        java.util.Random random = new java.util.Random(seed);

        // Pick random horizontal direction (0-3 for N/E/S/W)
        // Direction.NORTH = 2, SOUTH = 0, WEST = 1, EAST = 3
        Direction[] horizontalDirections = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        Direction horizontalFacing = horizontalDirections[random.nextInt(4)];

        // Pick random hit side (0-5 for all directions)
        Direction hitSide = Direction.values()[random.nextInt(6)];

        // Create fake placement context
        BlockPlaceContext context = createFakePlacementContext(pos, horizontalFacing, hitSide);

        // Try to get placement state from block
        BlockState placementState = null;
        try {
            placementState = block.getStateForPlacement(context);
        } catch (Exception e) {
            // Some blocks might throw exceptions - ignore
        }

        // Fall back to default state if placement state is null
        if (placementState == null) {
            placementState = block.defaultBlockState();
        }

        return placementState;
    }

    /**
     * Creates a fake ItemPlacementContext for simulating player placement.
     */
    private BlockPlaceContext createFakePlacementContext(BlockPos pos, Direction horizontalFacing, Direction hitSide) {
        // Create a fake BlockHitResult
        Vec3 hitPos = Vec3.atCenterOf(pos);
        BlockHitResult hitResult = new BlockHitResult(hitPos, hitSide, pos, false);

        // Create ItemPlacementContext
        // The context simulates a player placing a block
        Level world = this.level();
        ItemStack stack = new ItemStack(Items.STONE); // Dummy item, not used by most blocks

        return new BlockPlaceContext(world, null, InteractionHand.MAIN_HAND, stack, hitResult) {
            @Override
            public Direction getHorizontalDirection() {
                return horizontalFacing;
            }

            @Override
            public Direction getNearestLookingDirection() {
                return hitSide;
            }
        };
    }

    private boolean consumeFromShulkerBox(ItemStack shulkerBox, String blockId) {
        // Get the container component from the shulker box
        ItemContainerContents container = shulkerBox.get(DataComponents.CONTAINER);
        if (container == null) return false;

        // Convert stream to list for easier manipulation
        java.util.List<ItemStack> contents = new java.util.ArrayList<>(container.allItemsCopyStream().toList());

        // Search through the shulker box contents
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
                String stackId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                if (stackId.equals(blockId)) {
                    // Decrement this stack and write back an updated immutable container component.
                    ItemStack modifiedStack = stack.copy();
                    modifiedStack.shrink(1);
                    contents.set(i, modifiedStack);

                    shulkerBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
                    return true;
                }
            }
        }
        return false;
    }

    public boolean consumeBlockFromInventory(String blockId) {
        // Search inventory for matching block
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
                String stackId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                if (stackId.equals(blockId)) {
                    stack.shrink(1);
                    return true;
                }
            }
        }

        // If not found in regular inventory, check shulker boxes
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            // Check if this is a shulker box
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                if (consumeFromShulkerBox(stack, blockId)) {
                    return true;
                }
            }
        }

        return false;
    }

    public BlockState getBlockStateFromId(String blockId) {
        try {
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(blockId);
            if (id == null) return null;
            net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
            if (block == null) return null;
            return block.defaultBlockState();
        } catch (Exception e) {
            return null;
        }
    }


    // Terraforming mode logic is now in TerraformingBuildStrategy

    // Persistence: width, gradient, inventory, owner UUID (1.21.10 storage API)
    @Override
    protected void addAdditionalSaveData(ValueOutput view) {
        view.putString("Mode", getBuildMode().name());
        view.putBoolean("BuildingPaths", isBuildingPaths());
        view.putBoolean("WaitingForResources", isWaitingForResources());
        if (buildStartPosition != null) {
            view.putInt("BuildStartX", buildStartPosition.getX());
            view.putInt("BuildStartY", buildStartPosition.getY());
            view.putInt("BuildStartZ", buildStartPosition.getZ());
        }
        if (resourceWaitAnchor != null) {
            view.putInt("ResourceWaitX", resourceWaitAnchor.getX());
            view.putInt("ResourceWaitY", resourceWaitAnchor.getY());
            view.putInt("ResourceWaitZ", resourceWaitAnchor.getZ());
        }
        view.putInt("PathWidth", this.pathWidth);
        view.putFloat("GradWindow", this.gradientWindow);
        view.putFloat("StepWindow", this.stepGradientWindow);
        view.putFloat("FWindow", this.surfaceGradientWindow);
        view.putInt("GradNoiseMain", this.gradientNoiseScaleMain);
        view.putInt("GradNoiseStep", this.gradientNoiseScaleStep);
        view.putInt("FNoiseScale", this.gradientNoiseScaleSurface);

        for (int i = 0; i < 9; i++) {
            String val = gradient[i] == null ? "" : gradient[i];
            view.putString("G" + i, val);
        }
        for (int i = 0; i < 9; i++) {
            String val = stepGradient[i] == null ? "" : stepGradient[i];
            view.putString("S" + i, val);
        }
        for (int i = 0; i < 9; i++) {
            String val = surfaceGradient[i] == null ? "" : surfaceGradient[i];
            view.putString("F" + i, val);
        }

        NonNullList<ItemStack> stacks = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        for (int i = 0; i < INVENTORY_SIZE; i++) stacks.set(i, inventory.getItem(i));
        ContainerHelper.saveAllItems(view.child("Inventory"), stacks, true);

        if (ownerUuid != null) view.putString("Owner", ownerUuid.toString());

        // Wall-mode persisted bits
        if (this.wallOrigin != null) {
            view.putInt("WallOX", this.wallOrigin.getX());
            view.putInt("WallOY", this.wallOrigin.getY());
            view.putInt("WallOZ", this.wallOrigin.getZ());
        }
        if (this.wallJsonFile != null) view.putString("WallJson", this.wallJsonFile);
        if (this.wallUniqueBlockIds != null && !this.wallUniqueBlockIds.isEmpty()) {
            view.putInt("WallUniqCount", this.wallUniqueBlockIds.size());
            for (int i = 0; i < this.wallUniqueBlockIds.size(); i++) {
                view.putString("WallU" + i, this.wallUniqueBlockIds.get(i));
            }
        } else {
            view.putInt("WallUniqCount", 0);
        }
        if (this.wallJoinSignature != null) view.putString("WallJoinSig", this.wallJoinSignature);
        if (this.wallJoinAxis != null) view.putString("WallJoinAxis", this.wallJoinAxis.name());
        view.putInt("WallJoinU", this.wallJoinUSize);
        view.putInt("WallModCount", this.wallModuleCount);
        view.putInt("WallModLongest", this.wallLongestModule);
        view.putBoolean("WallSliceSym", this.wallSliceSymmetric);
        // Join template
        view.putInt("WallJoinTplCount", wallJoinTemplate == null ? 0 : wallJoinTemplate.size());
        for (int i = 0; wallJoinTemplate != null && i < wallJoinTemplate.size(); i++) {
            var e = wallJoinTemplate.get(i);
            view.putInt("WJT_dy" + i, e.dy);
            view.putInt("WJT_du" + i, e.du);
            view.putString("WJT_id" + i, e.id);
        }
        // Wall groups persistence
        view.putInt("WallGroupCount", wallGroupSlots.size());
        for (int g = 0; g < wallGroupSlots.size(); g++) {
            view.putFloat("WallGW" + g, (g < wallGroupWindows.size()) ? wallGroupWindows.get(g) : 1.0f);
            view.putInt("WallGNS" + g, (g < wallGroupNoiseScales.size()) ? wallGroupNoiseScales.get(g) : 1);
            String[] arr = wallGroupSlots.get(g);
            for (int i = 0; i < 9; i++) {
                String v = (arr != null && i < arr.length && arr[i] != null) ? arr[i] : "";
                view.putString("WallGS" + g + "_" + i, v);
            }
        }
        // Mapping for unique ids → group index
        for (int i = 0; i < wallUniqueBlockIds.size(); i++) {
            String id = wallUniqueBlockIds.get(i);
            int grp = wallBlockGroup.getOrDefault(id, 0);
            view.putInt("WallGM" + i, grp);
        }

        // Tower-mode persisted bits
        if (this.towerOrigin != null) {
            view.putInt("TowerOX", this.towerOrigin.getX());
            view.putInt("TowerOY", this.towerOrigin.getY());
            view.putInt("TowerOZ", this.towerOrigin.getZ());
        }
        if (this.towerJsonFile != null) view.putString("TowerJson", this.towerJsonFile);
        view.putInt("TowerHeight", this.towerHeight);
        if (this.towerUniqueBlockIds != null && !this.towerUniqueBlockIds.isEmpty()) {
            view.putInt("TowerUniqCount", this.towerUniqueBlockIds.size());
            for (int i = 0; i < this.towerUniqueBlockIds.size(); i++) {
                view.putString("TowerU" + i, this.towerUniqueBlockIds.get(i));
            }
        } else {
            view.putInt("TowerUniqCount", 0);
        }
        // Tower block counts
        if (this.towerBlockCounts != null && !this.towerBlockCounts.isEmpty()) {
            view.putInt("TowerCountsSize", this.towerBlockCounts.size());
            int idx = 0;
            for (var entry : this.towerBlockCounts.entrySet()) {
                view.putString("TowerC_id" + idx, entry.getKey());
                view.putInt("TowerC_cnt" + idx, entry.getValue());
                idx++;
            }
        } else {
            view.putInt("TowerCountsSize", 0);
        }
        // Tower groups persistence
        view.putInt("TowerGroupCount", towerGroupSlots.size());
        for (int g = 0; g < towerGroupSlots.size(); g++) {
            view.putFloat("TowerGW" + g, (g < towerGroupWindows.size()) ? towerGroupWindows.get(g) : 1.0f);
            view.putInt("TowerGNS" + g, (g < towerGroupNoiseScales.size()) ? towerGroupNoiseScales.get(g) : 1);
            String[] arr = towerGroupSlots.get(g);
            for (int i = 0; i < 9; i++) {
                String v = (arr != null && i < arr.length && arr[i] != null) ? arr[i] : "";
                view.putString("TowerGS" + g + "_" + i, v);
            }
        }
        // Mapping for unique ids → group index
        for (int i = 0; i < towerUniqueBlockIds.size(); i++) {
            String id = towerUniqueBlockIds.get(i);
            int grp = towerBlockGroup.getOrDefault(id, 0);
            view.putInt("TowerGM" + i, grp);
        }

        // Strategy state persisted via polymorphic dispatch (Mining, Excavation modes)
        if (activeStrategy != null) {
            activeStrategy.writeLegacyNbt(view);
        }

        // Terraforming-mode UI settings (remain in entity)
        view.putInt("TFormScanRadius", this.terraformingScanRadius);
        view.putInt("TFormAlpha", this.terraformingAlpha);
        // Terraforming gradients
        for (int i = 0; i < 9; i++) {
            String v = (terraformingGradientVertical != null && i < terraformingGradientVertical.length && terraformingGradientVertical[i] != null) ? terraformingGradientVertical[i] : "";
            view.putString("TFormGV" + i, v);
        }
        for (int i = 0; i < 9; i++) {
            String h = (terraformingGradientHorizontal != null && i < terraformingGradientHorizontal.length && terraformingGradientHorizontal[i] != null) ? terraformingGradientHorizontal[i] : "";
            view.putString("TFormGH" + i, h);
        }
        for (int i = 0; i < 9; i++) {
            String s = (terraformingGradientSloped != null && i < terraformingGradientSloped.length && terraformingGradientSloped[i] != null) ? terraformingGradientSloped[i] : "";
            view.putString("TFormGS" + i, s);
        }
        view.putInt("TFormGVWindow", this.terraformingGradientVerticalWindow);
        view.putInt("TFormGHWindow", this.terraformingGradientHorizontalWindow);
        view.putInt("TFormGSWindow", this.terraformingGradientSlopedWindow);
        view.putInt("TFormGVScale", this.terraformingGradientVerticalScale);
        view.putInt("TFormGHScale", this.terraformingGradientHorizontalScale);
        view.putInt("TFormGSScale", this.terraformingGradientSlopedScale);

        // Note: Terraforming state is now written via activeStrategy.writeLegacyNbt(view) above

        // Tree-mode persisted bits
        if (this.treeOrigin != null) {
            view.putInt("TreeOX", this.treeOrigin.getX());
            view.putInt("TreeOY", this.treeOrigin.getY());
            view.putInt("TreeOZ", this.treeOrigin.getZ());
        }
        if (this.treeJsonFile != null) view.putString("TreeJson", this.treeJsonFile);
        if (this.treeGroundBlockId != null) view.putString("TreeGroundId", this.treeGroundBlockId);
        view.putInt("TreeTilingPreset", this.treeTilingPreset.ordinal());
        // Note: TreeWaitingForInventory is now written via activeStrategy.writeLegacyNbt(view) above

        // Tree unique block IDs
        if (this.treeUniqueBlockIds != null && !this.treeUniqueBlockIds.isEmpty()) {
            view.putInt("TreeUniqCount", this.treeUniqueBlockIds.size());
            for (int i = 0; i < this.treeUniqueBlockIds.size(); i++) {
                view.putString("TreeU" + i, this.treeUniqueBlockIds.get(i));
            }
        } else {
            view.putInt("TreeUniqCount", 0);
        }

        // Tree modules (store voxel positions)
        view.putInt("TreeModuleCount", this.treeModules.size());
        for (int m = 0; m < this.treeModules.size(); m++) {
            ninja.trek.mc.goldgolem.tree.TreeModule module = this.treeModules.get(m);
            view.putInt("TreeMod" + m + "Size", module.voxels.size());
            int vIdx = 0;
            for (net.minecraft.core.BlockPos pos : module.voxels) {
                view.putInt("TreeMod" + m + "V" + vIdx + "X", pos.getX());
                view.putInt("TreeMod" + m + "V" + vIdx + "Y", pos.getY());
                view.putInt("TreeMod" + m + "V" + vIdx + "Z", pos.getZ());
                vIdx++;
            }
        }

        // Tree groups persistence
        view.putInt("TreeGroupCount", treeGroupSlots.size());
        for (int g = 0; g < treeGroupSlots.size(); g++) {
            view.putFloat("TreeGW" + g, (g < treeGroupWindows.size()) ? treeGroupWindows.get(g) : 1.0f);
            view.putInt("TreeGNS" + g, (g < treeGroupNoiseScales.size()) ? treeGroupNoiseScales.get(g) : 1);
            String[] arr = treeGroupSlots.get(g);
            for (int i = 0; i < 9; i++) {
                String v = (arr != null && i < arr.length && arr[i] != null) ? arr[i] : "";
                view.putString("TreeGS" + g + "_" + i, v);
            }
        }
        // Mapping for unique ids → group index
        for (int i = 0; i < treeUniqueBlockIds.size(); i++) {
            String id = treeUniqueBlockIds.get(i);
            int grp = treeBlockGroup.getOrDefault(id, 0);
            view.putInt("TreeGM" + i, grp);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput view) {
        String mode = view.getStringOr("Mode", BuildMode.PATH.name());
        try {
            setBuildMode(BuildMode.valueOf(mode));
        } catch (IllegalArgumentException ex) {
            setBuildMode(BuildMode.PATH);
        }
        // Restore building state (after mode is set)
        boolean wasBuildingPaths = view.getBooleanOr("BuildingPaths", false);
        boolean wasWaitingForResources = view.getBooleanOr("WaitingForResources", false);
        if (view.contains("BuildStartX")) {
            this.buildStartPosition = new BlockPos(
                    view.getIntOr("BuildStartX", 0),
                    view.getIntOr("BuildStartY", 0),
                    view.getIntOr("BuildStartZ", 0));
        } else {
            this.buildStartPosition = null;
        }
        if (view.contains("ResourceWaitX")) {
            this.resourceWaitAnchor = new BlockPos(
                    view.getIntOr("ResourceWaitX", 0),
                    view.getIntOr("ResourceWaitY", 0),
                    view.getIntOr("ResourceWaitZ", 0));
        } else {
            this.resourceWaitAnchor = null;
        }
        this.pathWidth = Math.max(1, Math.min(9, view.getIntOr("PathWidth", this.pathWidth)));
        this.gradientWindow = Math.max(0.0f, Math.min(9.0f, view.getFloatOr("GradWindow", this.gradientWindow)));
        this.stepGradientWindow = Math.max(0.0f, Math.min(9.0f, view.getFloatOr("StepWindow", this.stepGradientWindow)));
        this.surfaceGradientWindow = Math.max(0.0f, Math.min(9.0f, view.getFloatOr("FWindow", this.surfaceGradientWindow)));
        int legacyScale = view.getIntOr("GradNoiseScale", 1);
        this.gradientNoiseScaleMain = Math.max(1, Math.min(16, view.getIntOr("GradNoiseMain", legacyScale)));
        this.gradientNoiseScaleStep = Math.max(1, Math.min(16, view.getIntOr("GradNoiseStep", legacyScale)));
        this.gradientNoiseScaleSurface = Math.max(1, Math.min(16, view.getIntOr("FNoiseScale", 1)));

        for (int i = 0; i < 9; i++) {
            gradient[i] = view.getStringOr("G" + i, "");
        }
        gradientCopyDirty = true; // Invalidate cache after loading from NBT
        for (int i = 0; i < 9; i++) {
            stepGradient[i] = view.getStringOr("S" + i, "");
        }
        for (int i = 0; i < 9; i++) {
            surfaceGradient[i] = view.getStringOr("F" + i, "");
        }

        NonNullList<ItemStack> stacks = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(view.childOrEmpty("Inventory"), stacks);
        for (int i = 0; i < INVENTORY_SIZE; i++) inventory.setItem(i, stacks.get(i));

        var ownerOpt = view.getString("Owner");
        this.ownerUuid = ownerOpt.isPresent() && !ownerOpt.get().isEmpty() ? java.util.UUID.fromString(ownerOpt.get()) : null;

        // Wall-mode bits
        if (view.contains("WallOX")) {
            this.wallOrigin = new net.minecraft.core.BlockPos(view.getIntOr("WallOX", 0), view.getIntOr("WallOY", 0), view.getIntOr("WallOZ", 0));
        } else {
            this.wallOrigin = null;
        }
        this.wallJsonFile = view.getStringOr("WallJson", null);
        int c = view.getIntOr("WallUniqCount", 0);
        if (c > 0) {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>(c);
            for (int i = 0; i < c; i++) ids.add(view.getStringOr("WallU" + i, ""));
            this.wallUniqueBlockIds = ids;
        } else {
            this.wallUniqueBlockIds = java.util.Collections.emptyList();
        }
        this.wallJoinSignature = view.getStringOr("WallJoinSig", null);
        String a = view.getStringOr("WallJoinAxis", null);
        if (a != null) { try { this.wallJoinAxis = ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.valueOf(a); } catch (IllegalArgumentException ignored) {} }
        this.wallJoinUSize = Math.max(1, view.getIntOr("WallJoinU", 1));
        this.wallModuleCount = view.getIntOr("WallModCount", 0);
        this.wallLongestModule = view.getIntOr("WallModLongest", 0);
        this.wallSliceSymmetric = view.getBooleanOr("WallSliceSym", true);
        int jt = view.getIntOr("WallJoinTplCount", 0);
        if (jt > 0) {
            java.util.ArrayList<ninja.trek.mc.goldgolem.world.entity.strategy.wall.JoinEntry> list = new java.util.ArrayList<>(jt);
            for (int i = 0; i < jt; i++) {
                int dy = view.getIntOr("WJT_dy" + i, 0);
                int du = view.getIntOr("WJT_du" + i, 0);
                String id = view.getStringOr("WJT_id" + i, "");
                list.add(new ninja.trek.mc.goldgolem.world.entity.strategy.wall.JoinEntry(dy, du, id));
            }
            this.wallJoinTemplate = list;
        } else {
            this.wallJoinTemplate = java.util.Collections.emptyList();
        }
        // Wall groups
        wallGroupSlots.clear(); wallGroupWindows.clear(); wallGroupNoiseScales.clear(); wallBlockGroup.clear();
        int gc = view.getIntOr("WallGroupCount", 0);
        for (int g = 0; g < gc; g++) {
            float w = view.getFloatOr("WallGW" + g, 1.0f);
            wallGroupWindows.add(Math.max(0.0f, Math.min(9.0f, w)));
            int ns = view.getIntOr("WallGNS" + g, 1);
            wallGroupNoiseScales.add(Math.max(1, Math.min(16, ns)));
            String[] arr = new String[9];
            for (int i = 0; i < 9; i++) arr[i] = view.getStringOr("WallGS" + g + "_" + i, "");
            wallGroupSlots.add(arr);
        }
        for (int i = 0; i < wallUniqueBlockIds.size(); i++) {
            int grp = view.getIntOr("WallGM" + i, 0);
            String id = wallUniqueBlockIds.get(i);
            wallBlockGroup.put(id, Math.max(0, Math.min(Math.max(0, wallGroupSlots.size() - 1), grp)));
        }

        // Tower-mode bits
        if (view.contains("TowerOX")) {
            this.towerOrigin = new net.minecraft.core.BlockPos(view.getIntOr("TowerOX", 0), view.getIntOr("TowerOY", 0), view.getIntOr("TowerOZ", 0));
        } else {
            this.towerOrigin = null;
        }
        this.towerJsonFile = view.getStringOr("TowerJson", null);
        this.towerHeight = view.getIntOr("TowerHeight", 0);
        int tc = view.getIntOr("TowerUniqCount", 0);
        if (tc > 0) {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>(tc);
            for (int i = 0; i < tc; i++) ids.add(view.getStringOr("TowerU" + i, ""));
            this.towerUniqueBlockIds = ids;
        } else {
            this.towerUniqueBlockIds = java.util.Collections.emptyList();
        }
        // Tower block counts
        int tcs = view.getIntOr("TowerCountsSize", 0);
        if (tcs > 0) {
            java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
            for (int i = 0; i < tcs; i++) {
                String id = view.getStringOr("TowerC_id" + i, "");
                int cnt = view.getIntOr("TowerC_cnt" + i, 0);
                if (!id.isEmpty()) counts.put(id, cnt);
            }
            this.towerBlockCounts = counts;
        } else {
            this.towerBlockCounts = java.util.Collections.emptyMap();
        }
        // Tower groups
        towerGroupSlots.clear(); towerGroupWindows.clear(); towerGroupNoiseScales.clear(); towerBlockGroup.clear();
        int tgc = view.getIntOr("TowerGroupCount", 0);
        for (int g = 0; g < tgc; g++) {
            float w = view.getFloatOr("TowerGW" + g, 1.0f);
            towerGroupWindows.add(Math.max(0.0f, Math.min(9.0f, w)));
            int ns = view.getIntOr("TowerGNS" + g, 1);
            towerGroupNoiseScales.add(Math.max(1, Math.min(16, ns)));
            String[] arr = new String[9];
            for (int i = 0; i < 9; i++) arr[i] = view.getStringOr("TowerGS" + g + "_" + i, "");
            towerGroupSlots.add(arr);
        }
        for (int i = 0; i < towerUniqueBlockIds.size(); i++) {
            int grp = view.getIntOr("TowerGM" + i, 0);
            String id = towerUniqueBlockIds.get(i);
            towerBlockGroup.put(id, Math.max(0, Math.min(Math.max(0, towerGroupSlots.size() - 1), grp)));
        }

        // Strategy state loaded via polymorphic dispatch (Mining, Excavation, Terraforming, Tree modes)
        initializeStrategyForCurrentMode();
        if (activeStrategy != null) {
            activeStrategy.readLegacyNbt(view);
            activeStrategy.setWaitingForResources(wasWaitingForResources);
        }

        // Terraforming-mode UI settings (remain in entity)
        this.terraformingScanRadius = view.getIntOr("TFormScanRadius", 2);
        this.terraformingAlpha = view.getIntOr("TFormAlpha", 3);
        // Terraforming gradients
        for (int i = 0; i < 9; i++) {
            terraformingGradientVertical[i] = view.getStringOr("TFormGV" + i, "");
        }
        for (int i = 0; i < 9; i++) {
            terraformingGradientHorizontal[i] = view.getStringOr("TFormGH" + i, "");
        }
        for (int i = 0; i < 9; i++) {
            terraformingGradientSloped[i] = view.getStringOr("TFormGS" + i, "");
        }
        this.terraformingGradientVerticalWindow = view.getIntOr("TFormGVWindow", 1);
        this.terraformingGradientHorizontalWindow = view.getIntOr("TFormGHWindow", 1);
        this.terraformingGradientSlopedWindow = view.getIntOr("TFormGSWindow", 1);
        this.terraformingGradientVerticalScale = Math.max(1, Math.min(16, view.getIntOr("TFormGVScale", 1)));
        this.terraformingGradientHorizontalScale = Math.max(1, Math.min(16, view.getIntOr("TFormGHScale", 1)));
        this.terraformingGradientSlopedScale = Math.max(1, Math.min(16, view.getIntOr("TFormGSScale", 1)));

        // Note: Terraforming state is now read via activeStrategy.readLegacyNbt(view) above

        // Tree-mode persisted bits
        if (view.contains("TreeOX")) {
            int x = view.getIntOr("TreeOX", 0);
            int y = view.getIntOr("TreeOY", 0);
            int z = view.getIntOr("TreeOZ", 0);
            this.treeOrigin = new net.minecraft.core.BlockPos(x, y, z);
        }
        this.treeJsonFile = view.getString("TreeJson").orElse(null);
        this.treeGroundBlockId = view.getString("TreeGroundId").orElse(null);
        int presetOrdinal = view.getIntOr("TreeTilingPreset", 0);
        this.treeTilingPreset = ninja.trek.mc.goldgolem.tree.TilingPreset.fromOrdinal(presetOrdinal);
        // Tree state is deserialized by strategy (if active)
        // Note: treeWaitingForInventory is transient - strategy will rebuild state when building resumes

        // Tree unique block IDs
        int treeUniqCount = view.getIntOr("TreeUniqCount", 0);
        if (treeUniqCount > 0) {
            this.treeUniqueBlockIds = new java.util.ArrayList<>();
            for (int i = 0; i < treeUniqCount; i++) {
                String id = view.getStringOr("TreeU" + i, "");
                if (!id.isEmpty()) {
                    this.treeUniqueBlockIds.add(id);
                }
            }
        } else {
            this.treeUniqueBlockIds = java.util.Collections.emptyList();
        }

        // Tree modules
        int treeModCount = view.getIntOr("TreeModuleCount", 0);
        if (treeModCount > 0) {
            this.treeModules = new java.util.ArrayList<>();
            for (int m = 0; m < treeModCount; m++) {
                int voxelSize = view.getIntOr("TreeMod" + m + "Size", 0);
                java.util.Set<net.minecraft.core.BlockPos> voxels = new java.util.HashSet<>();
                for (int v = 0; v < voxelSize; v++) {
                    int vx = view.getIntOr("TreeMod" + m + "V" + v + "X", 0);
                    int vy = view.getIntOr("TreeMod" + m + "V" + v + "Y", 0);
                    int vz = view.getIntOr("TreeMod" + m + "V" + v + "Z", 0);
                    voxels.add(new net.minecraft.core.BlockPos(vx, vy, vz));
                }
                if (!voxels.isEmpty()) {
                    this.treeModules.add(new ninja.trek.mc.goldgolem.tree.TreeModule(voxels));
                }
            }
        } else {
            this.treeModules = java.util.Collections.emptyList();
        }

        // Tree groups persistence
        int treeGroupCount = view.getIntOr("TreeGroupCount", 0);
        this.treeGroupSlots.clear();
        this.treeGroupWindows.clear();
        this.treeGroupNoiseScales.clear();
        this.treeBlockGroup.clear();
        for (int g = 0; g < treeGroupCount; g++) {
            float window = view.getFloatOr("TreeGW" + g, 1.0f);
            this.treeGroupWindows.add(window);
            int ns = view.getIntOr("TreeGNS" + g, 1);
            this.treeGroupNoiseScales.add(Math.max(1, Math.min(16, ns)));
            String[] arr = new String[9];
            for (int i = 0; i < 9; i++) {
                arr[i] = view.getStringOr("TreeGS" + g + "_" + i, "");
            }
            this.treeGroupSlots.add(arr);
        }
        // Restore group mappings
        for (int i = 0; i < treeUniqueBlockIds.size(); i++) {
            String id = treeUniqueBlockIds.get(i);
            int grp = view.getIntOr("TreeGM" + i, 0);
            this.treeBlockGroup.put(id, grp);
        }

        // Note: treeTileCache and treeWFCBuilder are NOT persisted - they will be regenerated when building resumes

        // Restore building state (do this at the end, after all strategy state is loaded)
        if (wasBuildingPaths) {
            setBuildingPaths(true);
        }
    }

    public Container getInventory() { return inventory; }

    public int getPathWidth() { return pathWidth; }
    public void setPathWidth(int width) {
        int w = Math.max(1, Math.min(9, width));
        // Snap to odd widths to keep a center column
        if ((w & 1) == 0) {
            w = (w < 9) ? (w + 1) : (w - 1);
        }
        this.pathWidth = w;
    }
    public float getGradientWindow() { return gradientWindow; }
    public void setGradientWindow(float w) { this.gradientWindow = Math.max(0.0f, Math.min(9.0f, w)); }
    public float getStepGradientWindow() { return stepGradientWindow; }
    public void setStepGradientWindow(float w) { this.stepGradientWindow = Math.max(0.0f, Math.min(9.0f, w)); }
    public float getSurfaceGradientWindow() { return surfaceGradientWindow; }
    public void setSurfaceGradientWindow(float w) { this.surfaceGradientWindow = Math.max(0.0f, Math.min(9.0f, w)); }
    public int getGradientNoiseScaleMain() { return gradientNoiseScaleMain; }
    public int getGradientNoiseScaleStep() { return gradientNoiseScaleStep; }
    public int getGradientNoiseScaleSurface() { return gradientNoiseScaleSurface; }
    public void setGradientNoiseScaleMain(int scale) { this.gradientNoiseScaleMain = Math.max(1, Math.min(16, scale)); }
    public void setGradientNoiseScaleStep(int scale) { this.gradientNoiseScaleStep = Math.max(1, Math.min(16, scale)); }
    public void setGradientNoiseScaleSurface(int scale) { this.gradientNoiseScaleSurface = Math.max(1, Math.min(16, scale)); }
    public int getGradientNoiseScale(int row) { return row == 0 ? gradientNoiseScaleMain : gradientNoiseScaleStep; }
    public void setGradientNoiseScale(int row, int scale) {
        if (row == 0) setGradientNoiseScaleMain(scale);
        else setGradientNoiseScaleStep(scale);
    }

    public double sampleGradientNoise01(BlockPos pos, int scale) {
        return sampleGradientNoise01(pos.getX(), pos.getY(), pos.getZ(), scale);
    }

    public double sampleGradientNoise01(int x, int y, int z, int scale) {
        SimplexNoise sampler = getGradientNoiseSampler();
        double s = (double) Math.max(1, scale);
        double n = sampler.getValue((double) x / s, (double) y / s, (double) z / s);
        double u01 = (n + 1.0) * 0.5;
        if (u01 < 0.0) return 0.0;
        if (u01 > 1.0) return 1.0;
        return u01;
    }

    private SimplexNoise getGradientNoiseSampler() {
        long seed = resolveWorldSeed();
        if (gradientNoiseSampler == null || gradientNoiseSeedCache != seed) {
            gradientNoiseSeedCache = seed;
            gradientNoiseSampler = new SimplexNoise(RandomSource.create(seed));
        }
        return gradientNoiseSampler;
    }

    private long resolveWorldSeed() {
        if (this.level() instanceof ServerLevel sw && sw.getServer() != null) {
            return sw.getSeed();
        }
        return 0L;
    }

    // ========== Shared tracking field accessors (PATH/WALL modes) ==========
    public Vec3 getTrackStart() { return trackStart; }
    public void setTrackStart(Vec3 start) { this.trackStart = start; }

    public java.util.ArrayDeque<ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg> getPendingLines() { return pendingLines; }
    public ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg getCurrentLine() { return currentLine; }
    public void setCurrentLine(ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg line) { this.currentLine = line; }

    /** Queue a position for mining during path mode. */
    public void enqueuePathMine(BlockPos pos) { pathPendingMines.addLast(pos); }
    /** Get the path-mode gradient mining helper. */
    public ninja.trek.mc.goldgolem.world.entity.strategy.GradientMiningHelper getPathGradientMiner() { return pathGradientMiner; }
    /** Get the path-mode pending mine queue. */
    public java.util.ArrayDeque<BlockPos> getPathPendingMines() { return pathPendingMines; }

    /**
     * Record a block position as placed to prevent duplicate placements.
     * @return true if this is a new placement, false if already recorded
     */
    public boolean recordPlaced(long key) {
        synchronized (ringBufferLock) {
            if (recentPlaced.contains(key)) return false;
            if (placedSize == placedRing.length) {
                long old = placedRing[placedHead];
                recentPlaced.remove(old);
                placedRing[placedHead] = key;
                placedHead = (placedHead + 1) % placedRing.length;
            } else {
                placedRing[(placedHead + placedSize) % placedRing.length] = key;
                placedSize++;
            }
            recentPlaced.add(key);
            return true;
        }
    }

    /**
     * Unrecord a block position (best-effort, no-op to avoid thrash).
     */
    public void unrecordPlaced(long key) {
        synchronized (ringBufferLock) {
            // best-effort: keep it recorded to avoid thrash; no-op
        }
    }

    /**
     * Clear placement tracking state.
     */
    public void clearPlacementTracking() {
        synchronized (ringBufferLock) {
            recentPlaced.clear();
            placedHead = 0;
            placedSize = 0;
        }
    }

    /**
     * Check if a block position was recently placed.
     * @return true if the key is in the recent placement tracking
     */
    public boolean wasRecentlyPlaced(long key) {
        synchronized (ringBufferLock) {
            return recentPlaced.contains(key);
        }
    }

    public String[] getGradientCopy() {
        if (gradientCopyDirty || cachedGradientCopy == null) {
            cachedGradientCopy = new String[9];
            for (int i = 0; i < 9; i++) {
                cachedGradientCopy[i] = (gradient[i] == null) ? "" : gradient[i];
            }
            gradientCopyDirty = false;
        }
        return cachedGradientCopy.clone(); // Return clone for safety
    }
    public String[] getStepGradientCopy() {
        String[] copy = new String[9];
        for (int i = 0; i < 9; i++) {
            copy[i] = (stepGradient[i] == null) ? "" : stepGradient[i];
        }
        return copy;
    }
    public void setGradientSlot(int idx, String id) {
        if (idx < 0 || idx >= 9) return;
        String value = (id == null || id.isEmpty()) ? "" : id;
        gradient[idx] = value;
        gradientCopyDirty = true;
    }
    public void setGradient(int idx, String value) {
        if (idx < 0 || idx >= 9) return;
        gradient[idx] = value;
        gradientCopyDirty = true;
    }
    public void setStepGradientSlot(int idx, String id) {
        if (idx < 0 || idx >= 9) return;
        String value = (id == null || id.isEmpty()) ? "" : id;

        stepGradient[idx] = value;
    }
    public String[] getSurfaceGradientCopy() {
        String[] copy = new String[9];
        for (int i = 0; i < 9; i++) {
            copy[i] = (surfaceGradient[i] == null) ? "" : surfaceGradient[i];
        }
        return copy;
    }
    public void setSurfaceGradientSlot(int idx, String id) {
        if (idx < 0 || idx >= 9) return;
        String value = (id == null || id.isEmpty()) ? "" : id;
        surfaceGradient[idx] = value;
    }

    // Terraforming gradient getters/setters
    public String[] getTerraformingGradientVerticalCopy() {
        String[] copy = new String[9];
        for (int i = 0; i < 9; i++) {
            copy[i] = (terraformingGradientVertical[i] == null || terraformingGradientVertical[i].isEmpty()) ? "" : terraformingGradientVertical[i];
        }
        return copy;
    }

    public String[] getTerraformingGradientHorizontalCopy() {
        String[] copy = new String[9];
        for (int i = 0; i < 9; i++) {
            copy[i] = (terraformingGradientHorizontal[i] == null || terraformingGradientHorizontal[i].isEmpty()) ? "" : terraformingGradientHorizontal[i];
        }
        return copy;
    }

    public String[] getTerraformingGradientSlopedCopy() {
        String[] copy = new String[9];
        for (int i = 0; i < 9; i++) {
            copy[i] = (terraformingGradientSloped[i] == null || terraformingGradientSloped[i].isEmpty()) ? "" : terraformingGradientSloped[i];
        }
        return copy;
    }

    public void setTerraformingGradientVerticalSlot(int idx, String id) {
        if (idx < 0 || idx >= 9) return;
        String value = (id == null || id.isEmpty()) ? "" : id;
        terraformingGradientVertical[idx] = value;
    }

    public void setTerraformingGradientHorizontalSlot(int idx, String id) {
        if (idx < 0 || idx >= 9) return;
        String value = (id == null || id.isEmpty()) ? "" : id;
        terraformingGradientHorizontal[idx] = value;
    }

    public void setTerraformingGradientSlopedSlot(int idx, String id) {
        if (idx < 0 || idx >= 9) return;
        String value = (id == null || id.isEmpty()) ? "" : id;
        terraformingGradientSloped[idx] = value;
    }

    public int getTerraformingGradientVerticalWindow() { return terraformingGradientVerticalWindow; }
    public void setTerraformingGradientVerticalWindow(int w) { this.terraformingGradientVerticalWindow = Math.max(0, Math.min(9, w)); }
    public int getTerraformingGradientVerticalScale() { return terraformingGradientVerticalScale; }
    public void setTerraformingGradientVerticalScale(int scale) { this.terraformingGradientVerticalScale = Math.max(1, Math.min(16, scale)); }

    public int getTerraformingGradientHorizontalWindow() { return terraformingGradientHorizontalWindow; }
    public void setTerraformingGradientHorizontalWindow(int w) { this.terraformingGradientHorizontalWindow = Math.max(0, Math.min(9, w)); }
    public int getTerraformingGradientHorizontalScale() { return terraformingGradientHorizontalScale; }
    public void setTerraformingGradientHorizontalScale(int scale) { this.terraformingGradientHorizontalScale = Math.max(1, Math.min(16, scale)); }

    public int getTerraformingGradientSlopedWindow() { return terraformingGradientSlopedWindow; }
    public void setTerraformingGradientSlopedWindow(int w) { this.terraformingGradientSlopedWindow = Math.max(0, Math.min(9, w)); }
    public int getTerraformingGradientSlopedScale() { return terraformingGradientSlopedScale; }
    public void setTerraformingGradientSlopedScale(int scale) { this.terraformingGradientSlopedScale = Math.max(1, Math.min(16, scale)); }

    // Ownership (simple UUID-based)
    private java.util.UUID ownerUuid;
    private java.lang.ref.WeakReference<Player> cachedOwner = null;
    private int ownerCacheTicksRemaining = 0;

    public void setOwner(Player player) { this.ownerUuid = player.getUUID(); }
    public boolean isOwner(Player player) { return ownerUuid != null && player != null && ownerUuid.equals(player.getUUID()); }

    public Player getOwnerPlayer() {
        if (ownerUuid == null) return null;

        // Check cache first
        if (ownerCacheTicksRemaining > 0 && cachedOwner != null) {
            Player cached = cachedOwner.get();
            if (cached != null && cached.getUUID().equals(ownerUuid)) {
                return cached;
            }
        }

        // Cache miss - do lookup
        for (Player p : this.level().players()) {
            if (ownerUuid.equals(p.getUUID())) {
                cachedOwner = new java.lang.ref.WeakReference<>(p);
                ownerCacheTicksRemaining = OWNER_CACHE_DURATION;
                return p;
            }
        }
        return null;
    }

    @Override
    public void setCustomName(Component name) {
        Component prev = this.getCustomName();
        super.setCustomName(name);
        if (suppressSnapshotWrite) {
            return;
        }
        Level world = this.level();
        if (world != null && !world.isClientSide()) {
            String prevText = prev != null ? prev.getString() : null;
            String nextText = name != null ? name.getString() : null;
            if (!Objects.equals(prevText, nextText)) {
                writeSnapshotForName(nextText);
            }
        }
    }

    public void setCustomNameNoSnapshot(Component name) {
        suppressSnapshotWrite = true;
        try {
            super.setCustomName(name);
        } finally {
            suppressSnapshotWrite = false;
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        // Feeding: start building when owner feeds a gold nugget; consume one and show hearts
        var stack = player.getItemInHand(hand);
        if (stack != null && stack.is(net.minecraft.world.item.Items.GOLD_NUGGET)) {
            if (!isOwner(player)) {
                // Claim in singleplayer if prior owner offline
                var server = sp.level().getServer();
                boolean singleplayer = !server.isDedicatedServer();
                boolean ownerOnline = (ownerUuid != null) && (server.getPlayerList().getPlayer(ownerUuid) != null);
                if (singleplayer && !ownerOnline) {
                    setOwner(player);
                } else {
                    sp.sendOverlayMessage(Component.translatable("message.gold_golem.not_owner"));
                    return InteractionResult.FAIL;
                }
            }
            if (!this.level().isClientSide()) {
                // Use polymorphic dispatch for feed interaction
                initializeStrategyForCurrentMode();
                BuildStrategy.FeedResult result = activeStrategy != null
                    ? activeStrategy.handleFeedInteraction(player)
                    : BuildStrategy.FeedResult.NOT_HANDLED;

                switch (result) {
                    case STARTED, RESUMED -> {
                        prepareBuildReturnState(result == BuildStrategy.FeedResult.STARTED);
                        this.buildingPaths = true;
                        this.entityData.set(BUILDING_PATHS, true);
                        if (!player.isCreative()) stack.shrink(1);
                        spawnHearts();
                        if (result == BuildStrategy.FeedResult.RESUMED) {
                            sp.sendOverlayMessage(Component.literal("[Gold Golem] Resuming!"));
                        }
                        // Path/Wall/Tower modes need trackStart initialization
                        if (activeStrategy != null && activeStrategy.usesPlayerTracking()) {
                            // For wall mode RESUMED, resume from last module endpoint
                            Vec3 resumePos = null;
                            if (result == BuildStrategy.FeedResult.RESUMED
                                    && activeStrategy instanceof ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy wall) {
                                resumePos = wall.getResumeTrackStart();
                            }
                            this.trackStart = resumePos != null ? resumePos
                                    : new Vec3(this.getX(), this.getY() + 0.05, this.getZ());
                            var owner = getOwnerPlayer();
                            if (owner instanceof net.minecraft.server.level.ServerPlayer spOwner) {
                                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of(), java.util.Optional.of(this.trackStart), false);
                            }
                            clearPlacementTracking();
                        }
                    }
                    case ALREADY_ACTIVE -> {
                        sp.sendOverlayMessage(Component.literal("[Gold Golem] Already active!"));
                        return InteractionResult.FAIL;
                    }
                    case NOT_HANDLED -> {
                        // Default behavior: just start building
                        prepareBuildReturnState(true);
                        this.buildingPaths = true;
                        this.entityData.set(BUILDING_PATHS, true);
                        if (!player.isCreative()) stack.shrink(1);
                        spawnHearts();
                    }
                }
            }
            return InteractionResult.CONSUME;
        }
        // Otherwise open UI as before (owner only gate)
        if (!isOwner(player)) {
            var server = sp.level().getServer();
            boolean singleplayer = !server.isDedicatedServer();
            boolean ownerOnline = (ownerUuid != null) && (server.getPlayerList().getPlayer(ownerUuid) != null);
            if (singleplayer && !ownerOnline) {
                setOwner(player);
            } else {
                sp.sendOverlayMessage(Component.translatable("message.gold_golem.not_owner"));
                return InteractionResult.FAIL;
            }
        }
        // Stop movement and track GUI viewer
        this.setGuiViewer(player.getUUID());
        this.getNavigation().stop();
        GolemScreens.open(sp, this.getId(), this.inventory);
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel world, net.minecraft.world.damagesource.DamageSource source, float amount) {
        LOGGER.debug("Taking damage - Source: {}, Amount: {}, Type: {}", source.getMsgId(), amount, source.type());

        // Immune to suffocation damage (being inside blocks)
        if (source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)) {
            LOGGER.debug("Blocked suffocation damage (IN_WALL)");
            return false;
        }
        var attacker = source.getEntity();
        if (attacker instanceof Player p && isOwner(p)) {
            boolean ignoreOwnerDamage = source.is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)
                && amount <= 1.0F;
            // Stop building on owner hit; show angry particles; ignore only low (fist) damage
            this.buildingPaths = false;
            this.entityData.set(BUILDING_PATHS, false);

            // Use polymorphic dispatch for owner damage handling
            if (activeStrategy != null) {
                activeStrategy.handleOwnerDamage();
            }

            // Common cleanup for path-tracking modes
            clearBuildReturnState();
            this.trackStart = null;
            this.pendingLines.clear();
            this.currentLine = null;
            // Clear client lines
            if (attacker instanceof net.minecraft.server.level.ServerPlayer spOwner) {
                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of(), java.util.Optional.empty(), false);
            }

            spawnAngry();
            clearPlacementTracking();
            if (ignoreOwnerDamage) {
                LOGGER.debug("Ignored low owner damage (fist attack)");
                return false; // cancel low (fist) damage
            }
        }
        LOGGER.debug("Applying damage - Source: {}, Amount: {}", source.getMsgId(), amount);
        return super.hurtServer(world, source, amount);
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        LOGGER.debug("Died - Cause: {}, Type: {}", source.getMsgId(), source.type());
        super.die(source);
        if (!(this.level() instanceof ServerLevel world)) return;

        // Drop all items from the inventory
        for (int i = 0; i < this.inventory.getContainerSize(); ++i) {
            ItemStack itemStack = this.inventory.getItem(i);
            if (!itemStack.isEmpty()) {
                this.spawnAtLocation(world, itemStack);
                this.inventory.setItem(i, ItemStack.EMPTY);
            }
        }

        String dropName = "";
        Component custom = getCustomName();
        if (custom != null) {
            dropName = custom.getString();
        }
        if (dropName == null || dropName.isBlank()) {
            String jsonName = getCurrentJsonName();
            if (jsonName != null && !jsonName.isBlank()) {
                dropName = jsonName.endsWith(".json") ? jsonName.substring(0, jsonName.length() - 5) : jsonName;
            }
        }
        if (dropName == null || dropName.isBlank()) {
            dropName = "gold_golem";
        }
        Path snapshot = writeSnapshotForName(dropName);
        if (snapshot == null) return;
        String fname = snapshot.getFileName().toString();
        if (fname.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            dropName = fname.substring(0, fname.length() - 5);
        } else {
            dropName = fname;
        }
        ItemStack pumpkin = new ItemStack(Items.CARVED_PUMPKIN);
        pumpkin.set(DataComponents.CUSTOM_NAME, Component.literal(dropName));
        this.spawnAtLocation(world, pumpkin);
    }

    private void spawnHearts() {
        if (this.level() instanceof ServerLevel sw) {
            sw.sendParticles(ParticleTypes.HEART, this.getX(), this.getY() + 1.0, this.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
        }
    }
    private void spawnAngry() {
        if (this.level() instanceof ServerLevel sw) {
            sw.sendParticles(ParticleTypes.ANGRY_VILLAGER, this.getX(), this.getY() + 1.0, this.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
        }
    }
    private void spawnThunderClouds() {
        if (this.level() instanceof ServerLevel sw) {
            sw.sendParticles(ParticleTypes.CLOUD, this.getX(), this.getY() + 1.0, this.getZ(), 12, 0.4, 0.2, 0.4, 0.02);
        }
    }

    public void handleMissingBuildingBlock() {
        if (this.level().isClientSide()) return;
        this.buildingPaths = false;
        this.entityData.set(BUILDING_PATHS, false);
        if (activeStrategy != null) {
            activeStrategy.setWaitingForResources(true);
        }
        this.getNavigation().stop();
        if (getBuildMode().returnsToBuildStartWhenOutOfBlocks()) {
            if (buildStartPosition == null) {
                buildStartPosition = this.blockPosition();
            }
            resourceWaitAnchor = buildStartPosition;
            this.getNavigation().moveTo(
                    resourceWaitAnchor.getX() + 0.5,
                    resourceWaitAnchor.getY(),
                    resourceWaitAnchor.getZ() + 0.5,
                    0.8);
        } else {
            resourceWaitAnchor = null;
        }
        spawnAngry();
        if (activeStrategy != null && activeStrategy.usesPlayerTracking()) {
            this.trackStart = null;
            this.pendingLines.clear();
            this.currentLine = null;
            Player owner = getOwnerPlayer();
            if (owner instanceof net.minecraft.server.level.ServerPlayer spOwner) {
                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of(), java.util.Optional.empty(), false);
            }
        }
    }

    private Vec3 withFloorY(Vec3 pos) {
        var world = this.level();
        int bx = net.minecraft.util.Mth.floor(pos.x);
        int bz = net.minecraft.util.Mth.floor(pos.z);
        int y0 = net.minecraft.util.Mth.floor(pos.y);
        // Search down a small window to find the nearest full-cube ground
        for (int yy = y0 + 1; yy >= y0 - 8; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isCollisionShapeFullBlock(world, test)) {
                return new Vec3(pos.x, yy + 0.05, pos.z);
            }
        }
        // Fallback: just lift slightly
        return new Vec3(pos.x, pos.y + 0.05, pos.z);
    }

    // Place a single offset column at the given center x/z for strip index j
    public void placeOffsetAt(double x, double y, double z, double px, double pz, int stripWidth, int j, boolean xMajor, net.minecraft.core.Direction travelDir) {
        int w = Math.max(1, Math.min(9, stripWidth));
        var world = this.level();
        double ox = x + px * j;
        double oz = z + pz * j;
        int bx = Mth.floor(ox);
        int bz = Mth.floor(oz);
        int y0 = Mth.floor(y);
        Integer groundY = null;
        for (int yy = y0 + 1; yy >= y0 - 6; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isCollisionShapeFullBlock(world, test)) { groundY = yy; break; }
        }
        if (groundY == null) return;
        int gIdx = sampleGradientIndex(w, j, bx, groundY, bz, getGradientNoiseScaleMain());
        if (gIdx < 0) return;
        String id = gradient[gIdx] == null ? "" : gradient[gIdx];
        if (id.isEmpty()) return;

        // Check for mine action in main gradient
        if (ninja.trek.mc.goldgolem.util.GradientSlotUtil.isMineAction(id)) {
            // Mine the surface block at this column
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos rp = new BlockPos(bx, groundY + dy, bz);
                var rs = world.getBlockState(rp);
                if (rs.isAir() || !rs.isCollisionShapeFullBlock(world, rp)) continue;
                BlockPos ap = rp.above();
                var as2 = world.getBlockState(ap);
                if (as2.isCollisionShapeFullBlock(world, ap)) continue;
                enqueuePathMine(rp);
                break;
            }
            return; // don't process surface/step when main is mine
        }

        var ident = net.minecraft.resources.Identifier.tryParse(id);
        if (ident == null) return;
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(ident);
        if (block == null) return;
        // Replace only exposed surface within a 3-block vertical window
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos rp = new BlockPos(bx, groundY + dy, bz);
            var rs = world.getBlockState(rp);
            if (rs.isAir() || !rs.isCollisionShapeFullBlock(world, rp)) continue; // must be solid
            BlockPos ap = rp.above();
            var as = world.getBlockState(ap);
            if (as.isCollisionShapeFullBlock(world, ap)) continue; // not surface if blocked above
            if (rs.is(block)) break; // already desired block at surface
            long key = rp.asLong();
            if (!recordPlaced(key)) break;
            // Prevent placing blocks inside self to avoid suffocation damage
            if (wouldBlockOverlapSelf(rp)) {
                unrecordPlaced(key);
                break;
            }
            if (!consumeBlockFromInventory(id)) {
                unrecordPlaced(key);
                handleMissingBuildingBlock();
                return;
            }
            world.setBlock(rp, block.defaultBlockState(), 3);

            break; // only one placement per column
        }

        // Surface gradient placement (decorations on top of ground surface)
        boolean hasSurfaceSlots = false;
        for (int i = 0; i < surfaceGradient.length; i++) {
            if (surfaceGradient[i] != null && !surfaceGradient[i].isEmpty()) { hasSurfaceSlots = true; break; }
        }
        if (hasSurfaceSlots && groundY != null) {
            Integer topY = null;
            for (int yy = groundY + 4; yy >= groundY - 4; yy--) {
                BlockPos tp = new BlockPos(bx, yy, bz);
                if (world.getBlockState(tp).isCollisionShapeFullBlock(world, tp)) { topY = yy; break; }
            }
            if (topY != null) {
                BlockPos abovePos = new BlockPos(bx, topY + 1, bz);
                BlockState aboveState = world.getBlockState(abovePos);
                if (!aboveState.isCollisionShapeFullBlock(world, abovePos)) {
                    int sIdx = sampleSurfaceGradientIndex(w, j, bx, topY, bz, gradientNoiseScaleSurface);
                    if (sIdx >= 0) {
                        String sid = surfaceGradient[sIdx] == null ? "" : surfaceGradient[sIdx];
                        if (!sid.isEmpty()) {
                            // Surface gradient mine action: shovel special case → dirt path
                            if (ninja.trek.mc.goldgolem.util.GradientSlotUtil.isMineAction(sid)) {
                                BlockPos surfaceBlock = new BlockPos(bx, topY, bz);
                                BlockState surfState = world.getBlockState(surfaceBlock);
                                if (surfState.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || surfState.is(net.minecraft.world.level.block.Blocks.DIRT)) {
                                    world.setBlock(surfaceBlock, net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState(), 3);
                                } else {
                                    // Not grass/dirt: queue for mining
                                    enqueuePathMine(surfaceBlock);
                                }
                            } else {
                                var sIdent = net.minecraft.resources.Identifier.tryParse(sid);
                                if (sIdent != null) {
                                    var sBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(sIdent);
                                    if (sBlock != null) {
                                        long surfKey = abovePos.asLong();
                                        if (recordPlaced(surfKey)) {
                                            if (wouldBlockOverlapSelf(abovePos)) {
                                                unrecordPlaced(surfKey);
                                            } else if (!consumeBlockFromInventory(sid)) {
                                                unrecordPlaced(surfKey);
                                                handleMissingBuildingBlock();
                                                return;
                                            } else {
                                                world.setBlock(abovePos, sBlock.defaultBlockState(), 3);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Step placement in air with neighbor solid along major axis, with headroom
        int yStep = groundY + 1;
        BlockPos stepPos = new BlockPos(bx, yStep, bz);
        var stepState = world.getBlockState(stepPos);
        if (stepState.isAir()) {
            boolean neighborSolid = false;
            if (xMajor) {
                BlockPos n1 = stepPos.west();
                BlockPos n2 = stepPos.east();
                var s1 = world.getBlockState(n1);
                var s2 = world.getBlockState(n2);
                neighborSolid = (!s1.isAir() && s1.isCollisionShapeFullBlock(world, n1)) || (!s2.isAir() && s2.isCollisionShapeFullBlock(world, n2));
            } else {
                BlockPos n1 = stepPos.north();
                BlockPos n2 = stepPos.south();
                var s1 = world.getBlockState(n1);
                var s2 = world.getBlockState(n2);
                neighborSolid = (!s1.isAir() && s1.isCollisionShapeFullBlock(world, n1)) || (!s2.isAir() && s2.isCollisionShapeFullBlock(world, n2));
            }
            if (neighborSolid) {
                BlockPos above = stepPos.above();
                var as = world.getBlockState(above);
                if (!as.isCollisionShapeFullBlock(world, above)) {
                    int gIdxStep = sampleStepGradientIndex(w, j, bx, yStep, bz, getGradientNoiseScaleStep());
                    if (gIdxStep >= 0) {
                        String sid = stepGradient[gIdxStep] == null ? "" : stepGradient[gIdxStep];
                        if (!sid.isEmpty()) {
                            // Step gradient mine action: mine the step position
                            if (ninja.trek.mc.goldgolem.util.GradientSlotUtil.isMineAction(sid)) {
                                // Step is air, mine the block below it (ground)
                                enqueuePathMine(new BlockPos(bx, groundY, bz));
                                return;
                            }
                            var sIdent = net.minecraft.resources.Identifier.tryParse(sid);
                            if (sIdent != null) {
                                var sBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(sIdent);
                                if (sBlock != null) {
                                    // Avoid double consumption if step block equals base block
                                    if (sBlock.asItem() == block.asItem()) return;
                                    long key2 = stepPos.asLong();
                                    if (recordPlaced(key2)) {
                                        var placeState = sBlock.defaultBlockState();
                                        if (sBlock instanceof net.minecraft.world.level.block.StairBlock) {
                                            try { placeState = placeState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, travelDir); } catch (IllegalArgumentException ignored) {}
                                            try { placeState = placeState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, travelDir); } catch (IllegalArgumentException ignored) {}
                                            try { placeState = placeState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT); } catch (IllegalArgumentException ignored) {}
                                            try { placeState = placeState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, Boolean.FALSE); } catch (IllegalArgumentException ignored) {}
                                        } else if (sBlock instanceof net.minecraft.world.level.block.SlabBlock) {
                                            try { placeState = placeState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM); } catch (IllegalArgumentException ignored) {}
                                            try { placeState = placeState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, Boolean.FALSE); } catch (IllegalArgumentException ignored) {}
                                        }
                                        // Prevent placing blocks inside self to avoid suffocation damage
                                        if (wouldBlockOverlapSelf(stepPos)) {
                                            unrecordPlaced(key2);
                                            return;
                                        }
                                        if (!consumeBlockFromInventory(sid)) {
                                            unrecordPlaced(key2);
                                            handleMissingBuildingBlock();
                                            return;
                                        }
                                        world.setBlock(stepPos, placeState, 3);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void placeStripAt(double x, double y, double z, double px, double pz) {
        int w = Math.max(1, Math.min(9, this.pathWidth));
        int half = (w - 1) / 2;
        var world = this.level();
        for (int j = -half; j <= half; j++) {
            double ox = x + px * j;
            double oz = z + pz * j;
            int bx = Mth.floor(ox);
            int bz = Mth.floor(oz);
            int y0 = Mth.floor(y);
            Integer groundY = null;
            for (int yy = y0 + 1; yy >= y0 - 6; yy--) {
                BlockPos test = new BlockPos(bx, yy, bz);
                var st = world.getBlockState(test);
                if (!st.isAir() && st.isCollisionShapeFullBlock(world, test)) { groundY = yy; break; }
            }
            if (groundY == null) continue;
            int gIdx = sampleGradientIndex(w, j, bx, groundY, bz, getGradientNoiseScaleMain());
            if (gIdx < 0) continue;
            String id = gradient[gIdx] == null ? "" : gradient[gIdx];
            if (id.isEmpty()) continue;
            var ident = net.minecraft.resources.Identifier.tryParse(id);
            if (ident == null) continue;
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(ident);
            if (block == null) continue;
            // Replace only exposed surface within a 3-block vertical window
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos rp2 = new BlockPos(bx, groundY + dy, bz);
                var rs2 = world.getBlockState(rp2);
                if (rs2.isAir() || !rs2.isCollisionShapeFullBlock(world, rp2)) continue; // must be solid
                BlockPos ap2 = rp2.above();
                var as2 = world.getBlockState(ap2);
                if (as2.isCollisionShapeFullBlock(world, ap2)) continue; // not surface if blocked above
                if (rs2.is(block)) break; // already desired block at surface
                long key2 = rp2.asLong();
                if (!recordPlaced(key2)) break;
                // Prevent placing blocks inside self to avoid suffocation damage
                if (wouldBlockOverlapSelf(rp2)) {
                    unrecordPlaced(key2);
                    break;
                }
                if (!consumeBlockFromInventory(id)) {
                    unrecordPlaced(key2);
                    handleMissingBuildingBlock();
                    return;
                }
                world.setBlock(rp2, block.defaultBlockState(), 3);

                break; // one placement per column
            }
        }
    }

    private int sampleGradientIndex(int stripWidth, int j, int bx, int by, int bz, int noiseScale) {
        int G = 0;
        for (int i = gradient.length - 1; i >= 0; i--) {
            if (gradient[i] != null && !gradient[i].isEmpty()) { G = i + 1; break; }
        }
        if (G <= 0) return -1;

        // Map based on distance from center: left GUI slot = center, right = edges (either side)
        int half = (stripWidth - 1) / 2;
        int dist = Math.abs(j);
        int denom = Math.max(1, half);
        double s = (double) dist / (double) denom * (double) (G - 1);

        float Wcap = Math.min(this.gradientWindow, G);
        double W = (double) Wcap;
        if (W == 0.0) {
            int idx = (int) Math.round(s);
            return Mth.clamp(idx, 0, G - 1);
        }

        // Use symmetric jitter per distance from center so both sides match
        double u01 = sampleGradientNoise01(bx, by, bz, noiseScale);
        double u = (u01 * W) - (W * 0.5);
        double sprime = s + u;

        double a = -0.5;
        double b = (double) G - 0.5;
        double L = b - a;
        double y = (sprime - a) % (2.0 * L);
        if (y < 0) y += 2.0 * L;
        double r = (y <= L) ? y : (2.0 * L - y);
        double sref = a + r;

        int idx = (int) Math.round(sref);
        return Mth.clamp(idx, 0, G - 1);
    }

    private int sampleStepGradientIndex(int stripWidth, int j, int bx, int by, int bz, int noiseScale) {
        int G = 0;
        for (int i = stepGradient.length - 1; i >= 0; i--) {
            if (stepGradient[i] != null && !stepGradient[i].isEmpty()) { G = i + 1; break; }
        }
        if (G <= 0) return -1;

        int half = (stripWidth - 1) / 2;
        int dist = Math.abs(j);
        int denom = Math.max(1, half);
        double s = (double) dist / (double) denom * (double) (G - 1);

        float Wcap = Math.min(this.stepGradientWindow, G);
        double W = (double) Wcap;
        if (W == 0.0) {
            int idx = (int) Math.round(s);
            return Mth.clamp(idx, 0, G - 1);
        }

        double u01 = sampleGradientNoise01(bx, by, bz, noiseScale);
        double u = (u01 * W) - (W * 0.5);
        double sprime = s + u;

        double a = -0.5;
        double b = (double) G - 0.5;
        double L = b - a;
        double y = (sprime - a) % (2.0 * L);
        if (y < 0) y += 2.0 * L;
        double r = (y <= L) ? y : (2.0 * L - y);
        double sref = a + r;

        int idx = (int) Math.round(sref);
        return Mth.clamp(idx, 0, G - 1);
    }

    private int sampleSurfaceGradientIndex(int stripWidth, int j, int bx, int by, int bz, int noiseScale) {
        int G = 0;
        for (int i = surfaceGradient.length - 1; i >= 0; i--) {
            if (surfaceGradient[i] != null && !surfaceGradient[i].isEmpty()) { G = i + 1; break; }
        }
        if (G <= 0) return -1;

        int half = (stripWidth - 1) / 2;
        int dist = Math.abs(j);
        int denom = Math.max(1, half);
        double s = (double) dist / (double) denom * (double) (G - 1);

        float Wcap = Math.min(this.surfaceGradientWindow, G);
        double W = (double) Wcap;
        if (W == 0.0) {
            int idx = (int) Math.round(s);
            return Mth.clamp(idx, 0, G - 1);
        }

        double u01 = sampleGradientNoise01(bx, by, bz, noiseScale);
        double u = (u01 * W) - (W * 0.5);
        double sprime = s + u;

        double a = -0.5;
        double b = (double) G - 0.5;
        double L = b - a;
        double y = (sprime - a) % (2.0 * L);
        if (y < 0) y += 2.0 * L;
        double r = (y <= L) ? y : (2.0 * L - y);
        double sref = a + r;

        int idx = (int) Math.round(sref);
        return Mth.clamp(idx, 0, G - 1);
    }

    public int sampleWallGradient(String[] slots, float window, int noiseScale, int moduleHeight, int relY, BlockPos pos) {
        // Count non-empty gradient slots
        int G = 0;
        for (int i = 8; i >= 0; i--) {
            if (slots[i] != null && !slots[i].isEmpty()) {
                G = i + 1;
                break;
            }
        }
        if (G == 0) return -1;

        // Map Y position within module to gradient space [0, G-1]
        // Use per-module height (similar to tower mode's currentY approach)
        double s = (moduleHeight > 0) ? ((double) relY / (double) moduleHeight) * (G - 1) : 0.0;

        // Apply windowing
        float W = Math.min(window, G);
        if (W > 0) {
            // Deterministic random offset based on position
            double u = sampleGradientNoise01(pos, noiseScale);
            double uOffset = (u * W) - (W / 2.0);
            s += uOffset;
        }

        // Edge reflection (triangle wave)
        double a = -0.5;
        double b = G - 0.5;
        double L = b - a;
        double y = (s - a) % (2 * L);
        if (y < 0) y += 2 * L;
        double r = (y <= L) ? y : (2 * L - y);
        double s_ref = a + r;

        // Clamp and round
        int index = (int) Math.round(s_ref);
        return Math.max(0, Math.min(G - 1, index));
    }

    public int findItem(net.minecraft.world.item.Item item) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var st = inventory.getItem(i);
            if (!st.isEmpty() && st.is(item)) return i;
        }
        return -1;
    }

    /**
     * Decrement one item from an inventory slot.
     */
    public void decrementInventorySlot(int slot) {
        if (slot < 0 || slot >= inventory.getContainerSize()) return;
        var st = inventory.getItem(slot);
        if (!st.isEmpty()) {
            st.shrink(1);
            inventory.setItem(slot, st);
        }
    }

    // removed: runtime block use logging helper

}

class FollowGoldNuggetHolderGoal extends Goal {
    private final GoldGolemEntity golem;
    private final double speed;
    private final double stopDistance;
    private Player target;

    public FollowGoldNuggetHolderGoal(GoldGolemEntity golem, double speed, double stopDistance) {
        this.golem = golem;
        this.speed = speed;
        this.stopDistance = stopDistance;
    }

    @Override
    public boolean canUse() {
        if (golem.isBuildingPaths()) return false;
        if (golem.hasGuiViewer()) return false; // Stay in place while GUI is open
        if (golem.getBuildMode() == BuildMode.MINING) return false; // Never follow in mining mode
        if (golem.getBuildMode() == BuildMode.EXCAVATION) return false; // Never follow in excavation mode
        if (golem.getBuildMode() == BuildMode.TUNNEL) return false; // Never follow in tunnel mode
        // Only follow the owner; find the owner player in-world
        Player owner = null;
        for (Player player : golem.level().players()) {
            if (golem.isOwner(player)) { owner = player; break; }
        }
        if (owner == null) return false;
        if (!isHoldingNugget(owner)) return false;
        if (golem.distanceToSqr(owner) > (24.0 * 24.0)) return false;
        this.target = owner;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (golem.isBuildingPaths()) return false;
        if (golem.hasGuiViewer()) return false; // Stay in place while GUI is open
        if (golem.getBuildMode() == BuildMode.MINING) return false; // Never follow in mining mode
        if (golem.getBuildMode() == BuildMode.EXCAVATION) return false; // Never follow in excavation mode
        if (golem.getBuildMode() == BuildMode.TUNNEL) return false; // Never follow in tunnel mode
        if (target == null || !target.isAlive()) return false;
        // Ensure target remains the owner
        if (!golem.isOwner(target)) return false;
        if (!isHoldingNugget(target)) return false;
        double distSq = golem.distanceToSqr(target);
        return distSq > (stopDistance * stopDistance);
    }

    @Override
    public void stop() {
        this.target = null;
        this.golem.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null) return;
        this.golem.getLookControl().setLookAt(target, 30.0f, 30.0f);
        double distSq = golem.distanceToSqr(target);
        if (distSq > (stopDistance * stopDistance)) {
            this.golem.getNavigation().moveTo(target, this.speed);
        } else {
            this.golem.getNavigation().stop();
        }
    }

    private static boolean isHoldingNugget(Player player) {
        var nugget = net.minecraft.world.item.Items.GOLD_NUGGET;
        return player.getMainHandItem().is(nugget) || player.getOffhandItem().is(nugget);
    }
}

class PathingAwareWanderGoal extends WaterAvoidingRandomStrollGoal {
    private final GoldGolemEntity golem;

    public PathingAwareWanderGoal(GoldGolemEntity golem, double speed) {
        super(golem, speed);
        this.golem = golem;
    }

    @Override
    public boolean canUse() {
        if (golem.isBuildingPaths()) return false;
        if (golem.hasGuiViewer()) return false; // Stay in place while GUI is open
        if (golem.getBuildMode() == BuildMode.MINING) return false; // Never wander in mining mode
        if (golem.getBuildMode() == BuildMode.EXCAVATION) return false; // Never wander in excavation mode
        if (golem.getBuildMode() == BuildMode.TUNNEL) return false; // Never wander in tunnel mode
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (golem.isBuildingPaths()) return false;
        if (golem.hasGuiViewer()) return false; // Stay in place while GUI is open
        if (golem.getBuildMode() == BuildMode.MINING) return false; // Never wander in mining mode
        if (golem.getBuildMode() == BuildMode.EXCAVATION) return false; // Never wander in excavation mode
        if (golem.getBuildMode() == BuildMode.TUNNEL) return false; // Never wander in tunnel mode
        return super.canContinueToUse();
    }

    @Override
    protected Vec3 getPosition() {
        Vec3 base = super.getPosition();

        BlockPos waitAnchor = golem.isWaitingForResources() ? golem.getResourceWaitAnchor() : null;
        Player playerAnchor = waitAnchor == null ? getAnchorPlayer() : null;
        if (waitAnchor == null && playerAnchor == null) return base;

        double cx = waitAnchor != null ? waitAnchor.getX() + 0.5 : playerAnchor.getX();
        double cy = waitAnchor != null ? waitAnchor.getY() : playerAnchor.getY();
        double cz = waitAnchor != null ? waitAnchor.getZ() + 0.5 : playerAnchor.getZ();
        double max = waitAnchor != null ? 4.0 : 12.0;
        double maxSq = max * max;

        if (waitAnchor != null) {
            double golemDx = golem.getX() - cx;
            double golemDz = golem.getZ() - cz;
            if (golemDx * golemDx + golemDz * golemDz > maxSq) {
                return new Vec3(cx, cy, cz);
            }
        }

        if (base == null) {
            // No base target; pick a random point within the applicable anchor radius.
            java.util.Random rnd = new java.util.Random(golem.getRandom().nextLong());
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            double r = waitAnchor != null ? rnd.nextDouble() * max : 6.0 + rnd.nextDouble() * 6.0;
            return new Vec3(cx + Math.cos(angle) * r, cy, cz + Math.sin(angle) * r);
        }

        double dx = base.x - cx;
        double dy = base.y - cy;
        double dz = base.z - cz;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq <= maxSq) return base;

        double dist = Math.sqrt(distSq);
        if (dist < 1e-4) return new Vec3(cx, cy, cz);
        double scale = max / dist;
        // Clamp to the applicable anchor radius; keep base Y for smoother navigation.
        return new Vec3(cx + dx * scale, base.y, cz + dz * scale);
    }

    private Player getAnchorPlayer() {
        // Prefer the owner if present
        Player owner = null;
        for (Player p : golem.level().players()) {
            if (golem.isOwner(p)) { owner = p; break; }
        }
        if (owner != null) return owner;

        // Otherwise, use the nearest player
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : golem.level().players()) {
            double d = golem.distanceToSqr(p);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }
}
