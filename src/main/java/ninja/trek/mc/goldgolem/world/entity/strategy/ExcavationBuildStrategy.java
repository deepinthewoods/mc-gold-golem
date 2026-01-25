package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.OreMiningMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.registry.RegistryKeys;

import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for Excavation mode.
 * Excavates an area in a spiral pattern and deposits materials in chests.
 */
public class ExcavationBuildStrategy extends AbstractBuildStrategy {

    // Excavation configuration
    private BlockPos chestPos1 = null;
    private BlockPos chestPos2 = null;
    private Direction dir1 = null;
    private Direction dir2 = null;
    private BlockPos startPos = null;
    private int height = 3;
    private int depth = 16;  // 0 = infinite (stops at gold blocks)
    private OreMiningMode oreMiningMode = OreMiningMode.SILK_TOUCH_FORTUNE;

    // Excavation direction (opposite of chest directions)
    private Direction primaryExcavDir = null;   // Opposite of dir1
    private Direction secondaryExcavDir = null; // Opposite of dir2

    // Excavation progress state
    private int currentRing = 0;
    private int ringProgress = 0;
    private boolean returningToChest = false;
    private boolean idleAtStart = false;

    // Excavation runtime state (not persisted)
    private String buildingBlockType = null;
    private int breakProgress = 0;
    private BlockPos currentTarget = null;
    private int miningSwingTick = 0;
    private static final int MINING_SWING_INTERVAL = 5; // ticks between swings

    // PlacementPlanner for smart movement (reused for mining)
    private PlacementPlanner planner;
    private int ticksInAir = 0;
    private static final int TICKS_IN_AIR_THRESHOLD = 5; // Brief air time is fine

    // Movement tracking for return-to-chest stuck detection
    private double lastX = 0, lastZ = 0;
    private int noMovementTicks = 0;
    private static final double MOVEMENT_THRESHOLD = 0.1;
    private static final int STUCK_TICKS_BEFORE_TELEPORT = 100;

    @Override
    public BuildMode getMode() {
        return BuildMode.EXCAVATION;
    }

    @Override
    public String getNbtPrefix() {
        return "Excav";
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        // Reset transient state
        currentTarget = null;
        breakProgress = 0;
        planner = new PlacementPlanner(golem);
        ticksInAir = 0;
    }

    @Override
    public void tick(GoldGolemEntity golem, PlayerEntity owner) {
        if (entity == null) return;
        tickExcavationMode();
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        // Clear breaking overlay before cleanup
        if (currentTarget != null && entity != null && entity.getEntityWorld() instanceof ServerWorld sw) {
            sw.setBlockBreakingInfo(entity.getId(), currentTarget, -1);
        }
        super.cleanup(golem);
        clearState();
    }

    @Override
    public boolean isComplete() {
        return false; // Excavation completes when it reaches depth, handled in tick
    }

    @Override
    public boolean usesPlayerTracking() {
        return false; // Excavation operates autonomously
    }

    // ==================== Configuration Methods ====================

    public void setConfig(BlockPos chest1, BlockPos chest2, Direction dir1, Direction dir2, BlockPos startPos) {
        this.chestPos1 = chest1;
        this.chestPos2 = chest2;
        this.dir1 = dir1;
        this.dir2 = dir2;
        this.startPos = startPos;
        this.idleAtStart = true; // Start idle, waiting for gold nugget

        // Compute excavation directions (opposite of chest directions)
        this.primaryExcavDir = dir1 != null ? dir1.getOpposite() : null;
        this.secondaryExcavDir = dir2 != null ? dir2.getOpposite() : null;
    }

    public void setSliders(int height, int depth) {
        int newHeight = Math.max(1, Math.min(5, height));
        int newDepth = Math.max(0, Math.min(64, depth));  // 0 = infinite

        // If height changed, restart excavation (but not for depth changes)
        if (newHeight != this.height && !idleAtStart) {
            this.height = newHeight;
            this.depth = newDepth;
            restartExcavation();
        } else {
            this.height = newHeight;
            this.depth = newDepth;
        }
    }

    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    public OreMiningMode getOreMiningMode() { return oreMiningMode; }
    public void setOreMiningMode(OreMiningMode mode) { this.oreMiningMode = mode != null ? mode : OreMiningMode.ALWAYS; }

    public boolean isIdleAtStart() { return idleAtStart; }
    public void setIdleAtStart(boolean idle) { this.idleAtStart = idle; }

