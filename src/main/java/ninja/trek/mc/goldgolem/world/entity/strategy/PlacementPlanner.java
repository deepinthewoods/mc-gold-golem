package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.*;

/**
 * Utility class for reach-aware block placement.
 * Ensures the golem moves within reach of blocks before placing them,
 * handles deferred blocks, and teleportation as last resort.
 */
public class PlacementPlanner {

    // Configuration
    private static final double MAX_REACH = 4.5;  // Similar to player reach
    private static final int MAX_DEFER_ATTEMPTS = 3;
    private static final int STUCK_THRESHOLD_TICKS = 20;

    // Reference to golem
    private final GoldGolemEntity golem;

    // Block queues
    private final Deque<BlockPos> remainingBlocks = new ArrayDeque<>();
    private final Deque<DeferredBlock> deferredBlocks = new ArrayDeque<>();
    private final Map<BlockPos, Integer> deferAttempts = new HashMap<>();

    // Current state
    private BlockPos currentTarget = null;
    private BlockPos currentStandPos = null;
    private int stuckTicks = 0;
    private boolean navigatingToStandPos = false;

    /**
     * Represents a block that was deferred because it couldn't be reached.
     */
    private static class DeferredBlock {
        final BlockPos pos;
        int attempts;

        DeferredBlock(BlockPos pos) {
            this.pos = pos;
            this.attempts = 1;
        }
    }

    /**
     * Result of finding a placement position.
     */
    public static class PlacementPosition {
        public final BlockPos standPosition;

        public PlacementPosition(BlockPos standPosition) {
            this.standPosition = standPosition;
        }
    }

    /**
     * Result of a tick operation.
     */
    public enum TickResult {
        WORKING,        // Still working on current block
        PLACED_BLOCK,   // Successfully placed a block this tick
        DEFERRED,       // Current block was deferred
        COMPLETED,      // All blocks placed
        IDLE            // Nothing to do
    }

    public PlacementPlanner(GoldGolemEntity golem) {
        this.golem = golem;
    }

