package ninja.trek.mc.goldgolem.world.entity;

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
    public enum BuildMode { PATH, WALL }
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
            // Look at owner while building
            PlayerEntity owner = getOwnerPlayer();
            if (owner != null) {
                this.getLookControl().lookAt(owner, 30.0f, 30.0f);
            }
            if (this.buildMode == BuildMode.WALL) { tickWallMode(owner); return; }
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
            this.trackStart = null;
            this.pendingLines.clear();
            this.currentLine = null;
            spawnAngry();
            // clear client lines
                    if (attacker instanceof net.minecraft.server.network.ServerPlayerEntity spOwner) {
                        ninja.trek.mc.goldgolem.net.ServerNet.sendLines(spOwner, this.getId(), java.util.List.of(), java.util.Optional.empty());
                    }
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
        return super.canStart();
    }

    @Override
    public boolean shouldContinue() {
        if (golem.isBuildingPaths()) return false;
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