    public void resetToIdle() {
        idleAtStart = true;
        returningToChest = false;
        // Clear breaking overlay before resetting target
        if (currentTarget != null && entity != null && entity.getEntityWorld() instanceof ServerWorld sw) {
            sw.setBlockBreakingInfo(entity.getId(), currentTarget, -1);
        }
        currentTarget = null;
        breakProgress = 0;
        miningSwingTick = 0;
        ticksInAir = 0;
        noMovementTicks = 0;
        if (planner != null) {
            planner.clear();
        }
        if (entity != null) {
            entity.setCurrentMiningTool(ItemStack.EMPTY);
        }
    }

    public void startFromIdle() {
        idleAtStart = false;
        // Skip rings that are already excavated
        skipCompletedRings();
    }

    /**
     * Restart excavation from the beginning, skipping already-completed rings.
     * Called when height changes or when fed a nugget while active.
     */
    public void restartExcavation() {
        // Clear current state
        currentRing = 0;
        ringProgress = 0;
        returningToChest = false;
        if (currentTarget != null && entity != null && entity.getEntityWorld() instanceof ServerWorld sw) {
            sw.setBlockBreakingInfo(entity.getId(), currentTarget, -1);
        }
        currentTarget = null;
        breakProgress = 0;
        miningSwingTick = 0;
        ticksInAir = 0;
        noMovementTicks = 0;
        if (planner != null) {
            planner.clear();
        }
        if (entity != null) {
            entity.setCurrentMiningTool(ItemStack.EMPTY);
        }
        // Skip already-completed rings
        skipCompletedRings();
        idleAtStart = false;
    }

    /**
     * Skip rings that are already fully excavated (all air).
     * Advances currentRing to the first ring that has blocks to mine.
     */
    private void skipCompletedRings() {
        if (entity == null || startPos == null) return;

        int maxRing = depth > 0 ? depth - 1 : 63;
        while (currentRing <= maxRing) {
            if (ringHasBlocksToMine(currentRing)) {
                break; // Found a ring with blocks to mine
            }
            currentRing++;
            ringProgress = 0;
        }
    }

    /**
     * Check if a ring has any blocks that need to be mined.
     */
    private boolean ringHasBlocksToMine(int ring) {
        int blocksInRing = 2 * ring + 1;
        for (int progress = 0; progress < blocksInRing; progress++) {
            BlockPos basePos = getExpandingSquarePosition(ring, progress);
            for (int dy = 0; dy < height; dy++) {
                BlockPos pos = basePos.up(dy);
                if (shouldMineBlock(pos)) {
                    return true; // Found at least one block to mine
                }
            }
        }
        return false; // All blocks in this ring are air or shouldn't be mined
    }

    public void clearState() {
        currentRing = 0;
        ringProgress = 0;
        returningToChest = false;
        idleAtStart = false;
        // Clear breaking overlay before resetting target
        if (currentTarget != null && entity != null && entity.getEntityWorld() instanceof ServerWorld sw) {
            sw.setBlockBreakingInfo(entity.getId(), currentTarget, -1);
        }
        currentTarget = null;
        breakProgress = 0;
        miningSwingTick = 0;
        ticksInAir = 0;
        noMovementTicks = 0;
        if (planner != null) {
            planner.clear();
        }
        if (entity != null) {
            entity.setCurrentMiningTool(ItemStack.EMPTY);
        }
    }

    // ==================== NBT Serialization ====================

