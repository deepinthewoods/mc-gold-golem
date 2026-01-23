package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Utility class for reach-aware block placement.
 * Ensures the golem moves within reach of blocks before placing them,
 * handles deferred blocks, and teleportation as last resort.
 */
public class PlacementPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlacementPlanner.class);

    // Configuration
    private static final double MAX_REACH = 6.0;  // Extended reach for golem building
    private static final double PLANNING_REACH_BUFFER = 0.5;
    private static final int MAX_DEFER_ATTEMPTS = 3;
    private static final int STUCK_THRESHOLD_TICKS = 20;
    private static final int SUFFOCATION_TELEPORT_RADIUS = 6;
    private static final double MIN_MOVE_DIST_SQ = 0.0004;
    private static final int DEFERRED_RETRY_INTERVAL = 4;
    private static final int MAX_CANDIDATES_PER_TICK = 3;
    private static final int SKIP_RETRY_TICKS = 12;
    private static final int MAX_PATHFINDS_PER_TICK = 4;
    private static final int PATH_CACHE_TTL_TICKS = 60;
    private static final int MIN_NAV_FAILURES_FOR_TELEPORT = 2;
    private static final int PATH_FAILURE_WINDOW_TICKS = 30;
    private static final boolean DEBUG_COUNTERS = false;

    // Reference to golem
    private final GoldGolemEntity golem;

    // Block queues
    private final Deque<BlockPos> remainingBlocks = new ArrayDeque<>();
    private final Deque<DeferredBlock> deferredBlocks = new ArrayDeque<>();
    private final Map<BlockPos, Integer> deferAttempts = new HashMap<>();
    private final Map<BlockPos, Long> skipUntilTick = new HashMap<>();
    private final Map<BlockPos, PathCheck> pathCache = new HashMap<>();

    // Current state
    private BlockPos currentTarget = null;
    private BlockPos currentStandPos = null;
    private int stuckTicks = 0;
    private boolean navigatingToStandPos = false;
    private Vec3d lastNavPos = null;
    private int deferredRetryCountdown = 0;
    private BlockPos preselectedStandPos = null;
    private boolean selectionBlockedByBudget = false;
    private int navigationFailures = 0;
    private long lastPathFailureTick = Long.MIN_VALUE;
    private long lastPathBudgetTick = Long.MIN_VALUE;
    private int remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;

    private long debugLastTick = Long.MIN_VALUE;
    private int debugPathfindCalls = 0;
    private int debugCacheHits = 0;

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

    private static class PlacementSearchResult {
        final BlockPos standPosition;
        final boolean budgetLimited;
        final boolean hasValidStand;

        private PlacementSearchResult(BlockPos standPosition, boolean budgetLimited, boolean hasValidStand) {
            this.standPosition = standPosition;
            this.budgetLimited = budgetLimited;
            this.hasValidStand = hasValidStand;
        }
    }

    private static class PathCheck {
        final boolean canPath;
        final long expiresAt;

        private PathCheck(boolean canPath, long expiresAt) {
            this.canPath = canPath;
            this.expiresAt = expiresAt;
        }
    }

    private enum PathCheckStatus {
        PATHABLE,
        NOT_PATHABLE,
        UNKNOWN
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
        skipUntilTick.clear();
        pathCache.clear();
        currentTarget = null;
        currentStandPos = null;
        stuckTicks = 0;
        navigatingToStandPos = false;
        lastNavPos = null;
        deferredRetryCountdown = 0;
        preselectedStandPos = null;
        selectionBlockedByBudget = false;
        navigationFailures = 0;
        lastPathFailureTick = Long.MIN_VALUE;
        lastPathBudgetTick = Long.MIN_VALUE;
        remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;

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
        if (blocks.isEmpty()) {
            return;
        }

        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());
        Comparator<BlockPos> comparator = Comparator
                .comparingInt(BlockPos::getY)
                .thenComparingDouble(b -> golemPos.squaredDistanceTo(b.getX(), b.getY(), b.getZ()));

        List<BlockPos> existing = new ArrayList<>(remainingBlocks);
        List<BlockPos> incoming = new ArrayList<>(blocks);
        existing.sort(comparator);
        incoming.sort(comparator);

        remainingBlocks.clear();
        int i = 0;
        int j = 0;
        while (i < existing.size() && j < incoming.size()) {
            BlockPos a = existing.get(i);
            BlockPos b = incoming.get(j);
            if (comparator.compare(a, b) <= 0) {
                remainingBlocks.addLast(a);
                i++;
            } else {
                remainingBlocks.addLast(b);
                j++;
            }
        }
        while (i < existing.size()) {
            remainingBlocks.addLast(existing.get(i++));
        }
        while (j < incoming.size()) {
            remainingBlocks.addLast(incoming.get(j++));
        }
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
        if (DEBUG_COUNTERS) {
            long now = golem.getEntityWorld().getTime();
            if (now != debugLastTick) {
                debugLastTick = now;
                debugPathfindCalls = 0;
                debugCacheHits = 0;
            }
        }

        if (tryTeleportIfSuffocating()) {
            return TickResult.WORKING;
        }

        // Select next block if needed
        if (currentTarget == null) {
            currentTarget = selectNextBlock();
            if (currentTarget == null) {
                if (selectionBlockedByBudget || !remainingBlocks.isEmpty() || !deferredBlocks.isEmpty()) {
                    LOGGER.debug("No target selected, still working: budget={} remaining={} deferred={}",
                        selectionBlockedByBudget, remainingBlocks.size(), deferredBlocks.size());
                    return TickResult.WORKING;
                }
                LOGGER.info("All blocks placed, returning COMPLETED");
                return TickResult.COMPLETED;
            }

            // If already in reach, place without moving.
            Vec3d golemPos = new Vec3d(golem.getX(), golem.getEyeY(), golem.getZ());
            if (isWithinReach(golemPos, currentTarget, MAX_REACH)) {
                currentStandPos = golem.getBlockPos();
                navigatingToStandPos = false;
                stuckTicks = 0;
                lastNavPos = null;
                preselectedStandPos = null;
                navigationFailures = 0;
            } else {
                PlacementSearchResult placement;
                if (preselectedStandPos != null) {
                    placement = new PlacementSearchResult(preselectedStandPos, false, true);
                    preselectedStandPos = null;
                } else {
                    // Find where to stand to place this block
                    placement = findPlacementResult(currentTarget);
                }

                if (placement.standPosition == null) {
                    if (placement.budgetLimited) {
                        return TickResult.WORKING;
                    }
                    // Pathfinding failed - use aggressive fallback: find ANY position and teleport
                    BlockPos fallbackPos = findAnyStandPosition(currentTarget);
                    if (fallbackPos != null) {
                        LOGGER.info("Using fallback teleport: target={} fallback={}", currentTarget, fallbackPos);
                        teleportToStandPosition(fallbackPos);
                        currentStandPos = fallbackPos;
                        navigatingToStandPos = false;
                        stuckTicks = 0;
                        lastNavPos = null;
                        navigationFailures = 0;
                    } else {
                        // No valid position at all - force place from current position
                        LOGGER.info("No valid stand position, force placing: target={}", currentTarget);
                        currentStandPos = golem.getBlockPos();
                        navigatingToStandPos = false;
                        stuckTicks = 0;
                        lastNavPos = null;
                        navigationFailures = 0;
                    }
                } else {
                    // Found a valid pathable stand position
                    currentStandPos = placement.standPosition;
                    navigatingToStandPos = true;
                    stuckTicks = 0;
                    lastNavPos = null;
                    navigationFailures = 0;
                    LOGGER.info("Selected standPos={} for target={}", currentStandPos, currentTarget);
                }
            }
        }

        // Navigate to stand position
        if (navigatingToStandPos && currentStandPos != null) {
            double dx = golem.getX() - (currentStandPos.getX() + 0.5);
            double dy = golem.getY() - currentStandPos.getY();
            double dz = golem.getZ() - (currentStandPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            double distY = Math.abs(dy);

            // Check if we're close enough to place
            Vec3d golemPos = new Vec3d(golem.getX(), golem.getEyeY(), golem.getZ());
            boolean inReach = currentTarget != null && isWithinReach(golemPos, currentTarget, MAX_REACH);
            if (inReach) {
                navigatingToStandPos = false;
                stuckTicks = 0;
                golem.getNavigation().stop();
            } else {
                // Keep navigating
                boolean started = golem.getNavigation().startMovingTo(
                        currentStandPos.getX() + 0.5, currentStandPos.getY(), currentStandPos.getZ() + 0.5, 1.1);
                if (!started) {
                    // Navigation failed to start - teleport immediately
                    if (currentStandPos != null && !currentStandPos.equals(golem.getBlockPos())) {
                        LOGGER.info("Navigation failed, teleporting: standPos={} target={}",
                                currentStandPos, currentTarget);
                        teleportToStandPosition(currentStandPos);
                    }
                    navigatingToStandPos = false;
                    stuckTicks = 0;
                    lastNavPos = null;
                    return TickResult.WORKING;
                }
                navigationFailures = 0;

                // Check if stuck
                Vec3d now = new Vec3d(golem.getX(), golem.getY(), golem.getZ());
                double movedSq = lastNavPos == null ? Double.POSITIVE_INFINITY : now.squaredDistanceTo(lastNavPos);
                boolean idle = golem.getNavigation().isIdle();
                if ((!started || idle || movedSq < MIN_MOVE_DIST_SQ) && !inReach) {
                    stuckTicks++;
                    if (stuckTicks >= STUCK_THRESHOLD_TICKS) {
                        // Stuck - teleport immediately
                        LOGGER.info("Stuck, teleporting: standPos={} target={}", currentStandPos, currentTarget);
                        teleportToStandPosition(currentStandPos);
                        stuckTicks = 0;
                        navigatingToStandPos = false;
                        lastNavPos = null;
                    }
                } else {
                    stuckTicks = 0;
                }
                lastNavPos = now;

                return TickResult.WORKING;
            }
        }

        // We're at the stand position, place the block
        if (currentTarget != null) {
            Vec3d golemPos = new Vec3d(golem.getX(), golem.getEyeY(), golem.getZ());
            boolean inReach = isWithinReach(golemPos, currentTarget, MAX_REACH);

            if (!inReach) {
                // Not in reach - try to find a better position and teleport
                BlockPos betterPos = findAnyStandPosition(currentTarget);
                if (betterPos != null && !betterPos.equals(golem.getBlockPos())) {
                    LOGGER.info("Teleporting to better position: target={} pos={}", currentTarget, betterPos);
                    teleportToStandPosition(betterPos);
                    return TickResult.WORKING;
                }
                // No better position - force place anyway
                LOGGER.info("Force placing out of range: target={}", currentTarget);
            }

            // Place the block (even if slightly out of range)
            BlockPos nextTarget = peekNextTarget();
            LOGGER.info("Attempting to place block at target={} golemPos={} nextTarget={}",
                currentTarget, golem.getBlockPos(), nextTarget);
            boolean placed = blockPlacer.placeBlock(currentTarget, nextTarget);
            if (placed) {
                LOGGER.info("Successfully placed block at {} remaining={} deferred={}",
                    currentTarget, remainingBlocks.size(), deferredBlocks.size());
                remainingBlocks.remove(currentTarget);
                deferAttempts.remove(currentTarget);
                currentTarget = null;
                currentStandPos = null;
                navigationFailures = 0;
                return TickResult.PLACED_BLOCK;
            } else {
                // Couldn't place (missing inventory) - keep target and return IDLE to stop building
                // Golem will wait to be fed a nugget to restart
                LOGGER.info("Block placer rejected (missing inventory?), stopping: target={}", currentTarget);
                return TickResult.IDLE;
            }
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
        selectionBlockedByBudget = false;
        preselectedStandPos = null;
        long now = golem.getEntityWorld().getTime();
        pruneSkipMap(now);

        if (!deferredBlocks.isEmpty()) {
            if (remainingBlocks.isEmpty() || deferredRetryCountdown <= 0) {
                DeferredBlock deferred = deferredBlocks.pollFirst();
                if (deferred != null) {
                    deferredRetryCountdown = DEFERRED_RETRY_INTERVAL;
                    return deferred.pos;
                }
            } else {
                deferredRetryCountdown--;
            }
        }

        Vec3d golemEyePos = new Vec3d(golem.getX(), golem.getEyeY(), golem.getZ());

        // PHASE 1: Prioritize blocks within reach to avoid unnecessary teleporting
        // This ensures we place ALL reachable blocks before moving elsewhere
        BlockPos inReachBlock = findBlockWithinReach(golemEyePos, now);
        if (inReachBlock != null) {
            return inReachBlock;
        }

        // PHASE 2: No blocks in reach - re-sort by distance to current golem position
        // This ensures when we do teleport/pathfind, it's to the nearest cluster
        resortByDistanceToGolem();

        // PHASE 3: Select with pathfinding for blocks that require movement
        int attempts = 0;
        BlockPos fallback = null;
        while (attempts < MAX_CANDIDATES_PER_TICK && !remainingBlocks.isEmpty()) {
            BlockPos pos = remainingBlocks.pollFirst();
            if (pos == null) {
                break;
            }
            if (fallback == null) {
                fallback = pos;
            }
            Long skipUntil = skipUntilTick.get(pos);
            if (skipUntil != null && skipUntil > now) {
                remainingBlocks.addLast(pos);
                attempts++;
                continue;
            }

            // Double-check reach (golem might have moved slightly)
            if (isWithinReach(golemEyePos, pos, MAX_REACH)) {
                return pos;
            }

            PlacementSearchResult placement = findPlacementResult(pos);
            if (placement.budgetLimited && placement.standPosition == null) {
                selectionBlockedByBudget = true;
                // Defer to increment attempt count and try later (prevents getting stuck on one block)
                defer(pos);
                break;
            }
            if (placement.standPosition != null) {
                preselectedStandPos = placement.standPosition;
                return pos;
            }

            skipUntilTick.put(pos, now + SKIP_RETRY_TICKS);
            remainingBlocks.addLast(pos);
            attempts++;
        }

        if (!selectionBlockedByBudget && fallback != null) {
            remainingBlocks.remove(fallback);
            return fallback;
        }

        if (!remainingBlocks.isEmpty() || !deferredBlocks.isEmpty()) {
             LOGGER.warn("PlacementPlanner: yielded no target. Remaining={}, Deferred={}, BudgetLimited={}",
                 remainingBlocks.size(), deferredBlocks.size(), selectionBlockedByBudget);
        }

        return null;
    }

    /**
     * Find any block within reach of the golem, respecting skip timers.
     * Scans the queue and returns the first reachable block found.
     * @return A block position within reach, or null if none found
     */
    private BlockPos findBlockWithinReach(Vec3d golemEyePos, long now) {
        for (Iterator<BlockPos> it = remainingBlocks.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            Long skipUntil = skipUntilTick.get(pos);
            if (skipUntil != null && skipUntil > now) {
                continue;
            }
            if (isWithinReach(golemEyePos, pos, MAX_REACH)) {
                it.remove();
                return pos;
            }
        }
        return null;
    }

    /**
     * Re-sort remaining blocks by distance to golem's current position.
     * Uses Java's TimSort which is O(n) on nearly-sorted data.
     * Called when no blocks are within reach, before teleporting to nearest cluster.
     */
    private void resortByDistanceToGolem() {
        if (remainingBlocks.size() <= 1) return;

        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());
        List<BlockPos> blocks = new ArrayList<>(remainingBlocks);
        blocks.sort(Comparator.comparingDouble(b ->
            golemPos.squaredDistanceTo(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)));
        remainingBlocks.clear();
        remainingBlocks.addAll(blocks);

        LOGGER.debug("Re-sorted {} blocks by distance to golem at {}", blocks.size(), golem.getBlockPos());
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

    private boolean tryTeleportIfSuffocating() {
        var world = golem.getEntityWorld();
        if (world.isClient()) {
            return false;
        }
        if (!golem.isInsideWall() && world.isSpaceEmpty(golem)) {
            return false;
        }

        BlockPos safePos = null;
        if (currentStandPos != null && canStandAt(currentStandPos)) {
            safePos = currentStandPos;
        } else if (currentTarget != null) {
            PlacementSearchResult placement = findPlacementResult(currentTarget);
            if (placement.standPosition != null) {
                safePos = placement.standPosition;
            }
        }

        if (safePos == null) {
            safePos = findNearestSafeStandPosition(golem.getBlockPos(), SUFFOCATION_TELEPORT_RADIUS);
        }

        if (safePos == null) {
            return false;
        }

        teleportToStandPosition(safePos);
        navigatingToStandPos = false;
        stuckTicks = 0;
        currentStandPos = null;
        lastNavPos = null;
        if (currentTarget != null) {
            remainingBlocks.addFirst(currentTarget);
            currentTarget = null;
        }
        return true;
    }

    /**
     * Find a valid position to stand at to place the target block.
     */
    /**
     * Find a valid stand position, preferring pathable spots and early exit.
     * For tower building, prioritizes positions closer to the target's Y level (higher up).
     */
    private PlacementSearchResult findPlacementResult(BlockPos target) {
        int reach = (int) Math.ceil(MAX_REACH);
        Vec3d golemPos = new Vec3d(golem.getX(), golem.getY(), golem.getZ());
        int targetY = target.getY();

        // Collect all valid stand positions, then sort by preference
        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos standPos = target.add(dx, dy, dz);

                    // Skip positions that would place the block inside the golem
                    // (target at feet level or head level)
                    if (standPos.equals(target) || standPos.up().equals(target)) {
                        continue;
                    }

                    Vec3d standEye = new Vec3d(standPos.getX() + 0.5, standPos.getY() + golem.getEyeHeight(golem.getPose()), standPos.getZ() + 0.5);

                    if (isWithinReach(standEye, target, MAX_REACH) && canStandAt(standPos)) {
                        candidates.add(standPos);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            LOGGER.warn("No valid stand candidates found for target={}", target);
            return new PlacementSearchResult(null, false, false);
        }

        // Sort candidates: prefer positions at or below target Y (for tower building - ground is more reliable below)
        // then by Y distance, then by distance to golem
        candidates.sort((a, b) -> {
            // First, prefer positions at or below target Y (ground is guaranteed from previous layers)
            boolean aBelow = a.getY() <= targetY;
            boolean bBelow = b.getY() <= targetY;
            if (aBelow != bBelow) {
                return aBelow ? -1 : 1;  // Below/at target comes first
            }
            // Then prefer positions closer to target Y level
            int aYDist = Math.abs(a.getY() - targetY);
            int bYDist = Math.abs(b.getY() - targetY);
            if (aYDist != bYDist) {
                return Integer.compare(aYDist, bYDist);
            }
            // Then sort by distance to golem
            double distA = golemPos.squaredDistanceTo(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
            double distB = golemPos.squaredDistanceTo(b.getX() + 0.5, b.getY(), b.getZ() + 0.5);
            return Double.compare(distA, distB);
        });

        BlockPos fallback = candidates.get(0); // Best candidate (closest to target Y)
        boolean budgetLimited = false;
        int notPathableCount = 0;

        // Debug: log candidate selection
        if (candidates.size() <= 5) {
            LOGGER.info("Stand candidates for target={}: {}", target, candidates);
        } else {
            LOGGER.info("Stand candidates for target={}: top5={}, total={}", target,
                candidates.subList(0, 5), candidates.size());
        }

        // Check pathability for candidates (in sorted order)
        for (BlockPos standPos : candidates) {
            Vec3d standEye = new Vec3d(standPos.getX() + 0.5, standPos.getY() + golem.getEyeHeight(golem.getPose()), standPos.getZ() + 0.5);

            // Only consider pathing to spots that are comfortably within reach
            if (!isWithinReach(standEye, target, MAX_REACH - PLANNING_REACH_BUFFER)) {
                continue;
            }

            PathCheckStatus status = canPathTo(standPos);
            if (status == PathCheckStatus.PATHABLE) {
                return new PlacementSearchResult(standPos, false, true);
            }
            if (status == PathCheckStatus.UNKNOWN) {
                budgetLimited = true;
            } else if (status == PathCheckStatus.NOT_PATHABLE) {
                notPathableCount++;
            }
        }

        int attempts = deferAttempts.getOrDefault(target, 0);
        int golemY = golem.getBlockPos().getY();

        // For tower building: if we found positions close to target Y but can't path to them,
        // use the fallback (teleport) after just 1 defer attempt, not 2
        int fallbackYDist = Math.abs(fallback.getY() - targetY);
        boolean fallbackIsCloseToTarget = fallbackYDist <= 2;

        // Key insight: if the fallback is significantly ABOVE the golem, pathfinding will fail
        // because entities can't walk up without stairs/ladders. Teleport immediately.
        int fallbackAboveGolem = fallback.getY() - golemY;
        if (fallbackAboveGolem >= 1) {
            LOGGER.info("Fallback above golem, teleporting up: fallback={} golemY={} target={}",
                fallback, golemY, target);
            return new PlacementSearchResult(fallback, false, true);
        }

        // If target is above the golem, we're likely in tower mode - teleport immediately
        int targetAboveGolem = targetY - golemY;
        if (targetAboveGolem >= 2) {
            LOGGER.info("Target above golem (tower mode), using fallback: fallback={} golemY={} target={}",
                fallback, golemY, target);
            return new PlacementSearchResult(fallback, false, true);
        }

        if (attempts >= MAX_DEFER_ATTEMPTS - 1) {
            LOGGER.info("Using fallback after max attempts: fallback={} target={}", fallback, target);
            return new PlacementSearchResult(fallback, false, true);
        }

        // If we have a good fallback (close to target Y) and many positions weren't pathable,
        // use it sooner - this helps with tower building where golem needs to teleport up
        if (fallbackIsCloseToTarget && notPathableCount >= 3) {
            LOGGER.info("Using close fallback for tower: fallback={} target={} notPathable={}",
                fallback, target, notPathableCount);
            return new PlacementSearchResult(fallback, false, true);
        }

        // If we checked several positions and none were pathable, just use the fallback
        // This prevents getting stuck when pathfinding is unreliable
        if (notPathableCount >= 5) {
            LOGGER.info("Many unpathable positions, using fallback: fallback={} target={} notPathable={}",
                fallback, target, notPathableCount);
            return new PlacementSearchResult(fallback, false, true);
        }

        if (budgetLimited) {
            return new PlacementSearchResult(null, true, true);
        }

        // Final fallback: if we have valid candidates but couldn't path to any,
        // just return the best one and let the caller teleport
        LOGGER.info("No pathable positions found, using fallback anyway: fallback={} target={}",
            fallback, target);
        return new PlacementSearchResult(fallback, false, true);
    }

    private BlockPos findNearestSafeStandPosition(BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (!canStandAt(pos)) {
                        continue;
                    }
                    double dist = dx * dx + dy * dy + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }

        return best;
    }

    /**
     * Check if a position is within reach to place a block.
     */
    private boolean isWithinReach(Vec3d from, BlockPos target, double maxReach) {
        double dx = from.x - (target.getX() + 0.5);
        double dy = from.y - (target.getY() + 0.5);
        double dz = from.z - (target.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist <= maxReach;
    }

    /**
     * Check if the golem can stand at a position.
     */
    private boolean canStandAt(BlockPos pos) {
        var world = golem.getEntityWorld();

        // Check for solid ground below
        BlockPos groundPos = pos.down();
        BlockState groundState = world.getBlockState(groundPos);
        if (!groundState.isSolidBlock(world, groundPos) && !groundState.hasSolidTopSurface(world, groundPos, golem)) {
            return false;
        }

        // Check for air at feet
        BlockState feetState = world.getBlockState(pos);
        if (!feetState.isAir()) {
            return false;
        }

        // Check for air at head level only if golem is tall enough
        if (golem.getHeight() > 1.0) {
            BlockState headState = world.getBlockState(pos.up());
            if (!headState.isAir()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Find ANY valid position to stand/teleport to for placing the target block.
     * Tries ground positions first, then air positions (for placing while falling).
     * Returns null only if no empty space exists within reach at all.
     */
    private BlockPos findAnyStandPosition(BlockPos target) {
        int reach = (int) Math.ceil(MAX_REACH);
        var world = golem.getEntityWorld();

        BlockPos bestGround = null;
        double bestGroundDist = Double.MAX_VALUE;
        BlockPos bestAir = null;
        double bestAirDist = Double.MAX_VALUE;
        int targetY = target.getY();

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos pos = target.add(dx, dy, dz);

                    // Skip positions that would place the block inside the golem
                    if (pos.equals(target) || pos.up().equals(target)) {
                        continue;
                    }

                    Vec3d standEye = new Vec3d(pos.getX() + 0.5, pos.getY() + golem.getEyeHeight(golem.getPose()), pos.getZ() + 0.5);

                    if (!isWithinReach(standEye, target, MAX_REACH)) {
                        continue;
                    }

                    BlockState feetState = world.getBlockState(pos);
                    if (!feetState.isAir()) {
                        continue;
                    }

                    // Check head clearance
                    if (golem.getHeight() > 1.0) {
                        BlockState headState = world.getBlockState(pos.up());
                        if (!headState.isAir()) {
                            continue;
                        }
                    }

                    // Prefer positions at or below target Y (ground is more reliable), then closer to target Y
                    // Positions above target get a penalty since ground may not exist yet
                    int yDiff = pos.getY() - targetY;
                    double yPenalty = yDiff > 0 ? yDiff * 20 : Math.abs(yDiff) * 10;  // Above target = larger penalty
                    double dist = yPenalty + Math.abs(dx) + Math.abs(dz);

                    // Check if this is a ground position
                    if (canStandAt(pos)) {
                        if (dist < bestGroundDist) {
                            bestGroundDist = dist;
                            bestGround = pos;
                        }
                    } else {
                        // Air position (no solid ground) - golem will fall
                        if (dist < bestAirDist) {
                            bestAirDist = dist;
                            bestAir = pos;
                        }
                    }
                }
            }
        }

        // Prefer ground positions, fall back to air
        if (bestGround != null) {
            return bestGround;
        }
        return bestAir;
    }

    /**
     * Quick heuristic to check if we can path to a position.
     */
    private PathCheckStatus canPathTo(BlockPos pos) {
        // Check if destination is valid
        if (!canStandAt(pos)) {
            return PathCheckStatus.NOT_PATHABLE;
        }

        long now = golem.getEntityWorld().getTime();
        refreshPathBudget(now);

        PathCheck cached = pathCache.get(pos);
        if (cached != null && cached.expiresAt >= now) {
            if (DEBUG_COUNTERS) {
                debugCacheHits++;
            }
            return cached.canPath ? PathCheckStatus.PATHABLE : PathCheckStatus.NOT_PATHABLE;
        }

        if (hasDirectLine(pos)) {
            pathCache.put(pos, new PathCheck(true, now + PATH_CACHE_TTL_TICKS));
            return PathCheckStatus.PATHABLE;
        }

        if (remainingPathfindBudget <= 0) {
            return PathCheckStatus.UNKNOWN;
        }

        remainingPathfindBudget--;
        if (DEBUG_COUNTERS) {
            debugPathfindCalls++;
        }

        Path path = golem.getNavigation().findPathTo(pos, 0);
        boolean canPath = path != null && path.reachesTarget();
        if (!canPath) {
            lastPathFailureTick = now;
        }
        pathCache.put(pos, new PathCheck(canPath, now + PATH_CACHE_TTL_TICKS));
        return canPath ? PathCheckStatus.PATHABLE : PathCheckStatus.NOT_PATHABLE;
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
        skipUntilTick.clear();
        pathCache.clear();
        currentTarget = null;
        currentStandPos = null;
        stuckTicks = 0;
        navigatingToStandPos = false;
        lastNavPos = null;
        deferredRetryCountdown = 0;
        preselectedStandPos = null;
        selectionBlockedByBudget = false;
        navigationFailures = 0;
        lastPathFailureTick = Long.MIN_VALUE;
        lastPathBudgetTick = Long.MIN_VALUE;
        remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;
    }

    /**
     * Write state to NBT for persistence.
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
        nbt.putIntArray("RemainingBlocks", encodePositions(remainingBlocks));
        nbt.putIntArray("DeferredBlocks", encodeDeferredPositions());
        writeDeferAttempts(nbt);
    }

    /**
     * Read state from NBT.
     */
    public void readNbt(net.minecraft.nbt.NbtCompound nbt) {
        remainingBlocks.clear();
        deferredBlocks.clear();
        deferAttempts.clear();
        skipUntilTick.clear();
        pathCache.clear();

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
        deferredRetryCountdown = 0;
        preselectedStandPos = null;
        selectionBlockedByBudget = false;
        navigationFailures = 0;
        lastPathFailureTick = Long.MIN_VALUE;
        lastPathBudgetTick = Long.MIN_VALUE;
        remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;

        int[] remaining = nbt.getIntArray("RemainingBlocks").orElseGet(() -> new int[0]);
        int[] deferred = nbt.getIntArray("DeferredBlocks").orElseGet(() -> new int[0]);
        decodePositions(remaining, remainingBlocks);
        decodeDeferredPositions(deferred);
        readDeferAttempts(nbt);
        applyDeferredAttemptCounts();
        if (currentTarget != null) {
            remainingBlocks.remove(currentTarget);
        }
    }

    public void writeView(WriteView view) {
        if (currentTarget != null) {
            view.putInt("CurrentTargetX", currentTarget.getX());
            view.putInt("CurrentTargetY", currentTarget.getY());
            view.putInt("CurrentTargetZ", currentTarget.getZ());
        }
        if (currentStandPos != null) {
            view.putInt("CurrentStandX", currentStandPos.getX());
            view.putInt("CurrentStandY", currentStandPos.getY());
            view.putInt("CurrentStandZ", currentStandPos.getZ());
        }
        view.putBoolean("NavigatingToStandPos", navigatingToStandPos);
        view.putInt("StuckTicks", stuckTicks);
        view.putIntArray("RemainingBlocks", encodePositions(remainingBlocks));
        view.putIntArray("DeferredBlocks", encodeDeferredPositions());
        writeDeferAttempts(view);
    }

    public void readView(ReadView view) {
        remainingBlocks.clear();
        deferredBlocks.clear();
        deferAttempts.clear();
        skipUntilTick.clear();
        pathCache.clear();

        if (view.contains("CurrentTargetX")) {
            currentTarget = new BlockPos(
                    view.getInt("CurrentTargetX", 0),
                    view.getInt("CurrentTargetY", 0),
                    view.getInt("CurrentTargetZ", 0)
            );
        } else {
            currentTarget = null;
        }
        if (view.contains("CurrentStandX")) {
            currentStandPos = new BlockPos(
                    view.getInt("CurrentStandX", 0),
                    view.getInt("CurrentStandY", 0),
                    view.getInt("CurrentStandZ", 0)
            );
        } else {
            currentStandPos = null;
        }
        navigatingToStandPos = view.getBoolean("NavigatingToStandPos", false);
        stuckTicks = view.getInt("StuckTicks", 0);
        deferredRetryCountdown = 0;
        preselectedStandPos = null;
        selectionBlockedByBudget = false;
        navigationFailures = 0;
        lastPathFailureTick = Long.MIN_VALUE;
        lastPathBudgetTick = Long.MIN_VALUE;
        remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;

        int[] remaining = view.getOptionalIntArray("RemainingBlocks").orElseGet(() -> new int[0]);
        int[] deferred = view.getOptionalIntArray("DeferredBlocks").orElseGet(() -> new int[0]);
        decodePositions(remaining, remainingBlocks);
        decodeDeferredPositions(deferred);
        readDeferAttempts(view);
        applyDeferredAttemptCounts();
        if (currentTarget != null) {
            remainingBlocks.remove(currentTarget);
        }
    }

    private static int[] encodePositions(Collection<BlockPos> positions) {
        int[] data = new int[positions.size() * 3];
        int i = 0;
        for (BlockPos pos : positions) {
            data[i++] = pos.getX();
            data[i++] = pos.getY();
            data[i++] = pos.getZ();
        }
        return data;
    }

    private static void decodePositions(int[] data, Deque<BlockPos> out) {
        if (data == null || data.length < 3) {
            return;
        }
        for (int i = 0; i + 2 < data.length; i += 3) {
            out.addLast(new BlockPos(data[i], data[i + 1], data[i + 2]));
        }
    }

    private int[] encodeDeferredPositions() {
        int[] data = new int[deferredBlocks.size() * 3];
        int i = 0;
        for (DeferredBlock block : deferredBlocks) {
            data[i++] = block.pos.getX();
            data[i++] = block.pos.getY();
            data[i++] = block.pos.getZ();
        }
        return data;
    }

    private void decodeDeferredPositions(int[] data) {
        if (data == null || data.length < 3) {
            return;
        }
        for (int i = 0; i + 2 < data.length; i += 3) {
            deferredBlocks.addLast(new DeferredBlock(new BlockPos(data[i], data[i + 1], data[i + 2])));
        }
    }

    private void writeDeferAttempts(net.minecraft.nbt.NbtCompound nbt) {
        int size = deferAttempts.size();
        int[] posData = new int[size * 3];
        int[] counts = new int[size];
        int i = 0;
        for (var entry : deferAttempts.entrySet()) {
            BlockPos pos = entry.getKey();
            posData[i * 3] = pos.getX();
            posData[i * 3 + 1] = pos.getY();
            posData[i * 3 + 2] = pos.getZ();
            counts[i] = entry.getValue();
            i++;
        }
        nbt.putIntArray("DeferAttemptPos", posData);
        nbt.putIntArray("DeferAttemptCounts", counts);
    }

    private void writeDeferAttempts(WriteView view) {
        int size = deferAttempts.size();
        int[] posData = new int[size * 3];
        int[] counts = new int[size];
        int i = 0;
        for (var entry : deferAttempts.entrySet()) {
            BlockPos pos = entry.getKey();
            posData[i * 3] = pos.getX();
            posData[i * 3 + 1] = pos.getY();
            posData[i * 3 + 2] = pos.getZ();
            counts[i] = entry.getValue();
            i++;
        }
        view.putIntArray("DeferAttemptPos", posData);
        view.putIntArray("DeferAttemptCounts", counts);
    }

    private void readDeferAttempts(net.minecraft.nbt.NbtCompound nbt) {
        int[] posData = nbt.getIntArray("DeferAttemptPos").orElseGet(() -> new int[0]);
        int[] counts = nbt.getIntArray("DeferAttemptCounts").orElseGet(() -> new int[0]);
        loadDeferAttempts(posData, counts);
    }

    private void readDeferAttempts(ReadView view) {
        int[] posData = view.getOptionalIntArray("DeferAttemptPos").orElseGet(() -> new int[0]);
        int[] counts = view.getOptionalIntArray("DeferAttemptCounts").orElseGet(() -> new int[0]);
        loadDeferAttempts(posData, counts);
    }

    private void loadDeferAttempts(int[] posData, int[] counts) {
        if (posData == null || counts == null) {
            return;
        }
        int entries = Math.min(counts.length, posData.length / 3);
        for (int i = 0; i < entries; i++) {
            int idx = i * 3;
            BlockPos pos = new BlockPos(posData[idx], posData[idx + 1], posData[idx + 2]);
            deferAttempts.put(pos, counts[i]);
        }
    }

    private void applyDeferredAttemptCounts() {
        if (deferredBlocks.isEmpty() || deferAttempts.isEmpty()) {
            return;
        }
        for (DeferredBlock block : deferredBlocks) {
            Integer attempts = deferAttempts.get(block.pos);
            if (attempts != null) {
                block.attempts = attempts;
            }
        }
    }

    private void pruneSkipMap(long now) {
        if (skipUntilTick.isEmpty()) {
            return;
        }
        skipUntilTick.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private void refreshPathBudget(long now) {
        if (now != lastPathBudgetTick) {
            lastPathBudgetTick = now;
            remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;
            if (!pathCache.isEmpty()) {
                pathCache.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
            }
        }
    }

    private boolean hasDirectLine(BlockPos pos) {
        var world = golem.getEntityWorld();
        Vec3d start = new Vec3d(golem.getX(), golem.getEyeY(), golem.getZ());
        Vec3d end = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        HitResult hit = world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                golem
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean shouldTeleport() {
        long now = golem.getEntityWorld().getTime();
        if (navigationFailures < MIN_NAV_FAILURES_FOR_TELEPORT) {
            return false;
        }
        return now - lastPathFailureTick <= PATH_FAILURE_WINDOW_TICKS;
    }
}
