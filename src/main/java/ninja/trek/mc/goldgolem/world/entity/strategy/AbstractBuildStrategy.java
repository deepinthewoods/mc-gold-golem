package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Abstract base class for build strategies with shared navigation,
 * animation, and utility methods.
 */
public abstract class AbstractBuildStrategy implements BuildStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractBuildStrategy.class);
    private static final int TORCH_SCAN_RADIUS = 5; // Scan within placement range
    private static final Random random = new Random();

    protected GoldGolemEntity entity;
    protected int stuckTicks = 0;
    protected int placementTickCounter = 0;
    protected boolean leftHandActive = true;
    protected boolean waitingForResources = false;

    @Override
    public void setEntity(GoldGolemEntity entity) {
        this.entity = entity;
    }

    @Override
    public GoldGolemEntity getEntity() {
        return entity;
    }

    /**
     * Navigate to a target with stuck detection and teleport recovery.
     * @param golem The golem entity
     * @param target Target position
     * @param speed Movement speed
     * @param teleportThreshold Ticks before teleporting when stuck
     */
    protected void navigateToWithStuckDetection(GoldGolemEntity golem, Vec3d target,
                                                 double speed, int teleportThreshold) {
        double dx = golem.getX() - target.x;
        double dz = golem.getZ() - target.z;
        double distSq = dx * dx + dz * dz;

        golem.getNavigation().startMovingTo(target.x, target.y, target.z, speed);

        if (golem.getNavigation().isIdle() && distSq > 1.0) {
            stuckTicks++;
            if (stuckTicks >= teleportThreshold) {
                teleportWithParticles(golem, target);
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
    }

    /**
     * Teleport the golem to a target position with portal particle effects.
     * Adds a small Y offset (0.1) to prevent clipping into ground blocks.
     */
    protected void teleportWithParticles(GoldGolemEntity golem, Vec3d target) {
        LOGGER.info("Gold Golem stuck detected! Teleporting from {} to {}", golem.getBlockPos(), target);
        if (golem.getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.PORTAL,
                golem.getX(), golem.getY() + 0.5, golem.getZ(),
                40, 0.5, 0.5, 0.5, 0.2);
            sw.spawnParticles(ParticleTypes.PORTAL,
                target.x, target.y + 0.5, target.z,
                40, 0.5, 0.5, 0.5, 0.2);
        }
        // Add small Y offset (0.1) to ensure golem spawns clearly above the floor
        // and doesn't clip into the ground block causing brief suffocation
        golem.refreshPositionAndAngles(target.x, target.y + 0.1, target.z,
            golem.getYaw(), golem.getPitch());
        golem.setVelocity(0, 0, 0);  // Clear velocity to prevent unexpected movement
        golem.getNavigation().stop();
    }

    /**
     * Increment the placement tick counter (2-tick cycle).
     * @return true if a block should be placed this tick
     */
    protected boolean shouldPlaceThisTick() {
        placementTickCounter = (placementTickCounter + 1) % 2;
        return placementTickCounter == 0;
    }

    /**
     * Alternate between left and right hand for placement.
     */
    protected void alternateHand() {
        leftHandActive = !leftHandActive;
    }

    /**
     * @return true if the left hand is active for the next placement
     */
    protected boolean isLeftHandActive() {
        return leftHandActive;
    }

    @Override
    public boolean isWaitingForResources() {
        return waitingForResources;
    }

    @Override
    public void setWaitingForResources(boolean waiting) {
        waitingForResources = waiting;
    }

    /**
     * Compute ground-level Y for navigation target.
     * Searches downward from the given position to find solid ground.
     */
    protected double computeGroundTargetY(GoldGolemEntity golem, Vec3d pos) {
        BlockPos.Mutable mut = new BlockPos.Mutable((int) Math.floor(pos.x), (int) Math.floor(pos.y) + 2, (int) Math.floor(pos.z));
        for (int i = 0; i < 10; i++) {
            if (!golem.getEntityWorld().getBlockState(mut).isAir()) {
                return mut.getY() + 1;
            }
            mut.setY(mut.getY() - 1);
        }
        return pos.y;
    }

    /**
     * Sample a value from a gradient based on a deterministic position-based random.
     * @param gradient Array of 9 block IDs (some may be null)
     * @param window Window width in slot units
     * @param pos Block position for deterministic sampling
     * @return Selected block ID or null if no valid blocks
     */
    protected String sampleGradient(String[] gradient, float window, BlockPos pos) {
        return sampleGradient(gradient, window, 1, pos);
    }

    /**
     * Sample a value from a gradient using simplex noise with a specific scale.
     */
    protected String sampleGradient(String[] gradient, float window, int noiseScale, BlockPos pos) {
        // Count valid slots
        int validCount = 0;
        for (String s : gradient) {
            if (s != null && !s.isEmpty()) validCount++;
        }
        if (validCount == 0) return null;

        // Generate deterministic noise value based on position
        float t = entity != null
                ? (float) entity.sampleGradientNoise01(pos, noiseScale)
                : deterministic01(pos.getX(), pos.getY(), pos.getZ());

        // Map to gradient position with window
        float center = t * 9.0f;
        float halfWindow = window * 0.5f;
        float minPos = center - halfWindow;
        float maxPos = center + halfWindow;

        // Clamp and select from valid slots
        int minSlot = Math.max(0, (int) Math.floor(minPos));
        int maxSlot = Math.min(8, (int) Math.ceil(maxPos));

        // Find a valid slot in range
        for (int i = minSlot; i <= maxSlot; i++) {
            if (gradient[i] != null && !gradient[i].isEmpty()) {
                return gradient[i];
            }
        }

        // Fallback: find nearest valid slot
        for (int delta = 0; delta < 9; delta++) {
            int below = minSlot - delta;
            int above = maxSlot + delta;
            if (below >= 0 && gradient[below] != null && !gradient[below].isEmpty()) {
                return gradient[below];
            }
            if (above <= 8 && gradient[above] != null && !gradient[above].isEmpty()) {
                return gradient[above];
            }
        }
        return null;
    }

    /**
     * Generate a deterministic 0-1 value from block coordinates.
     * Uses a simple hash for reproducible randomness.
     */
    protected float deterministic01(int x, int y, int z) {
        int hash = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        return (float) ((hash & 0x7FFFFFFF) % 10000) / 10000.0f;
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        this.entity = golem;
        stuckTicks = 0;
        placementTickCounter = 0;
        waitingForResources = false;
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        stuckTicks = 0;
        placementTickCounter = 0;
        waitingForResources = false;
    }

    @Override
    public void stop(GoldGolemEntity golem) {
        cleanup(golem);
    }

    /**
     * Check if a block is a torch (wall torch or floor torch).
     * Used to avoid mining torches directly.
     */
    protected boolean isTorchBlock(String blockId) {
        return blockId.contains("torch");
    }

    /**
     * Try to place a torch in a dark area within range.
     * Scans one random block per tick and places a torch if:
     * - The golem has torches in inventory
     * - The position has light level 0
     * - The position is a valid floor (air above solid block)
     *
     * @return true if a torch was placed
     */
    protected boolean tryPlaceTorchInDarkArea() {
        if (entity == null || entity.getEntityWorld().isClient()) return false;

        // Check if we have torches
        Inventory inventory = entity.getInventory();
        int torchSlot = findTorchSlot(inventory);
        if (torchSlot == -1) return false;

        // Pick a random position within scan radius
        BlockPos center = entity.getBlockPos();
        int dx = random.nextInt(TORCH_SCAN_RADIUS * 2 + 1) - TORCH_SCAN_RADIUS;
        int dy = random.nextInt(5) - 2; // -2 to +2 vertical range
        int dz = random.nextInt(TORCH_SCAN_RADIUS * 2 + 1) - TORCH_SCAN_RADIUS;
        BlockPos checkPos = center.add(dx, dy, dz);

        // Check if this is a valid torch placement position
        if (!isValidTorchPlacement(checkPos)) return false;

        // Check light level (block light only, not sky light for underground mining)
        int lightLevel = entity.getEntityWorld().getLightLevel(LightType.BLOCK, checkPos);
        if (lightLevel > 0) return false;

        // Place the torch
        entity.getEntityWorld().setBlockState(checkPos, Blocks.TORCH.getDefaultState());

        // Consume torch from inventory
        ItemStack torchStack = inventory.getStack(torchSlot);
        torchStack.decrement(1);
        if (torchStack.isEmpty()) {
            inventory.setStack(torchSlot, ItemStack.EMPTY);
        }

        // Trigger hand animation
        entity.beginHandAnimation(isLeftHandActive(), checkPos, null);
        alternateHand();

        return true;
    }

    /**
     * Find a slot containing torches in the inventory.
     * @return slot index or -1 if no torches found
     */
    private int findTorchSlot(Inventory inventory) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.TORCH) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if a position is valid for torch placement.
     * Must be air with a solid block below.
     */
    private boolean isValidTorchPlacement(BlockPos pos) {
        var world = entity.getEntityWorld();

        // Must be air
        if (!world.getBlockState(pos).isAir()) return false;

        // Must have solid block below
        BlockPos below = pos.down();
        var belowState = world.getBlockState(below);
        return belowState.isSolidBlock(world, below);
    }
}