    @Override
    public void writeNbt(NbtCompound nbt) {
        if (chestPos1 != null) {
            nbt.putInt("Chest1X", chestPos1.getX());
            nbt.putInt("Chest1Y", chestPos1.getY());
            nbt.putInt("Chest1Z", chestPos1.getZ());
        }
        if (chestPos2 != null) {
            nbt.putInt("Chest2X", chestPos2.getX());
            nbt.putInt("Chest2Y", chestPos2.getY());
            nbt.putInt("Chest2Z", chestPos2.getZ());
        }
        if (dir1 != null) nbt.putString("Dir1", dir1.name());
        if (dir2 != null) nbt.putString("Dir2", dir2.name());
        if (primaryExcavDir != null) nbt.putString("PrimaryExcavDir", primaryExcavDir.name());
        if (secondaryExcavDir != null) nbt.putString("SecondaryExcavDir", secondaryExcavDir.name());
        if (startPos != null) {
            nbt.putInt("StartX", startPos.getX());
            nbt.putInt("StartY", startPos.getY());
            nbt.putInt("StartZ", startPos.getZ());
        }
        nbt.putInt("Height", height);
        nbt.putInt("Depth", depth);
        nbt.putInt("OreMiningMode", oreMiningMode.ordinal());
        nbt.putInt("CurrentRing", currentRing);
        nbt.putInt("RingProgress", ringProgress);
        nbt.putBoolean("ReturningToChest", returningToChest);
        nbt.putBoolean("IdleAtStart", idleAtStart);
        if (buildingBlockType != null) {
            nbt.putString("BuildingBlock", buildingBlockType);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        if (nbt.contains("Chest1X")) {
            chestPos1 = new BlockPos(nbt.getInt("Chest1X", 0), nbt.getInt("Chest1Y", 0), nbt.getInt("Chest1Z", 0));
        } else {
            chestPos1 = null;
        }
        if (nbt.contains("Chest2X")) {
            chestPos2 = new BlockPos(nbt.getInt("Chest2X", 0), nbt.getInt("Chest2Y", 0), nbt.getInt("Chest2Z", 0));
        } else {
            chestPos2 = null;
        }
        if (nbt.contains("Dir1")) {
            try {
                dir1 = Direction.valueOf(nbt.getString("Dir1", "NORTH"));
            } catch (IllegalArgumentException ignored) {
                dir1 = null;
            }
        }
        if (nbt.contains("Dir2")) {
            try {
                dir2 = Direction.valueOf(nbt.getString("Dir2", "EAST"));
            } catch (IllegalArgumentException ignored) {
                dir2 = null;
            }
        }
        if (nbt.contains("PrimaryExcavDir")) {
            try {
                primaryExcavDir = Direction.valueOf(nbt.getString("PrimaryExcavDir", "SOUTH"));
            } catch (IllegalArgumentException ignored) {
                primaryExcavDir = null;
            }
        }
        if (nbt.contains("SecondaryExcavDir")) {
            try {
                secondaryExcavDir = Direction.valueOf(nbt.getString("SecondaryExcavDir", "WEST"));
            } catch (IllegalArgumentException ignored) {
                secondaryExcavDir = null;
            }
        }
        if (nbt.contains("StartX")) {
            startPos = new BlockPos(nbt.getInt("StartX", 0), nbt.getInt("StartY", 0), nbt.getInt("StartZ", 0));
        } else {
            startPos = null;
        }
        height = nbt.getInt("Height", 3);
        depth = nbt.getInt("Depth", 16);
        oreMiningMode = OreMiningMode.fromOrdinal(nbt.getInt("OreMiningMode", 0));
        currentRing = nbt.getInt("CurrentRing", 0);
        ringProgress = nbt.getInt("RingProgress", 0);
        returningToChest = nbt.getBoolean("ReturningToChest", false);
        idleAtStart = nbt.getBoolean("IdleAtStart", false);
        buildingBlockType = nbt.contains("BuildingBlock") ? nbt.getString("BuildingBlock", null) : null;
    }

    // ==================== Polymorphic Dispatch Methods ====================

    @Override
    public int getConfigInt(String key, int defaultValue) {
        return switch (key) {
            case "height" -> height;
            case "depth" -> depth;
            case "oreMiningMode" -> oreMiningMode.ordinal();
            default -> defaultValue;
        };
    }

    @Override
    public void writeLegacyNbt(WriteView view) {
        if (chestPos1 != null) {
            view.putInt("ExcavChest1X", chestPos1.getX());
            view.putInt("ExcavChest1Y", chestPos1.getY());
            view.putInt("ExcavChest1Z", chestPos1.getZ());
        }
        if (chestPos2 != null) {
            view.putInt("ExcavChest2X", chestPos2.getX());
            view.putInt("ExcavChest2Y", chestPos2.getY());
            view.putInt("ExcavChest2Z", chestPos2.getZ());
        }
        if (dir1 != null) {
            view.putString("ExcavDir1", dir1.name());
        }
        if (dir2 != null) {
            view.putString("ExcavDir2", dir2.name());
        }
        if (primaryExcavDir != null) {
            view.putString("ExcavPrimaryDir", primaryExcavDir.name());
        }
        if (secondaryExcavDir != null) {
            view.putString("ExcavSecondaryDir", secondaryExcavDir.name());
        }
        if (startPos != null) {
            view.putInt("ExcavStartX", startPos.getX());
            view.putInt("ExcavStartY", startPos.getY());
            view.putInt("ExcavStartZ", startPos.getZ());
        }
        view.putInt("ExcavHeight", height);
        view.putInt("ExcavDepth", depth);
        view.putInt("ExcavOreMiningMode", oreMiningMode.ordinal());
        view.putInt("ExcavCurrentRing", currentRing);
        view.putInt("ExcavRingProgress", ringProgress);
        view.putBoolean("ExcavReturningToChest", returningToChest);
        view.putBoolean("ExcavIdleAtStart", idleAtStart);
        if (buildingBlockType != null) {
            view.putString("ExcavBuildingBlock", buildingBlockType);
        }
    }

    @Override
    public void readLegacyNbt(ReadView view) {
        if (view.contains("ExcavChest1X")) {
            chestPos1 = new BlockPos(
                view.getInt("ExcavChest1X", 0),
                view.getInt("ExcavChest1Y", 0),
                view.getInt("ExcavChest1Z", 0)
            );
        } else {
            chestPos1 = null;
        }
        if (view.contains("ExcavChest2X")) {
            chestPos2 = new BlockPos(
                view.getInt("ExcavChest2X", 0),
                view.getInt("ExcavChest2Y", 0),
                view.getInt("ExcavChest2Z", 0)
            );
        } else {
            chestPos2 = null;
        }
        String excavDir1 = view.getString("ExcavDir1", null);
        if (excavDir1 != null) {
            try {
                dir1 = Direction.valueOf(excavDir1);
            } catch (IllegalArgumentException ignored) {
                dir1 = null;
            }
        }
        String excavDir2 = view.getString("ExcavDir2", null);
        if (excavDir2 != null) {
            try {
                dir2 = Direction.valueOf(excavDir2);
            } catch (IllegalArgumentException ignored) {
                dir2 = null;
            }
        }
        String excavPrimaryDir = view.getString("ExcavPrimaryDir", null);
        if (excavPrimaryDir != null) {
            try {
                primaryExcavDir = Direction.valueOf(excavPrimaryDir);
            } catch (IllegalArgumentException ignored) {
                primaryExcavDir = null;
            }
        }
        String excavSecondaryDir = view.getString("ExcavSecondaryDir", null);
        if (excavSecondaryDir != null) {
            try {
                secondaryExcavDir = Direction.valueOf(excavSecondaryDir);
            } catch (IllegalArgumentException ignored) {
                secondaryExcavDir = null;
            }
        }
        if (view.contains("ExcavStartX")) {
            startPos = new BlockPos(
                view.getInt("ExcavStartX", 0),
                view.getInt("ExcavStartY", 0),
                view.getInt("ExcavStartZ", 0)
            );
        } else {
            startPos = null;
        }
        height = view.getInt("ExcavHeight", 3);
        depth = view.getInt("ExcavDepth", 16);
        oreMiningMode = OreMiningMode.fromOrdinal(view.getInt("ExcavOreMiningMode", 0));
        currentRing = view.getInt("ExcavCurrentRing", 0);
        ringProgress = view.getInt("ExcavRingProgress", 0);
        returningToChest = view.getBoolean("ExcavReturningToChest", false);
        idleAtStart = view.getBoolean("ExcavIdleAtStart", false);
        String block = view.getString("ExcavBuildingBlock", null);
        buildingBlockType = (block != null && !block.isEmpty()) ? block : null;
    }

    @Override
    public boolean canStartFromIdle() {
        return isIdleAtStart();
    }

    @Override
    public FeedResult handleFeedInteraction(PlayerEntity player) {
        if (isWaitingForResources()) {
            setWaitingForResources(false);
            return FeedResult.RESUMED;
        }
        if (isIdleAtStart()) {
            startFromIdle();
            return FeedResult.STARTED;
        }
        // Already active - restart excavation from the beginning
        restartExcavation();
        return FeedResult.STARTED;
    }

    @Override
    public void handleOwnerDamage() {
        resetToIdle();
    }

    // ==================== Excavation Logic ====================

    private void tickExcavationMode() {
        if (chestPos1 == null || chestPos2 == null || startPos == null) {
            entity.setBuildingPaths(false);
            return;
        }

        // State 1: Idle at start (waiting for gold nugget)
        if (idleAtStart) {
            double dx = entity.getX() - (startPos.getX() + 0.5);
            double dz = entity.getZ() - (startPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            if (distSq > 4.0) {
                entity.getNavigation().startMovingTo(startPos.getX() + 0.5,
                    startPos.getY(), startPos.getZ() + 0.5, 1.0);
            } else {
                entity.getNavigation().stop();
            }
            return;
        }

        // State 2: Returning to chest to deposit
        if (returningToChest) {
            tickExcavationReturn();
            return;
        }

        // State 3: Check if inventory is full (need to return)
        if (isInventoryFull()) {
            returningToChest = true;
            currentTarget = null;
            breakProgress = 0;
            return;
        }

        // State 4: Active excavation
        tickExcavationActive();
    }

    private void tickExcavationReturn() {
        BlockPos nearestChest = getNearestChest();
        double dx = entity.getX() - (nearestChest.getX() + 0.5);
        double dz = entity.getZ() - (nearestChest.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;

        if (distSq > 4.0) {
            entity.getNavigation().startMovingTo(nearestChest.getX() + 0.5,
                nearestChest.getY(), nearestChest.getZ() + 0.5, 1.1);

            // Track actual movement for stuck detection
            double movedX = entity.getX() - lastX;
            double movedZ = entity.getZ() - lastZ;
            double movedDistSq = movedX * movedX + movedZ * movedZ;

            if (movedDistSq < MOVEMENT_THRESHOLD * MOVEMENT_THRESHOLD) {
                noMovementTicks++;
                if (noMovementTicks >= STUCK_TICKS_BEFORE_TELEPORT && distSq > 9.0) {
                    teleportToChest(nearestChest);
                    noMovementTicks = 0;
                }
            } else {
                noMovementTicks = 0;
            }

            lastX = entity.getX();
            lastZ = entity.getZ();
        } else {
            entity.getNavigation().stop();
            depositInventoryToChest(nearestChest);
            returningToChest = false;
            noMovementTicks = 0;
            if (isInventoryFull()) {
                idleAtStart = true;
                entity.setBuildingPaths(false);
            }
        }
    }

    private void tickExcavationActive() {
        // Reactive floor building - only when falling into deep gap
        if (needsFloorSupport()) {
            placeFloorBlocks();
        }

        // Try to place torches in dark areas
        tryPlaceTorchInDarkArea();

        // Ensure planner is initialized
        if (planner == null) {
            planner = new PlacementPlanner(entity);
        }

        // Initialize planner for current ring if needed
        if (planner.isComplete()) {
            List<BlockPos> ringBlocks = getBlocksForCurrentRing();
            if (ringBlocks.isEmpty()) {
                // Ring is complete (all blocks mined) - return to chest before starting next ring
                currentRing++;
                ringProgress = 0;

                // Check if excavation complete
                int maxRing = depth > 0 ? depth - 1 : 63;
                if (currentRing > maxRing) {
                    // Final return to chest before going idle (only if we have items to deposit)
                    if (!isInventoryEmpty()) {
                        returningToChest = true;
                        currentTarget = null;
                        breakProgress = 0;
                        return;
                    }
                    // Nothing to deposit, just go idle
                    idleAtStart = true;
                    entity.setBuildingPaths(false);
                    return;
                }

                // Return to chest to deposit items after completing this ring (only if we have items)
                if (!isInventoryEmpty()) {
                    returningToChest = true;
                    currentTarget = null;
                    breakProgress = 0;
                    return;
                }
                // Nothing to deposit, continue to next ring
                return;
            }
            planner.setBlocks(ringBlocks, pos -> !shouldMineBlock(pos));
        }

        // Let planner handle movement and reach
        PlacementPlanner.TickResult result = planner.tick((pos, nextPos) -> {
            return mineBlock(pos);  // Returns true when block is broken
        });

        // Handle completion
        if (result == PlacementPlanner.TickResult.COMPLETED) {
            // Ring complete, advance to next ring on next tick
            currentRing++;
            ringProgress = 0;

            // Return to chest to deposit items after each ring completion (only if we have items)
            if (!isInventoryEmpty()) {
                returningToChest = true;
                currentTarget = null;
                breakProgress = 0;
            }
        }
    }

    /**
     * Get all blocks to mine for the current ring.
     * Returns blocks column-by-column (all Y levels for each XZ position).
     */
    private List<BlockPos> getBlocksForCurrentRing() {
        List<BlockPos> blocks = new ArrayList<>();
        int blocksInRing = 2 * currentRing + 1;

        for (int progress = 0; progress < blocksInRing; progress++) {
            BlockPos basePos = getExpandingSquarePosition(currentRing, progress);
            for (int dy = 0; dy < height; dy++) {
                BlockPos pos = basePos.up(dy);
                if (shouldMineBlock(pos)) {
                    blocks.add(pos);
                }
            }
        }
        return blocks;
    }

    /**
     * Mine a single block. Returns true when the block is fully broken.
     */
    private boolean mineBlock(BlockPos pos) {
        if (entity.getEntityWorld().isClient()) return false;

        BlockState state = entity.getEntityWorld().getBlockState(pos);
        if (state.isAir()) {
            // Block already gone
            currentTarget = null;
            breakProgress = 0;
            return true;
        }

        if (currentTarget == null || !currentTarget.equals(pos)) {
            // Clear previous breaking overlay
            if (currentTarget != null && entity.getEntityWorld() instanceof ServerWorld sw) {
                sw.setBlockBreakingInfo(entity.getId(), currentTarget, -1);
            }
            currentTarget = pos;
            breakProgress = 0;
            miningSwingTick = 0;
        }

        ItemStack bestTool = findBestTool(state);
        float breakSpeed = bestTool.isEmpty() ? 1.0f : bestTool.getMiningSpeedMultiplier(state);
        breakSpeed *= 0.5f;

        float hardness = state.getHardness(entity.getEntityWorld(), pos);
        if (hardness < 0) {
            // Unbreakable
            currentTarget = null;
            breakProgress = 0;
            return true; // Skip this block
        }

        int requiredTicks = (int) Math.ceil((hardness * 30.0f) / breakSpeed);
        requiredTicks = Math.max(1, requiredTicks);

        breakProgress++;
        miningSwingTick++;

        // Set the mining tool for display
        entity.setCurrentMiningTool(bestTool);

        // Update breaking overlay (stages 0-9)
        if (entity.getEntityWorld() instanceof ServerWorld sw) {
            int breakStage = (int) ((float) breakProgress / requiredTicks * 10.0f);
            breakStage = Math.min(9, Math.max(0, breakStage));
            sw.setBlockBreakingInfo(entity.getId(), pos, breakStage);
        }

        // Trigger continuous arm swing animation
        if (miningSwingTick >= MINING_SWING_INTERVAL) {
            miningSwingTick = 0;
            entity.beginHandAnimation(isLeftHandActive(), pos, null);
            alternateHand();

            // Spawn small block particles during mining
            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                BlockStateParticleEffect particleEffect = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
                sw.spawnParticles(particleEffect,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    3, 0.2, 0.2, 0.2, 0.05);
            }
        }

        if (breakProgress >= requiredTicks) {
            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                var drops = net.minecraft.block.Block.getDroppedStacks(state, sw, pos,
                    entity.getEntityWorld().getBlockEntity(pos), entity, bestTool);

                for (ItemStack drop : drops) {
                    addToInventory(drop);
                }
            }

            entity.getEntityWorld().breakBlock(pos, false);

            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                // Clear breaking overlay
                sw.setBlockBreakingInfo(entity.getId(), pos, -1);

                // Spawn burst of block-specific particles
                BlockStateParticleEffect particleEffect = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
                sw.spawnParticles(particleEffect,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    30, 0.4, 0.4, 0.4, 0.15);
            }

            currentTarget = null;
            breakProgress = 0;
            miningSwingTick = 0;
            return true; // Block broken
        }

        return false; // Still mining
    }

