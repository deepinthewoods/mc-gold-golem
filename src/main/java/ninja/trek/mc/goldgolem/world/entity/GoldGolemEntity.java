package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.particle.ParticleTypes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.ActionResult;
import net.minecraft.world.World;
import ninja.trek.mc.goldgolem.screen.GolemScreens;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;

import java.util.Optional;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.world.entity.strategy.BuildStrategy;
import ninja.trek.mc.goldgolem.world.entity.strategy.BuildStrategyRegistry;

public class GoldGolemEntity extends PathAwareEntity {
    public static final int INVENTORY_SIZE = 27;

    // Data trackers for client-server sync
    private static final TrackedData<Integer> LEFT_HAND_ANIMATION_TICK = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> RIGHT_HAND_ANIMATION_TICK = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> LEFT_ARM_HAS_TARGET = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> RIGHT_ARM_HAS_TARGET = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Optional<BlockPos>> LEFT_HAND_TARGET_POS = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.OPTIONAL_BLOCK_POS);
    private static final TrackedData<Optional<BlockPos>> RIGHT_HAND_TARGET_POS = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.OPTIONAL_BLOCK_POS);
    private static final TrackedData<Optional<BlockPos>> LEFT_HAND_NEXT_POS = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.OPTIONAL_BLOCK_POS);
    private static final TrackedData<Optional<BlockPos>> RIGHT_HAND_NEXT_POS = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.OPTIONAL_BLOCK_POS);
    private static final TrackedData<Boolean> BUILDING_PATHS = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> BUILD_MODE = DataTracker.registerData(GoldGolemEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE);
    private final String[] gradient = new String[9];
    private final String[] stepGradient = new String[9];
    private float gradientWindow = 1.0f; // window width in slot units (0..9)
    private float stepGradientWindow = 1.0f; // window width for step gradient
    private int pathWidth = 3;
    private boolean buildingPaths = false;

    // Strategy pattern for build modes
    private BuildStrategy activeStrategy = null;

    // Wall-mode captured data (scaffold)
    private java.util.List<String> wallUniqueBlockIds = java.util.Collections.emptyList();
    private net.minecraft.util.math.BlockPos wallOrigin = null; // absolute origin of capture
    private String wallJsonFile = null; // saved snapshot path (relative to game dir)
    private String wallJoinSignature = null; // common join-slice signature
    private ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis wallJoinAxis = null;
    private int wallJoinUSize = 1;
    private int wallModuleCount = 0;
    private int wallLongestModule = 0; // by voxel count for now
    private java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> wallTemplates = java.util.Collections.emptyList();
    private ModulePlacement currentModulePlacement = null;
    private final java.util.ArrayDeque<ModulePlacement> pendingModules = new java.util.ArrayDeque<>();
    private int wallLastDirX = 1, wallLastDirZ = 0; // cardinal last forward dir
    // Join-slice inferred template: points in (dy,du) with ids; uses wallJoinAxis
    private java.util.List<JoinEntry> wallJoinTemplate = java.util.Collections.emptyList();

    private static final class JoinEntry {
        final int dy; final int du; final String id;
        JoinEntry(int dy, int du, String id) { this.dy = dy; this.du = du; this.id = id == null ? "" : id; }
    }
    // Wall UI state: dynamic gradient groups
    private final java.util.List<String[]> wallGroupSlots = new java.util.ArrayList<>(); // each String[9]
    private final java.util.List<Float> wallGroupWindows = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> wallBlockGroup = new java.util.HashMap<>();

    // Tower-mode captured data
    private java.util.List<String> towerUniqueBlockIds = java.util.Collections.emptyList();
    private java.util.Map<String, Integer> towerBlockCounts = java.util.Collections.emptyMap();
    private net.minecraft.util.math.BlockPos towerOrigin = null; // absolute origin (bottom gold block)
    private String towerJsonFile = null; // saved snapshot path (relative to game dir)
    private int towerHeight = 0; // total height to build (in blocks)
    private ninja.trek.mc.goldgolem.tower.TowerModuleTemplate towerTemplate = null;
    // Tower UI state: dynamic gradient groups (same as wall mode)
    private final java.util.List<String[]> towerGroupSlots = new java.util.ArrayList<>(); // each String[9]
    private final java.util.List<Float> towerGroupWindows = new java.util.ArrayList<>();
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
    private final String[] terraformingGradientVertical = new String[9]; // steep/cliff surfaces
    private final String[] terraformingGradientHorizontal = new String[9]; // flat surfaces
    private final String[] terraformingGradientSloped = new String[9]; // diagonal surfaces
    private int terraformingGradientVerticalWindow = 1; // window for vertical gradient (0..9)
    private int terraformingGradientHorizontalWindow = 1; // window for horizontal gradient (0..9)
    private int terraformingGradientSlopedWindow = 1; // window for sloped gradient (0..9)

    // Tree-mode captured data (UI fields stay in entity, state fields moved to TreeBuildStrategy)
    private java.util.List<ninja.trek.mc.goldgolem.tree.TreeModule> treeModules = java.util.Collections.emptyList();
    private java.util.List<String> treeUniqueBlockIds = java.util.Collections.emptyList();
    private net.minecraft.util.math.BlockPos treeOrigin = null; // second gold block position
    private String treeJsonFile = null; // saved snapshot path (relative to game dir)
    private ninja.trek.mc.goldgolem.tree.TilingPreset treeTilingPreset = ninja.trek.mc.goldgolem.tree.TilingPreset.SMALL_3x3;
    // Tree UI state: dynamic gradient groups (same pattern as wall/tower)
    private final java.util.List<String[]> treeGroupSlots = new java.util.ArrayList<>(); // each String[9]
    private final java.util.List<Float> treeGroupWindows = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> treeBlockGroup = new java.util.HashMap<>();

    // Shared tracking fields (used by PATH and WALL modes)
    private Vec3d trackStart = null;
    private java.util.ArrayDeque<ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg> pendingLines = new java.util.ArrayDeque<>();
    private ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg currentLine = null;
    private final LongOpenHashSet recentPlaced = new LongOpenHashSet(8192);
    private final long[] placedRing = new long[8192];
    private int placedHead = 0;
    private int placedSize = 0;

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
    private static final float ARM_SWING_MIN_ANGLE = 15.0f;
    private static final float ARM_SWING_MAX_ANGLE = 70.0f;
    private float leftArmRotation = 0.0f;  // Current rotation in degrees
    private float rightArmRotation = 0.0f; // Current rotation in degrees
    private float leftArmTarget = 0.0f;    // Target rotation for this swing
    private float rightArmTarget = 0.0f;   // Target rotation for this swing
    private int armSwingTimer = 0;         // Timer counting down from SWING_DURATION_TICKS

    // Block placement animation (new system)
    private int placementTickCounter = 0;  // 0-1 tick counter (places every 2 ticks)
    private boolean leftHandActive = true; // Which hand places next
    private Vec3d leftArmTargetBlock = null;  // Block position left arm points at
    private Vec3d rightArmTargetBlock = null; // Block position right arm points at
    private int leftHandAnimationTick = -1;   // -1 = idle, 0-3 = animation cycle
    private int rightHandAnimationTick = -1;  // -1 = idle, 0-3 = animation cycle
    private BlockPos nextLeftBlock = null;    // Next block for left hand
    private BlockPos nextRightBlock = null;   // Next block for right hand
    private boolean leftHandJustActivated = false;
    private boolean rightHandJustActivated = false;

    public boolean isBuildingPaths() { return this.dataTracker.get(BUILDING_PATHS); }
    public float getLeftEyeYaw() { return leftEyeYaw; }
    public float getLeftEyePitch() { return leftEyePitch; }
    public float getRightEyeYaw() { return rightEyeYaw; }
    public float getRightEyePitch() { return rightEyePitch; }
    public double getWheelRotation() { return wheelRotation; }
    public float getLeftArmRotation() { return leftArmRotation; }
    public float getRightArmRotation() { return rightArmRotation; }
    public int getLeftHandAnimationTick() { return this.dataTracker.get(LEFT_HAND_ANIMATION_TICK); }
    public int getRightHandAnimationTick() { return this.dataTracker.get(RIGHT_HAND_ANIMATION_TICK); }
    public boolean shouldShowLeftHandItem() {
        int tick = getLeftHandAnimationTick();
        return tick >= 0 && tick <= 1;
    }
    public boolean shouldShowRightHandItem() {
        int tick = getRightHandAnimationTick();
        return tick >= 0 && tick <= 1;
    }
    public ItemStack getLeftHandItem() {
        if (!shouldShowLeftHandItem()) return ItemStack.EMPTY;
        // Return the first block item from inventory
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.item.BlockItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
    public ItemStack getRightHandItem() {
        if (!shouldShowRightHandItem()) return ItemStack.EMPTY;
        // Return the first block item from inventory
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.item.BlockItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
    public BuildMode getBuildMode() {
        int ordinal = this.dataTracker.get(BUILD_MODE);
        BuildMode[] values = BuildMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BuildMode.PATH;
    }
    public void setBuildMode(BuildMode mode) {
        this.dataTracker.set(BUILD_MODE, (mode == null ? BuildMode.PATH : mode).ordinal());
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
    }

    /**
     * Stop building and clean up the active strategy.
     */
    public void stopBuilding() {
        this.buildingPaths = false;
        this.dataTracker.set(BUILDING_PATHS, false);
        if (activeStrategy != null) {
            activeStrategy.stop(this);
        }
        this.getNavigation().stop();
    }

    /**
     * Start building with the current strategy.
     */
    public void startBuilding() {
        this.buildingPaths = true;
        this.dataTracker.set(BUILDING_PATHS, true);
        initializeStrategyForCurrentMode();
    }

    /**
     * Set the building state (called by strategies).
     */
    public void setBuildingPaths(boolean building) {
        this.buildingPaths = building;
        this.dataTracker.set(BUILDING_PATHS, building);
    }

    public void setWallCapture(java.util.List<String> uniqueIds, net.minecraft.util.math.BlockPos origin, String jsonPath) {
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
    public void setWallTemplates(java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> tpls) { this.wallTemplates = tpls == null ? java.util.Collections.emptyList() : tpls; }
    public void setWallJoinTemplate(java.util.List<int[]> pointsDyDuAndIdIndex, java.util.List<String> idLut) {
        java.util.ArrayList<JoinEntry> list = new java.util.ArrayList<>();
        for (int[] p : pointsDyDuAndIdIndex) {
            int dy = p[0], du = p[1], idx = p[2];
            String id = (idx >= 0 && idx < idLut.size()) ? idLut.get(idx) : "";
            list.add(new JoinEntry(dy, du, id));
        }
        this.wallJoinTemplate = list;
    }
    public void initWallGroups(java.util.List<String> uniqueBlocks) {
        wallGroupSlots.clear(); wallGroupWindows.clear(); wallBlockGroup.clear();
        // default: one group per unique; if gold present, merge it with first non-gold
        int idx = 0;
        int firstNonGold = -1;
        for (String id : uniqueBlocks) {
            String[] arr = new String[9];
            wallGroupSlots.add(arr);
            wallGroupWindows.add(1.0f);
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
    public java.util.List<String[]> getWallGroupSlots() { return wallGroupSlots; }
    public java.util.Map<String, Integer> getWallBlockGroup() { return wallBlockGroup; }
    public java.util.List<String> getWallGroupFlatSlots() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(wallGroupSlots.size() * 9);
        for (String[] arr : wallGroupSlots) {
            for (int i = 0; i < 9; i++) out.add(arr[i] == null ? "" : arr[i]);
        }
        return out;
    }
    public void setWallBlockGroup(String blockId, int group) {
        if (group < 0) { // create new
            wallGroupSlots.add(new String[9]);
            wallGroupWindows.add(1.0f);
            group = wallGroupSlots.size() - 1;
        } else if (group >= wallGroupSlots.size()) {
            return;
        }
        wallBlockGroup.put(blockId, group);
    }
    public void setWallGroupWindow(int group, float window) {
        if (group < 0 || group >= wallGroupWindows.size()) return;
        wallGroupWindows.set(group, Math.max(0.0f, Math.min(9.0f, window)));
    }
    public void setWallGroupSlot(int group, int slot, String id) {
        if (group < 0 || group >= wallGroupSlots.size()) return;
        if (slot < 0 || slot >= 9) return;
        String[] arr = wallGroupSlots.get(group);
        arr[slot] = (id == null) ? "" : id;
    }

    // Tower mode methods
    public void setTowerCapture(java.util.List<String> uniqueIds, java.util.Map<String, Integer> counts,
                                net.minecraft.util.math.BlockPos origin, String jsonPath, int height,
                                ninja.trek.mc.goldgolem.tower.TowerModuleTemplate template) {
        this.towerUniqueBlockIds = uniqueIds == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(uniqueIds);
        this.towerBlockCounts = counts == null ? java.util.Collections.emptyMap() : new java.util.HashMap<>(counts);
        this.towerOrigin = origin;
        this.towerJsonFile = jsonPath;
        this.towerHeight = height;
        this.towerTemplate = template;
        // Initialize tower groups
        initTowerGroups(uniqueIds);
    }
    public java.util.List<String> getTowerUniqueBlockIds() { return java.util.Collections.unmodifiableList(this.towerUniqueBlockIds); }
    public java.util.Map<String, Integer> getTowerBlockCounts() { return java.util.Collections.unmodifiableMap(this.towerBlockCounts); }
    public int getTowerHeight() { return towerHeight; }
    public void setTowerHeight(int height) { this.towerHeight = Math.max(1, Math.min(256, height)); }
    public ninja.trek.mc.goldgolem.tower.TowerModuleTemplate getTowerTemplate() { return towerTemplate; }

    public void initTowerGroups(java.util.List<String> uniqueBlocks) {
        towerGroupSlots.clear(); towerGroupWindows.clear(); towerBlockGroup.clear();
        // Default: one group per unique block type
        int idx = 0;
        for (String id : uniqueBlocks) {
            String[] arr = new String[9];
            towerGroupSlots.add(arr);
            towerGroupWindows.add(1.0f);
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
    public java.util.List<String[]> getTowerGroupSlots() { return towerGroupSlots; }
    public java.util.Map<String, Integer> getTowerBlockGroup() { return towerBlockGroup; }
    public BlockPos getTowerOrigin() { return towerOrigin; }
    public java.util.List<String> getTowerGroupFlatSlots() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(towerGroupSlots.size() * 9);
        for (String[] arr : towerGroupSlots) {
            for (int i = 0; i < 9; i++) out.add(arr[i] == null ? "" : arr[i]);
        }
        return out;
    }
    public void setTowerBlockGroup(String blockId, int group) {
        if (group < 0) { // create new
            towerGroupSlots.add(new String[9]);
            towerGroupWindows.add(1.0f);
            group = towerGroupSlots.size() - 1;
        } else if (group >= towerGroupSlots.size()) {
            return;
        }
        towerBlockGroup.put(blockId, group);
    }
    public void setTowerGroupWindow(int group, float window) {
        if (group < 0 || group >= towerGroupWindows.size()) return;
        towerGroupWindows.set(group, Math.max(0.0f, Math.min(9.0f, window)));
    }
    public void setTowerGroupSlot(int group, int slot, String id) {
        if (group < 0 || group >= towerGroupSlots.size()) return;
        if (slot < 0 || slot >= 9) return;
        String[] arr = towerGroupSlots.get(group);
        arr[slot] = (id == null) ? "" : id;
    }

    // Tree mode configuration
    public void setTreeCapture(java.util.List<ninja.trek.mc.goldgolem.tree.TreeModule> modules,
                              java.util.List<String> uniqueIds, net.minecraft.util.math.BlockPos origin, String jsonPath) {
        this.treeModules = modules == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(modules);
        this.treeUniqueBlockIds = uniqueIds == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(uniqueIds);
        this.treeOrigin = origin;
        this.treeJsonFile = jsonPath;
        // Initialize tree groups
        initTreeGroups(uniqueIds);
    }
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
        treeGroupSlots.clear(); treeGroupWindows.clear(); treeBlockGroup.clear();
        // Default: one group per unique block type
        int idx = 0;
        for (String id : uniqueBlocks) {
            String[] arr = new String[9];
            treeGroupSlots.add(arr);
            treeGroupWindows.add(1.0f);
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
    public java.util.List<String> getTreeGroupFlatSlots() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(treeGroupSlots.size() * 9);
        for (String[] arr : treeGroupSlots) {
            for (int i = 0; i < 9; i++) out.add(arr[i] == null ? "" : arr[i]);
        }
        return out;
    }
    public void setTreeBlockGroup(String blockId, int group) {
        if (group < 0) { // create new
            treeGroupSlots.add(new String[9]);
            treeGroupWindows.add(1.0f);
            group = treeGroupSlots.size() - 1;
        } else if (group >= treeGroupSlots.size()) {
            return;
        }
        treeBlockGroup.put(blockId, group);
    }
    public void setTreeGroupWindow(int group, float window) {
        if (group < 0 || group >= treeGroupWindows.size()) return;
        treeGroupWindows.set(group, Math.max(0.0f, Math.min(9.0f, window)));
    }
    public void setTreeGroupSlot(int group, int slot, String id) {
        if (group < 0 || group >= treeGroupSlots.size()) return;
        if (slot < 0 || slot >= 9) return;
        String[] arr = treeGroupSlots.get(group);
        arr[slot] = (id == null) ? "" : id;
    }
    public BlockPos getTreeOrigin() { return treeOrigin; }
    public java.util.Map<String, Integer> getTreeBlockGroup() { return treeBlockGroup; }
    public java.util.List<String[]> getTreeGroupSlots() { return treeGroupSlots; }

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
        this.dataTracker.set(LEFT_HAND_TARGET_POS, pos);
    }
    public void setLeftArmHasTarget(boolean hasTarget) {
        this.dataTracker.set(LEFT_ARM_HAS_TARGET, hasTarget);
    }
    public void setLeftHandAnimationTick(int tick) {
        this.dataTracker.set(LEFT_HAND_ANIMATION_TICK, tick);
    }

    // Mining mode configuration
    public void setMiningConfig(BlockPos chestPos, net.minecraft.util.math.Direction miningDir, BlockPos startPos) {
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
    public void setExcavationConfig(BlockPos chest1, BlockPos chest2, net.minecraft.util.math.Direction dir1, net.minecraft.util.math.Direction dir2, BlockPos startPos) {
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

    public GoldGolemEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(LEFT_HAND_ANIMATION_TICK, -1);
        builder.add(RIGHT_HAND_ANIMATION_TICK, -1);
        builder.add(LEFT_ARM_HAS_TARGET, false);
        builder.add(RIGHT_ARM_HAS_TARGET, false);
        builder.add(LEFT_HAND_TARGET_POS, Optional.empty());
        builder.add(RIGHT_HAND_TARGET_POS, Optional.empty());
        builder.add(LEFT_HAND_NEXT_POS, Optional.empty());
        builder.add(RIGHT_HAND_NEXT_POS, Optional.empty());
        builder.add(BUILDING_PATHS, false);
        builder.add(BUILD_MODE, BuildMode.PATH.ordinal());
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

    public static DefaultAttributeContainer.Builder createAttributes() {
        return DefaultAttributeContainer.builder()
                .add(EntityAttributes.MAX_HEALTH, 40.0)
                .add(EntityAttributes.MAX_ABSORPTION, 0.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.FOLLOW_RANGE, 32.0)
                .add(EntityAttributes.ARMOR, 0.0)
                .add(EntityAttributes.ARMOR_TOUGHNESS, 0.0)
                .add(EntityAttributes.WAYPOINT_TRANSMIT_RANGE, 0.0)
                .add(EntityAttributes.STEP_HEIGHT, 0.6)
                .add(EntityAttributes.WATER_MOVEMENT_EFFICIENCY, 1.0)
                .add(EntityAttributes.MOVEMENT_EFFICIENCY, 1.0)
                .add(EntityAttributes.GRAVITY, 0.08)
                .add(EntityAttributes.SAFE_FALL_DISTANCE, 3.0)
                .add(EntityAttributes.FALL_DAMAGE_MULTIPLIER, 1.0)
                .add(EntityAttributes.JUMP_STRENGTH, 0.42);
    }

    @Override
    protected void initGoals() {
        // Follow players holding gold nuggets (approach within 1.5 blocks)
        this.goalSelector.add(3, new FollowGoldNuggetHolderGoal(this, 1.1, 1.5));
        this.goalSelector.add(5, new PathingAwareWanderGoal(this, 0.8));
        this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(7, new LookAroundGoal(this));
    }

    @Override
    protected Text getDefaultName() {
        return Text.translatable("entity.gold_golem.gold_golem");
    }

    @Override
    public void tick() {
        super.tick();

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

        if (this.getEntityWorld().isClient()) {
            updateClientHandTargetsFromTracker();
        }

        // Determine which animation system to use
        boolean leftAnimating = (leftHandAnimationTick >= 0 && (leftArmTargetBlock != null || this.dataTracker.get(LEFT_ARM_HAS_TARGET)));
        boolean rightAnimating = (rightHandAnimationTick >= 0 && (rightArmTargetBlock != null || this.dataTracker.get(RIGHT_ARM_HAS_TARGET)));
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
            leftArmRotation = MathHelper.lerp(progress, prevLeftTarget, leftArmTarget);
            rightArmRotation = MathHelper.lerp(progress, prevRightTarget, rightArmTarget);
            armSwingTimer--;
            // Update eyes randomly when not placing blocks
            updateRandomEyeMovement();
        } else {
            // Idle - return arms to neutral
            leftArmRotation = MathHelper.lerp(0.1f, leftArmRotation, 0.0f);
            rightArmRotation = MathHelper.lerp(0.1f, rightArmRotation, 0.0f);
            armSwingTimer = 0;
            // Update eyes randomly when idle
            updateRandomEyeMovement();
        }

        buildingPaths = isBuildingPaths(); // Read from data tracker

        if (this.getEntityWorld().isClient()) return;
        if (buildingPaths) {
            // Increment placement tick counter (2-tick cycle)
            placementTickCounter = (placementTickCounter + 1) % 2;

            // Initialize strategy if needed
            if (activeStrategy == null || activeStrategy.getMode() != getBuildMode()) {
                initializeStrategyForCurrentMode();
            }

            // Use strategy for building logic
            if (activeStrategy != null) {
                PlayerEntity owner = null;
                if (activeStrategy.usesPlayerTracking()) {
                    owner = getOwnerPlayer();
                    if (owner != null) {
                        this.getLookControl().lookAt(owner, 30.0f, 30.0f);
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

    private void updateRandomEyeMovement() {
        // Update eye look directions randomly every 5-10 ticks
        if (eyeUpdateCooldown <= 0) {
            // Random look direction for left eye (within a reasonable range)
            leftEyeYaw = (this.getRandom().nextFloat() - 0.5f) * 120.0f; // ±60 degrees from center
            leftEyePitch = (this.getRandom().nextFloat() - 0.5f) * 60.0f; // ±30 degrees from center

            // Random look direction for right eye (independent)
            rightEyeYaw = (this.getRandom().nextFloat() - 0.5f) * 120.0f;
            rightEyePitch = (this.getRandom().nextFloat() - 0.5f) * 60.0f;

            // Set next update time (5-10 ticks)
            eyeUpdateCooldown = 5 + this.getRandom().nextInt(6);
        } else {
            eyeUpdateCooldown--;
        }
    }

    private void updateArmAndEyePositions() {
        // Update left arm and eye
        if (leftArmTargetBlock != null) {
            // SERVER: Has exact block position, calculate precise angle
            Vec3d armPos = new Vec3d(this.getX() - 0.3, this.getY() + 1.0, this.getZ()); // Left arm position (approx)
            Vec3d targetPos = leftArmTargetBlock;

            // For ticks 2-3, adjust target based on animation state
            if (leftHandAnimationTick == 3 && nextLeftBlock != null) {
                targetPos = new Vec3d(nextLeftBlock.getX() + 0.5, nextLeftBlock.getY() + 0.5, nextLeftBlock.getZ() + 0.5);
            }

            // Calculate direction to target
            double dx = targetPos.x - armPos.x;
            double dy = targetPos.y - armPos.y;
            double dz = targetPos.z - armPos.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            // Calculate pitch (vertical angle)
            float pitch = (float) Math.toDegrees(Math.atan2(dy, horizontalDist));
            leftArmRotation = -pitch; // Negative because positive rotation is forward/down

            // Update left eye to look at same target (relative to head)
            double eyeDx = targetPos.x - this.getX();
            double eyeDy = targetPos.y - (this.getY() + 1.5); // Eye height approx
            double eyeDz = targetPos.z - this.getZ();
            double eyeHorizontalDist = Math.sqrt(eyeDx * eyeDx + eyeDz * eyeDz);

            leftEyeYaw = (float) Math.toDegrees(Math.atan2(-eyeDx, eyeDz)); // Relative to forward
            leftEyePitch = (float) Math.toDegrees(Math.atan2(-eyeDy, eyeHorizontalDist));
        } else if (leftHandAnimationTick >= 0) {
            // CLIENT: Animation is active but no block position - use default "placing" pose
            // Point arm forward and down as if placing a block in front
            leftArmRotation = 45.0f; // 45 degrees forward/down
            // Eyes look forward and slightly down
            leftEyeYaw = 0.0f;
            leftEyePitch = 15.0f; // Looking slightly down
        } else {
            // Idle - neutral
            leftEyeYaw = 0.0f;
            leftEyePitch = 0.0f;
        }

        // Update right arm and eye
        if (rightArmTargetBlock != null) {
            // SERVER: Has exact block position, calculate precise angle
            Vec3d armPos = new Vec3d(this.getX() + 0.3, this.getY() + 1.0, this.getZ()); // Right arm position (approx)
            Vec3d targetPos = rightArmTargetBlock;

            // For ticks 2-3, adjust target based on animation state
            if (rightHandAnimationTick == 3 && nextRightBlock != null) {
                targetPos = new Vec3d(nextRightBlock.getX() + 0.5, nextRightBlock.getY() + 0.5, nextRightBlock.getZ() + 0.5);
            }

            // Calculate direction to target
            double dx = targetPos.x - armPos.x;
            double dy = targetPos.y - armPos.y;
            double dz = targetPos.z - armPos.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            // Calculate pitch (vertical angle)
            float pitch = (float) Math.toDegrees(Math.atan2(dy, horizontalDist));
            rightArmRotation = -pitch;

            // Update right eye to look at same target
            double eyeDx = targetPos.x - this.getX();
            double eyeDy = targetPos.y - (this.getY() + 1.5);
            double eyeDz = targetPos.z - this.getZ();
            double eyeHorizontalDist = Math.sqrt(eyeDx * eyeDx + eyeDz * eyeDz);

            rightEyeYaw = (float) Math.toDegrees(Math.atan2(-eyeDx, eyeDz));
            rightEyePitch = (float) Math.toDegrees(Math.atan2(-eyeDy, eyeHorizontalDist));
        } else if (rightHandAnimationTick >= 0) {
            // CLIENT: Animation is active but no block position - use default "placing" pose
            // Point arm forward and down as if placing a block in front
            rightArmRotation = 45.0f; // 45 degrees forward/down
            // Eyes look forward and slightly down
            rightEyeYaw = 0.0f;
            rightEyePitch = 15.0f; // Looking slightly down
        } else {
            // Idle - neutral
            rightEyeYaw = 0.0f;
            rightEyePitch = 0.0f;
        }
    }

    private static Vec3d blockCenter(BlockPos pos) {
        return pos == null ? null : new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private void updateClientHandTargetsFromTracker() {
        if (!this.getEntityWorld().isClient()) return;

        if (this.dataTracker.get(LEFT_ARM_HAS_TARGET)) {
            Optional<BlockPos> current = this.dataTracker.get(LEFT_HAND_TARGET_POS);
            leftArmTargetBlock = current.map(GoldGolemEntity::blockCenter).orElse(null);
            nextLeftBlock = this.dataTracker.get(LEFT_HAND_NEXT_POS).orElse(null);
        } else {
            leftArmTargetBlock = null;
            nextLeftBlock = null;
        }

        if (this.dataTracker.get(RIGHT_ARM_HAS_TARGET)) {
            Optional<BlockPos> current = this.dataTracker.get(RIGHT_HAND_TARGET_POS);
            rightArmTargetBlock = current.map(GoldGolemEntity::blockCenter).orElse(null);
            nextRightBlock = this.dataTracker.get(RIGHT_HAND_NEXT_POS).orElse(null);
        } else {
            rightArmTargetBlock = null;
            nextRightBlock = null;
        }
    }

    public void beginHandAnimation(boolean isLeft, BlockPos placedBlock, BlockPos previewBlock) {
        if (placedBlock == null) return;
        Vec3d center = blockCenter(placedBlock);

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

        this.dataTracker.set(isLeft ? LEFT_HAND_ANIMATION_TICK : RIGHT_HAND_ANIMATION_TICK, 0);
        this.dataTracker.set(isLeft ? LEFT_ARM_HAS_TARGET : RIGHT_ARM_HAS_TARGET, true);
        this.dataTracker.set(isLeft ? LEFT_HAND_TARGET_POS : RIGHT_HAND_TARGET_POS, Optional.ofNullable(placedBlock));
        this.dataTracker.set(isLeft ? LEFT_HAND_NEXT_POS : RIGHT_HAND_NEXT_POS, Optional.ofNullable(previewBlock));
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

        this.dataTracker.set(isLeft ? LEFT_HAND_ANIMATION_TICK : RIGHT_HAND_ANIMATION_TICK, -1);
        this.dataTracker.set(isLeft ? LEFT_ARM_HAS_TARGET : RIGHT_ARM_HAS_TARGET, false);
        this.dataTracker.set(isLeft ? LEFT_HAND_TARGET_POS : RIGHT_HAND_TARGET_POS, Optional.empty());
        this.dataTracker.set(isLeft ? LEFT_HAND_NEXT_POS : RIGHT_HAND_NEXT_POS, Optional.empty());
    }

    private void advanceHandAnimationTicks() {
        if (this.getEntityWorld().isClient()) return;
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
        if (next >= 4) {
            clearHandAnimation(isLeft);
        } else {
            this.dataTracker.set(isLeft ? LEFT_HAND_ANIMATION_TICK : RIGHT_HAND_ANIMATION_TICK, next);
            if (isLeft) {
                leftHandAnimationTick = next;
            } else {
                rightHandAnimationTick = next;
            }
        }
    }

    public double computeGroundTargetY(Vec3d pos) {
        int bx = MathHelper.floor(pos.x);
        int bz = MathHelper.floor(pos.z);
        int y0 = MathHelper.floor(pos.y);
        var world = this.getEntityWorld();
        Integer groundY = null;
        for (int yy = y0 + 3; yy >= y0 - 8; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isFullCube(world, test)) { groundY = yy; break; }
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

    // WALL MODE runtime tick handler
    private void tickWallMode(PlayerEntity owner) {
        // Track anchors and enqueue modules based on movement
        if (owner != null && owner.isOnGround()) {
            Vec3d p = new Vec3d(owner.getX(), owner.getY() + 0.05, owner.getZ());
            if (trackStart == null) {
                trackStart = p;
            } else {
                double threshold = Math.max(2.0, getWallLongestHoriz() + 1.0);
                double dist = Math.sqrt((p.x - trackStart.x) * (p.x - trackStart.x) + (p.z - trackStart.z) * (p.z - trackStart.z));
                if (dist >= threshold) {
                    var cand = chooseNextModule(trackStart, p);
                    if (cand != null) {
                        pendingModules.addLast(cand);
                        // update anchor to end
                        trackStart = cand.end();
                        // preview
                        if (this.getEntityWorld() instanceof ServerWorld) {
                            var owner2 = getOwnerPlayer();
                            if (owner2 instanceof net.minecraft.server.network.ServerPlayerEntity sp2) {
                                java.util.List<Vec3d> list = new java.util.ArrayList<>();
                                list.add(cand.anchor()); list.add(cand.end());
                                java.util.Optional<Vec3d> anchor = java.util.Optional.ofNullable(this.trackStart);
                                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp2, this.getId(), list, anchor);
                            }
                        }
                    }
                }
            }
        }
        if (currentModulePlacement == null) {
            currentModulePlacement = pendingModules.pollFirst();
            if (currentModulePlacement != null) {
                currentModulePlacement.begin(this);
                Vec3d end = currentModulePlacement.end();
                double ty = computeGroundTargetY(end);
                this.getNavigation().startMovingTo(end.x, ty, end.z, 1.1);
            }
        }
        if (currentModulePlacement != null) {
            // Place 1 block every 2 ticks, alternating hands (same as path mode)
            if (placementTickCounter == 0) {
                // For wall mode, we'll place blocks but without specific position tracking for now
                // This maintains the alternating hand animation
                currentModulePlacement.placeSome(this, 1); // Place only 1 block
            }
            Vec3d end = currentModulePlacement.end();
            double ty = computeGroundTargetY(end);
            this.getNavigation().startMovingTo(end.x, ty, end.z, 1.1);
            double dx = this.getX() - end.x;
            double dz = this.getZ() - end.z;
            double distSq = dx * dx + dz * dz;
            if (this.getNavigation().isIdle() && distSq > 1.0) {
                stuckTicks++;
                if (stuckTicks >= 20) {
                    if (this.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 0.5, this.getZ(), 40, 0.5, 0.5, 0.5, 0.2);
                        sw.spawnParticles(ParticleTypes.PORTAL, end.x, ty + 0.5, end.z, 40, 0.5, 0.5, 0.5, 0.2);
                    }
                    this.refreshPositionAndAngles(end.x, ty, end.z, this.getYaw(), this.getPitch());
                    this.getNavigation().stop();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
            if (currentModulePlacement.done()) {
                currentModulePlacement = null;
            }
        }
    }

    // Tower mode logic has been moved to TowerBuildStrategy

    // Tree mode logic has been moved to TreeBuildStrategy

    // Mining methods have been moved to MiningBuildStrategy

    private ItemStack transferToInventory(ItemStack stack, net.minecraft.inventory.Inventory targetInv) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

        // Try to merge with existing stacks first
        for (int i = 0; i < targetInv.size(); i++) {
            ItemStack targetStack = targetInv.getStack(i);
            if (targetStack.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, targetStack)) {
                int space = targetStack.getMaxCount() - targetStack.getCount();
                if (space > 0) {
                    int toTransfer = Math.min(space, stack.getCount());
                    targetStack.setCount(targetStack.getCount() + toTransfer);
                    targetInv.setStack(i, targetStack);
                    stack.decrement(toTransfer);
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                }
            }
        }

        // Place in empty slots
        for (int i = 0; i < targetInv.size(); i++) {
            if (targetInv.getStack(i).isEmpty()) {
                targetInv.setStack(i, stack.copy());
                return ItemStack.EMPTY;
            }
        }

        // Chest is full, return remainder
        return stack;
    }

    private boolean shouldMineBlock(BlockPos pos) {
        BlockState state = this.getEntityWorld().getBlockState(pos);
        return !state.isAir() && state.getHardness(this.getEntityWorld(), pos) >= 0;
    }

    private boolean isOreBlock(String blockId) {
        return blockId.contains("_ore") || blockId.contains("ancient_debris") ||
               blockId.equals("minecraft:gilded_blackstone");
    }

    private boolean isGravityBlock(net.minecraft.block.Block block) {
        return block instanceof net.minecraft.block.FallingBlock;
    }

    private String getBlockIdFromStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.item.BlockItem blockItem)) {
            return null;
        }
        return net.minecraft.registry.Registries.BLOCK.getId(blockItem.getBlock()).toString();
    }

    private ItemStack findBestTool(BlockState state) {
        ItemStack bestTool = ItemStack.EMPTY;
        float bestSpeed = 1.0f;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !stack.isSuitableFor(state)) continue;

            float speed = stack.getMiningSpeedMultiplier(state);
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
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack slot = inventory.getStack(i);
            if (slot.isEmpty()) continue;

            if (ItemStack.areItemsAndComponentsEqual(stack, slot)) {
                int space = slot.getMaxCount() - slot.getCount();
                if (space > 0) {
                    int toAdd = Math.min(space, stack.getCount());
                    slot.setCount(slot.getCount() + toAdd);
                    inventory.setStack(i, slot);
                    stack.decrement(toAdd);
                    if (stack.isEmpty()) return;
                }
            }
        }

        // Place in empty slots
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setStack(i, stack.copy());
                return;
            }
        }

        // Inventory full, drop on ground
        if (this.getEntityWorld() instanceof ServerWorld sw) {
            this.dropStack(sw, stack);
        }
    }

    public void placeBlockFromInventory(BlockPos pos, BlockState state, BlockPos nextPos) {
        // Check if block already exists at position
        if (this.getEntityWorld().getBlockState(pos).equals(state)) return;

        // Try to consume block from inventory
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
        if (consumeBlockFromInventory(blockId)) {
            this.getEntityWorld().setBlockState(pos, state);
            // Set hand animation with current and next block positions
            beginHandAnimation(leftHandActive, pos, nextPos);
            leftHandActive = !leftHandActive;
        }
    }

    private boolean consumeFromShulkerBox(ItemStack shulkerBox, String blockId) {
        // Get the container component from the shulker box
        ContainerComponent container = shulkerBox.get(DataComponentTypes.CONTAINER);
        if (container == null) return false;

        // Convert stream to list for easier manipulation
        java.util.List<ItemStack> contents = new java.util.ArrayList<>(container.stream().toList());

        // Search through the shulker box contents
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof net.minecraft.item.BlockItem bi) {
                String stackId = net.minecraft.registry.Registries.BLOCK.getId(bi.getBlock()).toString();
                if (stackId.equals(blockId)) {
                    // Decrement this stack and write back an updated immutable container component.
                    ItemStack modifiedStack = stack.copy();
                    modifiedStack.decrement(1);
                    contents.set(i, modifiedStack);

                    shulkerBox.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
                    return true;
                }
            }
        }
        return false;
    }

    public boolean consumeBlockFromInventory(String blockId) {
        // Search inventory for matching block
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof net.minecraft.item.BlockItem bi) {
                String stackId = net.minecraft.registry.Registries.BLOCK.getId(bi.getBlock()).toString();
                if (stackId.equals(blockId)) {
                    stack.decrement(1);
                    return true;
                }
            }
        }

        // If not found in regular inventory, check shulker boxes
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            // Check if this is a shulker box
            if (stack.getItem() instanceof net.minecraft.item.BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                if (consumeFromShulkerBox(stack, blockId)) {
                    return true;
                }
            }
        }

        return false;
    }

    public BlockState getBlockStateFromId(String blockId) {
        try {
            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(blockId);
            if (id == null) return null;
            net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK.get(id);
            if (block == null) return null;
            return block.getDefaultState();
        } catch (Exception e) {
            return null;
        }
    }


    // Terraforming mode logic is now in TerraformingBuildStrategy

    // Persistence: width, gradient, inventory, owner UUID (1.21.10 storage API)
    @Override
    protected void writeCustomData(WriteView view) {
        view.putString("Mode", getBuildMode().name());
        view.putInt("PathWidth", this.pathWidth);
        view.putFloat("GradWindow", this.gradientWindow);
        view.putFloat("StepWindow", this.stepGradientWindow);

        for (int i = 0; i < 9; i++) {
            String val = gradient[i] == null ? "" : gradient[i];
            view.putString("G" + i, val);
        }
        for (int i = 0; i < 9; i++) {
            String val = stepGradient[i] == null ? "" : stepGradient[i];
            view.putString("S" + i, val);
        }

        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY);
        for (int i = 0; i < INVENTORY_SIZE; i++) stacks.set(i, inventory.getStack(i));
        Inventories.writeData(view.get("Inventory"), stacks, true);

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
        view.putInt("TowerCurrentY", this.towerCurrentY);
        view.putInt("TowerPlacementCursor", this.towerPlacementCursor);
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

        // Note: Terraforming state is now written via activeStrategy.writeLegacyNbt(view) above

        // Tree-mode persisted bits
        if (this.treeOrigin != null) {
            view.putInt("TreeOX", this.treeOrigin.getX());
            view.putInt("TreeOY", this.treeOrigin.getY());
            view.putInt("TreeOZ", this.treeOrigin.getZ());
        }
        if (this.treeJsonFile != null) view.putString("TreeJson", this.treeJsonFile);
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
            for (net.minecraft.util.math.BlockPos pos : module.voxels) {
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
    protected void readCustomData(ReadView view) {
        String mode = view.getString("Mode", BuildMode.PATH.name());
        try {
            setBuildMode(BuildMode.valueOf(mode));
        } catch (IllegalArgumentException ex) {
            setBuildMode(BuildMode.PATH);
        }
        this.pathWidth = Math.max(1, Math.min(9, view.getInt("PathWidth", this.pathWidth)));
        this.gradientWindow = Math.max(0.0f, Math.min(9.0f, view.getFloat("GradWindow", this.gradientWindow)));
        this.stepGradientWindow = Math.max(0.0f, Math.min(9.0f, view.getFloat("StepWindow", this.stepGradientWindow)));

        for (int i = 0; i < 9; i++) {
            gradient[i] = view.getString("G" + i, "");
        }
        for (int i = 0; i < 9; i++) {
            stepGradient[i] = view.getString("S" + i, "");
        }

        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY);
        Inventories.readData(view.getReadView("Inventory"), stacks);
        for (int i = 0; i < INVENTORY_SIZE; i++) inventory.setStack(i, stacks.get(i));

        var ownerOpt = view.getOptionalString("Owner");
        this.ownerUuid = ownerOpt.isPresent() && !ownerOpt.get().isEmpty() ? java.util.UUID.fromString(ownerOpt.get()) : null;

        // Wall-mode bits
        if (view.contains("WallOX")) {
            this.wallOrigin = new net.minecraft.util.math.BlockPos(view.getInt("WallOX", 0), view.getInt("WallOY", 0), view.getInt("WallOZ", 0));
        } else {
            this.wallOrigin = null;
        }
        this.wallJsonFile = view.getString("WallJson", null);
        int c = view.getInt("WallUniqCount", 0);
        if (c > 0) {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>(c);
            for (int i = 0; i < c; i++) ids.add(view.getString("WallU" + i, ""));
            this.wallUniqueBlockIds = ids;
        } else {
            this.wallUniqueBlockIds = java.util.Collections.emptyList();
        }
        this.wallJoinSignature = view.getString("WallJoinSig", null);
        String a = view.getString("WallJoinAxis", null);
        if (a != null) { try { this.wallJoinAxis = ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.valueOf(a); } catch (IllegalArgumentException ignored) {} }
        this.wallJoinUSize = Math.max(1, view.getInt("WallJoinU", 1));
        this.wallModuleCount = view.getInt("WallModCount", 0);
        this.wallLongestModule = view.getInt("WallModLongest", 0);
        int jt = view.getInt("WallJoinTplCount", 0);
        if (jt > 0) {
            java.util.ArrayList<JoinEntry> list = new java.util.ArrayList<>(jt);
            for (int i = 0; i < jt; i++) {
                int dy = view.getInt("WJT_dy" + i, 0);
                int du = view.getInt("WJT_du" + i, 0);
                String id = view.getString("WJT_id" + i, "");
                list.add(new JoinEntry(dy, du, id));
            }
            this.wallJoinTemplate = list;
        } else {
            this.wallJoinTemplate = java.util.Collections.emptyList();
        }
        // Wall groups
        wallGroupSlots.clear(); wallGroupWindows.clear(); wallBlockGroup.clear();
        int gc = view.getInt("WallGroupCount", 0);
        for (int g = 0; g < gc; g++) {
            float w = view.getFloat("WallGW" + g, 1.0f);
            wallGroupWindows.add(Math.max(0.0f, Math.min(9.0f, w)));
            String[] arr = new String[9];
            for (int i = 0; i < 9; i++) arr[i] = view.getString("WallGS" + g + "_" + i, "");
            wallGroupSlots.add(arr);
        }
        for (int i = 0; i < wallUniqueBlockIds.size(); i++) {
            int grp = view.getInt("WallGM" + i, 0);
            String id = wallUniqueBlockIds.get(i);
            wallBlockGroup.put(id, Math.max(0, Math.min(Math.max(0, wallGroupSlots.size() - 1), grp)));
        }

        // Tower-mode bits
        if (view.contains("TowerOX")) {
            this.towerOrigin = new net.minecraft.util.math.BlockPos(view.getInt("TowerOX", 0), view.getInt("TowerOY", 0), view.getInt("TowerOZ", 0));
        } else {
            this.towerOrigin = null;
        }
        this.towerJsonFile = view.getString("TowerJson", null);
        this.towerHeight = view.getInt("TowerHeight", 0);
        this.towerCurrentY = view.getInt("TowerCurrentY", 0);
        this.towerPlacementCursor = view.getInt("TowerPlacementCursor", 0);
        int tc = view.getInt("TowerUniqCount", 0);
        if (tc > 0) {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>(tc);
            for (int i = 0; i < tc; i++) ids.add(view.getString("TowerU" + i, ""));
            this.towerUniqueBlockIds = ids;
        } else {
            this.towerUniqueBlockIds = java.util.Collections.emptyList();
        }
        // Tower block counts
        int tcs = view.getInt("TowerCountsSize", 0);
        if (tcs > 0) {
            java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
            for (int i = 0; i < tcs; i++) {
                String id = view.getString("TowerC_id" + i, "");
                int cnt = view.getInt("TowerC_cnt" + i, 0);
                if (!id.isEmpty()) counts.put(id, cnt);
            }
            this.towerBlockCounts = counts;
        } else {
            this.towerBlockCounts = java.util.Collections.emptyMap();
        }
        // Tower groups
        towerGroupSlots.clear(); towerGroupWindows.clear(); towerBlockGroup.clear();
        int tgc = view.getInt("TowerGroupCount", 0);
        for (int g = 0; g < tgc; g++) {
            float w = view.getFloat("TowerGW" + g, 1.0f);
            towerGroupWindows.add(Math.max(0.0f, Math.min(9.0f, w)));
            String[] arr = new String[9];
            for (int i = 0; i < 9; i++) arr[i] = view.getString("TowerGS" + g + "_" + i, "");
            towerGroupSlots.add(arr);
        }
        for (int i = 0; i < towerUniqueBlockIds.size(); i++) {
            int grp = view.getInt("TowerGM" + i, 0);
            String id = towerUniqueBlockIds.get(i);
            towerBlockGroup.put(id, Math.max(0, Math.min(Math.max(0, towerGroupSlots.size() - 1), grp)));
        }

        // Strategy state loaded via polymorphic dispatch (Mining, Excavation, Terraforming, Tree modes)
        initializeStrategyForCurrentMode();
        if (activeStrategy != null) {
            activeStrategy.readLegacyNbt(view);
        }

        // Terraforming-mode UI settings (remain in entity)
        this.terraformingScanRadius = view.getInt("TFormScanRadius", 2);
        this.terraformingAlpha = view.getInt("TFormAlpha", 3);
        // Terraforming gradients
        for (int i = 0; i < 9; i++) {
            terraformingGradientVertical[i] = view.getString("TFormGV" + i, "");
        }
        for (int i = 0; i < 9; i++) {
            terraformingGradientHorizontal[i] = view.getString("TFormGH" + i, "");
        }
        for (int i = 0; i < 9; i++) {
            terraformingGradientSloped[i] = view.getString("TFormGS" + i, "");
        }
        this.terraformingGradientVerticalWindow = view.getInt("TFormGVWindow", 1);
        this.terraformingGradientHorizontalWindow = view.getInt("TFormGHWindow", 1);
        this.terraformingGradientSlopedWindow = view.getInt("TFormGSWindow", 1);

        // Note: Terraforming state is now read via activeStrategy.readLegacyNbt(view) above

        // Tree-mode persisted bits
        if (view.contains("TreeOX")) {
            int x = view.getInt("TreeOX", 0);
            int y = view.getInt("TreeOY", 0);
            int z = view.getInt("TreeOZ", 0);
            this.treeOrigin = new net.minecraft.util.math.BlockPos(x, y, z);
        }
        this.treeJsonFile = view.getOptionalString("TreeJson").orElse(null);
        int presetOrdinal = view.getInt("TreeTilingPreset", 0);
        this.treeTilingPreset = ninja.trek.mc.goldgolem.tree.TilingPreset.fromOrdinal(presetOrdinal);
        // Tree state is deserialized by strategy (if active)
        // Note: treeWaitingForInventory is transient - strategy will rebuild state when building resumes

        // Tree unique block IDs
        int treeUniqCount = view.getInt("TreeUniqCount", 0);
        if (treeUniqCount > 0) {
            this.treeUniqueBlockIds = new java.util.ArrayList<>();
            for (int i = 0; i < treeUniqCount; i++) {
                String id = view.getString("TreeU" + i, "");
                if (!id.isEmpty()) {
                    this.treeUniqueBlockIds.add(id);
                }
            }
        } else {
            this.treeUniqueBlockIds = java.util.Collections.emptyList();
        }

        // Tree modules
        int treeModCount = view.getInt("TreeModuleCount", 0);
        if (treeModCount > 0) {
            this.treeModules = new java.util.ArrayList<>();
            for (int m = 0; m < treeModCount; m++) {
                int voxelSize = view.getInt("TreeMod" + m + "Size", 0);
                java.util.Set<net.minecraft.util.math.BlockPos> voxels = new java.util.HashSet<>();
                for (int v = 0; v < voxelSize; v++) {
                    int vx = view.getInt("TreeMod" + m + "V" + v + "X", 0);
                    int vy = view.getInt("TreeMod" + m + "V" + v + "Y", 0);
                    int vz = view.getInt("TreeMod" + m + "V" + v + "Z", 0);
                    voxels.add(new net.minecraft.util.math.BlockPos(vx, vy, vz));
                }
                if (!voxels.isEmpty()) {
                    this.treeModules.add(new ninja.trek.mc.goldgolem.tree.TreeModule(voxels));
                }
            }
        } else {
            this.treeModules = java.util.Collections.emptyList();
        }

        // Tree groups persistence
        int treeGroupCount = view.getInt("TreeGroupCount", 0);
        this.treeGroupSlots.clear();
        this.treeGroupWindows.clear();
        this.treeBlockGroup.clear();
        for (int g = 0; g < treeGroupCount; g++) {
            float window = view.getFloat("TreeGW" + g, 1.0f);
            this.treeGroupWindows.add(window);
            String[] arr = new String[9];
            for (int i = 0; i < 9; i++) {
                arr[i] = view.getString("TreeGS" + g + "_" + i, "");
            }
            this.treeGroupSlots.add(arr);
        }
        // Restore group mappings
        for (int i = 0; i < treeUniqueBlockIds.size(); i++) {
            String id = treeUniqueBlockIds.get(i);
            int grp = view.getInt("TreeGM" + i, 0);
            this.treeBlockGroup.put(id, grp);
        }

        // Note: treeTileCache and treeWFCBuilder are NOT persisted - they will be regenerated when building resumes
    }

    public Inventory getInventory() { return inventory; }

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

    // ========== Shared tracking field accessors (PATH/WALL modes) ==========
    public Vec3d getTrackStart() { return trackStart; }
    public void setTrackStart(Vec3d start) { this.trackStart = start; }

    public java.util.ArrayDeque<ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg> getPendingLines() { return pendingLines; }
    public ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg getCurrentLine() { return currentLine; }
    public void setCurrentLine(ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg line) { this.currentLine = line; }

    /**
     * Record a block position as placed to prevent duplicate placements.
     * @return true if this is a new placement, false if already recorded
     */
    public boolean recordPlaced(long key) {
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

    /**
     * Unrecord a block position (best-effort, no-op to avoid thrash).
     */
    public void unrecordPlaced(long key) {
        // best-effort: keep it recorded to avoid thrash; no-op
    }

    /**
     * Clear placement tracking state.
     */
    public void clearPlacementTracking() {
        recentPlaced.clear();
        placedHead = 0;
        placedSize = 0;
    }

    public String[] getGradientCopy() {
        String[] copy = new String[9];
        for (int i = 0; i < 9; i++) {
            copy[i] = (gradient[i] == null) ? "" : gradient[i];
        }
        return copy;
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
        // Debug log to help trace slot updates from client → server
        
        gradient[idx] = value;
    }
    public void setStepGradientSlot(int idx, String id) {
        if (idx < 0 || idx >= 9) return;
        String value = (id == null || id.isEmpty()) ? "" : id;

        stepGradient[idx] = value;
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

    public int getTerraformingGradientHorizontalWindow() { return terraformingGradientHorizontalWindow; }
    public void setTerraformingGradientHorizontalWindow(int w) { this.terraformingGradientHorizontalWindow = Math.max(0, Math.min(9, w)); }

    public int getTerraformingGradientSlopedWindow() { return terraformingGradientSlopedWindow; }
    public void setTerraformingGradientSlopedWindow(int w) { this.terraformingGradientSlopedWindow = Math.max(0, Math.min(9, w)); }

    // Ownership (simple UUID-based)
    private java.util.UUID ownerUuid;
    public void setOwner(PlayerEntity player) { this.ownerUuid = player.getUuid(); }
    public boolean isOwner(PlayerEntity player) { return ownerUuid != null && player != null && ownerUuid.equals(player.getUuid()); }

    public PlayerEntity getOwnerPlayer() {
        if (ownerUuid == null) return null;
        for (PlayerEntity p : this.getEntityWorld().getPlayers()) {
            if (ownerUuid.equals(p.getUuid())) return p;
        }
        return null;
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, net.minecraft.util.Hand hand) {
        if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity sp)) {
            return ActionResult.SUCCESS;
        }
        // Feeding: start building when owner feeds a gold nugget; consume one and show hearts
        var stack = player.getStackInHand(hand);
        if (stack != null && stack.isOf(net.minecraft.item.Items.GOLD_NUGGET)) {
            if (!isOwner(player)) {
                // Claim in singleplayer if prior owner offline
                var server = sp.getEntityWorld().getServer();
                boolean singleplayer = !server.isDedicated();
                boolean ownerOnline = (ownerUuid != null) && (server.getPlayerManager().getPlayer(ownerUuid) != null);
                if (singleplayer && !ownerOnline) {
                    setOwner(player);
                } else {
                    sp.sendMessage(Text.translatable("message.gold_golem.not_owner"), true);
                    return ActionResult.FAIL;
                }
            }
            if (!this.getEntityWorld().isClient()) {
                // Use polymorphic dispatch for feed interaction
                initializeStrategyForCurrentMode();
                BuildStrategy.FeedResult result = activeStrategy != null
                    ? activeStrategy.handleFeedInteraction(player)
                    : BuildStrategy.FeedResult.NOT_HANDLED;

                switch (result) {
                    case STARTED, RESUMED -> {
                        this.buildingPaths = true;
                        this.dataTracker.set(BUILDING_PATHS, true);
                        if (!player.isCreative()) stack.decrement(1);
                        spawnHearts();
                        if (result == BuildStrategy.FeedResult.RESUMED) {
                            sp.sendMessage(Text.literal("[Gold Golem] Resuming!"), true);
                        }
                        // Path/Wall/Tower modes need trackStart initialization
                        if (activeStrategy != null && activeStrategy.usesPlayerTracking()) {
                            this.trackStart = new Vec3d(this.getX(), this.getY() + 0.05, this.getZ());
                            var owner = getOwnerPlayer();
                            if (owner instanceof net.minecraft.server.network.ServerPlayerEntity spOwner) {
                                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of(), java.util.Optional.of(this.trackStart));
                            }
                            recentPlaced.clear();
                            placedHead = placedSize = 0;
                        }
                    }
                    case ALREADY_ACTIVE -> {
                        sp.sendMessage(Text.literal("[Gold Golem] Already active!"), true);
                        return ActionResult.FAIL;
                    }
                    case NOT_HANDLED -> {
                        // Default behavior: just start building
                        this.buildingPaths = true;
                        this.dataTracker.set(BUILDING_PATHS, true);
                        if (!player.isCreative()) stack.decrement(1);
                        spawnHearts();
                    }
                }
            }
            return ActionResult.CONSUME;
        }
        // Otherwise open UI as before (owner only gate)
        if (!isOwner(player)) {
            var server = sp.getEntityWorld().getServer();
            boolean singleplayer = !server.isDedicated();
            boolean ownerOnline = (ownerUuid != null) && (server.getPlayerManager().getPlayer(ownerUuid) != null);
            if (singleplayer && !ownerOnline) {
                setOwner(player);
            } else {
                sp.sendMessage(Text.translatable("message.gold_golem.not_owner"), true);
                return ActionResult.FAIL;
            }
        }
        GolemScreens.open(sp, this.getId(), this.inventory);
        return ActionResult.CONSUME;
    }

    @Override
    public boolean damage(net.minecraft.server.world.ServerWorld world, net.minecraft.entity.damage.DamageSource source, float amount) {
        var attacker = source.getAttacker();
        if (attacker instanceof PlayerEntity p && isOwner(p)) {
            // Stop building on owner hit; show angry particles; ignore damage
            this.buildingPaths = false;
            this.dataTracker.set(BUILDING_PATHS, false);

            // Use polymorphic dispatch for owner damage handling
            if (activeStrategy != null) {
                activeStrategy.handleOwnerDamage();
            }

            // Common cleanup for path-tracking modes
            this.trackStart = null;
            this.pendingLines.clear();
            this.currentLine = null;
            // Clear client lines
            if (attacker instanceof net.minecraft.server.network.ServerPlayerEntity spOwner) {
                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of(), java.util.Optional.empty());
            }

            spawnAngry();
            recentPlaced.clear();
            placedHead = placedSize = 0;
            return false; // cancel damage
        }
        return super.damage(world, source, amount);
    }

    private void spawnHearts() {
        if (this.getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.HEART, this.getX(), this.getY() + 1.0, this.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
        }
    }
    private void spawnAngry() {
        if (this.getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.ANGRY_VILLAGER, this.getX(), this.getY() + 1.0, this.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
        }
    }

    private Vec3d withFloorY(Vec3d pos) {
        var world = this.getEntityWorld();
        int bx = net.minecraft.util.math.MathHelper.floor(pos.x);
        int bz = net.minecraft.util.math.MathHelper.floor(pos.z);
        int y0 = net.minecraft.util.math.MathHelper.floor(pos.y);
        // Search down a small window to find the nearest full-cube ground
        for (int yy = y0 + 1; yy >= y0 - 8; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isFullCube(world, test)) {
                return new Vec3d(pos.x, yy + 0.05, pos.z);
            }
        }
        // Fallback: just lift slightly
        return new Vec3d(pos.x, pos.y + 0.05, pos.z);
    }

    // Place a single offset column at the given center x/z for strip index j
    public void placeOffsetAt(double x, double y, double z, double px, double pz, int stripWidth, int j, boolean xMajor, net.minecraft.util.math.Direction travelDir) {
        int w = Math.max(1, Math.min(9, stripWidth));
        var world = this.getEntityWorld();
        double ox = x + px * j;
        double oz = z + pz * j;
        int bx = MathHelper.floor(ox);
        int bz = MathHelper.floor(oz);
        int gIdx = sampleGradientIndex(w, j, bx, bz);
        if (gIdx < 0) return;
        String id = gradient[gIdx] == null ? "" : gradient[gIdx];
        if (id.isEmpty()) return;
        var ident = net.minecraft.util.Identifier.tryParse(id);
        if (ident == null) return;
        var block = net.minecraft.registry.Registries.BLOCK.get(ident);
        if (block == null) return;

        int y0 = MathHelper.floor(y);
        Integer groundY = null;
        for (int yy = y0 + 1; yy >= y0 - 6; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isFullCube(world, test)) { groundY = yy; break; }
        }
        if (groundY == null) return;
        // Replace only exposed surface within a 3-block vertical window
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos rp = new BlockPos(bx, groundY + dy, bz);
            var rs = world.getBlockState(rp);
            if (rs.isAir() || !rs.isFullCube(world, rp)) continue; // must be solid
            BlockPos ap = rp.up();
            var as = world.getBlockState(ap);
            if (as.isFullCube(world, ap)) continue; // not surface if blocked above
            if (rs.isOf(block)) break; // already desired block at surface
            long key = rp.asLong();
            if (!recordPlaced(key)) break;
            int invSlot = findItem(block.asItem());
            if (invSlot < 0) { unrecordPlaced(key); break; }
            world.setBlockState(rp, block.getDefaultState(), 3);
            var stInv = inventory.getStack(invSlot);
            stInv.decrement(1);
            inventory.setStack(invSlot, stInv);
            
            break; // only one placement per column
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
                neighborSolid = (!s1.isAir() && s1.isFullCube(world, n1)) || (!s2.isAir() && s2.isFullCube(world, n2));
            } else {
                BlockPos n1 = stepPos.north();
                BlockPos n2 = stepPos.south();
                var s1 = world.getBlockState(n1);
                var s2 = world.getBlockState(n2);
                neighborSolid = (!s1.isAir() && s1.isFullCube(world, n1)) || (!s2.isAir() && s2.isFullCube(world, n2));
            }
            if (neighborSolid) {
                BlockPos above = stepPos.up();
                var as = world.getBlockState(above);
                if (!as.isFullCube(world, above)) {
                    int gIdxStep = sampleStepGradientIndex(w, j, bx, bz);
                    if (gIdxStep >= 0) {
                        String sid = stepGradient[gIdxStep] == null ? "" : stepGradient[gIdxStep];
                        if (!sid.isEmpty()) {
                            var sIdent = net.minecraft.util.Identifier.tryParse(sid);
                            if (sIdent != null) {
                                var sBlock = net.minecraft.registry.Registries.BLOCK.get(sIdent);
                                if (sBlock != null) {
                                    // Avoid double consumption if step block equals base block
                                    if (sBlock.asItem() == block.asItem()) return;
                                    long key2 = stepPos.asLong();
                                    if (recordPlaced(key2)) {
                                        int invSlot2 = findItem(sBlock.asItem());
                                        if (invSlot2 >= 0) {
                                            var placeState = sBlock.getDefaultState();
                                            if (sBlock instanceof net.minecraft.block.StairsBlock) {
                                                try { placeState = placeState.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, travelDir); } catch (IllegalArgumentException ignored) {}
                                                try { placeState = placeState.with(net.minecraft.state.property.Properties.FACING, travelDir); } catch (IllegalArgumentException ignored) {}
                                                try { placeState = placeState.with(net.minecraft.state.property.Properties.STAIR_SHAPE, net.minecraft.block.enums.StairShape.STRAIGHT); } catch (IllegalArgumentException ignored) {}
                                                try { placeState = placeState.with(net.minecraft.state.property.Properties.WATERLOGGED, Boolean.FALSE); } catch (IllegalArgumentException ignored) {}
                                            } else if (sBlock instanceof net.minecraft.block.SlabBlock) {
                                                try { placeState = placeState.with(net.minecraft.state.property.Properties.SLAB_TYPE, net.minecraft.block.enums.SlabType.BOTTOM); } catch (IllegalArgumentException ignored) {}
                                                try { placeState = placeState.with(net.minecraft.state.property.Properties.WATERLOGGED, Boolean.FALSE); } catch (IllegalArgumentException ignored) {}
                                            }
                                            world.setBlockState(stepPos, placeState, 3);
                                            var st2 = inventory.getStack(invSlot2);
                                            st2.decrement(1);
                                            inventory.setStack(invSlot2, st2);
                                            
                                        } else {
                                            unrecordPlaced(key2);
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

    public void placeStripAt(double x, double y, double z, double px, double pz) {
        int w = Math.max(1, Math.min(9, this.pathWidth));
        int half = (w - 1) / 2;
        var world = this.getEntityWorld();
        for (int j = -half; j <= half; j++) {
            double ox = x + px * j;
            double oz = z + pz * j;
            int bx = MathHelper.floor(ox);
            int bz = MathHelper.floor(oz);
            int gIdx = sampleGradientIndex(w, j, bx, bz);
            if (gIdx < 0) continue;
            String id = gradient[gIdx] == null ? "" : gradient[gIdx];
            if (id.isEmpty()) continue;
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) continue;
            var block = net.minecraft.registry.Registries.BLOCK.get(ident);
            if (block == null) continue;

            int y0 = MathHelper.floor(y);
            Integer groundY = null;
            for (int yy = y0 + 1; yy >= y0 - 6; yy--) {
                BlockPos test = new BlockPos(bx, yy, bz);
                var st = world.getBlockState(test);
                if (!st.isAir() && st.isFullCube(world, test)) { groundY = yy; break; }
            }
            if (groundY == null) continue;
            // Replace only exposed surface within a 3-block vertical window
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos rp2 = new BlockPos(bx, groundY + dy, bz);
                var rs2 = world.getBlockState(rp2);
                if (rs2.isAir() || !rs2.isFullCube(world, rp2)) continue; // must be solid
                BlockPos ap2 = rp2.up();
                var as2 = world.getBlockState(ap2);
                if (as2.isFullCube(world, ap2)) continue; // not surface if blocked above
                if (rs2.isOf(block)) break; // already desired block at surface
                long key2 = rp2.asLong();
                if (!recordPlaced(key2)) break;
                int invSlot = findItem(block.asItem());
                if (invSlot < 0) { unrecordPlaced(key2); break; }
                world.setBlockState(rp2, block.getDefaultState(), 3);
                var stInv = inventory.getStack(invSlot);
                stInv.decrement(1);
                inventory.setStack(invSlot, stInv);
                
                break; // one placement per column
            }
        }
    }

    private int sampleGradientIndex(int stripWidth, int j, int bx, int bz) {
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
            return MathHelper.clamp(idx, 0, G - 1);
        }

        // Use symmetric jitter per distance from center so both sides match
        double u01 = deterministic01(bx, bz, dist);
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
        return MathHelper.clamp(idx, 0, G - 1);
    }

    private int sampleStepGradientIndex(int stripWidth, int j, int bx, int bz) {
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
            return MathHelper.clamp(idx, 0, G - 1);
        }

        double u01 = deterministic01(bx, bz, dist);
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
        return MathHelper.clamp(idx, 0, G - 1);
    }

    public int sampleWallGradient(String[] slots, float window, int moduleHeight, int relY, BlockPos pos) {
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
            double u = deterministic01(pos.getX(), pos.getZ(), relY);
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

    private double deterministic01(int bx, int bz, int j) {
        long v = 0x9E3779B97F4A7C15L;
        v ^= ((long) this.getId() * 0x9E3779B97F4A7C15L);
        v ^= ((long) bx * 0xC2B2AE3D27D4EB4FL);
        v ^= ((long) bz * 0x165667B19E3779F9L);
        v ^= ((long) j * 0x85EBCA77C2B2AE63L);
        v ^= (v >>> 33);
        v *= 0xff51afd7ed558ccdL;
        v ^= (v >>> 33);
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= (v >>> 33);
        return (Double.longBitsToDouble((v >>> 12) | 0x3FF0000000000000L) - 1.0);
    }

    // WALL MODE helpers and types
    private double getWallLongestHoriz() {
        double longest = 0.0;
        for (var t : wallTemplates) longest = Math.max(longest, t.horizLen());
        return longest;
    }

    private ModulePlacement chooseNextModule(Vec3d anchor, Vec3d playerPos) {
        if (wallTemplates == null || wallTemplates.isEmpty()) return null;
        double bestScore = Double.POSITIVE_INFINITY;
        ModulePlacement best = null;
        for (int ti = 0; ti < wallTemplates.size(); ti++) {
            var tpl = wallTemplates.get(ti);
            int dyModule = tpl.bMarker.getY() - tpl.aMarker.getY();
            int dxModule = tpl.bMarker.getX() - tpl.aMarker.getX();
            int dzModule = tpl.bMarker.getZ() - tpl.aMarker.getZ();
            for (int rot = 0; rot < 4; rot++) {
                for (int mir = 0; mir < 2; mir++) {
                    int[] d = rotateAndMirror(dxModule, dyModule, dzModule, rot, mir == 1);
                    Vec3d end = new Vec3d(anchor.x + d[0], anchor.y + d[1], anchor.z + d[2]);
                    // Y rule: toward player Y and no overshoot
                    double dyNeed = playerPos.y - anchor.y;
                    double dyStep = d[1];
                    boolean okY = Math.signum(dyStep) == Math.signum(dyNeed) || Math.abs(dyNeed) < 1e-6 || dyStep == 0.0;
                    if (okY) okY = Math.abs(dyStep) <= Math.abs(dyNeed) + 1e-6;
                    double yScore = Math.abs(dyNeed - dyStep);
                    double xz = Math.hypot(end.x - playerPos.x, end.z - playerPos.z);
                    double score = (okY ? 0.0 : 1000.0) + yScore * 10.0 + xz;
                    if (score < bestScore) { bestScore = score; best = new ModulePlacement(ti, rot, mir == 1, anchor, end); }
                }
            }
        }
        // Consider empty corner (gap only) turning left/right by wall thickness when useful
        int t = Math.max(1, this.wallJoinUSize);
        int lx = wallLastDirX, lz = wallLastDirZ;
        int[][] perps = new int[][]{ new int[]{-lz, lx}, new int[]{lz, -lx} };
        for (int[] pv : perps) {
            int dx = pv[0] * t;
            int dz = pv[1] * t;
            Vec3d end = new Vec3d(anchor.x + dx, anchor.y, anchor.z + dz);
            double dyNeed = playerPos.y - anchor.y;
            double yScore = Math.abs(dyNeed - 0.0);
            double xz = Math.hypot(end.x - playerPos.x, end.z - playerPos.z);
            double score = yScore * 10.0 + xz + 0.5; // slight penalty vs real module
            if (score < bestScore) {
                bestScore = score;
                best = new GapPlacement(dx, dz, anchor, end, pv[0], pv[1]);
            }
        }
        return best;
    }

    private static int[] rotateAndMirror(int x, int y, int z, int rot, boolean mirror) {
        int rx = x, rz = z;
        switch (rot & 3) {
            case 1 -> { int ox = rx; rx = -rz; rz = ox; }
            case 2 -> { rx = -rx; rz = -rz; }
            case 3 -> { int ox = rx; rx = rz; rz = -ox; }
        }
        if (mirror) rx = -rx;
        return new int[]{rx, y, rz};
    }

    private void placeBlockStateAt(int wx, int wy, int wz, net.minecraft.block.BlockState baseState, int rot, boolean mirror) {
        var world = this.getEntityWorld();
        BlockPos pos = new BlockPos(wx, wy, wz);
        net.minecraft.block.Block block = baseState.getBlock();
        var current = world.getBlockState(pos);
        if (!current.isAir() && current.isOf(block)) return;
        long key = pos.asLong();
        if (!recordPlaced(key)) return;
        int invSlot = findItem(block.asItem());
        if (invSlot < 0) { unrecordPlaced(key); return; }
        net.minecraft.util.BlockRotation rotation = switch (rot & 3) {
            case 1 -> net.minecraft.util.BlockRotation.CLOCKWISE_90;
            case 2 -> net.minecraft.util.BlockRotation.CLOCKWISE_180;
            case 3 -> net.minecraft.util.BlockRotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.util.BlockRotation.NONE;
        };
        net.minecraft.util.BlockMirror mir = mirror ? net.minecraft.util.BlockMirror.LEFT_RIGHT : net.minecraft.util.BlockMirror.NONE;
        net.minecraft.block.BlockState place = baseState;
        try { place = place.rotate(rotation); } catch (Throwable ignored) {}
        try { place = place.mirror(mir); } catch (Throwable ignored) {}
        try {
            if (place.contains(net.minecraft.state.property.Properties.WATERLOGGED)) {
                place = place.with(net.minecraft.state.property.Properties.WATERLOGGED, Boolean.FALSE);
            }
        } catch (Throwable ignored) {}
        world.setBlockState(pos, place, 3);
        var st = inventory.getStack(invSlot);
        st.decrement(1);
        inventory.setStack(invSlot, st);
    }

    private class ModulePlacement {
        final int tplIndex;
        final int rot; // 0..3
        final boolean mirror;
        final Vec3d anchor;
        final Vec3d end;
        java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel> voxels;
        int cursor = 0;
        boolean joinPlaced = false;
        ModulePlacement(int tplIndex, int rot, boolean mirror, Vec3d anchor, Vec3d end) {
            this.tplIndex = tplIndex; this.rot = rot; this.mirror = mirror; this.anchor = anchor; this.end = end;
        }
        Vec3d anchor() { return anchor; }
        Vec3d end() { return end; }
        void begin(GoldGolemEntity golem) {
            var tpl = wallTemplates.get(tplIndex);
            this.voxels = tpl.voxels;
            // update last direction
            int dx = wallTemplates.get(tplIndex).bMarker.getX() - wallTemplates.get(tplIndex).aMarker.getX();
            int dz = wallTemplates.get(tplIndex).bMarker.getZ() - wallTemplates.get(tplIndex).aMarker.getZ();
            int[] d = rotateAndMirror(dx, 0, dz, rot, mirror);
            if (Math.abs(d[0]) >= Math.abs(d[2])) { wallLastDirX = Integer.signum(d[0]); wallLastDirZ = 0; }
            else { wallLastDirX = 0; wallLastDirZ = Integer.signum(d[2]); }
        }
        void placeSome(GoldGolemEntity golem, int maxOps) {
            if (!joinPlaced) { placeJoinSliceAtAnchor(); joinPlaced = true; }
            var tpl = wallTemplates.get(tplIndex);
            int ops = 0;
            // Calculate module height for gradient sampling
            int moduleMinY = tpl.minY;
            int moduleMaxY = tpl.voxels.stream().mapToInt(v -> v.rel.getY()).max().orElse(moduleMinY);
            int moduleHeight = Math.max(1, moduleMaxY - moduleMinY + 1);

            while (cursor < voxels.size() && ops < maxOps) {
                var v = voxels.get(cursor++);
                int rx = v.rel.getX();
                int ry = v.rel.getY();
                int rz = v.rel.getZ();
                int[] d = rotateAndMirror(rx, ry, rz, rot, mirror);
                int wx = MathHelper.floor(anchor.x) + d[0];
                int wy = MathHelper.floor(anchor.y) + d[1];
                int wz = MathHelper.floor(anchor.z) + d[2];

                // Apply gradient sampling for wall mode
                BlockState stateToPlace = v.state;
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(v.state.getBlock()).toString();
                Integer groupIdx = wallBlockGroup.get(blockId);
                if (groupIdx != null && groupIdx >= 0 && groupIdx < wallGroupSlots.size()) {
                    String[] slots = wallGroupSlots.get(groupIdx);
                    float window = (groupIdx < wallGroupWindows.size()) ? wallGroupWindows.get(groupIdx) : 1.0f;
                    // Calculate relative Y position within module (0 at bottom)
                    int relY = ry - moduleMinY;
                    int sampledIndex = golem.sampleWallGradient(slots, window, moduleHeight, relY, new BlockPos(wx, wy, wz));
                    if (sampledIndex >= 0 && sampledIndex < 9) {
                        String sampledId = slots[sampledIndex];
                        if (sampledId != null && !sampledId.isEmpty()) {
                            BlockState sampledState = golem.getBlockStateFromId(sampledId);
                            if (sampledState != null) {
                                stateToPlace = sampledState;
                            }
                        }
                    }
                }

                placeBlockStateAt(wx, wy, wz, stateToPlace, rot, mirror);
                ops++;
            }
        }
        boolean done() { return cursor >= (voxels == null ? 0 : voxels.size()); }

        private void placeJoinSliceAtAnchor() {
            if (wallJoinTemplate == null || wallJoinTemplate.isEmpty()) return;
            var tpl = wallTemplates.get(tplIndex);
            int dxm = tpl.bMarker.getX() - tpl.aMarker.getX();
            int dzm = tpl.bMarker.getZ() - tpl.aMarker.getZ();
            int[] d = rotateAndMirror(dxm, 0, dzm, rot, mirror);
            int fx = Integer.signum(d[0]);
            int fz = Integer.signum(d[2]);
            int px = -fz;
            int pz = fx;
            int ax = MathHelper.floor(anchor.x);
            int ay = MathHelper.floor(anchor.y);
            int az = MathHelper.floor(anchor.z);
            for (JoinEntry e : wallJoinTemplate) {
                if (e.id == null || e.id.isEmpty()) continue;
                int wx = ax + px * e.du;
                int wy = ay + e.dy;
                int wz = az + pz * e.du;
                var ident = net.minecraft.util.Identifier.tryParse(e.id);
                if (ident == null) continue;
                var block = net.minecraft.registry.Registries.BLOCK.get(ident);
                if (block == null) continue;
                placeBlockStateAt(wx, wy, wz, block.getDefaultState(), rot, mirror);
            }
        }
    }

    private final class GapPlacement extends ModulePlacement {
        final int dx, dz;
        final int dirx, dirz;
        GapPlacement(int dx, int dz, Vec3d anchor, Vec3d end, int dirx, int dirz) {
            super(-1, 0, false, anchor, end);
            this.dx = dx; this.dz = dz; this.dirx = dirx; this.dirz = dirz;
        }
        @Override void begin(GoldGolemEntity golem) {
            this.voxels = java.util.Collections.emptyList();
            wallLastDirX = dirx; wallLastDirZ = dirz;
        }
        @Override void placeSome(GoldGolemEntity golem, int maxOps) { /* nothing */ }
        @Override boolean done() { return true; }
    }

    public int findItem(net.minecraft.item.Item item) {
        for (int i = 0; i < inventory.size(); i++) {
            var st = inventory.getStack(i);
            if (!st.isEmpty() && st.isOf(item)) return i;
        }
        return -1;
    }

    /**
     * Decrement one item from an inventory slot.
     */
    public void decrementInventorySlot(int slot) {
        if (slot < 0 || slot >= inventory.size()) return;
        var st = inventory.getStack(slot);
        if (!st.isEmpty()) {
            st.decrement(1);
            inventory.setStack(slot, st);
        }
    }

    // removed: runtime block use logging helper

}

class FollowGoldNuggetHolderGoal extends Goal {
    private final GoldGolemEntity golem;
    private final double speed;
    private final double stopDistance;
    private PlayerEntity target;

    public FollowGoldNuggetHolderGoal(GoldGolemEntity golem, double speed, double stopDistance) {
        this.golem = golem;
        this.speed = speed;
        this.stopDistance = stopDistance;
    }

    @Override
    public boolean canStart() {
        if (golem.isBuildingPaths()) return false;
        if (golem.getBuildMode() == BuildMode.MINING) return false; // Never follow in mining mode
        // Only follow the owner; find the owner player in-world
        PlayerEntity owner = null;
        for (PlayerEntity player : golem.getEntityWorld().getPlayers()) {
            if (golem.isOwner(player)) { owner = player; break; }
        }
        if (owner == null) return false;
        if (!isHoldingNugget(owner)) return false;
        if (golem.squaredDistanceTo(owner) > (24.0 * 24.0)) return false;
        this.target = owner;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (golem.isBuildingPaths()) return false;
        if (golem.getBuildMode() == BuildMode.MINING) return false; // Never follow in mining mode
        if (target == null || !target.isAlive()) return false;
        // Ensure target remains the owner
        if (!golem.isOwner(target)) return false;
        if (!isHoldingNugget(target)) return false;
        double distSq = golem.squaredDistanceTo(target);
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
        this.golem.getLookControl().lookAt(target, 30.0f, 30.0f);
        double distSq = golem.squaredDistanceTo(target);
        if (distSq > (stopDistance * stopDistance)) {
            this.golem.getNavigation().startMovingTo(target, this.speed);
        } else {
            this.golem.getNavigation().stop();
        }
    }

    private static boolean isHoldingNugget(PlayerEntity player) {
        var nugget = net.minecraft.item.Items.GOLD_NUGGET;
        return player.getMainHandStack().isOf(nugget) || player.getOffHandStack().isOf(nugget);
    }
}

class PathingAwareWanderGoal extends WanderAroundFarGoal {
    private final GoldGolemEntity golem;

    public PathingAwareWanderGoal(GoldGolemEntity golem, double speed) {
        super(golem, speed);
        this.golem = golem;
    }

    @Override
    public boolean canStart() {
        if (golem.isBuildingPaths()) return false;
        if (golem.getBuildMode() == BuildMode.MINING) return false; // Never wander in mining mode
        return super.canStart();
    }

    @Override
    public boolean shouldContinue() {
        if (golem.isBuildingPaths()) return false;
        if (golem.getBuildMode() == BuildMode.MINING) return false; // Never wander in mining mode
        return super.shouldContinue();
    }

    @Override
    protected Vec3d getWanderTarget() {
        Vec3d base = super.getWanderTarget();

        // Anchor to a player: prefer owner; otherwise nearest player
        PlayerEntity anchor = getAnchorPlayer();
        if (anchor == null) return base;

        double cx = anchor.getX();
        double cy = anchor.getY();
        double cz = anchor.getZ();
        double max = 12.0;
        double maxSq = max * max;

        if (base == null) {
            // No base target; pick a random point within the radius around the player
            java.util.Random rnd = new java.util.Random(golem.getRandom().nextLong());
            double angle = rnd.nextDouble() * Math.PI * 2.0;
            double r = 6.0 + rnd.nextDouble() * 6.0; // 6..12
            return new Vec3d(cx + Math.cos(angle) * r, cy, cz + Math.sin(angle) * r);
        }

        double dx = base.x - cx;
        double dy = base.y - cy;
        double dz = base.z - cz;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq <= maxSq) return base;

        double dist = Math.sqrt(distSq);
        if (dist < 1e-4) return new Vec3d(cx, cy, cz);
        double scale = max / dist;
        // Clamp to the 12-block sphere around the anchor player; keep base Y for smoother nav
        return new Vec3d(cx + dx * scale, base.y, cz + dz * scale);
    }

    private PlayerEntity getAnchorPlayer() {
        // Prefer the owner if present
        PlayerEntity owner = null;
        for (PlayerEntity p : golem.getEntityWorld().getPlayers()) {
            if (golem.isOwner(p)) { owner = p; break; }
        }
        if (owner != null) return owner;

        // Otherwise, use the nearest player
        PlayerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (PlayerEntity p : golem.getEntityWorld().getPlayers()) {
            double d = golem.squaredDistanceTo(p);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }
}