    /**
     * Set the blocks to place. Clears any existing state.
     * Blocks are sorted by Y (bottom-up) for proper build order.
     */
    public void setBlocks(List<BlockPos> blocks) {
        remainingBlocks.clear();
        deferredBlocks.clear();
        deferAttempts.clear();
        currentTarget = null;
        currentStandPos = null;
        stuckTicks = 0;
        navigatingToStandPos = false;

        // Sort blocks by Y level (bottom to top), then by distance from golem
        List<BlockPos> sorted = new ArrayList<>(blocks);
        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());
        sorted.sort(Comparator
                .comparingInt(BlockPos::getY)
                .thenComparingDouble(b -> golemPos.squaredDistanceTo(b.getX(), b.getY(), b.getZ())));
        remainingBlocks.addAll(sorted);
    }

    /**
     * Add more blocks to place (appends to existing queue).
     */
    public void addBlocks(List<BlockPos> blocks) {
        // Sort new blocks by Y
        List<BlockPos> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingInt(BlockPos::getY));
        remainingBlocks.addAll(sorted);
    }

    /**
     * Check if all blocks have been placed.
     */
    public boolean isComplete() {
        return remainingBlocks.isEmpty() && deferredBlocks.isEmpty() && currentTarget == null;
    }

    /**
     * Get the current target block being worked on.
     */
    public BlockPos getCurrentTarget() {
        return currentTarget;
    }

    /**
     * Get count of remaining blocks.
     */
    public int getRemainingCount() {
        return remainingBlocks.size() + deferredBlocks.size() + (currentTarget != null ? 1 : 0);
    }

    /**
     * Main tick method. Call this every tick while building.
     * @param blockPlacer Callback to actually place the block (handles inventory, animation, etc.)
     * @return The result of this tick
     */
    public TickResult tick(BlockPlacer blockPlacer) {
        // Select next block if needed
        if (currentTarget == null) {
            currentTarget = selectNextBlock();
            if (currentTarget == null) {
                // Try deferred blocks
                DeferredBlock deferred = deferredBlocks.pollFirst();
                if (deferred != null) {
                    currentTarget = deferred.pos;
                }
            }
            if (currentTarget == null) {
                return TickResult.COMPLETED;
            }

            // Find where to stand to place this block
            PlacementPosition placement = findPlacementPosition(currentTarget);
            if (placement == null) {
                // Can't reach this block, defer it
                defer(currentTarget);
                currentTarget = null;
                return TickResult.DEFERRED;
            }

            currentStandPos = placement.standPosition;
            navigatingToStandPos = true;
            stuckTicks = 0;
        }

        // Navigate to stand position
        if (navigatingToStandPos && currentStandPos != null) {
            double dx = golem.getX() - (currentStandPos.getX() + 0.5);
            double dy = golem.getY() - currentStandPos.getY();
            double dz = golem.getZ() - (currentStandPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            double distY = Math.abs(dy);

            // Check if we're close enough to place
            if (distSq < 1.5 && distY < 2.0) {
                navigatingToStandPos = false;
                stuckTicks = 0;
            } else {
                // Keep navigating
                double ty = golem.computeGroundTargetY(new Vec3d(currentStandPos.getX() + 0.5, currentStandPos.getY(), currentStandPos.getZ() + 0.5));
                golem.getNavigation().startMovingTo(currentStandPos.getX() + 0.5, ty, currentStandPos.getZ() + 0.5, 1.1);

                // Check if stuck
                if (golem.getNavigation().isIdle() && distSq > 1.5) {
                    stuckTicks++;
                    if (stuckTicks >= STUCK_THRESHOLD_TICKS) {
                        // Try to teleport to stand position
                        teleportToStandPosition(currentStandPos);
                        stuckTicks = 0;
                        navigatingToStandPos = false;
                    }
                } else {
                    stuckTicks = 0;
                }

                return TickResult.WORKING;
            }
        }

        // We're at the stand position, place the block if within reach
        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());
        if (currentTarget != null && isWithinReach(golemPos, currentTarget)) {
            // Place the block
            BlockPos nextTarget = peekNextTarget();
            boolean placed = blockPlacer.placeBlock(currentTarget, nextTarget);
            if (placed) {
                remainingBlocks.remove(currentTarget);
                deferAttempts.remove(currentTarget);
                currentTarget = null;
                currentStandPos = null;
                return TickResult.PLACED_BLOCK;
            } else {
                // Couldn't place (maybe no inventory), defer
                defer(currentTarget);
                currentTarget = null;
                currentStandPos = null;
                return TickResult.DEFERRED;
            }
        } else if (currentTarget != null) {
            // Not within reach even though we thought we would be - defer
            defer(currentTarget);
            currentTarget = null;
            currentStandPos = null;
            return TickResult.DEFERRED;
        }

        return TickResult.IDLE;
    }

    /**
     * Callback interface for placing blocks.
     */
    @FunctionalInterface
    public interface BlockPlacer {
        /**
         * Place a block at the given position.
         * @param pos The position to place at
         * @param nextPos The next block position (for animation preview), may be null
         * @return true if the block was placed successfully
         */
        boolean placeBlock(BlockPos pos, BlockPos nextPos);
    }

    // ========== Private Methods ==========

    private BlockPos selectNextBlock() {
        // Find the best next block from remaining blocks
        // Prioritize: lower Y, then closer to golem, then more accessible
        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos pos : remainingBlocks) {
            // Skip if we can't possibly reach it (quick check)
            PlacementPosition placement = findPlacementPosition(pos);
            if (placement == null) continue;

            // Score: prioritize lower Y, then distance
            double yScore = pos.getY() * 1000;  // Y level is primary
            double distScore = golemPos.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ());
            double score = yScore + distScore;

            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }

        if (best != null) {
            remainingBlocks.remove(best);
        }
        return best;
    }

    private BlockPos peekNextTarget() {
        // Peek at what the next target will be (for animation)
        if (!remainingBlocks.isEmpty()) {
            return remainingBlocks.peek();
        }
        if (!deferredBlocks.isEmpty()) {
            return deferredBlocks.peek().pos;
        }
        return null;
    }

    private void defer(BlockPos pos) {
        int attempts = deferAttempts.merge(pos, 1, Integer::sum);
        if (attempts < MAX_DEFER_ATTEMPTS) {
            deferredBlocks.addLast(new DeferredBlock(pos));
        } else {
            // Block has been deferred too many times, force teleport next time
            // Re-add with high priority
            deferredBlocks.addFirst(new DeferredBlock(pos));
        }
    }

    /**
     * Find a valid position to stand at to place the target block.
     */
    private PlacementPosition findPlacementPosition(BlockPos target) {
        List<BlockPos> validPositions = getValidStandPositions(target);

        if (validPositions.isEmpty()) {
            // Check if we should teleport (block deferred many times)
            int attempts = deferAttempts.getOrDefault(target, 0);
            if (attempts >= MAX_DEFER_ATTEMPTS - 1) {
                // Find any valid stand position and prepare to teleport
                BlockPos teleportDest = findTeleportDestination(target);
                if (teleportDest != null) {
                    return new PlacementPosition(teleportDest);
                }
            }

            return null;
        }

        // Find the closest pathable position
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());

        for (BlockPos pos : validPositions) {
            double dist = golemPos.squaredDistanceTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

            // Check if we can path there (quick heuristic: no blocks in the way at ground level)
            if (canPathTo(pos)) {
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = pos;
                }
            }
        }

        if (closest != null) {
            return new PlacementPosition(closest);
        }

        // Return closest even if not pathable (will teleport if stuck)
        if (!validPositions.isEmpty()) {
            return new PlacementPosition(validPositions.get(0));
        }

        return null;
    }

    /**
     * Get all valid positions the golem could stand to place the target block.
     */
    private List<BlockPos> getValidStandPositions(BlockPos target) {
        List<BlockPos> positions = new ArrayList<>();
        int reach = (int) Math.ceil(MAX_REACH);

        for (int x = -reach; x <= reach; x++) {
            for (int y = -reach; y <= reach; y++) {
                for (int z = -reach; z <= reach; z++) {
                    BlockPos standPos = target.add(x, y, z);

                    if (isWithinReach(new Vec3d(standPos.getX() + 0.5, standPos.getY() + 1, standPos.getZ() + 0.5), target)
                            && canStandAt(standPos)) {
                        positions.add(standPos);
                    }
                }
            }
        }

        // Sort by distance to golem
        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());
        positions.sort(Comparator.comparingDouble(p ->
                golemPos.squaredDistanceTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5)));

        return positions;
    }

    /**
     * Check if a position is within reach to place a block.
     */
    private boolean isWithinReach(Vec3d from, BlockPos target) {
        double dx = from.x - (target.getX() + 0.5);
        double dy = from.y - (target.getY() + 0.5);
        double dz = from.z - (target.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist <= MAX_REACH;
    }

    /**
     * Check if the golem can stand at a position.
     */
    private boolean canStandAt(BlockPos pos) {
        var world = golem.getEntityWorld();

        // Check for solid ground below
        BlockState groundState = world.getBlockState(pos.down());
        if (!groundState.isSolidBlock(world, pos.down())) {
            return false;
        }

        // Check for air at feet and head level (golem is 2 blocks tall)
        BlockState feetState = world.getBlockState(pos);
        BlockState headState = world.getBlockState(pos.up());

        return feetState.isAir() && headState.isAir();
    }

    /**
     * Quick heuristic to check if we can path to a position.
     */
    private boolean canPathTo(BlockPos pos) {
        // For now, just check if the navigation can find a path
        // This is a simplified check - real pathfinding happens when we actually navigate
        var world = golem.getEntityWorld();

        // Check if destination is valid
        if (!canStandAt(pos)) {
            return false;
        }

        // Simple distance check - if very close, assume pathable
        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());
        double dist = golemPos.squaredDistanceTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (dist < 4) {
            return true;
        }

        // Check a rough path at ground level
        int steps = (int) Math.sqrt(dist);
        if (steps < 1) steps = 1;
        double dx = (pos.getX() + 0.5 - golem.getX()) / steps;
        double dz = (pos.getZ() + 0.5 - golem.getZ()) / steps;

        for (int i = 1; i < steps; i++) {
            int checkX = (int) Math.floor(golem.getX() + dx * i);
            int checkZ = (int) Math.floor(golem.getZ() + dz * i);
            int checkY = (int) golem.getY();

            // Check for blocking terrain
            BlockPos checkPos = new BlockPos(checkX, checkY, checkZ);
            BlockState state = world.getBlockState(checkPos);
            if (!state.isAir() && state.isSolidBlock(world, checkPos)) {
                // Check if we can go over it
                BlockState above1 = world.getBlockState(checkPos.up());
                BlockState above2 = world.getBlockState(checkPos.up(2));
                if (!above1.isAir() || !above2.isAir()) {
                    return false;  // Can't path through
                }
            }
        }

        return true;
    }

    /**
     * Find a position to teleport to for reaching a target.
     */
    private BlockPos findTeleportDestination(BlockPos target) {
        List<BlockPos> validPositions = getValidStandPositions(target);
        if (validPositions.isEmpty()) {
            return null;
        }
        // Return closest valid position
        return validPositions.get(0);
    }

    /**
     * Teleport the golem to a stand position.
     */
    private void teleportToStandPosition(BlockPos standPos) {
        if (golem.getEntityWorld() instanceof ServerWorld sw) {
            // Spawn particles at origin and destination
            sw.spawnParticles(ParticleTypes.PORTAL,
                    golem.getX(), golem.getY() + 0.5, golem.getZ(),
                    40, 0.5, 0.5, 0.5, 0.2);
            sw.spawnParticles(ParticleTypes.PORTAL,
                    standPos.getX() + 0.5, standPos.getY() + 0.5, standPos.getZ() + 0.5,
                    40, 0.5, 0.5, 0.5, 0.2);
        }

        golem.refreshPositionAndAngles(
                standPos.getX() + 0.5,
                standPos.getY(),
                standPos.getZ() + 0.5,
                golem.getYaw(),
                golem.getPitch()
        );
        golem.getNavigation().stop();
    }

    /**
     * Clear all state. Call when stopping or resetting.
     */
    public void clear() {
        remainingBlocks.clear();
        deferredBlocks.clear();
        deferAttempts.clear();
        currentTarget = null;
        currentStandPos = null;
        stuckTicks = 0;
        navigatingToStandPos = false;
    }

    /**
     * Write state to NBT for persistence.
     * Note: We don't persist block positions as they'll be regenerated from the layer.
     */
    public void writeNbt(net.minecraft.nbt.NbtCompound nbt) {
        // Save current target if any
        if (currentTarget != null) {
            nbt.putInt("CurrentTargetX", currentTarget.getX());
            nbt.putInt("CurrentTargetY", currentTarget.getY());
            nbt.putInt("CurrentTargetZ", currentTarget.getZ());
        }

        // Save current stand pos
        if (currentStandPos != null) {
            nbt.putInt("CurrentStandX", currentStandPos.getX());
            nbt.putInt("CurrentStandY", currentStandPos.getY());
            nbt.putInt("CurrentStandZ", currentStandPos.getZ());
        }

        nbt.putBoolean("NavigatingToStandPos", navigatingToStandPos);
        nbt.putInt("StuckTicks", stuckTicks);
    }

    /**
     * Read state from NBT.
     * Note: Block positions are regenerated, only navigation state is restored.
     */
    public void readNbt(net.minecraft.nbt.NbtCompound nbt) {
        // Note: remainingBlocks and deferredBlocks will be repopulated by the strategy

        // Load current target
        if (nbt.contains("CurrentTargetX")) {
            currentTarget = new BlockPos(
                    nbt.getInt("CurrentTargetX", 0),
                    nbt.getInt("CurrentTargetY", 0),
                    nbt.getInt("CurrentTargetZ", 0)
            );
        }

        // Load current stand pos
        if (nbt.contains("CurrentStandX")) {
            currentStandPos = new BlockPos(
                    nbt.getInt("CurrentStandX", 0),
                    nbt.getInt("CurrentStandY", 0),
                    nbt.getInt("CurrentStandZ", 0)
            );
        }

        navigatingToStandPos = nbt.getBoolean("NavigatingToStandPos", false);
        stuckTicks = nbt.getInt("StuckTicks", 0);
    }
}
