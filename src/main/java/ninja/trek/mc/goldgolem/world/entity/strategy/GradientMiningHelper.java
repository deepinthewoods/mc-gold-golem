package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
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
        if (entity.level().isClientSide()) return false;

        BlockState state = entity.level().getBlockState(target);
        if (state.isAir()) {
            reset(entity);
            return true;
        }

        int breakId = isLeftHand ? entity.getId() : entity.getId() + 1000;

        // Find best tool if needed
        if (tool.isEmpty() || !tool.isCorrectToolForDrops(state)) {
            tool = findBestTool(entity, state);
        }

        float breakSpeed = tool.isEmpty() ? 1.0f : tool.getDestroySpeed(state);
        breakSpeed *= 0.25f; // 4x player time

        float hardness = state.getDestroySpeed(entity.level(), target);
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
        if (entity.level() instanceof ServerLevel sw) {
            int breakStage = (int) ((float) breakProgress / requiredTicks * 10.0f);
            breakStage = Math.min(9, Math.max(0, breakStage));
            sw.destroyBlockProgress(breakId, target, breakStage);
        }

        // Arm swing animation + particles
        if (swingTick >= MINING_SWING_INTERVAL) {
            swingTick = 0;
            entity.beginHandAnimation(isLeftHand, target, null);

            if (entity.level() instanceof ServerLevel sw) {
                BlockParticleOption particleEffect = new BlockParticleOption(ParticleTypes.BLOCK, state);
                sw.sendParticles(particleEffect,
                        target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                        3, 0.2, 0.2, 0.2, 0.05);
            }
        }

        if (breakProgress >= requiredTicks) {
            // Block fully broken
            if (entity.level() instanceof ServerLevel sw) {
                var drops = net.minecraft.world.level.block.Block.getDrops(state, sw, target,
                        entity.level().getBlockEntity(target), entity, tool);

                for (ItemStack drop : drops) {
                    addToInventory(entity, drop);
                }

                // Clear breaking overlay
                sw.destroyBlockProgress(breakId, target, -1);

                // Burst of particles
                BlockParticleOption particleEffect = new BlockParticleOption(ParticleTypes.BLOCK, state);
                sw.sendParticles(particleEffect,
                        target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                        30, 0.4, 0.4, 0.4, 0.15);
            }

            entity.level().destroyBlock(target, false);

            // Damage tool
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

            reset(entity);
            return true;
        }

        return false;
    }

    /**
     * Reset mining state and clear overlays.
     */
    public void reset(GoldGolemEntity entity) {
        if (target != null && entity != null && entity.level() instanceof ServerLevel sw) {
            sw.destroyBlockProgress(entity.getId(), target, -1);
            sw.destroyBlockProgress(entity.getId() + 1000, target, -1);
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

    /**
     * Notify that the golem's inventory changed, so tool cache is refreshed.
     */
    public void onInventoryChanged() {
        toolCache.invalidate();
        inventoryVersion++;
    }

    private static void addToInventory(GoldGolemEntity entity, ItemStack stack) {
        if (stack.isEmpty()) return;

        Container inventory = entity.getInventory();

        // Try stacking with existing items
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

        // Try empty slots
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
}
