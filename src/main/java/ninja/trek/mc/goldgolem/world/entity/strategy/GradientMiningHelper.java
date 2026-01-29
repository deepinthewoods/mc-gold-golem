package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

/**
 * Single-hand mining helper for gradient-based build strategies.
 * When a gradient slot contains the mine marker, this helper handles
 * breaking the block over multiple ticks with tool selection, animations,
 * and particle effects.
 */
public class GradientMiningHelper {

    private static final int MINING_SWING_INTERVAL = 5;

    private BlockPos target;
    private int breakProgress;
    private int swingTick;
    private ItemStack tool = ItemStack.EMPTY;
    private final ToolCache toolCache = new ToolCache();
    private int inventoryVersion;

    /**
     * @return true if currently mining a block.
     */
    public boolean isMining() {
        return target != null;
    }

    /**
     * Begin mining the block at the given position.
     */
    public void startMining(BlockPos pos) {
        this.target = pos;
        this.breakProgress = 0;
        this.swingTick = 0;
        this.tool = ItemStack.EMPTY;
    }

    /**
     * Tick the mining process. Must be called every tick while isMining() is true.
     *
     * @param entity       The golem entity
     * @param isLeftHand   Which hand to animate
     * @return true when the block has been fully broken
     */
    public boolean tickMining(GoldGolemEntity entity, boolean isLeftHand) {
        if (target == null) return true;
        if (entity.getEntityWorld().isClient()) return false;

        BlockState state = entity.getEntityWorld().getBlockState(target);
        if (state.isAir()) {
            reset(entity);
            return true;
        }

        int breakId = isLeftHand ? entity.getId() : entity.getId() + 1000;

        // Find best tool if needed
        if (tool.isEmpty() || !tool.isSuitableFor(state)) {
            tool = findBestTool(entity, state);
        }

        float breakSpeed = tool.isEmpty() ? 1.0f : tool.getMiningSpeedMultiplier(state);
        breakSpeed *= 0.25f; // 4x player time

        float hardness = state.getHardness(entity.getEntityWorld(), target);
        if (hardness < 0) {
            // Unbreakable block
            reset(entity);
            return true;
        }

        int requiredTicks = Math.max(1, (int) Math.ceil((hardness * 30.0f) / breakSpeed));

        breakProgress++;
        swingTick++;

        // Set mining tool for display
        if (isLeftHand) {
            entity.setLeftMiningTool(tool);
        } else {
            entity.setRightMiningTool(tool);
        }

        // Update breaking overlay (stages 0-9)
        if (entity.getEntityWorld() instanceof ServerWorld sw) {
            int breakStage = (int) ((float) breakProgress / requiredTicks * 10.0f);
            breakStage = Math.min(9, Math.max(0, breakStage));
            sw.setBlockBreakingInfo(breakId, target, breakStage);
        }

        // Arm swing animation + particles
        if (swingTick >= MINING_SWING_INTERVAL) {
            swingTick = 0;
            entity.beginHandAnimation(isLeftHand, target, null);

            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                BlockStateParticleEffect particleEffect = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
                sw.spawnParticles(particleEffect,
                        target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                        3, 0.2, 0.2, 0.2, 0.05);
            }
        }

        if (breakProgress >= requiredTicks) {
            // Block fully broken
            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                var drops = net.minecraft.block.Block.getDroppedStacks(state, sw, target,
                        entity.getEntityWorld().getBlockEntity(target), entity, tool);

                for (ItemStack drop : drops) {
                    addToInventory(entity, drop);
                }

                // Clear breaking overlay
                sw.setBlockBreakingInfo(breakId, target, -1);

                // Burst of particles
                BlockStateParticleEffect particleEffect = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
                sw.spawnParticles(particleEffect,
                        target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                        30, 0.4, 0.4, 0.4, 0.15);
            }

            entity.getEntityWorld().breakBlock(target, false);

            // Damage tool
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

            reset(entity);
            return true;
        }

        return false;
    }

    /**
     * Reset mining state and clear overlays.
     */
    public void reset(GoldGolemEntity entity) {
        if (target != null && entity != null && entity.getEntityWorld() instanceof ServerWorld sw) {
            sw.setBlockBreakingInfo(entity.getId(), target, -1);
            sw.setBlockBreakingInfo(entity.getId() + 1000, target, -1);
        }
        target = null;
        breakProgress = 0;
        swingTick = 0;
        tool = ItemStack.EMPTY;
        if (entity != null) {
            entity.setLeftMiningTool(ItemStack.EMPTY);
            entity.setRightMiningTool(ItemStack.EMPTY);
        }
    }

    private ItemStack findBestTool(GoldGolemEntity entity, BlockState state) {
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

    /**
     * Notify that the golem's inventory changed, so tool cache is refreshed.
     */
    public void onInventoryChanged() {
        toolCache.invalidate();
        inventoryVersion++;
    }

    private static void addToInventory(GoldGolemEntity entity, ItemStack stack) {
        if (stack.isEmpty()) return;

        Inventory inventory = entity.getInventory();

        // Try stacking with existing items
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

        // Try empty slots
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
}
