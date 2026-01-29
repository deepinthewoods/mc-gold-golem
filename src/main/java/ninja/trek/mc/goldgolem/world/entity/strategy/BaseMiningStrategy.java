package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
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
        if (entity != null) {
            entity.setLeftMiningTool(ItemStack.EMPTY);
            entity.setRightMiningTool(ItemStack.EMPTY);
        }
    }

    /**
     * Clear the block breaking overlay effects for both hands.
     */
    protected void clearBreakingOverlays() {
        if (entity != null && entity.getEntityWorld() instanceof ServerWorld sw) {
            if (leftTarget != null) {
                sw.setBlockBreakingInfo(entity.getId(), leftTarget, -1);
            }
            if (rightTarget != null) {
                sw.setBlockBreakingInfo(entity.getId() + 1000, rightTarget, -1);
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
        if (entity.getEntityWorld().isClient()) return false;

        BlockState state = entity.getEntityWorld().getBlockState(pos);
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
        if (tool.isEmpty() || !tool.isSuitableFor(state)) {
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

        float breakSpeed = tool.isEmpty() ? 1.0f : tool.getMiningSpeedMultiplier(state);
        // 4x player time = 0.25f multiplier (each hand is 4x slower, but together they equal 2x)
        breakSpeed *= 0.25f;

        float hardness = state.getHardness(entity.getEntityWorld(), pos);
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
        if (entity.getEntityWorld() instanceof ServerWorld sw) {
            int breakStage = (int) ((float) breakProgress / requiredTicks * 10.0f);
            breakStage = Math.min(9, Math.max(0, breakStage));
            sw.setBlockBreakingInfo(breakId, pos, breakStage);
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
            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                BlockStateParticleEffect particleEffect = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
                sw.spawnParticles(particleEffect,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    3, 0.2, 0.2, 0.2, 0.05);
            }
        }

        if (breakProgress >= requiredTicks) {
            // Block is fully broken
            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                var drops = net.minecraft.block.Block.getDroppedStacks(state, sw, pos,
                    entity.getEntityWorld().getBlockEntity(pos), entity, tool);

                for (ItemStack drop : drops) {
                    addToInventory(drop);
                }
            }

            entity.getEntityWorld().breakBlock(pos, false);

            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                // Clear breaking overlay
                sw.setBlockBreakingInfo(breakId, pos, -1);

                // Spawn burst of block-specific particles
                BlockStateParticleEffect particleEffect = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
                sw.spawnParticles(particleEffect,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    30, 0.4, 0.4, 0.4, 0.15);
            }

            // Damage tool if applicable
            if (!tool.isEmpty() && tool.isDamageable()) {
                tool.damage(1, entity, EquipmentSlot.MAINHAND);
                Inventory inventory = entity.getInventory();
                for (int i = 0; i < inventory.size(); i++) {
                    if (inventory.getStack(i) == tool) {
                        inventory.setStack(i, tool);
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

        Inventory inventory = entity.getInventory();
        int[] cachedToolSlots = toolCache.getToolSlots(inventory, inventoryVersion);

        // First pass: find the best tool from cached tool slots
        for (int i : cachedToolSlots) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !stack.isSuitableFor(state)) continue;

            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > firstSpeed) {
                firstSpeed = speed;
                firstTool = stack;
                firstSlot = i;
            }
        }

        // Second pass: find a different tool (different stack) for the other hand
        for (int i : cachedToolSlots) {
            if (i == firstSlot) continue; // Skip the first tool's slot

            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !stack.isSuitableFor(state)) continue;

            float speed = stack.getMiningSpeedMultiplier(state);
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

        Inventory inventory = entity.getInventory();
        int[] cachedToolSlots = toolCache.getToolSlots(inventory, inventoryVersion);

        for (int i : cachedToolSlots) {
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

    // ==================== Inventory Operations ====================

    /**
     * Add an item stack to the golem's inventory.
     * Tries to stack with existing items first, then uses empty slots.
     * Drops items on the ground if inventory is full.
     */
    protected void addToInventory(ItemStack stack) {
        if (stack.isEmpty()) return;

        Inventory inventory = entity.getInventory();

        // First try to stack with existing items
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

        // Then try empty slots
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setStack(i, stack.copy());
                return;
            }
        }

        // Inventory full - drop on ground
        if (entity.getEntityWorld() instanceof ServerWorld sw) {
            entity.dropStack(sw, stack);
        }
    }

    /**
     * Deposit inventory contents to a chest, keeping some building blocks.
     * @param chestPos Position of the chest to deposit into
     */
    protected void depositInventoryToChest(BlockPos chestPos) {
        if (chestPos == null || entity.getEntityWorld().isClient()) return;

        var chestEntity = entity.getEntityWorld().getBlockEntity(chestPos);
        if (!(chestEntity instanceof Inventory chestInv)) return;

        Inventory inventory = entity.getInventory();
        int buildingBlocksKept = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
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
    protected ItemStack transferToInventory(ItemStack stack, Inventory targetInv) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

        // First try to stack with existing items
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

        // Then try empty slots
        for (int i = 0; i < targetInv.size(); i++) {
            if (targetInv.getStack(i).isEmpty()) {
                targetInv.setStack(i, stack.copy());
                return ItemStack.EMPTY;
            }
        }

        return stack;
    }

    /**
     * Place building blocks under the golem's feet when standing over air.
     */
    protected void placeBlocksUnderFeet() {
        if (entity.getEntityWorld().isClient()) return;

        BlockPos below = entity.getBlockPos().down();
        if (!entity.getEntityWorld().getBlockState(below).isAir()) return;

        Inventory inventory = entity.getInventory();
        if (buildingBlockType == null) {
            // Find a suitable building block
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;

                var block = blockItem.getBlock();
                String blockId = Registries.BLOCK.getId(block).toString();

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
                    inventory.setStack(i, stack);
                    return;
                }
            }
            entity.handleMissingBuildingBlock();
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
    protected boolean isGravityBlock(net.minecraft.block.Block block) {
        return block instanceof FallingBlock;
    }

    /**
     * Get the block ID from an item stack (if it's a block item).
     */
    protected String getBlockIdFromStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        return Registries.BLOCK.getId(blockItem.getBlock()).toString();
    }

    // ==================== NBT Helpers ====================

    /**
     * Write base mining state to NBT.
     * Subclasses should call this in their writeNbt method.
     */
    protected void writeBaseMiningNbt(NbtCompound nbt) {
        if (buildingBlockType != null) {
            nbt.putString("BuildingBlock", buildingBlockType);
        }
    }

    /**
     * Read base mining state from NBT.
     * Subclasses should call this in their readNbt method.
     */
    protected void readBaseMiningNbt(NbtCompound nbt) {
        buildingBlockType = nbt.contains("BuildingBlock") ? nbt.getString("BuildingBlock", null) : null;
    }
}
