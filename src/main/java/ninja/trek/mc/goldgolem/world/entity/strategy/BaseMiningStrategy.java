package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for mining-related strategies (Mining and Excavation).
 * Provides shared dual-hand mining mechanics, tool management, inventory operations,
 * and block classification utilities.
 */
public abstract class BaseMiningStrategy extends AbstractBuildStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseMiningStrategy.class);

    // Dual-hand mining state - each hand mines independently at 4x player time
    protected BlockPos leftTarget = null;
    protected BlockPos rightTarget = null;
    protected int leftBreakProgress = 0;
    protected int rightBreakProgress = 0;
    protected int leftSwingTick = 0;
    protected int rightSwingTick = 0;
    protected ItemStack leftTool = ItemStack.EMPTY;
    protected ItemStack rightTool = ItemStack.EMPTY;
    protected static final int MINING_SWING_INTERVAL = 5; // ticks between swings

    // Navigation obstacle detection for mining through walls / bridging gaps
    protected int navStuckTicks = 0;
    protected double prevNavX = Double.NaN, prevNavZ = Double.NaN;
    private static final int OBSTACLE_CHECK_DELAY = 15; // ticks before trying to clear path
    private static final double NAV_STUCK_MOVEMENT_SQ = 0.01;

    // Building block type for floor placement
    protected String buildingBlockType = null;

    // Tool cache for efficient inventory scanning
    protected final ToolCache toolCache = new ToolCache();
    protected int inventoryVersion = 0;

    /**
     * Record for holding a pair of tools for dual-hand mining.
     */
    public record ToolPair(ItemStack left, ItemStack right) {}

    // ==================== Lifecycle Methods ====================

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        resetMiningState();
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        // Clear breaking overlays for both hands before cleanup
        clearBreakingOverlays();
        super.cleanup(golem);
        resetMiningState();
    }

    /**
     * Reset all mining-related transient state.
     * Subclasses should call this when resetting to idle.
     */
    protected void resetMiningState() {
        clearBreakingOverlays();
        leftTarget = null;
        rightTarget = null;
        leftBreakProgress = 0;
        rightBreakProgress = 0;
        leftSwingTick = 0;
        rightSwingTick = 0;
        leftTool = ItemStack.EMPTY;
        rightTool = ItemStack.EMPTY;
        navStuckTicks = 0;
        prevNavX = Double.NaN;
        prevNavZ = Double.NaN;
        if (entity != null) {
            entity.setLeftMiningTool(ItemStack.EMPTY);
            entity.setRightMiningTool(ItemStack.EMPTY);
        }
    }

    /**
     * Clear the block breaking overlay effects for both hands.
     */
    protected void clearBreakingOverlays() {
        if (entity != null && entity.level() instanceof ServerLevel sw) {
            if (leftTarget != null) {
                sw.destroyBlockProgress(entity.getId(), leftTarget, -1);
            }
            if (rightTarget != null) {
                sw.destroyBlockProgress(entity.getId() + 1000, rightTarget, -1);
            }
        }
    }

    // ==================== Dual-Hand Mining ====================

    /**
     * Mine a block with a specific hand. Each hand mines independently at 4x player time.
     * @param pos The block position to mine
     * @param isLeftHand True for left hand, false for right hand
     * @return true when the block is fully broken
     */
    protected boolean mineBlockWithHand(BlockPos pos, boolean isLeftHand) {
        if (entity.level().isClientSide()) return false;

        BlockState state = entity.level().getBlockState(pos);
        if (state.isAir()) {
            onBlockAlreadyAir(pos, isLeftHand);
            if (isLeftHand) {
                leftTarget = null;
                leftBreakProgress = 0;
            } else {
                rightTarget = null;
                rightBreakProgress = 0;
            }
            return true;
        }

        // Use unique entity ID for break overlay (offset for right hand)
        int breakId = isLeftHand ? entity.getId() : entity.getId() + 1000;

        // Find tool for this hand (uses different tools for each hand)
        ItemStack tool = isLeftHand ? leftTool : rightTool;
        if (tool.isEmpty() || !tool.isCorrectToolForDrops(state)) {
            // Find appropriate tool for this hand
            ToolPair tools = findTwoTools(state);
            if (isLeftHand) {
                leftTool = tools.left();
                tool = leftTool;
            } else {
                rightTool = tools.right();
                tool = rightTool;
            }
        }

        float breakSpeed = tool.isEmpty() ? 1.0f : tool.getDestroySpeed(state);
        // 4x player time = 0.25f multiplier (each hand is 4x slower, but together they equal 2x)
        breakSpeed *= 0.25f;

        float hardness = state.getDestroySpeed(entity.level(), pos);
        if (hardness < 0) {
            // Unbreakable
            if (isLeftHand) {
                leftTarget = null;
                leftBreakProgress = 0;
            } else {
                rightTarget = null;
                rightBreakProgress = 0;
            }
            return true; // Skip this block
        }

        int requiredTicks = (int) Math.ceil((hardness * 30.0f) / breakSpeed);
        requiredTicks = Math.max(1, requiredTicks);

        // Increment progress for this hand
        int breakProgress;
        int swingTick;
        if (isLeftHand) {
            leftBreakProgress++;
            leftSwingTick++;
            breakProgress = leftBreakProgress;
            swingTick = leftSwingTick;
        } else {
            rightBreakProgress++;
            rightSwingTick++;
            breakProgress = rightBreakProgress;
            swingTick = rightSwingTick;
        }

        // Set the mining tool for display on this hand
        if (isLeftHand) {
            entity.setLeftMiningTool(tool);
        } else {
            entity.setRightMiningTool(tool);
        }

        // Update breaking overlay (stages 0-9)
        if (entity.level() instanceof ServerLevel sw) {
            int breakStage = (int) ((float) breakProgress / requiredTicks * 10.0f);
            breakStage = Math.min(9, Math.max(0, breakStage));
            sw.destroyBlockProgress(breakId, pos, breakStage);
        }

        // Trigger arm swing animation - each hand points at its own target
        if (swingTick >= MINING_SWING_INTERVAL) {
            if (isLeftHand) {
                leftSwingTick = 0;
            } else {
                rightSwingTick = 0;
            }
            entity.beginHandAnimation(isLeftHand, pos, null);

            // Spawn small block particles during mining
            if (entity.level() instanceof ServerLevel sw) {
                BlockParticleOption particleEffect = new BlockParticleOption(ParticleTypes.BLOCK, state);
                sw.sendParticles(particleEffect,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    3, 0.2, 0.2, 0.2, 0.05);
            }
        }

        if (breakProgress >= requiredTicks) {
            // Block is fully broken
            if (entity.level() instanceof ServerLevel sw) {
                var drops = net.minecraft.world.level.block.Block.getDrops(state, sw, pos,
                    entity.level().getBlockEntity(pos), entity, tool);

                for (ItemStack drop : drops) {
                    addToInventory(drop);
                }
            }

            entity.level().destroyBlock(pos, false);

            if (entity.level() instanceof ServerLevel sw) {
                // Clear breaking overlay
                sw.destroyBlockProgress(breakId, pos, -1);

                // Spawn burst of block-specific particles
                BlockParticleOption particleEffect = new BlockParticleOption(ParticleTypes.BLOCK, state);
                sw.sendParticles(particleEffect,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    30, 0.4, 0.4, 0.4, 0.15);
            }

            // Damage tool if applicable
            if (!tool.isEmpty() && tool.isDamageableItem()) {
                tool.hurtAndBreak(1, entity, EquipmentSlot.MAINHAND);
                Container inventory = entity.getInventory();
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    if (inventory.getItem(i) == tool) {
                        inventory.setItem(i, tool);
                        break;
                    }
                }
            }

            // Notify subclass and reset hand state
            onBlockBroken(pos, isLeftHand, state);

            if (isLeftHand) {
                leftTarget = null;
                leftBreakProgress = 0;
                leftSwingTick = 0;
                leftTool = ItemStack.EMPTY;
            } else {
                rightTarget = null;
                rightBreakProgress = 0;
                rightSwingTick = 0;
                rightTool = ItemStack.EMPTY;
            }
            return true; // Block broken
        }

        return false; // Still mining
    }

    /**
     * Called when a block is found to already be air during mining.
     * Subclasses can override to handle pending ore removal, etc.
     */
    protected void onBlockAlreadyAir(BlockPos pos, boolean isLeftHand) {
        // Default implementation does nothing
    }

    /**
     * Called when a block is successfully broken.
     * Subclasses can override to scan for ores, update progress, etc.
     */
    protected void onBlockBroken(BlockPos pos, boolean isLeftHand, BlockState brokenState) {
        // Default implementation does nothing
    }

    // ==================== Tool Management ====================

    /**
     * Called when the golem's inventory changes.
     * Invalidates the tool cache so it will be rebuilt on next access.
     */
    public void onInventoryChanged() {
        toolCache.invalidate();
        inventoryVersion++;
    }

    /**
     * Find two different tools for dual-hand mining.
     * Returns a ToolPair - tries to use different tool stacks for each hand.
     * Uses the tool cache for efficient inventory scanning.
     */
    protected ToolPair findTwoTools(BlockState state) {
        ItemStack firstTool = ItemStack.EMPTY;
        ItemStack secondTool = ItemStack.EMPTY;
        float firstSpeed = 1.0f;
        float secondSpeed = 1.0f;
        int firstSlot = -1;

        Container inventory = entity.getInventory();
        int[] cachedToolSlots = toolCache.getToolSlots(inventory, inventoryVersion);

        // First pass: find the best tool from cached tool slots
        for (int i : cachedToolSlots) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) continue;

            float speed = stack.getDestroySpeed(state);
            if (speed > firstSpeed) {
                firstSpeed = speed;
                firstTool = stack;
                firstSlot = i;
            }
        }

        // Second pass: find a different tool (different stack) for the other hand
        for (int i : cachedToolSlots) {
            if (i == firstSlot) continue; // Skip the first tool's slot

            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) continue;

            float speed = stack.getDestroySpeed(state);
            if (speed > secondSpeed) {
                secondSpeed = speed;
                secondTool = stack;
            }
        }

        return new ToolPair(firstTool, secondTool);
    }

    /**
     * Find the best single tool for mining a block state.
     * Uses the tool cache for efficient inventory scanning.
     */
    protected ItemStack findBestTool(BlockState state) {
        ItemStack bestTool = ItemStack.EMPTY;
        float bestSpeed = 1.0f;

        Container inventory = entity.getInventory();
        int[] cachedToolSlots = toolCache.getToolSlots(inventory, inventoryVersion);

        for (int i : cachedToolSlots) {
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

    // ==================== Inventory Operations ====================

    /**
     * Add an item stack to the golem's inventory.
     * Tries to stack with existing items first, then uses empty slots.
     * Drops items on the ground if inventory is full.
     */
    protected void addToInventory(ItemStack stack) {
        if (stack.isEmpty()) return;

        Container inventory = entity.getInventory();

        // First try to stack with existing items
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

        // Then try empty slots
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack.copy());
                return;
            }
        }

        // Inventory full - drop on ground
        if (entity.level() instanceof ServerLevel sw) {
            entity.spawnAtLocation(sw, stack);
        }
    }

    /**
     * Deposit inventory contents to a chest, keeping some building blocks.
     * @param chestPos Position of the chest to deposit into
     */
    protected void depositInventoryToChest(BlockPos chestPos) {
        if (chestPos == null || entity.level().isClientSide()) return;

        var chestEntity = entity.level().getBlockEntity(chestPos);
        if (!(chestEntity instanceof Container chestInv)) return;

        Container inventory = entity.getInventory();
        int buildingBlocksKept = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            // Let subclasses filter items (e.g., keep tools)
            if (shouldSkipDeposit(stack)) continue;

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
                    inventory.setItem(i, stack);
                }
            } else if (!isBuildingBlock) {
                ItemStack remainder = transferToInventory(stack, chestInv);
                if (remainder.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                } else {
                    inventory.setItem(i, remainder);
                }
            }
        }
    }

    /**
     * Check if an item should be skipped during deposit (e.g., tools, torches).
     * Subclasses can override to customize deposit behavior.
     */
    protected boolean shouldSkipDeposit(ItemStack stack) {
        return false;
    }

    /**
     * Transfer an item stack to a target inventory.
     * @return Remaining items that couldn't be transferred
     */
    protected ItemStack transferToInventory(ItemStack stack, Container targetInv) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

        // First try to stack with existing items
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

        // Then try empty slots
        for (int i = 0; i < targetInv.getContainerSize(); i++) {
            if (targetInv.getItem(i).isEmpty()) {
                targetInv.setItem(i, stack.copy());
                return ItemStack.EMPTY;
            }
        }

        return stack;
    }

    /**
     * Place building blocks under the golem's feet when standing over air.
     */
    protected void placeBlocksUnderFeet() {
        if (entity.level().isClientSide()) return;

        BlockPos below = entity.blockPosition().below();
        if (!entity.level().getBlockState(below).isAir()) return;

        Container inventory = entity.getInventory();
        if (buildingBlockType == null) {
            // Find a suitable building block
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;

                var block = blockItem.getBlock();
                String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();

                if (!isOreBlock(blockId) && !isGravityBlock(block)) {
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
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;

                String blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
                if (blockId.equals(buildingBlockType)) {
                    BlockState state = blockItem.getBlock().defaultBlockState();
                    entity.level().setBlockAndUpdate(below, state);
                    entity.beginHandAnimation(isLeftHandActive(), below, null);
                    alternateHand();
                    stack.shrink(1);
                    inventory.setItem(i, stack);
                    return;
                }
            }
            entity.handleMissingBuildingBlock();
        }
    }

    // ==================== Navigation Helpers ====================

    /**
     * Find a walkable position adjacent to the target block for navigation.
     * The golem can't pathfind into a solid block, so this finds the nearest
     * air position next to the target's XZ column where the golem can stand.
     *
     * Checks: target column itself first (in case lower blocks are already mined),
     * then the 4 cardinal neighbors. Picks the closest to the golem.
     *
     * @param target The solid block the golem wants to mine
     * @return A navigable BlockPos, or the golem's own position as fallback
     */
    protected BlockPos findNavPositionNear(BlockPos target) {
        int floorY = entity.blockPosition().getY();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        // Check the target's own XZ column at floor level first, then neighbors
        BlockPos[] candidates = new BlockPos[5];
        candidates[0] = new BlockPos(target.getX(), floorY, target.getZ());
        int i = 1;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            candidates[i++] = new BlockPos(
                target.getX() + dir.getStepX(), floorY, target.getZ() + dir.getStepZ());
        }

        for (BlockPos candidate : candidates) {
            // Feet and head must be passable (no collision), ground must be solid
            BlockState feetState = entity.level().getBlockState(candidate);
            BlockState headState = entity.level().getBlockState(candidate.above());
            BlockState groundState = entity.level().getBlockState(candidate.below());

            boolean feetPassable = feetState.getCollisionShape(entity.level(), candidate).isEmpty();
            boolean headPassable = headState.getCollisionShape(entity.level(), candidate.above()).isEmpty();
            boolean hasGround = !groundState.getCollisionShape(entity.level(), candidate.below()).isEmpty();

            if (feetPassable && headPassable && hasGround) {
                double dx = entity.getX() - (candidate.getX() + 0.5);
                double dz = entity.getZ() - (candidate.getZ() + 0.5);
                double distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = candidate;
                }
            }
        }

        return best != null ? best : entity.blockPosition();
    }

    // ==================== Navigation Obstacle Handling ====================

    /**
     * Check if the golem is stuck navigating and find blocking blocks to mine through.
     * Call each tick during active mining after navigation is set.
     * @param target The block the golem is trying to reach
     * @return BlockPos of an obstruction to mine, or null
     */
    protected BlockPos checkNavigationObstacle(BlockPos target) {
        if (target == null || entity == null) return null;

        double gx = entity.getX();
        double gz = entity.getZ();

        // Close to target = not stuck
        double dx = gx - (target.getX() + 0.5);
        double dz = gz - (target.getZ() + 0.5);
        if (dx * dx + dz * dz <= 4.0) {
            navStuckTicks = 0;
            prevNavX = gx;
            prevNavZ = gz;
            return null;
        }

        // Check per-tick movement
        if (!Double.isNaN(prevNavX)) {
            double movedSq = (gx - prevNavX) * (gx - prevNavX) + (gz - prevNavZ) * (gz - prevNavZ);
            if (movedSq < NAV_STUCK_MOVEMENT_SQ) {
                navStuckTicks++;
            } else {
                navStuckTicks = 0;
            }
        }
        prevNavX = gx;
        prevNavZ = gz;

        if (navStuckTicks < OBSTACLE_CHECK_DELAY) return null;

        // Stuck! Try to find a blocking block to mine
        BlockPos blocking = findBlockingBlock(target);
        if (blocking != null) return blocking;

        // No wall found - try building a bridge toward the target
        tryBuildBridgeToward(target);
        return null;
    }

    /**
     * Find the first solid block between the golem and target at walk height.
     * Uses Bresenham stepping in XZ, checking feet and head levels.
     */
    protected BlockPos findBlockingBlock(BlockPos target) {
        int gx = entity.blockPosition().getX();
        int gz = entity.blockPosition().getZ();
        int gy = entity.blockPosition().getY();
        int tx = target.getX();
        int tz = target.getZ();

        int ddx = Math.abs(tx - gx);
        int ddz = Math.abs(tz - gz);
        int sx = gx < tx ? 1 : (gx > tx ? -1 : 0);
        int sz = gz < tz ? 1 : (gz > tz ? -1 : 0);

        if (ddx == 0 && ddz == 0) return null; // Same column

        int err = ddx - ddz;
        int cx = gx, cz = gz;

        for (int step = 0; step < 8; step++) {
            int e2 = err * 2;
            if (e2 > -ddz) { err -= ddz; cx += sx; }
            if (e2 < ddx) { err += ddx; cz += sz; }

            // Check feet and head level (golem is 2 blocks tall)
            for (int dy = 0; dy < 2; dy++) {
                BlockPos checkPos = new BlockPos(cx, gy + dy, cz);
                BlockState state = entity.level().getBlockState(checkPos);
                if (!state.isAir() && state.getDestroySpeed(entity.level(), checkPos) >= 0) {
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!isChestBlock(blockId) && !isTorchBlock(blockId)) {
                        return checkPos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Build a bridge block toward the target when stuck over a gap.
     * Places a building block at the next step's floor level if it's air.
     */
    protected void tryBuildBridgeToward(BlockPos target) {
        if (entity.level().isClientSide()) return;

        int gx = entity.blockPosition().getX();
        int gz = entity.blockPosition().getZ();
        int gy = entity.blockPosition().getY();
        int tx = target.getX();
        int tz = target.getZ();

        int dx = tx - gx;
        int dz = tz - gz;
        if (dx == 0 && dz == 0) return;

        // Step toward target on dominant axis
        int sx, sz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            sx = dx > 0 ? 1 : -1;
            sz = 0;
        } else {
            sx = 0;
            sz = dz > 0 ? 1 : -1;
        }

        // Check if the next step has no floor but is passable at walk level
        BlockPos nextGround = new BlockPos(gx + sx, gy - 1, gz + sz);
        BlockPos nextFeet = new BlockPos(gx + sx, gy, gz + sz);
        if (entity.level().getBlockState(nextGround).isAir()
                && entity.level().getBlockState(nextFeet).isAir()) {
            placeBlockAt(nextGround);
        }
    }

    /**
     * Place a building block at a specific position from inventory.
     */
    protected void placeBlockAt(BlockPos pos) {
        if (entity.level().isClientSide()) return;
        if (!entity.level().getBlockState(pos).isAir()) return;

        Container inventory = entity.getInventory();
        if (buildingBlockType == null) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
                var block = blockItem.getBlock();
                String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
                if (!isOreBlock(blockId) && !isGravityBlock(block)) {
                    buildingBlockType = blockId;
                    break;
                }
            }
            if (buildingBlockType == null) return;
        }

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            String blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
            if (blockId.equals(buildingBlockType)) {
                BlockState state = blockItem.getBlock().defaultBlockState();
                entity.level().setBlockAndUpdate(pos, state);
                entity.beginHandAnimation(isLeftHandActive(), pos, null);
                alternateHand();
                stack.shrink(1);
                inventory.setItem(i, stack);
                return;
            }
        }
    }

    // ==================== Block Classification ====================

    /**
     * Check if a block ID represents an ore block.
     */
    protected boolean isOreBlock(String blockId) {
        return blockId.contains("_ore") || blockId.contains("ancient_debris") ||
               blockId.equals("minecraft:gilded_blackstone");
    }

    /**
     * Check if a block ID represents a chest or storage container.
     */
    protected boolean isChestBlock(String blockId) {
        return blockId.contains("chest") || blockId.contains("barrel") ||
               blockId.contains("shulker_box");
    }

    /**
     * Check if a block is affected by gravity (sand, gravel, etc.).
     */
    protected boolean isGravityBlock(net.minecraft.world.level.block.Block block) {
        return block instanceof FallingBlock;
    }

    /**
     * Get the block ID from an item stack (if it's a block item).
     */
    protected String getBlockIdFromStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
    }

    // ==================== NBT Helpers ====================

    /**
     * Write base mining state to NBT.
     * Subclasses should call this in their writeNbt method.
     */
    protected void writeBaseMiningNbt(CompoundTag nbt) {
        if (buildingBlockType != null) {
            nbt.putString("BuildingBlock", buildingBlockType);
        }
    }

    /**
     * Read base mining state from NBT.
     * Subclasses should call this in their readNbt method.
     */
    protected void readBaseMiningNbt(CompoundTag nbt) {
        buildingBlockType = nbt.contains("BuildingBlock") ? nbt.getStringOr("BuildingBlock", null) : null;
    }
}