    /**
     * Check if the golem needs floor support (reactive floor building).
     * Only returns true when actually falling into a deep gap.
     */
    private boolean needsFloorSupport() {
        if (entity.isOnGround()) {
            ticksInAir = 0;
            return false;
        }

        ticksInAir++;
        if (ticksInAir < TICKS_IN_AIR_THRESHOLD) {
            return false; // Brief air time is fine (jumping, stepping)
        }

        // Check for deep gap (2+ blocks of air below)
        BlockPos below = entity.getBlockPos().down();
        BlockPos twoBelow = below.down();
        return entity.getEntityWorld().getBlockState(below).isAir()
            && entity.getEntityWorld().getBlockState(twoBelow).isAir();
    }

    private void teleportToChest(BlockPos chestPos) {
        entity.teleportWithParticles(chestPos);
    }

    private BlockPos getNearestChest() {
        double dx1 = entity.getX() - (chestPos1.getX() + 0.5);
        double dy1 = entity.getY() - (chestPos1.getY() + 0.5);
        double dz1 = entity.getZ() - (chestPos1.getZ() + 0.5);
        double dist1 = dx1 * dx1 + dy1 * dy1 + dz1 * dz1;

        double dx2 = entity.getX() - (chestPos2.getX() + 0.5);
        double dy2 = entity.getY() - (chestPos2.getY() + 0.5);
        double dz2 = entity.getZ() - (chestPos2.getZ() + 0.5);
        double dist2 = dx2 * dx2 + dy2 * dy2 + dz2 * dz2;

        return dist1 <= dist2 ? chestPos1 : chestPos2;
    }

