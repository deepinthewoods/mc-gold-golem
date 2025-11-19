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

import java.util.Optional;

public class GoldGolemEntity extends PathAwareEntity {
    public enum BuildMode { PATH, WALL, TOWER, MINING, EXCAVATION, TERRAFORMING, TREE }
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

    private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE);
    private final String[] gradient = new String[9];
    private final String[] stepGradient = new String[9];
    private int gradientWindow = 1; // window width in slot units (0..9)
    private int stepGradientWindow = 1; // window width for step gradient
    private int pathWidth = 3;
    private boolean buildingPaths = false;
    private BuildMode buildMode = BuildMode.PATH;
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
    private final java.util.List<Integer> wallGroupWindows = new java.util.ArrayList<>();
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
    private final java.util.List<Integer> towerGroupWindows = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> towerBlockGroup = new java.util.HashMap<>();
    // Tower building state
    private int towerCurrentY = 0; // current Y layer being placed (0 = bottom)
    private int towerPlacementCursor = 0; // cursor within current Y layer

    // Mining-mode state
    private BlockPos miningChestPos = null; // chest location for deposit
    private net.minecraft.util.math.Direction miningDirection = null; // primary tunnel direction (opposite to chest)
    private BlockPos miningStartPos = null; // starting position (at chest)
    private int miningBranchDepth = 16; // how far each branch extends (1-512)
    private int miningBranchSpacing = 3; // spacing between branches (1-16)
    private int miningTunnelHeight = 2; // tunnel height (2-6)
    private int miningPrimaryProgress = 0; // blocks mined in primary tunnel
    private int miningCurrentBranch = -1; // -1 = primary, 0+ = branch index
    private boolean miningBranchLeft = true; // true = mining left branch, false = right
    private int miningBranchProgress = 0; // blocks mined in current branch
    private boolean miningReturningToChest = false; // returning to deposit
    private boolean miningIdleAtChest = false; // waiting for chest space or nugget
    private java.util.Set<BlockPos> miningPendingOres = new java.util.HashSet<>(); // ores detected but not yet mined
    private String miningBuildingBlockType = null; // block ID to keep for placing under feet
    private int miningBreakProgress = 0; // ticks spent breaking current block
    private BlockPos miningCurrentTarget = null; // block currently being broken

    // Excavation-mode state
    private BlockPos excavationChestPos1 = null; // first chest location
    private BlockPos excavationChestPos2 = null; // second chest location
    private net.minecraft.util.math.Direction excavationDir1 = null; // direction of first chest
    private net.minecraft.util.math.Direction excavationDir2 = null; // direction of second chest
    private BlockPos excavationStartPos = null; // starting position (center/gold block)
    private int excavationHeight = 3; // tunnel height (1-5) - mines upward
    private int excavationDepth = 16; // radius from center (1-64)
    private int excavationCurrentRing = 0; // current concentric square ring (0 = center)
    private int excavationRingProgress = 0; // position within current ring (clockwise)
    private boolean excavationReturningToChest = false; // returning to deposit
    private boolean excavationIdleAtStart = false; // waiting for nugget
    private String excavationBuildingBlockType = null; // block ID to keep for placing floor
    private int excavationBreakProgress = 0; // ticks spent breaking current block
    private BlockPos excavationCurrentTarget = null; // block currently being broken

    // Terraforming-mode state
    private BlockPos terraformingOrigin = null; // center of 3x3 gold platform
    private java.util.List<BlockPos> terraformingSkeletonBlocks = null; // skeleton block positions
    private java.util.Set<net.minecraft.block.Block> terraformingSkeletonTypes = null; // skeleton block types
    private java.util.Map<Integer, java.util.List<BlockPos>> terraformingShellByLayer = null; // Y -> shell positions
    private int terraformingCurrentY = 0; // current Y layer being built
    private int terraformingLayerProgress = 0; // progress within current layer
    private BlockPos terraformingStartPos = null; // where golem was summoned
    private int terraformingScanRadius = 2; // slope detection radius (1-5)
    private int terraformingMinY = 0; // minimum Y bound
    private int terraformingMaxY = 0; // maximum Y bound
    private int terraformingAlpha = 3; // alpha shape parameter (concavity control, 1-10)
    // Three gradients for terraforming mode
    private final String[] terraformingGradientVertical = new String[9]; // steep/cliff surfaces
    private final String[] terraformingGradientHorizontal = new String[9]; // flat surfaces
    private final String[] terraformingGradientSloped = new String[9]; // diagonal surfaces
    private int terraformingGradientVerticalWindow = 1; // window for vertical gradient (0..9)
    private int terraformingGradientHorizontalWindow = 1; // window for horizontal gradient (0..9)
    private int terraformingGradientSlopedWindow = 1; // window for sloped gradient (0..9)

    // Tree-mode state
    private java.util.List<ninja.trek.mc.goldgolem.tree.TreeModule> treeModules = java.util.Collections.emptyList();
    private java.util.List<String> treeUniqueBlockIds = java.util.Collections.emptyList();
    private net.minecraft.util.math.BlockPos treeOrigin = null; // second gold block position
    private String treeJsonFile = null; // saved snapshot path (relative to game dir)
    private ninja.trek.mc.goldgolem.tree.TilingPreset treeTilingPreset = ninja.trek.mc.goldgolem.tree.TilingPreset.SMALL_3x3;
    private ninja.trek.mc.goldgolem.tree.TreeTileCache treeTileCache = null; // lazy-loaded when building starts
    private ninja.trek.mc.goldgolem.tree.TreeWFCBuilder treeWFCBuilder = null; // active WFC state
    private boolean treeTilesCached = false;
    private boolean treeWaitingForInventory = false; // true when out of blocks, waiting for restock
    // Tree UI state: dynamic gradient groups (same pattern as wall/tower)
    private final java.util.List<String[]> treeGroupSlots = new java.util.ArrayList<>(); // each String[9]
    private final java.util.List<Integer> treeGroupWindows = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> treeBlockGroup = new java.util.HashMap<>();

    private Vec3d trackStart = null;
    private java.util.ArrayDeque<LineSeg> pendingLines = new java.util.ArrayDeque<>();
    private LineSeg currentLine = null;
    private int placeCooldown = 0;
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
    public BuildMode getBuildMode() { return buildMode; }
    public void setBuildMode(BuildMode mode) { this.buildMode = mode == null ? BuildMode.PATH : mode; }
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
            wallGroupWindows.add(1);
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
    public java.util.List<Integer> getWallGroupWindows() { return new java.util.ArrayList<>(wallGroupWindows); }
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
            wallGroupWindows.add(1);
            group = wallGroupSlots.size() - 1;
        } else if (group >= wallGroupSlots.size()) {
            return;
        }
        wallBlockGroup.put(blockId, group);
    }
    public void setWallGroupWindow(int group, int window) {
        if (group < 0 || group >= wallGroupWindows.size()) return;
        wallGroupWindows.set(group, Math.max(0, Math.min(9, window)));
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
    public ninja.trek.mc.goldgolem.tower.TowerModuleTemplate getTowerTemplate() { return towerTemplate; }

    public void initTowerGroups(java.util.List<String> uniqueBlocks) {
        towerGroupSlots.clear(); towerGroupWindows.clear(); towerBlockGroup.clear();
        // Default: one group per unique block type
        int idx = 0;
        for (String id : uniqueBlocks) {
            String[] arr = new String[9];
            towerGroupSlots.add(arr);
            towerGroupWindows.add(1);
            towerBlockGroup.put(id, idx);
            idx++;
        }
    }
    public java.util.List<Integer> getTowerBlockGroupMap(java.util.List<String> uniqueBlocks) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>(uniqueBlocks.size());
        for (String id : uniqueBlocks) out.add(towerBlockGroup.getOrDefault(id, 0));
        return out;
    }
    public java.util.List<Integer> getTowerGroupWindows() { return new java.util.ArrayList<>(towerGroupWindows); }
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
            towerGroupWindows.add(1);
            group = towerGroupSlots.size() - 1;
        } else if (group >= towerGroupSlots.size()) {
            return;
        }
        towerBlockGroup.put(blockId, group);
    }
    public void setTowerGroupWindow(int group, int window) {
        if (group < 0 || group >= towerGroupWindows.size()) return;
        towerGroupWindows.set(group, Math.max(0, Math.min(9, window)));
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
            // Invalidate tile cache so it will be regenerated with new size
            this.treeTilesCached = false;
            this.treeTileCache = null;
            this.treeWFCBuilder = null;
        }
    }

    public void initTreeGroups(java.util.List<String> uniqueBlocks) {
        treeGroupSlots.clear(); treeGroupWindows.clear(); treeBlockGroup.clear();
        // Default: one group per unique block type
        int idx = 0;
        for (String id : uniqueBlocks) {
            String[] arr = new String[9];
            treeGroupSlots.add(arr);
            treeGroupWindows.add(1);
            treeBlockGroup.put(id, idx);
            idx++;
        }
    }
    public java.util.List<Integer> getTreeBlockGroupMap(java.util.List<String> uniqueBlocks) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>(uniqueBlocks.size());
        for (String id : uniqueBlocks) out.add(treeBlockGroup.getOrDefault(id, 0));
        return out;
    }
    public java.util.List<Integer> getTreeGroupWindows() { return new java.util.ArrayList<>(treeGroupWindows); }
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
            treeGroupWindows.add(1);
            group = treeGroupSlots.size() - 1;
        } else if (group >= treeGroupSlots.size()) {
            return;
        }
        treeBlockGroup.put(blockId, group);
    }
    public void setTreeGroupWindow(int group, int window) {
        if (group < 0 || group >= treeGroupWindows.size()) return;
        treeGroupWindows.set(group, Math.max(0, Math.min(9, window)));
    }
    public void setTreeGroupSlot(int group, int slot, String id) {
        if (group < 0 || group >= treeGroupSlots.size()) return;
        if (slot < 0 || slot >= 9) return;
        String[] arr = treeGroupSlots.get(group);
        arr[slot] = (id == null) ? "" : id;
    }

    // Mining mode configuration
    public void setMiningConfig(BlockPos chestPos, net.minecraft.util.math.Direction miningDir, BlockPos startPos) {
        this.miningChestPos = chestPos;
        this.miningDirection = miningDir;
        this.miningStartPos = startPos;
        this.miningIdleAtChest = true; // Start idle, waiting for gold nugget
    }
    public void setMiningSliders(int branchDepth, int branchSpacing, int tunnelHeight) {
        this.miningBranchDepth = Math.max(1, Math.min(512, branchDepth));
        this.miningBranchSpacing = Math.max(1, Math.min(16, branchSpacing));
        this.miningTunnelHeight = Math.max(2, Math.min(6, tunnelHeight));
    }
    public int getMiningBranchDepth() { return miningBranchDepth; }
    public int getMiningBranchSpacing() { return miningBranchSpacing; }
    public int getMiningTunnelHeight() { return miningTunnelHeight; }

    // Excavation mode configuration
    public void setExcavationConfig(BlockPos chest1, BlockPos chest2, net.minecraft.util.math.Direction dir1, net.minecraft.util.math.Direction dir2, BlockPos startPos) {
        this.excavationChestPos1 = chest1;
        this.excavationChestPos2 = chest2;
        this.excavationDir1 = dir1;
        this.excavationDir2 = dir2;
        this.excavationStartPos = startPos;
        this.excavationIdleAtStart = true; // Start idle, waiting for gold nugget
    }
    public void setExcavationSliders(int height, int depth) {
        this.excavationHeight = Math.max(1, Math.min(5, height));
        this.excavationDepth = Math.max(1, Math.min(64, depth));
    }
    public int getExcavationHeight() { return excavationHeight; }
    public int getExcavationDepth() { return excavationDepth; }

    public void setTerraformingConfig(ninja.trek.mc.goldgolem.terraforming.TerraformingDefinition def, BlockPos startPos) {
        this.terraformingOrigin = def.origin();
        this.terraformingSkeletonBlocks = new java.util.ArrayList<>(def.skeletonBlocks());
        this.terraformingSkeletonTypes = new java.util.HashSet<>(def.skeletonTypes());
        this.terraformingStartPos = startPos;
        this.terraformingMinY = def.minBound().getY();
        this.terraformingMaxY = def.maxBound().getY();

        // Generate shell using alpha shapes for each Y layer
        this.terraformingShellByLayer = new java.util.HashMap<>();
        for (int y = terraformingMinY; y <= terraformingMaxY; y++) {
            java.util.List<BlockPos> skeletonAtY = new java.util.ArrayList<>();
            for (BlockPos pos : terraformingSkeletonBlocks) {
                if (pos.getY() == y) {
                    skeletonAtY.add(pos);
                }
            }

            if (!skeletonAtY.isEmpty()) {
                java.util.Set<BlockPos> shellSet = ninja.trek.mc.goldgolem.terraforming.AlphaShape.generateShell(
                        skeletonAtY, terraformingAlpha, y);
                terraformingShellByLayer.put(y, new java.util.ArrayList<>(shellSet));
            }
        }

        // Start from minimum Y
        this.terraformingCurrentY = terraformingMinY;
        this.terraformingLayerProgress = 0;
    }

    public void setTerraformingScanRadius(int radius) {
        this.terraformingScanRadius = Math.max(1, Math.min(5, radius));
    }

    public int getTerraformingScanRadius() {
        return terraformingScanRadius;
    }

    public void setTerraformingAlpha(int alpha) {
        this.terraformingAlpha = Math.max(1, Math.min(10, alpha));
        // Regenerate shell if already initialized
        if (terraformingSkeletonBlocks != null && !terraformingSkeletonBlocks.isEmpty()) {
            terraformingShellByLayer = new java.util.HashMap<>();
            for (int y = terraformingMinY; y <= terraformingMaxY; y++) {
                java.util.List<BlockPos> skeletonAtY = new java.util.ArrayList<>();
                for (BlockPos pos : terraformingSkeletonBlocks) {
                    if (pos.getY() == y) {
                        skeletonAtY.add(pos);
                    }
                }

                if (!skeletonAtY.isEmpty()) {
                    java.util.Set<BlockPos> shellSet = ninja.trek.mc.goldgolem.terraforming.AlphaShape.generateShell(
                            skeletonAtY, terraformingAlpha, y);
                    terraformingShellByLayer.put(y, new java.util.ArrayList<>(shellSet));
                }
            }
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

        String side = this.getEntityWorld().isClient() ? "CLIENT" : "SERVER";
        buildingPaths = isBuildingPaths(); // Read from data tracker
        if (buildingPaths && anyAnimating) {
            System.out.println("[" + side + "] Building - Left anim: " + leftHandAnimationTick + ", Right anim: " + rightHandAnimationTick);
        }

        if (this.getEntityWorld().isClient()) return;
        if (buildingPaths) {
            // Increment placement tick counter (2-tick cycle)
            placementTickCounter = (placementTickCounter + 1) % 2;

            // Mining, Excavation, and Terraforming modes have different behavior - doesn't track owner
            if (this.buildMode == BuildMode.MINING) { tickMiningMode(); return; }
            if (this.buildMode == BuildMode.EXCAVATION) { tickExcavationMode(); return; }
            if (this.buildMode == BuildMode.TERRAFORMING) { tickTerraformingMode(); return; }

            // Look at owner while building (Path/Wall/Tower modes)
            PlayerEntity owner = getOwnerPlayer();
            if (owner != null) {
                this.getLookControl().lookAt(owner, 30.0f, 30.0f);
            }
            if (this.buildMode == BuildMode.WALL) { tickWallMode(owner); return; }
            if (this.buildMode == BuildMode.TOWER) { tickTowerMode(owner); return; }
            if (this.buildMode == BuildMode.TREE) { tickTreeMode(owner); return; }
            // Lines are now rendered client-side using RenderLayer lines via networking; no server particles.
            // Track lines while owner moves (require grounded for stability, no distance gate in pathing mode)
            if (owner != null && owner.isOnGround()) {
                // Capture slightly above the player's feet at creation time
                Vec3d p = new Vec3d(owner.getX(), owner.getY() + 0.05, owner.getZ());
                if (trackStart == null) {
                    trackStart = p;
                } else {
                    // Only create a new 3m segment once the player is 4m away from the current anchor
                    // If the player moved far, catch up by placing multiple 3m segments gated by 4m distance
                    double dist = trackStart.distanceTo(p);
                    while (dist >= 4.0) {
                        Vec3d dir = p.subtract(trackStart);
                        double len = dir.length();
                        if (len < 1e-6) break;
                        Vec3d unit = dir.multiply(1.0 / len);
                        Vec3d end = trackStart.add(unit.multiply(3.0));
                        enqueueLine(trackStart, end);
                        trackStart = end;
                        dist = trackStart.distanceTo(p);
                    }
                }
            }
            // Process current line
            if (currentLine == null) {
                currentLine = pendingLines.pollFirst();
                if (currentLine != null) {
                    currentLine.begin(this);
                    // Kick off movement toward the end of the line for steady progress
                    int endIdx = Math.max(0, currentLine.cells.size() - 1);
                    Vec3d tgt = currentLine.pointAtIndex(endIdx);
                    double ty0 = computeGroundTargetY(tgt);
                    this.getNavigation().startMovingTo(tgt.x, ty0, tgt.z, 1.1);
                    // Notify client that the current line started (so it renders while queue may be empty)
                    if (this.getEntityWorld() instanceof ServerWorld) {
                        var owner2 = getOwnerPlayer();
                        if (owner2 instanceof net.minecraft.server.network.ServerPlayerEntity sp2) {
                            java.util.List<net.minecraft.util.math.Vec3d> list2 = new java.util.ArrayList<>();
                            if (currentLine != null) {
                                list2.add(currentLine.a);
                                list2.add(currentLine.b);
                            }
                            for (LineSeg s2 : pendingLines) {
                                list2.add(s2.a);
                                list2.add(s2.b);
                            }
                            java.util.Optional<Vec3d> anchor2 = java.util.Optional.ofNullable(this.trackStart);
                            ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp2, this.getId(), list2, anchor2);
                        }
                    }
                }
            }
            if (currentLine != null) {
                // Placement paced by golem progress along the line
                // Place 1 block every 2 ticks, alternating hands
                if (placementTickCounter == 0) {
                    int endIdxPl = Math.max(0, currentLine.cells.size() - 1);
                    int progressCell = currentLine.progressCellIndex(this.getX(), this.getZ());
                    Vec3d endPtPl = currentLine.pointAtIndex(endIdxPl);
                    double exPl = this.getX() - endPtPl.x;
                    double ezPl = this.getZ() - endPtPl.z;
                    boolean nearEndPl = (exPl * exPl + ezPl * ezPl) <= (1.25 * 1.25);
                    int boundCell = (nearEndPl || progressCell >= (endIdxPl - 1)) ? endIdxPl : progressCell;

                    // Place exactly 1 block and get its position
                    BlockPos placedBlock = currentLine.placeNextBlock(this, boundCell);

                    if (placedBlock != null) {
                        BlockPos previewBlock = currentLine.getNextUnplacedBlock(boundCell);
                        if (leftHandActive) {
                            System.out.println("[SERVER] Placing block with LEFT hand at " + placedBlock + ", starting animation");
                            beginHandAnimation(true, placedBlock, previewBlock);
                        } else {
                            System.out.println("[SERVER] Placing block with RIGHT hand at " + placedBlock + ", starting animation");
                            beginHandAnimation(false, placedBlock, previewBlock);
                        }

                        // Alternate hands
                        leftHandActive = !leftHandActive;
                    } else {
                        System.out.println("[SERVER] Placement tick but no block placed (placedBlock is null)");
                    }
                }

                // Always path toward the end of the current segment to ensure we reach it
                int endIdx = Math.max(0, currentLine.cells.size() - 1);
                Vec3d end = currentLine.pointAtIndex(endIdx);
                double ty = computeGroundTargetY(end);
                this.getNavigation().startMovingTo(end.x, ty, end.z, 1.1);
                // Detect stuck navigation and recover by teleporting closer to the end
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

                // Complete the line only when all pending done AND we've effectively reached the end
                if (currentLine.isFullyProcessed()) {
                    if (distSq <= 0.75 * 0.75 || this.getNavigation().isIdle()) {
                        LineSeg done = currentLine;
                        LineSeg next = pendingLines.peekFirst();
                        if (next != null) placeCornerFill(done, next);
                        currentLine = null;
                        // Update client after completing a line so it can drop the finished segment
                        if (this.getEntityWorld() instanceof ServerWorld) {
                            var owner2 = getOwnerPlayer();
                            if (owner2 instanceof net.minecraft.server.network.ServerPlayerEntity sp2) {
                                java.util.List<net.minecraft.util.math.Vec3d> list = new java.util.ArrayList<>();
                                for (LineSeg s : pendingLines) {
                                    list.add(s.a);
                                    list.add(s.b);
                                }
                                java.util.Optional<Vec3d> anchor = java.util.Optional.ofNullable(this.trackStart);
                                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp2, this.getId(), list, anchor);
                            }
                        }
                    }
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

    private void beginHandAnimation(boolean isLeft, BlockPos placedBlock, BlockPos previewBlock) {
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

    private double computeGroundTargetY(Vec3d pos) {
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

    private void tickTowerMode(PlayerEntity owner) {
        // Tower mode: build at fixed location (towerOrigin), no player tracking
        if (!buildingPaths || towerTemplate == null || towerOrigin == null) return;

        // Check if we've finished building the tower
        if (towerCurrentY >= towerHeight) {
            buildingPaths = false;
            this.dataTracker.set(BUILDING_PATHS, false);
            return;
        }

        // Get all voxels for the current Y layer
        java.util.List<BlockPos> currentLayerVoxels = getCurrentTowerLayerVoxels();

        if (currentLayerVoxels.isEmpty()) {
            // No voxels in this layer, move to next Y level
            towerCurrentY++;
            towerPlacementCursor = 0;
            return;
        }

        // Place blocks at 2-tick intervals (same as other modes)
        if (placementTickCounter == 0 && towerPlacementCursor < currentLayerVoxels.size()) {
            BlockPos targetPos = currentLayerVoxels.get(towerPlacementCursor);

            // Try to pathfind to the target position
            double ty = computeGroundTargetY(new Vec3d(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5));
            this.getNavigation().startMovingTo(targetPos.getX() + 0.5, ty, targetPos.getZ() + 0.5, 1.1);

            // Check if stuck and teleport if necessary
            double dx = this.getX() - (targetPos.getX() + 0.5);
            double dz = this.getZ() - (targetPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            if (this.getNavigation().isIdle() && distSq > 1.0) {
                stuckTicks++;
                if (stuckTicks >= 20) {
                    if (this.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 0.5, this.getZ(), 40, 0.5, 0.5, 0.5, 0.2);
                        sw.spawnParticles(ParticleTypes.PORTAL, targetPos.getX() + 0.5, ty + 0.5, targetPos.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
                    }
                    this.refreshPositionAndAngles(targetPos.getX() + 0.5, ty, targetPos.getZ() + 0.5, this.getYaw(), this.getPitch());
                    this.getNavigation().stop();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }

            // Determine next block for animation preview
            BlockPos nextPos = null;
            if (towerPlacementCursor + 1 < currentLayerVoxels.size()) {
                nextPos = currentLayerVoxels.get(towerPlacementCursor + 1);
            }

            // Place the block
            placeTowerBlock(targetPos, nextPos);
            towerPlacementCursor++;
        }

        // Check if we've finished this layer
        if (towerPlacementCursor >= currentLayerVoxels.size()) {
            towerCurrentY++;
            towerPlacementCursor = 0;
        }
    }

    private java.util.List<BlockPos> getCurrentTowerLayerVoxels() {
        if (towerTemplate == null) return java.util.Collections.emptyList();

        java.util.List<BlockPos> layerVoxels = new java.util.ArrayList<>();
        int moduleHeight = towerTemplate.moduleHeight;
        if (moduleHeight == 0) return layerVoxels;

        // Determine which module repetition we're in and the Y offset within that module
        int moduleIndex = towerCurrentY / moduleHeight;
        int yWithinModule = towerCurrentY % moduleHeight;

        // Collect all voxels at this Y level within the current module
        for (var voxel : towerTemplate.voxels) {
            int relY = voxel.rel.getY();
            if (relY == yWithinModule) {
                // Calculate absolute position: origin + module offset + voxel relative position
                int absoluteY = towerOrigin.getY() + (moduleIndex * moduleHeight) + relY;
                BlockPos absPos = new BlockPos(
                    towerOrigin.getX() + voxel.rel.getX(),
                    absoluteY,
                    towerOrigin.getZ() + voxel.rel.getZ()
                );
                layerVoxels.add(absPos);
            }
        }

        return layerVoxels;
    }

    private void placeTowerBlock(BlockPos pos, BlockPos nextPos) {
        if (this.getEntityWorld().isClient()) return;

        // Get the original block state from the template
        BlockState targetState = getTowerBlockStateAt(pos);
        if (targetState == null) return;

        // Use gradient sampling to potentially replace with a different block
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(targetState.getBlock()).toString();
        Integer groupIdx = towerBlockGroup.get(blockId);
        if (groupIdx == null || groupIdx < 0 || groupIdx >= towerGroupSlots.size()) {
            // No group mapping, place original block
            placeBlockFromInventory(pos, targetState, nextPos);
            return;
        }

        // Sample gradient based on Y position in total tower (not module)
        String[] slots = towerGroupSlots.get(groupIdx);
        int window = (groupIdx < towerGroupWindows.size()) ? towerGroupWindows.get(groupIdx) : 1;
        int sampledIndex = sampleTowerGradient(slots, window, towerCurrentY, pos);

        if (sampledIndex >= 0 && sampledIndex < 9) {
            String sampledId = slots[sampledIndex];
            if (sampledId != null && !sampledId.isEmpty()) {
                BlockState sampledState = getBlockStateFromId(sampledId);
                if (sampledState != null) {
                    placeBlockFromInventory(pos, sampledState, nextPos);
                    return;
                }
            }
        }

        // Fallback: place original block
        placeBlockFromInventory(pos, targetState, nextPos);
    }

    private BlockState getTowerBlockStateAt(BlockPos pos) {
        if (towerTemplate == null || towerOrigin == null) return null;

        int moduleHeight = towerTemplate.moduleHeight;
        if (moduleHeight == 0) return null;

        // Calculate relative position from tower origin
        int relX = pos.getX() - towerOrigin.getX();
        int relY = pos.getY() - towerOrigin.getY();
        int relZ = pos.getZ() - towerOrigin.getZ();

        // Determine Y within module
        int yWithinModule = relY % moduleHeight;

        // Find matching voxel in template
        for (var voxel : towerTemplate.voxels) {
            if (voxel.rel.getX() == relX && voxel.rel.getY() == yWithinModule && voxel.rel.getZ() == relZ) {
                return voxel.state;
            }
        }

        return null;
    }

    private int sampleTowerGradient(String[] slots, int window, int currentY, BlockPos pos) {
        // Count non-empty gradient slots
        int G = 0;
        for (int i = 8; i >= 0; i--) {
            if (slots[i] != null && !slots[i].isEmpty()) {
                G = i + 1;
                break;
            }
        }
        if (G == 0) return -1;

        // Map Y position in tower to gradient space [0, G-1]
        double s = ((double) currentY / (double) towerHeight) * (G - 1);

        // Apply windowing
        int W = Math.min(window, G);
        if (W > 0) {
            // Deterministic random offset based on position
            double u = deterministic01(pos.getX(), pos.getZ(), currentY) * W - (W / 2.0);
            s += u;
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

    private void tickTreeMode(PlayerEntity owner) {
        // Tree mode: WFC-based building that follows player with nuggets
        if (!buildingPaths || treeModules.isEmpty() || treeOrigin == null) return;

        // If waiting for inventory, show angry particles and don't build
        if (treeWaitingForInventory) {
            // Show angry villager particles periodically
            if (this.age % 20 == 0) {
                if (this.getEntityWorld() instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                        this.getX(), this.getY() + 2.0, this.getZ(),
                        1, 0.2, 0.2, 0.2, 0.0);
                }
            }
            // Don't do any building work while waiting
            return;
        }

        // Cache tiles if not already done
        if (!treeTilesCached || treeTileCache == null) {
            try {
                // Build stop blocks set (air, gold, ground types)
                java.util.Set<net.minecraft.block.Block> stopBlocks = new java.util.HashSet<>();
                stopBlocks.add(net.minecraft.block.Blocks.GOLD_BLOCK);
                if (owner != null) {
                    BlockPos playerGround = owner.getBlockPos().down();
                    BlockState gs = this.getEntityWorld().getBlockState(playerGround);
                    net.minecraft.block.Block groundType = gs.getBlock();
                    if (groundType == net.minecraft.block.Blocks.GRASS_BLOCK ||
                        groundType == net.minecraft.block.Blocks.DIRT ||
                        groundType == net.minecraft.block.Blocks.DIRT_PATH) {
                        stopBlocks.add(net.minecraft.block.Blocks.GRASS_BLOCK);
                        stopBlocks.add(net.minecraft.block.Blocks.DIRT);
                        stopBlocks.add(net.minecraft.block.Blocks.DIRT_PATH);
                    }
                }

                // Extract tiles using current preset
                ninja.trek.mc.goldgolem.tree.TreeDefinition def =
                    new ninja.trek.mc.goldgolem.tree.TreeDefinition(treeOrigin, treeModules, treeUniqueBlockIds);
                treeTileCache = ninja.trek.mc.goldgolem.tree.TreeTileExtractor.extract(
                    this.getEntityWorld(), def, treeTilingPreset, treeOrigin);
                treeTilesCached = true;

                if (treeTileCache.isEmpty()) {
                    // No tiles extracted, stop building permanently
                    buildingPaths = false;
                    this.dataTracker.set(BUILDING_PATHS, false);
                    return;
                }

                // Initialize WFC builder at golem's current position (only if new)
                if (treeWFCBuilder == null) {
                    java.util.Random random = new java.util.Random(this.getUuid().getMostSignificantBits());
                    treeWFCBuilder = new ninja.trek.mc.goldgolem.tree.TreeWFCBuilder(
                        treeTileCache, this.getEntityWorld(), this.getBlockPos(), stopBlocks, random);
                }

            } catch (Exception e) {
                // Failed to cache tiles, stop building
                buildingPaths = false;
                this.dataTracker.set(BUILDING_PATHS, false);
                return;
            }
        }

        // Run WFC algorithm steps (run multiple steps per tick for faster generation)
        if (treeWFCBuilder != null && !treeWFCBuilder.isFinished()) {
            // Run a few WFC steps per tick
            for (int i = 0; i < 5 && !treeWFCBuilder.isFinished(); i++) {
                treeWFCBuilder.step();
            }
        }

        // Place blocks from WFC output at 2-tick intervals
        if (placementTickCounter == 0 && treeWFCBuilder != null && treeWFCBuilder.hasPendingBlocks()) {
            BlockPos buildPos = treeWFCBuilder.getNextBuildPosition();
            if (buildPos != null) {
                // Try to pathfind to the build position
                double ty = computeGroundTargetY(new Vec3d(buildPos.getX() + 0.5, buildPos.getY(), buildPos.getZ() + 0.5));
                this.getNavigation().startMovingTo(buildPos.getX() + 0.5, ty, buildPos.getZ() + 0.5, 1.1);

                // Check if stuck and teleport if necessary (same as tower mode)
                double dx = this.getX() - (buildPos.getX() + 0.5);
                double dz = this.getZ() - (buildPos.getZ() + 0.5);
                double distSq = dx * dx + dz * dz;
                if (this.getNavigation().isIdle() && distSq > 1.0) {
                    stuckTicks++;
                    if (stuckTicks >= 20) {
                        if (this.getEntityWorld() instanceof ServerWorld sw) {
                            sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 0.5, this.getZ(), 40, 0.5, 0.5, 0.5, 0.2);
                            sw.spawnParticles(ParticleTypes.PORTAL, buildPos.getX() + 0.5, ty + 0.5, buildPos.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
                        }
                        this.refreshPositionAndAngles(buildPos.getX() + 0.5, ty, buildPos.getZ() + 0.5, this.getYaw(), this.getPitch());
                        this.getNavigation().stop();
                        stuckTicks = 0;
                    }
                } else {
                    stuckTicks = 0;
                }

                // Place the blocks from the tile - returns false if inventory depleted
                boolean success = placeTreeTile(buildPos);
                if (!success) {
                    // Out of inventory - stop building and wait for restock
                    buildingPaths = false;
                    this.dataTracker.set(BUILDING_PATHS, false);
                    treeWaitingForInventory = true;

                    // Show angry villager particles
                    if (this.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                            this.getX(), this.getY() + 2.0, this.getZ(),
                            5, 0.3, 0.3, 0.3, 0.0);
                    }

                    // Put the build position back in the queue so we resume from here
                    // Note: TreeWFCBuilder's getNextBuildPosition already removed it, so we need to re-queue
                    // For now, we'll just leave it - the next tile will be attempted when resumed
                    return;
                }
            }
        }

        // Check if finished building (no more pending blocks and WFC is done)
        if (treeWFCBuilder != null && treeWFCBuilder.isFinished() && !treeWFCBuilder.hasPendingBlocks()) {
            buildingPaths = false;
            this.dataTracker.set(BUILDING_PATHS, false);
        }
    }

    private boolean placeTreeTile(BlockPos tileOriginPos) {
        if (this.getEntityWorld().isClient()) return true;
        if (treeWFCBuilder == null) return true;

        String tileId = treeWFCBuilder.getCollapsedTile(tileOriginPos);
        if (tileId == null) return true;

        ninja.trek.mc.goldgolem.tree.TreeTile tile = treeWFCBuilder.getTile(tileId);
        if (tile == null) return true;

        // Track if we successfully placed at least one block, and if we failed due to inventory
        boolean placedAny = false;
        boolean inventoryDepleted = false;

        // Place all blocks in the tile
        int tileSize = tile.size;
        for (int dx = 0; dx < tileSize; dx++) {
            for (int dy = 0; dy < tileSize; dy++) {
                for (int dz = 0; dz < tileSize; dz++) {
                    BlockPos placePos = tileOriginPos.add(dx, dy, dz);
                    BlockState targetState = tile.getBlock(dx, dy, dz);

                    // Skip air blocks
                    if (targetState.isAir()) continue;

                    // Don't overwrite existing non-air blocks
                    if (!this.getEntityWorld().getBlockState(placePos).isAir()) continue;

                    // Sample from gradient for this block type
                    BlockState finalState = sampleTreeGradient(targetState, placePos);

                    // Consume from inventory
                    if (!consumeBlockFromInventory(finalState.getBlock())) {
                        // No blocks in inventory - mark as depleted
                        inventoryDepleted = true;
                        continue;
                    }

                    // Place the block
                    this.getEntityWorld().setBlockState(placePos, finalState, 3);
                    placedAny = true;

                    // Spawn particles
                    if (this.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                            placePos.getX() + 0.5, placePos.getY() + 0.5, placePos.getZ() + 0.5,
                            3, 0.3, 0.3, 0.3, 0.0);
                    }
                }
            }
        }

        // If we depleted inventory and couldn't place anything this tile, fail
        if (inventoryDepleted && !placedAny) {
            return false; // Signal inventory depletion
        }

        // Update hand animations (use first block of tile for visual)
        if (placedAny) {
            BlockPos animPos = tileOriginPos;
            this.dataTracker.set(LEFT_HAND_TARGET_POS, Optional.of(animPos));
            this.dataTracker.set(LEFT_ARM_HAS_TARGET, true);
            this.dataTracker.set(LEFT_HAND_ANIMATION_TICK, 0);
        }

        return true; // Success (or partial success is okay)
    }

    private BlockState sampleTreeGradient(BlockState originalState, BlockPos pos) {
        // Get block ID
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(originalState.getBlock()).toString();

        // Find the gradient group for this block
        Integer groupIdx = treeBlockGroup.get(blockId);
        if (groupIdx == null || groupIdx >= treeGroupSlots.size()) {
            // No gradient assigned, use original
            return originalState;
        }

        String[] gradientSlots = treeGroupSlots.get(groupIdx);
        int window = treeGroupWindows.get(groupIdx);

        // Sample from gradient using position hash
        int lastNonEmpty = -1;
        for (int i = gradientSlots.length - 1; i >= 0; i--) {
            if (gradientSlots[i] != null && !gradientSlots[i].isEmpty()) {
                lastNonEmpty = i;
                break;
            }
        }

        if (lastNonEmpty < 0) {
            // Empty gradient, use original
            return originalState;
        }

        int idx = Math.abs(pos.getX() + pos.getZ() + pos.getY()) % window;
        if (idx > lastNonEmpty) idx = lastNonEmpty;

        String sampledBlockId = gradientSlots[idx];
        if (sampledBlockId == null || sampledBlockId.isEmpty()) {
            return originalState;
        }

        net.minecraft.block.Block sampledBlock = net.minecraft.registry.Registries.BLOCK.get(new net.minecraft.util.Identifier(sampledBlockId));
        return sampledBlock.getDefaultState();
    }

    private void tickMiningMode() {
        // Mining mode state machine
        if (miningChestPos == null || miningDirection == null || miningStartPos == null) {
            // Invalid state, stop mining
            buildingPaths = false;
            this.dataTracker.set(BUILDING_PATHS, false);
            return;
        }

        // State 1: Idle at chest (waiting for gold nugget)
        if (miningIdleAtChest) {
            // Navigate to start position and stay there
            double dx = this.getX() - (miningStartPos.getX() + 0.5);
            double dz = this.getZ() - (miningStartPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            if (distSq > 4.0) {
                this.getNavigation().startMovingTo(miningStartPos.getX() + 0.5,
                    miningStartPos.getY(), miningStartPos.getZ() + 0.5, 1.0);
            } else {
                this.getNavigation().stop();
            }
            return;
        }

        // State 2: Returning to chest to deposit
        if (miningReturningToChest) {
            tickMiningReturn();
            return;
        }

        // State 3: Check if inventory is full (need to return)
        if (isMiningInventoryFull()) {
            miningReturningToChest = true;
            miningCurrentTarget = null;
            miningBreakProgress = 0;
            return;
        }

        // State 4: Active mining
        tickMiningActive();
    }

    private void tickMiningReturn() {
        // Navigate back to chest
        double dx = this.getX() - (miningStartPos.getX() + 0.5);
        double dz = this.getZ() - (miningStartPos.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;

        if (distSq > 4.0) {
            // Still far from chest, navigate
            this.getNavigation().startMovingTo(miningStartPos.getX() + 0.5,
                miningStartPos.getY(), miningStartPos.getZ() + 0.5, 1.1);

            // Check if stuck and teleport if necessary
            if (this.getNavigation().isIdle() && distSq > 16.0) {
                stuckTicks++;
                if (stuckTicks >= 60) {
                    // Teleport to start position
                    if (this.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 0.5, this.getZ(),
                            40, 0.5, 0.5, 0.5, 0.2);
                        sw.spawnParticles(ParticleTypes.PORTAL, miningStartPos.getX() + 0.5,
                            miningStartPos.getY() + 0.5, miningStartPos.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
                    }
                    this.refreshPositionAndAngles(miningStartPos.getX() + 0.5, miningStartPos.getY(),
                        miningStartPos.getZ() + 0.5, this.getYaw(), this.getPitch());
                    this.getNavigation().stop();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
        } else {
            // Close to chest, deposit inventory
            this.getNavigation().stop();
            depositInventoryToChest();
            miningReturningToChest = false;
            // Check if chest is full after deposit
            if (isMiningInventoryFull()) {
                // Chest is full, go idle
                miningIdleAtChest = true;
                buildingPaths = false;
                this.dataTracker.set(BUILDING_PATHS, false);
            }
        }
    }

    private void tickMiningActive() {
        // Place blocks under feet if needed
        placeBlocksUnderFeet();

        // Scan for nearby ores and add to pending list
        scanForOres();

        // Determine next mining target
        BlockPos targetPos = getNextMiningTarget();
        if (targetPos == null) {
            // No more blocks to mine, stop
            buildingPaths = false;
            this.dataTracker.set(BUILDING_PATHS, false);
            return;
        }

        // Navigate to target
        double ty = targetPos.getY();
        this.getNavigation().startMovingTo(targetPos.getX() + 0.5, ty, targetPos.getZ() + 0.5, 1.1);

        // Check if close enough to mine
        double dx = this.getX() - (targetPos.getX() + 0.5);
        double dy = this.getY() - targetPos.getY();
        double dz = this.getZ() - (targetPos.getZ() + 0.5);
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq <= 25.0) { // Within 5 block reach
            // Mine the block
            mineBlock(targetPos);
        } else {
            // Reset mining progress if too far
            if (miningCurrentTarget != null && !miningCurrentTarget.equals(targetPos)) {
                miningCurrentTarget = null;
                miningBreakProgress = 0;
            }
        }

        // Check if stuck and teleport if necessary
        if (this.getNavigation().isIdle() && distSq > 16.0) {
            stuckTicks++;
            if (stuckTicks >= 60) {
                // Teleport to start position
                if (this.getEntityWorld() instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 0.5, this.getZ(),
                        40, 0.5, 0.5, 0.5, 0.2);
                    sw.spawnParticles(ParticleTypes.PORTAL, miningStartPos.getX() + 0.5,
                        miningStartPos.getY() + 0.5, miningStartPos.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
                }
                this.refreshPositionAndAngles(miningStartPos.getX() + 0.5, miningStartPos.getY(),
                    miningStartPos.getZ() + 0.5, this.getYaw(), this.getPitch());
                this.getNavigation().stop();
                stuckTicks = 0;
                miningCurrentTarget = null;
                miningBreakProgress = 0;
                // Reset mining state
                miningPrimaryProgress = 0;
                miningCurrentBranch = -1;
                miningBranchProgress = 0;
                miningPendingOres.clear();
            }
        } else {
            stuckTicks = 0;
        }
    }

    private boolean isMiningInventoryFull() {
        // Check if inventory has space (excluding one stack of building blocks)
        int emptySlots = 0;
        int buildingBlockCount = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
            } else if (miningBuildingBlockType != null) {
                String blockId = getBlockIdFromStack(stack);
                if (blockId != null && blockId.equals(miningBuildingBlockType)) {
                    buildingBlockCount += stack.getCount();
                }
            }
        }
        // Full if less than 2 empty slots (need room for building blocks)
        return emptySlots < 2;
    }

    private void depositInventoryToChest() {
        if (miningChestPos == null || this.getEntityWorld().isClient()) return;

        // Get chest inventory
        var chestEntity = this.getEntityWorld().getBlockEntity(miningChestPos);
        if (!(chestEntity instanceof net.minecraft.inventory.Inventory chestInv)) return;

        // Keep one stack of building blocks, deposit everything else
        int buildingBlocksKept = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            String blockId = getBlockIdFromStack(stack);
            boolean isBuildingBlock = miningBuildingBlockType != null && blockId != null &&
                blockId.equals(miningBuildingBlockType);

            if (isBuildingBlock && buildingBlocksKept < 64) {
                // Keep up to one stack of building blocks
                int toKeep = Math.min(64 - buildingBlocksKept, stack.getCount());
                buildingBlocksKept += toKeep;
                if (stack.getCount() > toKeep) {
                    // Deposit excess
                    ItemStack toDeposit = stack.copy();
                    toDeposit.setCount(stack.getCount() - toKeep);
                    ItemStack remainder = transferToInventory(toDeposit, chestInv);
                    stack.setCount(toKeep + (remainder.isEmpty() ? 0 : remainder.getCount()));
                    inventory.setStack(i, stack);
                }
            } else {
                // Deposit non-building blocks
                ItemStack remainder = transferToInventory(stack, chestInv);
                inventory.setStack(i, remainder);
            }
        }
    }

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

    private void placeBlocksUnderFeet() {
        if (this.getEntityWorld().isClient()) return;

        BlockPos below = this.getBlockPos().down();
        if (!this.getEntityWorld().getBlockState(below).isAir()) return;

        // Find a building block in inventory
        if (miningBuildingBlockType == null) {
            // Select first non-ore, non-gravity block as building block type
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.item.BlockItem blockItem)) continue;

                var block = blockItem.getBlock();
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(block).toString();

                if (!isOreBlock(blockId) && !isGravityBlock(block)) {
                    miningBuildingBlockType = blockId;
                    break;
                }
            }
        }

        if (miningBuildingBlockType != null) {
            // Find and consume the building block
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.item.BlockItem blockItem)) continue;

                String blockId = net.minecraft.registry.Registries.BLOCK.getId(blockItem.getBlock()).toString();
                if (blockId.equals(miningBuildingBlockType)) {
                    BlockState state = blockItem.getBlock().getDefaultState();
                    this.getEntityWorld().setBlockState(below, state);
                    stack.decrement(1);
                    inventory.setStack(i, stack);
                    return;
                }
            }
        }
    }

    private void scanForOres() {
        if (this.getEntityWorld().isClient()) return;

        // Scan 3 block radius for ores
        BlockPos center = this.getBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = this.getEntityWorld().getBlockState(pos);
                    String blockId = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();

                    if (isOreBlock(blockId) && !miningPendingOres.contains(pos)) {
                        miningPendingOres.add(pos);
                    }
                }
            }
        }
    }

    private BlockPos getNextMiningTarget() {
        // Priority 1: Mine pending ores
        if (!miningPendingOres.isEmpty()) {
            BlockPos orePos = miningPendingOres.iterator().next();
            // Check if ore still exists
            BlockState state = this.getEntityWorld().getBlockState(orePos);
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
            if (!isOreBlock(blockId) || state.isAir()) {
                miningPendingOres.remove(orePos);
                return getNextMiningTarget(); // Recursive call to get next
            }
            return orePos;
        }

        // Priority 2: Continue branch mining pattern
        return getNextBranchMiningTarget();
    }

    private BlockPos getNextBranchMiningTarget() {
        // Branch mining pattern:
        // - Primary tunnel in miningDirection
        // - Branches perpendicular every miningBranchSpacing blocks
        // - Each branch extends miningBranchDepth blocks to left and right

        if (miningCurrentBranch == -1) {
            // Mining primary tunnel
            BlockPos primaryStart = miningStartPos.offset(miningDirection, 1);
            BlockPos target = primaryStart.offset(miningDirection, miningPrimaryProgress);

            // Check if we should start a branch
            if (miningPrimaryProgress > 0 && miningPrimaryProgress % miningBranchSpacing == 0) {
                // Start a branch (left first)
                miningCurrentBranch = miningPrimaryProgress / miningBranchSpacing;
                miningBranchLeft = true;
                miningBranchProgress = 0;
                return getNextBranchMiningTarget(); // Get first block of branch
            }

            // Return next block in primary tunnel (height layers, don't mine floor)
            for (int y = 1; y < miningTunnelHeight; y++) {
                BlockPos layerTarget = target.up(y - 1);
                if (shouldMineBlock(layerTarget)) {
                    return layerTarget;
                }
            }

            // All layers mined, advance primary
            miningPrimaryProgress++;
            return getNextBranchMiningTarget();
        } else {
            // Mining a branch
            net.minecraft.util.math.Direction branchDir = getBranchDirection(miningBranchLeft);
            BlockPos branchStart = miningStartPos.offset(miningDirection, 1 + miningCurrentBranch * miningBranchSpacing);
            BlockPos target = branchStart.offset(branchDir, miningBranchProgress + 1);

            // Return next block in branch (height layers, don't mine floor)
            for (int y = 1; y < miningTunnelHeight; y++) {
                BlockPos layerTarget = target.up(y - 1);
                if (shouldMineBlock(layerTarget)) {
                    return layerTarget;
                }
            }

            // All layers mined, advance branch
            miningBranchProgress++;

            // Check if branch is complete
            if (miningBranchProgress >= miningBranchDepth) {
                if (miningBranchLeft) {
                    // Switch to right branch
                    miningBranchLeft = false;
                    miningBranchProgress = 0;
                } else {
                    // Both branches complete, return to primary
                    miningCurrentBranch = -1;
                    miningBranchProgress = 0;
                }
            }

            return getNextBranchMiningTarget();
        }
    }

    private net.minecraft.util.math.Direction getBranchDirection(boolean left) {
        // Get perpendicular direction
        if (miningDirection == net.minecraft.util.math.Direction.NORTH) {
            return left ? net.minecraft.util.math.Direction.WEST : net.minecraft.util.math.Direction.EAST;
        } else if (miningDirection == net.minecraft.util.math.Direction.SOUTH) {
            return left ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST;
        } else if (miningDirection == net.minecraft.util.math.Direction.EAST) {
            return left ? net.minecraft.util.math.Direction.NORTH : net.minecraft.util.math.Direction.SOUTH;
        } else { // WEST
            return left ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH;
        }
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

    private void mineBlock(BlockPos pos) {
        if (this.getEntityWorld().isClient()) return;

        BlockState state = this.getEntityWorld().getBlockState(pos);
        if (state.isAir()) {
            // Block already mined, clear pending ore if applicable
            miningPendingOres.remove(pos);
            miningCurrentTarget = null;
            miningBreakProgress = 0;
            return;
        }

        // Start mining a new block
        if (miningCurrentTarget == null || !miningCurrentTarget.equals(pos)) {
            miningCurrentTarget = pos;
            miningBreakProgress = 0;
        }

        // Find best tool in inventory
        ItemStack bestTool = findBestTool(state);
        float breakSpeed = bestTool.isEmpty() ? 1.0f : bestTool.getMiningSpeedMultiplier(state);

        // Golem mines at half speed (double the time)
        breakSpeed *= 0.5f;

        // Calculate break time in ticks
        float hardness = state.getHardness(this.getEntityWorld(), pos);
        if (hardness < 0) {
            // Unbreakable block
            miningCurrentTarget = null;
            miningBreakProgress = 0;
            return;
        }

        int requiredTicks = (int) Math.ceil((hardness * 30.0f) / breakSpeed);
        requiredTicks = Math.max(1, requiredTicks); // At least 1 tick

        miningBreakProgress++;

        // Show breaking animation (optional - could add later)

        if (miningBreakProgress >= requiredTicks) {
            // Break the block
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
            boolean isOre = isOreBlock(blockId);

            // Drop items into golem inventory
            if (this.getEntityWorld() instanceof ServerWorld sw) {
                var drops = net.minecraft.block.Block.getDroppedStacks(state, sw, pos,
                    this.getEntityWorld().getBlockEntity(pos), this, bestTool);

                for (ItemStack drop : drops) {
                    // Add to inventory
                    addToInventory(drop);
                }
            }

            // Break the block
            this.getEntityWorld().breakBlock(pos, false);

            // Damage tool if used
            if (!bestTool.isEmpty() && bestTool.isDamageable()) {
                bestTool.damage(1, this, net.minecraft.entity.EquipmentSlot.MAINHAND);
                // Update inventory
                for (int i = 0; i < inventory.size(); i++) {
                    if (inventory.getStack(i) == bestTool) {
                        inventory.setStack(i, bestTool);
                        break;
                    }
                }
            }

            // Clear pending ore
            miningPendingOres.remove(pos);
            miningCurrentTarget = null;
            miningBreakProgress = 0;

            // If it was an ore, mine surrounding blocks
            if (isOre) {
                mineOreSurroundings(pos);
            }
        }
    }

    private void mineOreSurroundings(BlockPos center) {
        // Mine 1 block in each of the 6 directions
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            BlockPos adjacent = center.offset(dir);
            BlockState state = this.getEntityWorld().getBlockState(adjacent);

            if (!state.isAir() && state.getHardness(this.getEntityWorld(), adjacent) >= 0) {
                // Add to pending mining targets (will be mined next)
                if (!miningPendingOres.contains(adjacent)) {
                    miningPendingOres.add(adjacent);
                }
            }
        }
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

    private void placeBlockFromInventory(BlockPos pos, BlockState state, BlockPos nextPos) {
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

    private boolean consumeBlockFromInventory(String blockId) {
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
        return false;
    }

    private BlockState getBlockStateFromId(String blockId) {
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

    // ========== EXCAVATION MODE METHODS ==========

    private void tickExcavationMode() {
        // Excavation mode state machine
        if (excavationChestPos1 == null || excavationChestPos2 == null || excavationStartPos == null) {
            // Invalid state, stop excavation
            buildingPaths = false;
            this.dataTracker.set(BUILDING_PATHS, false);
            return;
        }

        // State 1: Idle at start (waiting for gold nugget)
        if (excavationIdleAtStart) {
            // Navigate to start position and stay there
            double dx = this.getX() - (excavationStartPos.getX() + 0.5);
            double dz = this.getZ() - (excavationStartPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            if (distSq > 4.0) {
                this.getNavigation().startMovingTo(excavationStartPos.getX() + 0.5,
                    excavationStartPos.getY(), excavationStartPos.getZ() + 0.5, 1.0);
            } else {
                this.getNavigation().stop();
            }
            return;
        }

        // State 2: Returning to chest to deposit
        if (excavationReturningToChest) {
            tickExcavationReturn();
            return;
        }

        // State 3: Check if inventory is full (need to return)
        if (isExcavationInventoryFull()) {
            excavationReturningToChest = true;
            excavationCurrentTarget = null;
            excavationBreakProgress = 0;
            return;
        }

        // State 4: Active excavation
        tickExcavationActive();
    }

    private void tickExcavationReturn() {
        // Navigate to nearest chest
        BlockPos nearestChest = getNearestExcavationChest();
        double dx = this.getX() - (nearestChest.getX() + 0.5);
        double dz = this.getZ() - (nearestChest.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;

        if (distSq > 4.0) {
            // Still far from chest, navigate
            this.getNavigation().startMovingTo(nearestChest.getX() + 0.5,
                nearestChest.getY(), nearestChest.getZ() + 0.5, 1.1);

            // Check if stuck and teleport if necessary
            if (this.getNavigation().isIdle() && distSq > 16.0) {
                stuckTicks++;
                if (stuckTicks >= 60) {
                    // Teleport to chest
                    if (this.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 0.5, this.getZ(),
                            40, 0.5, 0.5, 0.5, 0.2);
                        sw.spawnParticles(ParticleTypes.PORTAL, nearestChest.getX() + 0.5,
                            nearestChest.getY() + 0.5, nearestChest.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
                    }
                    this.refreshPositionAndAngles(nearestChest.getX() + 0.5, nearestChest.getY(),
                        nearestChest.getZ() + 0.5, this.getYaw(), this.getPitch());
                    this.getNavigation().stop();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
        } else {
            // Close to chest, deposit inventory
            this.getNavigation().stop();
            depositExcavationInventoryToChest(nearestChest);
            excavationReturningToChest = false;
            // Check if chest is full after deposit
            if (isExcavationInventoryFull()) {
                // Chest is full, go idle
                excavationIdleAtStart = true;
                buildingPaths = false;
                this.dataTracker.set(BUILDING_PATHS, false);
            }
        }
    }

    private void tickExcavationActive() {
        // Place floor blocks if needed
        placeExcavationFloorBlocks();

        // Determine next excavation target
        BlockPos targetPos = getNextExcavationTarget();
        if (targetPos == null) {
            // Excavation complete, stop
            buildingPaths = false;
            this.dataTracker.set(BUILDING_PATHS, false);
            return;
        }

        // Navigate to target
        double ty = targetPos.getY();
        this.getNavigation().startMovingTo(targetPos.getX() + 0.5, ty, targetPos.getZ() + 0.5, 1.1);

        // Check if close enough to mine
        double dx = this.getX() - (targetPos.getX() + 0.5);
        double dy = this.getY() - targetPos.getY();
        double dz = this.getZ() - (targetPos.getZ() + 0.5);
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq <= 25.0) { // Within 5 block reach
            // Mine the block
            excavateBlock(targetPos);
        } else {
            // Reset mining progress if too far
            if (excavationCurrentTarget != null && !excavationCurrentTarget.equals(targetPos)) {
                excavationCurrentTarget = null;
                excavationBreakProgress = 0;
            }
        }
    }

    private BlockPos getNearestExcavationChest() {
        double dx1 = this.getX() - (excavationChestPos1.getX() + 0.5);
        double dy1 = this.getY() - (excavationChestPos1.getY() + 0.5);
        double dz1 = this.getZ() - (excavationChestPos1.getZ() + 0.5);
        double dist1 = dx1 * dx1 + dy1 * dy1 + dz1 * dz1;

        double dx2 = this.getX() - (excavationChestPos2.getX() + 0.5);
        double dy2 = this.getY() - (excavationChestPos2.getY() + 0.5);
        double dz2 = this.getZ() - (excavationChestPos2.getZ() + 0.5);
        double dist2 = dx2 * dx2 + dy2 * dy2 + dz2 * dz2;

        return dist1 <= dist2 ? excavationChestPos1 : excavationChestPos2;
    }

    private BlockPos getNextExcavationTarget() {
        // Mine in concentric square rings, clockwise
        // Each position mines a full column from golem Y up to Y + height - 1

        // Check if current ring is complete
        int ringSize = excavationCurrentRing == 0 ? 1 : (excavationCurrentRing * 8);
        if (excavationRingProgress >= ringSize) {
            // Move to next ring
            excavationCurrentRing++;
            excavationRingProgress = 0;
        }

        // Check if excavation is complete (reached depth)
        if (excavationCurrentRing > excavationDepth) {
            return null; // Done
        }

        // Get current position in the ring
        BlockPos ringPos = getRingPosition(excavationCurrentRing, excavationRingProgress);

        // Check each Y level in the column (golem Y level up to Y + height)
        int golemY = (int) this.getY();
        for (int dy = 0; dy < excavationHeight; dy++) {
            BlockPos checkPos = ringPos.up(dy);
            if (shouldMineBlock(checkPos)) {
                return checkPos;
            }
        }

        // All layers mined at this position, advance to next position in ring
        excavationRingProgress++;
        return getNextExcavationTarget(); // Recursive call
    }

    private BlockPos getRingPosition(int ring, int progress) {
        // Ring 0 is just the center
        if (ring == 0) {
            return excavationStartPos;
        }

        // For ring > 0, calculate position in clockwise square
        // Start from top-left corner, go clockwise
        int sideLength = ring * 2;
        int x = excavationStartPos.getX();
        int z = excavationStartPos.getZ();

        // Determine which side of the square we're on
        int perimeter = sideLength * 4;
        int pos = progress % perimeter;

        if (pos < sideLength) {
            // Top side (moving east)
            x = x - ring + pos;
            z = z - ring;
        } else if (pos < sideLength * 2) {
            // Right side (moving south)
            x = x + ring;
            z = z - ring + (pos - sideLength);
        } else if (pos < sideLength * 3) {
            // Bottom side (moving west)
            x = x + ring - (pos - sideLength * 2);
            z = z + ring;
        } else {
            // Left side (moving north)
            x = x - ring;
            z = z + ring - (pos - sideLength * 3);
        }

        return new BlockPos(x, excavationStartPos.getY(), z);
    }

    private boolean isExcavationInventoryFull() {
        // Check if inventory has space (excluding one stack of building blocks)
        int emptySlots = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
            }
        }
        // Full if less than 2 empty slots
        return emptySlots < 2;
    }

    private void depositExcavationInventoryToChest(BlockPos chestPos) {
        if (chestPos == null || this.getEntityWorld().isClient()) return;

        // Get chest inventory
        var chestEntity = this.getEntityWorld().getBlockEntity(chestPos);
        if (!(chestEntity instanceof net.minecraft.inventory.Inventory chestInv)) return;

        // Keep one stack of building blocks, deposit everything else
        int buildingBlocksKept = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            String blockId = getBlockIdFromStack(stack);
            boolean isBuildingBlock = excavationBuildingBlockType != null && blockId != null &&
                blockId.equals(excavationBuildingBlockType);

            if (isBuildingBlock && buildingBlocksKept < 64) {
                // Keep up to one stack of building blocks
                int toKeep = Math.min(64 - buildingBlocksKept, stack.getCount());
                buildingBlocksKept += toKeep;
                if (stack.getCount() > toKeep) {
                    // Deposit excess
                    ItemStack toDeposit = stack.copy();
                    toDeposit.setCount(stack.getCount() - toKeep);
                    ItemStack remainder = transferToInventory(toDeposit, chestInv);
                    stack.setCount(toKeep + (remainder.isEmpty() ? 0 : remainder.getCount()));
                    inventory.setStack(i, stack);
                }
            } else if (!isBuildingBlock) {
                // Deposit all non-building blocks
                ItemStack remainder = transferToInventory(stack, chestInv);
                if (remainder.isEmpty()) {
                    inventory.setStack(i, ItemStack.EMPTY);
                } else {
                    inventory.setStack(i, remainder);
                }
            }
        }
    }

    private void placeExcavationFloorBlocks() {
        if (this.getEntityWorld().isClient()) return;

        BlockPos below = this.getBlockPos().down();
        if (!this.getEntityWorld().getBlockState(below).isAir()) return;

        // Find a building block in inventory
        if (excavationBuildingBlockType == null) {
            // Select first non-gravity block as building block type
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.item.BlockItem blockItem)) continue;

                var block = blockItem.getBlock();
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(block).toString();

                if (!isGravityBlock(block)) {
                    excavationBuildingBlockType = blockId;
                    break;
                }
            }
        }

        if (excavationBuildingBlockType != null) {
            // Find and consume the building block
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.item.BlockItem blockItem)) continue;

                String blockId = net.minecraft.registry.Registries.BLOCK.getId(blockItem.getBlock()).toString();
                if (blockId.equals(excavationBuildingBlockType)) {
                    // Place the block
                    BlockState state = blockItem.getBlock().getDefaultState();
                    this.getEntityWorld().setBlockState(below, state);
                    stack.decrement(1);
                    return;
                }
            }
        }
    }

    private void excavateBlock(BlockPos pos) {
        if (this.getEntityWorld().isClient()) return;

        BlockState state = this.getEntityWorld().getBlockState(pos);
        if (state.isAir()) {
            // Block already mined
            excavationCurrentTarget = null;
            excavationBreakProgress = 0;
            return;
        }

        // Start mining a new block
        if (excavationCurrentTarget == null || !excavationCurrentTarget.equals(pos)) {
            excavationCurrentTarget = pos;
            excavationBreakProgress = 0;
        }

        // Find best tool in inventory
        ItemStack bestTool = findBestTool(state);
        float breakSpeed = bestTool.isEmpty() ? 1.0f : bestTool.getMiningSpeedMultiplier(state);

        // Golem mines at half speed (double the time)
        breakSpeed *= 0.5f;

        // Calculate break time in ticks
        float hardness = state.getHardness(this.getEntityWorld(), pos);
        if (hardness < 0) {
            // Unbreakable block
            excavationCurrentTarget = null;
            excavationBreakProgress = 0;
            return;
        }

        int requiredTicks = (int) Math.ceil((hardness * 30.0f) / breakSpeed);
        requiredTicks = Math.max(1, requiredTicks);

        excavationBreakProgress++;

        if (excavationBreakProgress >= requiredTicks) {
            // Break the block
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();

            // Drop items into golem inventory
            if (this.getEntityWorld() instanceof ServerWorld sw) {
                var drops = net.minecraft.block.Block.getDroppedStacks(state, sw, pos,
                    this.getEntityWorld().getBlockEntity(pos), this, bestTool);

                for (ItemStack drop : drops) {
                    addToInventory(drop);
                }
            }

            // Remove the block
            this.getEntityWorld().breakBlock(pos, false);

            // Show particles
            if (this.getEntityWorld() instanceof ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    10, 0.25, 0.25, 0.25, 0.1);
            }

            // Reset for next block
            excavationCurrentTarget = null;
            excavationBreakProgress = 0;
        }
    }

    // ========== END EXCAVATION MODE METHODS ==========

    // ========== TERRAFORMING MODE METHODS ==========

    private void tickTerraformingMode() {
        // Terraforming mode state machine
        if (terraformingShellByLayer == null || terraformingOrigin == null) {
            // Invalid state, stop building
            buildingPaths = false;
            this.dataTracker.set(BUILDING_PATHS, false);
            return;
        }

        // STATE: WAITING - idle at start position until activated with gold nugget
        if (!buildingPaths) {
            // Navigate to start position
            double dx = this.getX() - (terraformingStartPos.getX() + 0.5);
            double dz = this.getZ() - (terraformingStartPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            if (distSq > 4.0) {
                this.getNavigation().startMovingTo(terraformingStartPos.getX() + 0.5,
                    terraformingStartPos.getY(), terraformingStartPos.getZ() + 0.5, 1.0);
            } else {
                this.getNavigation().stop();
            }
            return;
        }

        // STATE: BUILDING - place shell blocks layer by layer
        java.util.List<BlockPos> currentLayer = terraformingShellByLayer.get(terraformingCurrentY);

        // If no shell at this Y level, move to next level
        if (currentLayer == null || currentLayer.isEmpty()) {
            terraformingCurrentY++;
            terraformingLayerProgress = 0;

            // Check if all layers are complete
            if (terraformingCurrentY > terraformingMaxY) {
                // All layers done - COMPLETE
                buildingPaths = false;
                this.dataTracker.set(BUILDING_PATHS, false);
            }
            return;
        }

        // If layer is complete, move to next layer
        if (terraformingLayerProgress >= currentLayer.size()) {
            terraformingLayerProgress = 0;
            terraformingCurrentY++;

            if (terraformingCurrentY > terraformingMaxY) {
                // All layers done - COMPLETE
                buildingPaths = false;
                this.dataTracker.set(BUILDING_PATHS, false);
            }
            return;
        }

        // Get current target position
        BlockPos targetPos = currentLayer.get(terraformingLayerProgress);

        // Navigate to position
        double distSq = this.getBlockPos().getSquaredDistance(targetPos);
        if (distSq > 16.0) {
            this.getNavigation().startMovingTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.0);

            // Stuck detection
            if (this.getNavigation().isIdle()) {
                stuckTicks++;
                if (stuckTicks >= 20) {
                    // Teleport
                    this.refreshPositionAndAngles(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                            this.getYaw(), this.getPitch());
                    if (this.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.PORTAL, this.getX(), this.getY() + 1.0, this.getZ(),
                                20, 0.5, 0.5, 0.5, 0.1);
                    }
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
            return;
        }

        stuckTicks = 0;

        // At position - place block every 2 ticks (controlled by placementTickCounter)
        if (placementTickCounter % 2 == 0) {
            // Remove skeleton block if present
            BlockState currentState = this.getEntityWorld().getBlockState(targetPos);
            if (terraformingSkeletonTypes != null && terraformingSkeletonTypes.contains(currentState.getBlock())) {
                this.getEntityWorld().breakBlock(targetPos, false);
            }

            // Place shell block using slope-based gradient sampling
            BlockState toPlace = sampleTerraformingGradient(targetPos);
            if (toPlace != null) {
                placeBlockFromInventory(targetPos, toPlace, targetPos.up());
            }

            terraformingLayerProgress++;
        }
    }

    /**
     * Samples the appropriate terraforming gradient based on local surface slope.
     * Classifies the surface as VERTICAL, HORIZONTAL, or SLOPED and samples from the corresponding gradient.
     */
    private BlockState sampleTerraformingGradient(BlockPos pos) {
        // Count surrounding shell blocks in scan radius to determine slope
        int vertical = 0;
        int horizontal = 0;

        for (BlockPos nearPos : BlockPos.iterate(
                pos.add(-terraformingScanRadius, -terraformingScanRadius, -terraformingScanRadius),
                pos.add(terraformingScanRadius, terraformingScanRadius, terraformingScanRadius))) {

            if (nearPos.equals(pos)) continue;

            // Check if this position is part of the shell or skeleton
            boolean isShellOrSkeleton = false;
            if (terraformingSkeletonBlocks != null && terraformingSkeletonBlocks.contains(nearPos)) {
                isShellOrSkeleton = true;
            } else {
                // Check if it's in any shell layer
                int nearY = nearPos.getY();
                if (terraformingShellByLayer.containsKey(nearY)) {
                    java.util.List<BlockPos> layer = terraformingShellByLayer.get(nearY);
                    if (layer != null && layer.contains(nearPos)) {
                        isShellOrSkeleton = true;
                    }
                }
            }

            if (!isShellOrSkeleton) continue;

            int dx = Math.abs(nearPos.getX() - pos.getX());
            int dy = Math.abs(nearPos.getY() - pos.getY());
            int dz = Math.abs(nearPos.getZ() - pos.getZ());

            // Classify as vertical or horizontal based on dominant axis
            if (dy > dx && dy > dz) {
                vertical++;
            } else {
                horizontal++;
            }
        }

        // Calculate slope ratio
        float total = vertical + horizontal;
        if (total == 0) {
            // No nearby blocks, default to horizontal
            return sampleGradientArray(terraformingGradientHorizontal, terraformingGradientHorizontalWindow, pos);
        }

        float ratio = vertical / total;

        // Classify and sample appropriate gradient
        if (ratio > 0.7f) {
            // Steep/vertical surface (cliffs, walls)
            return sampleGradientArray(terraformingGradientVertical, terraformingGradientVerticalWindow, pos);
        } else if (ratio < 0.3f) {
            // Flat/horizontal surface (floors, tops)
            return sampleGradientArray(terraformingGradientHorizontal, terraformingGradientHorizontalWindow, pos);
        } else {
            // Sloped/diagonal surface
            return sampleGradientArray(terraformingGradientSloped, terraformingGradientSlopedWindow, pos);
        }
    }

    /**
     * Samples from a gradient array using positional hashing (similar to path mode sampling).
     */
    private BlockState sampleGradientArray(String[] gradientArray, int window, BlockPos pos) {
        if (gradientArray == null || window <= 0) return null;

        // Count non-empty entries
        int g = 0;
        for (int i = gradientArray.length - 1; i >= 0; i--) {
            if (gradientArray[i] != null && !gradientArray[i].isEmpty()) {
                g = i + 1;
                break;
            }
        }

        if (g == 0) return null;

        // Clamp window
        int w = Math.max(0, Math.min(g, window));
        if (w == 0) return null;

        // Use positional hash to sample from gradient window
        long hash = (long) pos.getX() * 374761393L + (long) pos.getY() * 668265263L + (long) pos.getZ() * 2147483647L;
        int idx = (int) ((hash & 0x7FFFFFFFL) % w);

        String blockId = gradientArray[idx];
        if (blockId == null || blockId.isEmpty()) return null;

        var ident = net.minecraft.util.Identifier.tryParse(blockId);
        if (ident == null) return null;

        var block = net.minecraft.registry.Registries.BLOCK.get(ident);
        if (block == null) return null;

        return block.getDefaultState();
    }

    // ========== END TERRAFORMING MODE METHODS ==========

    // Persistence: width, gradient, inventory, owner UUID (1.21.10 storage API)
    @Override
    protected void writeCustomData(WriteView view) {
        view.putString("Mode", this.buildMode.name());
        view.putInt("PathWidth", this.pathWidth);
        view.putInt("GradWindow", this.gradientWindow);
        view.putInt("StepWindow", this.stepGradientWindow);

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
            view.putInt("WallGW" + g, (g < wallGroupWindows.size()) ? wallGroupWindows.get(g) : 1);
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
            view.putInt("TowerGW" + g, (g < towerGroupWindows.size()) ? towerGroupWindows.get(g) : 1);
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

        // Mining-mode persisted bits
        if (this.miningChestPos != null) {
            view.putInt("MiningChestX", this.miningChestPos.getX());
            view.putInt("MiningChestY", this.miningChestPos.getY());
            view.putInt("MiningChestZ", this.miningChestPos.getZ());
        }
        if (this.miningDirection != null) view.putString("MiningDir", this.miningDirection.name());
        if (this.miningStartPos != null) {
            view.putInt("MiningStartX", this.miningStartPos.getX());
            view.putInt("MiningStartY", this.miningStartPos.getY());
            view.putInt("MiningStartZ", this.miningStartPos.getZ());
        }
        view.putInt("MiningBranchDepth", this.miningBranchDepth);
        view.putInt("MiningBranchSpacing", this.miningBranchSpacing);
        view.putInt("MiningTunnelHeight", this.miningTunnelHeight);
        view.putInt("MiningPrimaryProgress", this.miningPrimaryProgress);
        view.putInt("MiningCurrentBranch", this.miningCurrentBranch);
        view.putBoolean("MiningBranchLeft", this.miningBranchLeft);
        view.putInt("MiningBranchProgress", this.miningBranchProgress);
        view.putBoolean("MiningReturningToChest", this.miningReturningToChest);
        view.putBoolean("MiningIdleAtChest", this.miningIdleAtChest);
        if (this.miningBuildingBlockType != null) view.putString("MiningBuildingBlock", this.miningBuildingBlockType);

        // Excavation-mode persisted bits
        if (this.excavationChestPos1 != null) {
            view.putInt("ExcavChest1X", this.excavationChestPos1.getX());
            view.putInt("ExcavChest1Y", this.excavationChestPos1.getY());
            view.putInt("ExcavChest1Z", this.excavationChestPos1.getZ());
        }
        if (this.excavationChestPos2 != null) {
            view.putInt("ExcavChest2X", this.excavationChestPos2.getX());
            view.putInt("ExcavChest2Y", this.excavationChestPos2.getY());
            view.putInt("ExcavChest2Z", this.excavationChestPos2.getZ());
        }
        if (this.excavationDir1 != null) view.putString("ExcavDir1", this.excavationDir1.name());
        if (this.excavationDir2 != null) view.putString("ExcavDir2", this.excavationDir2.name());
        if (this.excavationStartPos != null) {
            view.putInt("ExcavStartX", this.excavationStartPos.getX());
            view.putInt("ExcavStartY", this.excavationStartPos.getY());
            view.putInt("ExcavStartZ", this.excavationStartPos.getZ());
        }
        view.putInt("ExcavHeight", this.excavationHeight);
        view.putInt("ExcavDepth", this.excavationDepth);
        view.putInt("ExcavCurrentRing", this.excavationCurrentRing);
        view.putInt("ExcavRingProgress", this.excavationRingProgress);
        view.putBoolean("ExcavReturningToChest", this.excavationReturningToChest);
        view.putBoolean("ExcavIdleAtStart", this.excavationIdleAtStart);
        if (this.excavationBuildingBlockType != null) view.putString("ExcavBuildingBlock", this.excavationBuildingBlockType);

        // Terraforming-mode persisted bits
        if (this.terraformingOrigin != null) {
            view.putInt("TFormOriginX", this.terraformingOrigin.getX());
            view.putInt("TFormOriginY", this.terraformingOrigin.getY());
            view.putInt("TFormOriginZ", this.terraformingOrigin.getZ());
        }
        if (this.terraformingStartPos != null) {
            view.putInt("TFormStartX", this.terraformingStartPos.getX());
            view.putInt("TFormStartY", this.terraformingStartPos.getY());
            view.putInt("TFormStartZ", this.terraformingStartPos.getZ());
        }
        view.putInt("TFormScanRadius", this.terraformingScanRadius);
        view.putInt("TFormAlpha", this.terraformingAlpha);
        view.putInt("TFormMinY", this.terraformingMinY);
        view.putInt("TFormMaxY", this.terraformingMaxY);
        view.putInt("TFormCurrentY", this.terraformingCurrentY);
        view.putInt("TFormLayerProgress", this.terraformingLayerProgress);

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

        // Skeleton blocks
        if (this.terraformingSkeletonBlocks != null && !this.terraformingSkeletonBlocks.isEmpty()) {
            view.putInt("TFormSkeletonCount", this.terraformingSkeletonBlocks.size());
            for (int i = 0; i < this.terraformingSkeletonBlocks.size(); i++) {
                BlockPos pos = this.terraformingSkeletonBlocks.get(i);
                view.putInt("TFormSkel" + i + "X", pos.getX());
                view.putInt("TFormSkel" + i + "Y", pos.getY());
                view.putInt("TFormSkel" + i + "Z", pos.getZ());
            }
        } else {
            view.putInt("TFormSkeletonCount", 0);
        }

        // Skeleton types
        if (this.terraformingSkeletonTypes != null && !this.terraformingSkeletonTypes.isEmpty()) {
            view.putInt("TFormSkelTypesCount", this.terraformingSkeletonTypes.size());
            int idx = 0;
            for (net.minecraft.block.Block block : this.terraformingSkeletonTypes) {
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(block).toString();
                view.putString("TFormSkelType" + idx, blockId);
                idx++;
            }
        } else {
            view.putInt("TFormSkelTypesCount", 0);
        }
    }

    @Override
    protected void readCustomData(ReadView view) {
        String mode = view.getString("Mode", BuildMode.PATH.name());
        try {
            this.buildMode = BuildMode.valueOf(mode);
        } catch (IllegalArgumentException ex) {
            this.buildMode = BuildMode.PATH;
        }
        this.pathWidth = Math.max(1, Math.min(9, view.getInt("PathWidth", this.pathWidth)));
        this.gradientWindow = Math.max(0, Math.min(9, view.getInt("GradWindow", this.gradientWindow)));
        this.stepGradientWindow = Math.max(0, Math.min(9, view.getInt("StepWindow", this.stepGradientWindow)));

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
            int w = view.getInt("WallGW" + g, 1);
            wallGroupWindows.add(Math.max(0, Math.min(9, w)));
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
            int w = view.getInt("TowerGW" + g, 1);
            towerGroupWindows.add(Math.max(0, Math.min(9, w)));
            String[] arr = new String[9];
            for (int i = 0; i < 9; i++) arr[i] = view.getString("TowerGS" + g + "_" + i, "");
            towerGroupSlots.add(arr);
        }
        for (int i = 0; i < towerUniqueBlockIds.size(); i++) {
            int grp = view.getInt("TowerGM" + i, 0);
            String id = towerUniqueBlockIds.get(i);
            towerBlockGroup.put(id, Math.max(0, Math.min(Math.max(0, towerGroupSlots.size() - 1), grp)));
        }

        // Mining-mode bits
        if (view.contains("MiningChestX")) {
            this.miningChestPos = new BlockPos(view.getInt("MiningChestX", 0), view.getInt("MiningChestY", 0), view.getInt("MiningChestZ", 0));
        } else {
            this.miningChestPos = null;
        }
        String miningDir = view.getString("MiningDir", null);
        if (miningDir != null) {
            try { this.miningDirection = net.minecraft.util.math.Direction.valueOf(miningDir); } catch (IllegalArgumentException ignored) {}
        }
        if (view.contains("MiningStartX")) {
            this.miningStartPos = new BlockPos(view.getInt("MiningStartX", 0), view.getInt("MiningStartY", 0), view.getInt("MiningStartZ", 0));
        } else {
            this.miningStartPos = null;
        }
        this.miningBranchDepth = view.getInt("MiningBranchDepth", 16);
        this.miningBranchSpacing = view.getInt("MiningBranchSpacing", 3);
        this.miningTunnelHeight = view.getInt("MiningTunnelHeight", 2);
        this.miningPrimaryProgress = view.getInt("MiningPrimaryProgress", 0);
        this.miningCurrentBranch = view.getInt("MiningCurrentBranch", -1);
        this.miningBranchLeft = view.getBoolean("MiningBranchLeft", true);
        this.miningBranchProgress = view.getInt("MiningBranchProgress", 0);
        this.miningReturningToChest = view.getBoolean("MiningReturningToChest", false);
        this.miningIdleAtChest = view.getBoolean("MiningIdleAtChest", false);
        this.miningBuildingBlockType = view.getString("MiningBuildingBlock", null);

        // Excavation-mode bits
        if (view.contains("ExcavChest1X")) {
            this.excavationChestPos1 = new BlockPos(view.getInt("ExcavChest1X", 0), view.getInt("ExcavChest1Y", 0), view.getInt("ExcavChest1Z", 0));
        } else {
            this.excavationChestPos1 = null;
        }
        if (view.contains("ExcavChest2X")) {
            this.excavationChestPos2 = new BlockPos(view.getInt("ExcavChest2X", 0), view.getInt("ExcavChest2Y", 0), view.getInt("ExcavChest2Z", 0));
        } else {
            this.excavationChestPos2 = null;
        }
        String excavDir1 = view.getString("ExcavDir1", null);
        if (excavDir1 != null) {
            try { this.excavationDir1 = net.minecraft.util.math.Direction.valueOf(excavDir1); } catch (IllegalArgumentException ignored) {}
        }
        String excavDir2 = view.getString("ExcavDir2", null);
        if (excavDir2 != null) {
            try { this.excavationDir2 = net.minecraft.util.math.Direction.valueOf(excavDir2); } catch (IllegalArgumentException ignored) {}
        }
        if (view.contains("ExcavStartX")) {
            this.excavationStartPos = new BlockPos(view.getInt("ExcavStartX", 0), view.getInt("ExcavStartY", 0), view.getInt("ExcavStartZ", 0));
        } else {
            this.excavationStartPos = null;
        }
        this.excavationHeight = view.getInt("ExcavHeight", 3);
        this.excavationDepth = view.getInt("ExcavDepth", 16);
        this.excavationCurrentRing = view.getInt("ExcavCurrentRing", 0);
        this.excavationRingProgress = view.getInt("ExcavRingProgress", 0);
        this.excavationReturningToChest = view.getBoolean("ExcavReturningToChest", false);
        this.excavationIdleAtStart = view.getBoolean("ExcavIdleAtStart", false);
        this.excavationBuildingBlockType = view.getString("ExcavBuildingBlock", null);

        // Terraforming-mode bits
        if (view.contains("TFormOriginX")) {
            this.terraformingOrigin = new BlockPos(view.getInt("TFormOriginX", 0), view.getInt("TFormOriginY", 0), view.getInt("TFormOriginZ", 0));
        } else {
            this.terraformingOrigin = null;
        }
        if (view.contains("TFormStartX")) {
            this.terraformingStartPos = new BlockPos(view.getInt("TFormStartX", 0), view.getInt("TFormStartY", 0), view.getInt("TFormStartZ", 0));
        } else {
            this.terraformingStartPos = null;
        }
        this.terraformingScanRadius = view.getInt("TFormScanRadius", 2);
        this.terraformingAlpha = view.getInt("TFormAlpha", 3);
        this.terraformingMinY = view.getInt("TFormMinY", 0);
        this.terraformingMaxY = view.getInt("TFormMaxY", 0);
        this.terraformingCurrentY = view.getInt("TFormCurrentY", 0);
        this.terraformingLayerProgress = view.getInt("TFormLayerProgress", 0);

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

        // Skeleton blocks
        int skelCount = view.getInt("TFormSkeletonCount", 0);
        if (skelCount > 0) {
            this.terraformingSkeletonBlocks = new java.util.ArrayList<>();
            for (int i = 0; i < skelCount; i++) {
                int x = view.getInt("TFormSkel" + i + "X", 0);
                int y = view.getInt("TFormSkel" + i + "Y", 0);
                int z = view.getInt("TFormSkel" + i + "Z", 0);
                this.terraformingSkeletonBlocks.add(new BlockPos(x, y, z));
            }
        } else {
            this.terraformingSkeletonBlocks = null;
        }

        // Skeleton types
        int skelTypesCount = view.getInt("TFormSkelTypesCount", 0);
        if (skelTypesCount > 0) {
            this.terraformingSkeletonTypes = new java.util.HashSet<>();
            for (int i = 0; i < skelTypesCount; i++) {
                String blockId = view.getString("TFormSkelType" + i, "");
                if (!blockId.isEmpty()) {
                    var ident = net.minecraft.util.Identifier.tryParse(blockId);
                    if (ident != null) {
                        var block = net.minecraft.registry.Registries.BLOCK.get(ident);
                        if (block != null) {
                            this.terraformingSkeletonTypes.add(block);
                        }
                    }
                }
            }
        } else {
            this.terraformingSkeletonTypes = null;
        }

        // Regenerate shell layers from skeleton if we have the data
        if (this.terraformingSkeletonBlocks != null && !this.terraformingSkeletonBlocks.isEmpty()) {
            this.terraformingShellByLayer = new java.util.HashMap<>();
            for (int y = terraformingMinY; y <= terraformingMaxY; y++) {
                java.util.List<BlockPos> skeletonAtY = new java.util.ArrayList<>();
                for (BlockPos pos : terraformingSkeletonBlocks) {
                    if (pos.getY() == y) {
                        skeletonAtY.add(pos);
                    }
                }

                if (!skeletonAtY.isEmpty()) {
                    java.util.Set<BlockPos> shellSet = ninja.trek.mc.goldgolem.terraforming.AlphaShape.generateShell(
                            skeletonAtY, terraformingAlpha, y);
                    terraformingShellByLayer.put(y, new java.util.ArrayList<>(shellSet));
                }
            }
        }
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
    public int getGradientWindow() { return gradientWindow; }
    public void setGradientWindow(int w) { this.gradientWindow = Math.max(0, Math.min(9, w)); }
    public int getStepGradientWindow() { return stepGradientWindow; }
    public void setStepGradientWindow(int w) { this.stepGradientWindow = Math.max(0, Math.min(9, w)); }

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

    private PlayerEntity getOwnerPlayer() {
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
                if (this.buildMode == BuildMode.MINING) {
                    // Mining mode: only start if idle at chest
                    if (this.miningIdleAtChest) {
                        this.miningIdleAtChest = false;
                        this.buildingPaths = true;
                        this.dataTracker.set(BUILDING_PATHS, true);
                        if (!player.isCreative()) stack.decrement(1);
                        spawnHearts();
                    } else {
                        // Already mining or returning, ignore
                        sp.sendMessage(Text.literal("[Gold Golem] Already mining!"), true);
                        return ActionResult.FAIL;
                    }
                } else if (this.buildMode == BuildMode.EXCAVATION) {
                    // Excavation mode: only start if idle at start
                    if (this.excavationIdleAtStart) {
                        this.excavationIdleAtStart = false;
                        this.buildingPaths = true;
                        this.dataTracker.set(BUILDING_PATHS, true);
                        if (!player.isCreative()) stack.decrement(1);
                        spawnHearts();
                    } else {
                        // Already excavating or returning, ignore
                        sp.sendMessage(Text.literal("[Gold Golem] Already excavating!"), true);
                        return ActionResult.FAIL;
                    }
                } else if (this.buildMode == BuildMode.TERRAFORMING) {
                    // Terraforming mode: start building when nugget is fed
                    this.buildingPaths = true;
                    this.dataTracker.set(BUILDING_PATHS, true);
                    if (!player.isCreative()) stack.decrement(1);
                    spawnHearts();
                } else if (this.buildMode == BuildMode.TREE) {
                    // Tree mode: start or resume building
                    if (this.treeWaitingForInventory) {
                        // Resume from where we left off
                        this.treeWaitingForInventory = false;
                        this.buildingPaths = true;
                        this.dataTracker.set(BUILDING_PATHS, true);
                        if (!player.isCreative()) stack.decrement(1);
                        spawnHearts();
                        sp.sendMessage(Text.literal("[Gold Golem] Resuming Tree Mode building!"), true);
                    } else {
                        // Start fresh
                        this.buildingPaths = true;
                        this.dataTracker.set(BUILDING_PATHS, true);
                        if (!player.isCreative()) stack.decrement(1);
                        spawnHearts();
                    }
                } else {
                    // Path/Wall/Tower modes: start building as before
                    this.buildingPaths = true;
                    this.dataTracker.set(BUILDING_PATHS, true);
                    if (!player.isCreative()) stack.decrement(1);
                    spawnHearts();
                    // Initialize anchor at golem feet for preview when starting
                    this.trackStart = new Vec3d(this.getX(), this.getY() + 0.05, this.getZ());
                    // send initial (possibly empty) line list with anchor
                    var owner = getOwnerPlayer();
                    if (owner instanceof net.minecraft.server.network.ServerPlayerEntity spOwner) {
                        ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of(), java.util.Optional.of(this.trackStart));
                    }
                    // reset recent placements cache
                    recentPlaced.clear();
                    placedHead = placedSize = 0;
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
            if (this.buildMode == BuildMode.MINING) {
                // Reset to idle state for mining mode
                this.miningIdleAtChest = true;
                this.miningReturningToChest = false;
                this.miningCurrentTarget = null;
                this.miningBreakProgress = 0;
            } else if (this.buildMode == BuildMode.TREE) {
                // Tree mode: reset waiting state (allows canceling "out of inventory" state)
                this.treeWaitingForInventory = false;
            } else {
                // Path/Wall/Tower mode cleanup
                this.trackStart = null;
                this.pendingLines.clear();
                this.currentLine = null;
                // clear client lines
                if (attacker instanceof net.minecraft.server.network.ServerPlayerEntity spOwner) {
                    ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of(), java.util.Optional.empty());
                }
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

    private void enqueueLine(Vec3d a, Vec3d b) {
        LineSeg seg = new LineSeg(a, b);
        pendingLines.addLast(seg);
        // Sync to client for debug rendering (owner only)
        if (this.getEntityWorld() instanceof ServerWorld) {
            var owner = getOwnerPlayer();
            if (owner instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                java.util.List<net.minecraft.util.math.Vec3d> list = new java.util.ArrayList<>();
                if (currentLine != null) {
                    list.add(currentLine.a);
                    list.add(currentLine.b);
                }
                for (LineSeg s : pendingLines) {
                    list.add(s.a);
                    list.add(s.b);
                }
                java.util.Optional<Vec3d> anchor = java.util.Optional.ofNullable(this.trackStart);
                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp, this.getId(), list, anchor);
            }
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
    private void placeOffsetAt(double x, double y, double z, double px, double pz, int stripWidth, int j, boolean xMajor, net.minecraft.util.math.Direction travelDir) {
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

    private void placeStripAt(double x, double y, double z, double px, double pz) {
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

        int Wcap = Math.min(this.gradientWindow, G);
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

        int Wcap = Math.min(this.stepGradientWindow, G);
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
            while (cursor < voxels.size() && ops < maxOps) {
                var v = voxels.get(cursor++);
                int rx = v.rel.getX();
                int ry = v.rel.getY();
                int rz = v.rel.getZ();
                int[] d = rotateAndMirror(rx, ry, rz, rot, mirror);
                int wx = MathHelper.floor(anchor.x) + d[0];
                int wy = MathHelper.floor(anchor.y) + d[1];
                int wz = MathHelper.floor(anchor.z) + d[2];
                placeBlockStateAt(wx, wy, wz, v.state, rot, mirror);
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

    private void placeCornerFill(LineSeg prev, LineSeg next) {
        // Compute end position of prev and start of next at ground y estimate and place strips with both normals
        BlockPos endCell = prev.cells.isEmpty() ? BlockPos.ofFloored(prev.b) : prev.cells.get(prev.cells.size() - 1);
        BlockPos startCell = next.cells.isEmpty() ? BlockPos.ofFloored(next.a) : next.cells.get(0);
        double yPrev = prev.b.y;
        double x = endCell.getX() + 0.5;
        double z = endCell.getZ() + 0.5;
        // prev normal
        double len1 = Math.sqrt(prev.dirX * prev.dirX + prev.dirZ * prev.dirZ);
        double px1 = len1 > 1e-4 ? (-prev.dirZ / len1) : 0.0;
        double pz1 = len1 > 1e-4 ? ( prev.dirX / len1) : 0.0;
        // next normal
        double len2 = Math.sqrt(next.dirX * next.dirX + next.dirZ * next.dirZ);
        double px2 = len2 > 1e-4 ? (-next.dirZ / len2) : 0.0;
        double pz2 = len2 > 1e-4 ? ( next.dirX / len2) : 0.0;
        // Expand width by +1 to help fill gaps
        int old = this.pathWidth;
        this.pathWidth = Math.min(9, old + 1);
        placeStripAt(x, yPrev, z, px1, pz1);
        placeStripAt(x, yPrev, z, px2, pz2);
        this.pathWidth = old;
    }

    private boolean recordPlaced(long key) {
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

    private void unrecordPlaced(long key) {
        // best-effort: keep it recorded to avoid thrash; no-op
    }

    private int findItem(net.minecraft.item.Item item) {
        for (int i = 0; i < inventory.size(); i++) {
            var st = inventory.getStack(i);
            if (!st.isEmpty() && st.isOf(item)) return i;
        }
        return -1;
    }

    // removed: runtime block use logging helper

    private static class LineSeg {
        final Vec3d a;
        final Vec3d b;
        final double dirX;
        final double dirZ;
        final java.util.List<BlockPos> cells;
        // Pending placement state (initialized on begin)
        int widthSnapshot = 1;
        int half = 0;
        java.util.BitSet processed; // per (cellIndex * width + (j+half))
        int totalBits = 0;
        int scanBit = 0;
        LineSeg(Vec3d a, Vec3d b) {
            this.a = a;
            this.b = b;
            this.dirX = b.x - a.x;
            this.dirZ = b.z - a.z;
            this.cells = computeCells(BlockPos.ofFloored(a.x, 0, a.z), BlockPos.ofFloored(b.x, 0, b.z));
        }
        void begin(GoldGolemEntity golem) {
            this.widthSnapshot = Math.max(1, Math.min(9, golem.getPathWidth()));
            this.half = (widthSnapshot - 1) / 2;
            this.totalBits = Math.max(0, cells.size() * widthSnapshot);
            this.processed = new java.util.BitSet(totalBits);
            this.scanBit = 0;
        }
        boolean isFullyProcessed() {
            if (totalBits == 0) return true;
            int idx = processed.nextClearBit(0);
            return idx >= totalBits;
        }
        int progressCellIndex(double gx, double gz) {
            // Project golem XZ onto the AB vector to estimate progress along the line
            double ax = a.x, az = a.z;
            double vx = dirX, vz = dirZ;
            double denom = (vx * vx + vz * vz);
            double t = 0.0;
            if (denom > 1e-6) {
                double wx = gx - ax;
                double wz = gz - az;
                t = (wx * vx + wz * vz) / denom;
            }
            t = MathHelper.clamp(t, 0.0, 1.0);
            int n = Math.max(1, cells.size());
            return MathHelper.clamp((int) Math.floor(t * (n - 1)), 0, n - 1);
        }
        Vec3d pointAtIndex(int idx) {
            if (cells.isEmpty()) return b;
            int i = MathHelper.clamp(idx, 0, cells.size() - 1);
            BlockPos c = cells.get(i);
            double t = cells.size() <= 1 ? 1.0 : (double) i / (double) (cells.size() - 1);
            double y = MathHelper.lerp(t, a.y, b.y);
            return new Vec3d(c.getX() + 0.5, y, c.getZ() + 0.5);
        }
        void placePendingUpTo(GoldGolemEntity golem, int boundCell, int maxOps) {
            if (processed == null || totalBits == 0) return;
            int boundExclusive = Math.min(totalBits, Math.max(0, (boundCell + 1) * widthSnapshot));
            // Compute perpendicular
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            double px = len > 1e-4 ? (-dirZ / len) : 0.0;
            double pz = len > 1e-4 ? ( dirX / len) : 0.0;
            int ops = 0;
            int bit = processed.nextClearBit(scanBit);
            while (ops < maxOps && bit >= 0 && bit < boundExclusive) {
                int cellIndex = bit / widthSnapshot;
                int jIndex = bit % widthSnapshot;
                int j = jIndex - half;
                BlockPos cell = cells.get(cellIndex);
                double t = cells.size() <= 1 ? 1.0 : (double) cellIndex / (double) (cells.size() - 1);
                double y = MathHelper.lerp(t, a.y, b.y);
                double x = cell.getX() + 0.5;
                double z = cell.getZ() + 0.5;
                boolean xMajor = Math.abs(dirX) >= Math.abs(dirZ);
                net.minecraft.util.math.Direction travelDir = xMajor
                        ? (dirX >= 0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST)
                        : (dirZ >= 0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH);
                golem.placeOffsetAt(x, y, z, px, pz, widthSnapshot, j, xMajor, travelDir);
                processed.set(bit); // mark attempted (placed or skipped) to avoid thrash
                ops++;
                bit = processed.nextClearBit(bit + 1);
            }
            scanBit = Math.min(Math.max(0, bit), totalBits);
        }

        BlockPos placeNextBlock(GoldGolemEntity golem, int boundCell) {
            if (processed == null || totalBits == 0) return null;
            int boundExclusive = Math.min(totalBits, Math.max(0, (boundCell + 1) * widthSnapshot));
            // Compute perpendicular
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            double px = len > 1e-4 ? (-dirZ / len) : 0.0;
            double pz = len > 1e-4 ? ( dirX / len) : 0.0;

            int bit = processed.nextClearBit(scanBit);
            if (bit >= 0 && bit < boundExclusive) {
                int cellIndex = bit / widthSnapshot;
                int jIndex = bit % widthSnapshot;
                int j = jIndex - half;
                BlockPos cell = cells.get(cellIndex);
                double t = cells.size() <= 1 ? 1.0 : (double) cellIndex / (double) (cells.size() - 1);
                double y = MathHelper.lerp(t, a.y, b.y);
                double x = cell.getX() + 0.5;
                double z = cell.getZ() + 0.5;
                boolean xMajor = Math.abs(dirX) >= Math.abs(dirZ);
                net.minecraft.util.math.Direction travelDir = xMajor
                        ? (dirX >= 0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST)
                        : (dirZ >= 0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH);

                // Find the actual block position where we'll place
                int bx = MathHelper.floor(x + px * j);
                int bz = MathHelper.floor(z + pz * j);
                int y0 = MathHelper.floor(y);

                // Find ground Y
                var world = golem.getEntityWorld();
                Integer groundY = null;
                for (int yy = y0 + 1; yy >= y0 - 6; yy--) {
                    BlockPos test = new BlockPos(bx, yy, bz);
                    var st = world.getBlockState(test);
                    if (!st.isAir() && st.isFullCube(world, test)) { groundY = yy; break; }
                }

                BlockPos result = null;
                if (groundY != null) {
                    // Find the actual placement position
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos rp = new BlockPos(bx, groundY + dy, bz);
                        var rs = world.getBlockState(rp);
                        if (rs.isAir() || !rs.isFullCube(world, rp)) continue;
                        BlockPos ap = rp.up();
                        var as = world.getBlockState(ap);
                        if (as.isFullCube(world, ap)) continue;
                        result = rp;
                        break;
                    }
                }

                golem.placeOffsetAt(x, y, z, px, pz, widthSnapshot, j, xMajor, travelDir);
                processed.set(bit);
                scanBit = Math.min(Math.max(0, bit + 1), totalBits);

                return result != null ? result : new BlockPos(bx, groundY != null ? groundY : y0, bz);
            }
            return null;
        }

        BlockPos getNextUnplacedBlock(int boundCell) {
            if (processed == null || totalBits == 0) return null;
            int boundExclusive = Math.min(totalBits, Math.max(0, (boundCell + 1) * widthSnapshot));

            // Find the next unplaced block after scanBit
            int bit = processed.nextClearBit(scanBit);
            if (bit >= 0 && bit < boundExclusive) {
                int cellIndex = bit / widthSnapshot;
                int jIndex = bit % widthSnapshot;
                int j = jIndex - half;
                BlockPos cell = cells.get(cellIndex);
                double t = cells.size() <= 1 ? 1.0 : (double) cellIndex / (double) (cells.size() - 1);
                double y = MathHelper.lerp(t, a.y, b.y);

                double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
                double px = len > 1e-4 ? (-dirZ / len) : 0.0;
                double pz = len > 1e-4 ? ( dirX / len) : 0.0;
                double x = cell.getX() + 0.5;
                double z = cell.getZ() + 0.5;

                int bx = MathHelper.floor(x + px * j);
                int bz = MathHelper.floor(z + pz * j);
                int by = MathHelper.floor(y);

                return new BlockPos(bx, by, bz);
            }
            return null;
        }
        int suggestFollowIndex(double gx, double gz, int lookAhead) {
            int prog = progressCellIndex(gx, gz);
            int idx = Math.min(Math.max(0, prog + Math.max(1, lookAhead)), Math.max(0, cells.size() - 1));
            return idx;
        }
        private static java.util.List<BlockPos> computeCells(BlockPos a, BlockPos b) {
            // Supercover Bresenham: cover corners when both axes change to avoid diagonal gaps
            java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
            int x0 = a.getX();
            int z0 = a.getZ();
            int x1 = b.getX();
            int z1 = b.getZ();
            int dx = Math.abs(x1 - x0);
            int dz = Math.abs(z1 - z0);
            int sx = (x0 < x1) ? 1 : -1;
            int sz = (z0 < z1) ? 1 : -1;
            int err = dx - dz;
            int x = x0;
            int z = z0;
            out.add(new BlockPos(x, 0, z));
            while (x != x1 || z != z1) {
                int e2 = err << 1;
                if (e2 > -dz) { err -= dz; x += sx; out.add(new BlockPos(x, 0, z)); }
                if (e2 < dx) { err += dx; z += sz; out.add(new BlockPos(x, 0, z)); }
            }
            return out;
        }
    }
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
        if (golem.getBuildMode() == GoldGolemEntity.BuildMode.MINING) return false; // Never follow in mining mode
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
        if (golem.getBuildMode() == GoldGolemEntity.BuildMode.MINING) return false; // Never follow in mining mode
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
        if (golem.getBuildMode() == GoldGolemEntity.BuildMode.MINING) return false; // Never wander in mining mode
        return super.canStart();
    }

    @Override
    public boolean shouldContinue() {
        if (golem.isBuildingPaths()) return false;
        if (golem.getBuildMode() == GoldGolemEntity.BuildMode.MINING) return false; // Never wander in mining mode
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