    private boolean isInventoryFull() {
        Inventory inventory = entity.getInventory();
        int emptySlots = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
            }
        }
        // Need at least 2 empty slots for mined items
        return emptySlots < 2;
    }

    /**
     * Check if inventory has no depositable items (ignoring tools and torches).
     * Used to skip returning to chest when there's nothing to dump.
     */
    private boolean isInventoryEmpty() {
        Inventory inventory = entity.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && !isToolOrTorch(stack)) {
                // Found a non-tool/torch item that can be deposited
                return false;
            }
        }
        return true; // Only empty slots or tools/torches
    }

    /**
     * Check if an item is a tool or torch that should never be deposited.
     */
    private boolean isToolOrTorch(ItemStack stack) {
        if (stack.isEmpty()) return false;

        var item = stack.getItem();

        // Check for torches
        if (item == Items.TORCH || item == Items.SOUL_TORCH) {
            return true;
        }

        // Check for tools by item ID pattern
        String itemId = Registries.ITEM.getId(item).toString();
        if (itemId.contains("_pickaxe") || itemId.contains("_shovel") ||
            itemId.contains("_axe") || itemId.contains("_hoe") || itemId.contains("_sword")) {
            return true;
        }

        return false;
    }

    private void depositInventoryToChest(BlockPos chestPos) {
        if (chestPos == null || entity.getEntityWorld().isClient()) return;

        var chestEntity = entity.getEntityWorld().getBlockEntity(chestPos);
        if (!(chestEntity instanceof Inventory chestInv)) return;

        Inventory inventory = entity.getInventory();
        int buildingBlocksKept = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            // Never deposit tools or torches
            if (isToolOrTorch(stack)) continue;

            String blockId = getBlockIdFromStack(stack);
            boolean isBuildingBlock = buildingBlockType != null && blockId != null &&
                blockId.equals(buildingBlockType);

            if (isBuildingBlock && buildingBlocksKept < 64) {
                int toKeep = Math.min(64 - buildingBlocksKept, stack.getCount());
                buildingBlocksKept += toKeep;
                if (stack.getCount() > toKeep) {
                    ItemStack toDeposit = stack.copy();
                    toDeposit.setCount(stack.getCount() - toKeep);
                    ItemStack remainder = transferToInventory(toDeposit, chestInv);
                    stack.setCount(toKeep + (remainder.isEmpty() ? 0 : remainder.getCount()));
                    inventory.setStack(i, stack);
                }
            } else if (!isBuildingBlock) {
                ItemStack remainder = transferToInventory(stack, chestInv);
                if (remainder.isEmpty()) {
                    inventory.setStack(i, ItemStack.EMPTY);
                } else {
                    inventory.setStack(i, remainder);
                }
            }
        }
    }

    private ItemStack transferToInventory(ItemStack stack, Inventory targetInv) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

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

        for (int i = 0; i < targetInv.size(); i++) {
            if (targetInv.getStack(i).isEmpty()) {
                targetInv.setStack(i, stack.copy());
                return ItemStack.EMPTY;
            }
        }

        return stack;
    }


    private void placeFloorBlocks() {
        if (entity.getEntityWorld().isClient()) return;

        BlockPos below = entity.getBlockPos().down();
        if (!entity.getEntityWorld().getBlockState(below).isAir()) return;

        Inventory inventory = entity.getInventory();
        if (buildingBlockType == null) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;

                var block = blockItem.getBlock();
                String blockId = Registries.BLOCK.getId(block).toString();

                if (!isGravityBlock(block)) {
                    buildingBlockType = blockId;
                    break;
                }
            }
            if (buildingBlockType == null) {
                entity.handleMissingBuildingBlock();
                return;
            }
        }

        if (buildingBlockType != null) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;

                String blockId = Registries.BLOCK.getId(blockItem.getBlock()).toString();
                if (blockId.equals(buildingBlockType)) {
                    BlockState state = blockItem.getBlock().getDefaultState();
                    entity.getEntityWorld().setBlockState(below, state);
                    entity.beginHandAnimation(isLeftHandActive(), below, null);
                    alternateHand();
                    stack.decrement(1);
                    return;
                }
            }
            entity.handleMissingBuildingBlock();
        }
    }


    /**
     * Get position using expanding square pattern from corner.
     * startPos is the corner (near chests), excavating away from them.
     *
     * For ring N, we mine an L-shaped edge that completes an (N+1)x(N+1) square:
     * - Right edge: column N, rows 0 to N-1 (N blocks)
     * - Bottom edge: row N, columns 0 to N (N+1 blocks)
     * Total: 2N+1 blocks
     *
     * @param ring Ring index (0 = corner only, 1 = 2x2 square, etc.)
     * @param progress Position within the ring's L-shape (0 to 2*ring)
     * @return Block position to excavate
     */
    private BlockPos getExpandingSquarePosition(int ring, int progress) {
        Direction primary = primaryExcavDir != null ? primaryExcavDir : Direction.SOUTH;
        Direction secondary = secondaryExcavDir != null ? secondaryExcavDir : Direction.EAST;

        int col, row;
        if (progress < ring) {
            // Right edge: column = ring, row = progress
            col = ring;
            row = progress;
        } else {
            // Bottom edge: row = ring, column = progress - ring
            col = progress - ring;
            row = ring;
        }

        return startPos
            .offset(primary, col)
            .offset(secondary, row);
    }

    private boolean shouldMineBlock(BlockPos pos) {
        BlockState state = entity.getEntityWorld().getBlockState(pos);
        if (state.isAir() || state.getHardness(entity.getEntityWorld(), pos) < 0) {
            return false;
        }

        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();

        // Never mine chests (used for storage)
        if (isChestBlock(blockId)) {
            return false;
        }

        // Never mine torches directly (lighting is important)
        if (isTorchBlock(blockId)) {
            return false;
        }

        // In infinite mode (depth == 0), gold blocks act as boundaries
        if (depth == 0 && isGoldBlock(blockId)) {
            return false;
        }

        // Check ore mining mode
        if (isOreBlock(blockId)) {
            switch (oreMiningMode) {
                case NEVER:
                    return false;
                case SILK_TOUCH_FORTUNE:
                    if (!hasValidEnchantedTool()) {
                        return false;
                    }
                    break;
                case ALWAYS:
                default:
                    break;
            }
        }

        return true;
    }

    /**
     * Check if a block is an ore block.
     */
    private boolean isOreBlock(String blockId) {
        return blockId.contains("_ore") || blockId.contains("ancient_debris") ||
               blockId.equals("minecraft:gilded_blackstone");
    }

    /**
     * Check if a block is a chest or storage container.
     */
    private boolean isChestBlock(String blockId) {
        return blockId.contains("chest") || blockId.contains("barrel") ||
               blockId.contains("shulker_box");
    }

    /**
     * Check if a block is a gold block (boundary marker).
     */
    private boolean isGoldBlock(String blockId) {
        return blockId.equals("minecraft:gold_block");
    }

    /**
     * Check if the golem has a tool with Silk Touch or Fortune 3+.
     */
    private boolean hasValidEnchantedTool() {
        Inventory inventory = entity.getInventory();
        var world = entity.getEntityWorld();
        if (world == null) return false;

        var registryManager = world.getRegistryManager();
        var enchantmentRegistry = registryManager.getOrThrow(RegistryKeys.ENCHANTMENT);

        // Get entries using identifier from registry key
        var silkTouchEntry = enchantmentRegistry.getEntry(Enchantments.SILK_TOUCH.getValue());
        var fortuneEntry = enchantmentRegistry.getEntry(Enchantments.FORTUNE.getValue());

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            // Check for Silk Touch
            if (silkTouchEntry.isPresent() && EnchantmentHelper.getLevel(silkTouchEntry.get(), stack) > 0) {
                return true;
            }

            // Check for Fortune 3+
            if (fortuneEntry.isPresent() && EnchantmentHelper.getLevel(fortuneEntry.get(), stack) >= 3) {
                return true;
            }
        }
        return false;
    }


    private boolean isGravityBlock(net.minecraft.block.Block block) {
        return block instanceof FallingBlock;
    }

    private String getBlockIdFromStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        return Registries.BLOCK.getId(blockItem.getBlock()).toString();
    }

    private ItemStack findBestTool(BlockState state) {
        ItemStack bestTool = ItemStack.EMPTY;
        float bestSpeed = 1.0f;

        Inventory inventory = entity.getInventory();
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

        Inventory inventory = entity.getInventory();
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

        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setStack(i, stack.copy());
                return;
            }
        }

        if (entity.getEntityWorld() instanceof ServerWorld sw) {
            entity.dropStack(sw, stack);
        }
    }
}
