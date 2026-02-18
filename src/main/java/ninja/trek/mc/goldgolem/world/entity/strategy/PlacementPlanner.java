package ninja.trek.mc.goldgolem.world.entity.strategy;

import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Utility class for reach-aware block placement.
 * Ensures the golem moves within reach of blocks before placing them,
 * handles deferred blocks, and teleportation as last resort.
 */
public class PlacementPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlacementPlanner.class);

    // Configuration
    private static final double MAX_REACH = 6.0;  // Extended reach for golem building
    private static final double MAX_VERTICAL_REACH = 3.0;  // Vertical reach limit
    private static final double PLANNING_REACH_BUFFER = 0.5;
    private static final int MAX_DEFER_ATTEMPTS = 3;
    private static final int STUCK_THRESHOLD_TICKS = 60;  // Try pathfinding longer before giving up
    private static final int SUFFOCATION_TELEPORT_RADIUS = 6;
    private static final double MIN_MOVE_DIST_SQ = 0.0004;
    private static final int DEFERRED_RETRY_INTERVAL = 4;
    private static final int MAX_CANDIDATES_PER_TICK = 3;
    private static final int SKIP_RETRY_TICKS = 12;
    private static final int NEIGHBOR_CANDIDATE_COUNT = 8;  // Score top-N nearest candidates by neighbor count
    private static final int MAX_PATHFINDS_PER_TICK = 4;
    private static final int PATH_CACHE_TTL_TICKS = 60;
    private static final int MIN_NAV_FAILURES_FOR_TELEPORT = 2;
    private static final int PATH_FAILURE_WINDOW_TICKS = 20;
    private static final boolean DEBUG_COUNTERS = false;
    private static final int MAX_CONSECUTIVE_OVERLAP_DEFERRALS = 6;  // Teleport if we defer this many blocks in a row due to overlap
    private static final int NO_PROGRESS_TELEPORT_TICKS = 30;  // Teleport if no progress toward stand pos in this many ticks
    private static final int PLACEMENT_TIMEOUT_TICKS = 40;  // Reposition if no block placed in this many ticks (2 seconds)

    // Callback interfaces for organic placement
    @FunctionalInterface
    public interface BlockFilter {
        /** Return true if this block should be SKIPPED (excluded) right now. */
        boolean shouldExclude(BlockPos pos);
    }

    @FunctionalInterface
    public interface BlockScorer {
        /** Return a score for this block — higher = place sooner. */
        int score(BlockPos pos);
    }

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
    private Vec3 lastNavPos = null;
    private int deferredRetryCountdown = 0;
    private BlockPos preselectedStandPos = null;
    private boolean selectionBlockedByBudget = false;
    private int navigationFailures = 0;
    private long lastPathFailureTick = Long.MIN_VALUE;
    private long lastPathBudgetTick = Long.MIN_VALUE;
    private int remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;
    private int consecutiveOverlapDeferrals = 0;  // Track when golem is trapped by its own builds
    private int allFilteredTicks = 0;  // Track consecutive ticks where block filter excluded all candidates
    private static final int ALL_FILTERED_TELEPORT_THRESHOLD = 4;  // Bypass filter after this many consecutive all-filtered ticks
    private BlockPos wanderTarget = null;  // Random position to pathfind to when stuck due to overlap
    private int wanderTicks = 0;
    private static final int MAX_WANDER_TICKS = 30;  // Teleport after this many ticks if pathfinding fails (1.5 seconds)
    private double navBestDistSq = Double.MAX_VALUE;  // Best distance to standPos during current navigation
    private int noProgressTicks = 0;  // Ticks without meaningful progress toward standPos
    private int ticksSinceLastPlacement = 0;  // Ticks since last successful block placement

    // Optional filter and scorer for organic placement
    private BlockFilter blockFilter = null;
    private BlockScorer blockScorer = null;

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

    public void setBlockFilter(BlockFilter filter) { this.blockFilter = filter; }
    public void setBlockScorer(BlockScorer scorer) { this.blockScorer = scorer; }

    /**
     * Return the effective golem position for filter evaluation.
     * Uses the intended stand position or wander target when the golem is
     * moving, so the pyramid exclusion zone is based on where the golem
     * is heading rather than where it currently stands.
     */
    public BlockPos getFilterPosition() {
        if (navigatingToStandPos && currentStandPos != null) {
            return currentStandPos;
        }
        if (wanderTarget != null) {
            return wanderTarget;
        }
        return golem.blockPosition();
    }

    /**
     * Return the lowest Y among remaining + deferred + currentTarget.
     * Used by strategies to know when to feed the next layer.
     */
    public int getLowestPendingY() {
        int minY = Integer.MAX_VALUE;
        if (currentTarget != null) {
            minY = Math.min(minY, currentTarget.getY());
        }
        for (BlockPos pos : remainingBlocks) {
            minY = Math.min(minY, pos.getY());
        }
        for (DeferredBlock db : deferredBlocks) {
            minY = Math.min(minY, db.pos.getY());
        }
        return minY;
    }

    /**
     * Return true if any remaining/deferred/currentTarget blocks exist at this Y level.
     * Used by strategies to detect when the lowest layer is fully placed.
     */
    public boolean hasBlocksAtY(int y) {
        if (currentTarget != null && currentTarget.getY() == y) return true;
        for (BlockPos pos : remainingBlocks) {
            if (pos.getY() == y) return true;
        }
        for (DeferredBlock db : deferredBlocks) {
            if (db.pos.getY() == y) return true;
        }
        return false;
    }

    /**
     * Set the blocks to place. Clears any existing state.
     * Blocks are sorted by Y (bottom-up) for proper build order.
     */
    public void setBlocks(List<BlockPos> blocks) {
        setBlocks(blocks, null);
    }

    /**
     * Set the blocks to place with a checker to skip already-correct blocks.
     * Clears any existing state. Blocks are sorted by Y (bottom-up) for proper build order.
     * @param blocks The blocks to place
     * @param checker Optional checker to skip blocks that are already correctly placed
     */
    public void setBlocks(List<BlockPos> blocks, BlockChecker checker) {
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
        consecutiveOverlapDeferrals = 0;
        allFilteredTicks = 0;
        wanderTarget = null;
        wanderTicks = 0;
        navBestDistSq = Double.MAX_VALUE;
        noProgressTicks = 0;
        ticksSinceLastPlacement = 0;

        // Filter out blocks that are already correctly placed
        List<BlockPos> toPlace = blocks;
        if (checker != null) {
            toPlace = new ArrayList<>();
            int skipped = 0;
            for (BlockPos pos : blocks) {
                if (checker.isAlreadyCorrect(pos)) {
                    skipped++;
                } else {
                    toPlace.add(pos);
                }
            }
            if (skipped > 0) {
                LOGGER.debug("Skipped {} already-correct blocks, {} remaining to place", skipped, toPlace.size());
            }
        }

        // Sort blocks by Y level (bottom to top), then by distance from golem
        List<BlockPos> sorted = new ArrayList<>(toPlace);
        Vec3 golemPos = new Vec3(golem.getX(), golem.getY(), golem.getZ());
        sorted.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(b -> golemPos.distanceToSqr(b.getX(), b.getY(), b.getZ())));
        remainingBlocks.addAll(sorted);
    }

    /**
     * Add more blocks to place (appends to existing queue).
     */
    public void addBlocks(List<BlockPos> blocks) {
        addBlocks(blocks, null);
    }

    /**
     * Add more blocks to place with a checker to skip already-correct blocks.
     * @param blocks The blocks to add
     * @param checker Optional checker to skip blocks that are already correctly placed
     */
    public void addBlocks(List<BlockPos> blocks, BlockChecker checker) {
        if (blocks.isEmpty()) {
            return;
        }

        // Filter out blocks that are already correctly placed
        List<BlockPos> toAdd = blocks;
        if (checker != null) {
            toAdd = new ArrayList<>();
            int skipped = 0;
            for (BlockPos pos : blocks) {
                if (checker.isAlreadyCorrect(pos)) {
                    skipped++;
                } else {
                    toAdd.add(pos);
                }
            }
            if (skipped > 0) {
                LOGGER.debug("Skipped {} already-correct blocks when adding, {} remaining to add", skipped, toAdd.size());
            }
        }

        if (toAdd.isEmpty()) {
            return;
        }

        Vec3 golemPos = new Vec3(golem.getX(), golem.getY(), golem.getZ());
        Comparator<BlockPos> comparator = Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(b -> golemPos.distanceToSqr(b.getX(), b.getY(), b.getZ()));

        List<BlockPos> existing = new ArrayList<>(remainingBlocks);
        List<BlockPos> incoming = new ArrayList<>(toAdd);
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
     * Mark a block as completed and remove it from all queues.
     * Used when a block is handled outside the normal placement flow (e.g., mining).
     */
    public void markBlockDone(BlockPos pos) {
        remainingBlocks.remove(pos);
        deferredBlocks.removeIf(db -> db.pos.equals(pos));
        deferAttempts.remove(pos);
        skipUntilTick.remove(pos);
        if (pos.equals(currentTarget)) {
            currentTarget = null;
            currentStandPos = null;
        }
    }

    /**
     * Main tick method. Call this every tick while building.
     * @param blockPlacer Callback to actually place the block (handles inventory, animation, etc.)
     * @return The result of this tick
     */
    public TickResult tick(BlockPlacer blockPlacer) {
        return tick(blockPlacer, true);
    }

    /**
     * Main tick method with placement gating.
     * Navigation and target selection always run. Block placement only happens when placingAllowed is true.
     * @param blockPlacer Callback to actually place the block
     * @param placingAllowed If false, navigation and target selection run but no blocks are placed
     * @return The result of this tick
     */
    public TickResult tick(BlockPlacer blockPlacer, boolean placingAllowed) {
        if (DEBUG_COUNTERS) {
            long now = golem.level().getGameTime();
            if (now != debugLastTick) {
                debugLastTick = now;
                debugPathfindCalls = 0;
                debugCacheHits = 0;
            }
        }

        if (tryTeleportIfSuffocating()) {
            return TickResult.WORKING;
        }

        // Periodic diagnostic when golem seems stuck
        if (ticksSinceLastPlacement > 0 && ticksSinceLastPlacement % 20 == 0) {
            LOGGER.info("[PlacementDiag] idle={}t pos={} target={} standPos={} navigating={} wander={} remaining={} deferred={} allFiltered={} overlapDef={}",
                ticksSinceLastPlacement, golem.blockPosition(), currentTarget, currentStandPos,
                navigatingToStandPos, wanderTarget, remainingBlocks.size(), deferredBlocks.size(),
                allFilteredTicks, consecutiveOverlapDeferrals);
        }

        // Track time since last placement — force navigation if stuck too long
        ticksSinceLastPlacement++;
        if (ticksSinceLastPlacement >= PLACEMENT_TIMEOUT_TICKS && wanderTarget == null
                && (!remainingBlocks.isEmpty() || !deferredBlocks.isEmpty())) {
            LOGGER.debug("No block placed in {} ticks, forcing reposition (remaining={} deferred={})",
                ticksSinceLastPlacement, remainingBlocks.size(), deferredBlocks.size());
            ticksSinceLastPlacement = 0;
            // Abandon current navigation/target
            if (currentTarget != null) {
                remainingBlocks.addFirst(currentTarget);
                currentTarget = null;
                currentStandPos = null;
            }
            navigatingToStandPos = false;
            stuckTicks = 0;
            noProgressTicks = 0;
            navBestDistSq = Double.MAX_VALUE;
            lastNavPos = null;
            flushDeferredToRemaining();
            pathCache.clear();  // Clear stale path cache so fresh pathfinding occurs

            // Find a stand position for the nearest remaining block and navigate there
            BlockPos repositionPos = findRepositionStandPos();
            if (repositionPos == null) {
                repositionPos = findRandomNearbyPosition(golem.blockPosition(), 8);
            }
            if (repositionPos != null) {
                // Try pathfinding first — the golem can likely walk/jump there
                boolean navStarted = golem.getNavigation().moveTo(
                    repositionPos.getX() + 0.5, repositionPos.getY(), repositionPos.getZ() + 0.5, 1.1);
                if (navStarted) {
                    LOGGER.debug("Placement timeout: pathfinding to {}", repositionPos);
                    wanderTarget = repositionPos;
                    wanderTicks = 0;
                } else {
                    // Pathfinding failed — just teleport
                    LOGGER.debug("Placement timeout: teleporting to {}", repositionPos);
                    teleportToStandPosition(repositionPos);
                }
            } else {
                // Standard reposition failed — try escalating hole escape
                tryEscapeHole();
            }
        }

        // If wandering to unstick from overlap, keep navigating until arrived or timeout
        if (wanderTarget != null) {
            wanderTicks++;
            // Only restart navigation when idle — avoid interrupting jumps
            if (golem.getNavigation().isDone()) {
                golem.getNavigation().moveTo(
                    wanderTarget.getX() + 0.5, wanderTarget.getY(), wanderTarget.getZ() + 0.5, 1.1);
            }
            double dx = golem.getX() - (wanderTarget.getX() + 0.5);
            double dz = golem.getZ() - (wanderTarget.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            boolean arrived = distSq < 1.5;
            boolean timedOut = wanderTicks >= MAX_WANDER_TICKS;
            if (arrived || timedOut) {
                if (timedOut && !arrived) {
                    // Couldn't pathfind there — teleport as fallback
                    LOGGER.debug("Wander timed out after {} ticks, teleporting to {}",
                        wanderTicks, wanderTarget);
                    teleportToStandPosition(wanderTarget);
                } else {
                    LOGGER.debug("Wander arrived in {} ticks", wanderTicks);
                }
                golem.getNavigation().stop();
                wanderTarget = null;
                wanderTicks = 0;
            } else {
                return TickResult.WORKING;
            }
        }

        // Select next block if needed
        if (currentTarget == null) {
            currentTarget = selectNextBlock();
            if (currentTarget == null) {
                if (selectionBlockedByBudget || !remainingBlocks.isEmpty() || !deferredBlocks.isEmpty()) {
                    // Track consecutive ticks where filter excluded all candidates
                    if (!selectionBlockedByBudget && !remainingBlocks.isEmpty()) {
                        allFilteredTicks++;
                    }
                    // If only deferred blocks remain and none passed the filter,
                    // flush them back to remaining and find a reposition target
                    if (remainingBlocks.isEmpty() && !deferredBlocks.isEmpty() && wanderTarget == null) {
                        flushDeferredToRemaining();
                        BlockPos repositionPos = findRepositionStandPos();
                        if (repositionPos == null) {
                            repositionPos = findRandomNearbyPosition(golem.blockPosition(), 6);
                        }
                        if (repositionPos != null) {
                            LOGGER.debug("Only deferred blocks remain, repositioning to {}", repositionPos);
                            wanderTarget = repositionPos;
                            wanderTicks = 0;
                        } else {
                            tryEscapeHole();
                        }
                    }
                    LOGGER.debug("No target selected, still working: budget={} remaining={} deferred={} allFiltered={}",
                        selectionBlockedByBudget, remainingBlocks.size(), deferredBlocks.size(), allFilteredTicks);
                    return TickResult.WORKING;
                }
                LOGGER.debug("All blocks placed, returning COMPLETED");
                return TickResult.COMPLETED;
            }
            LOGGER.info("[Select] picked target={} golemPos={} allFiltered={} overlapDef={}",
                currentTarget, golem.blockPosition(), allFilteredTicks, consecutiveOverlapDeferrals);

            // If already in reach, check overlap before deciding to stay put.
            Vec3 golemPos = new Vec3(golem.getX(), golem.getEyeY(), golem.getZ());
            if (isWithinReach(golemPos, currentTarget, MAX_REACH)) {
                if (wouldOverlapGolem(currentTarget)) {
                    // Block is reachable but overlaps the golem's bounding box.
                    // Find a non-overlapping stand position instead of staying put.
                    PlacementSearchResult placement = findPlacementResult(currentTarget);
                    if (placement.standPosition != null && !placement.standPosition.equals(golem.blockPosition())) {
                        allFilteredTicks = 0;  // Actually navigating — real progress
                        currentStandPos = placement.standPosition;
                        navigatingToStandPos = true;
                        stuckTicks = 0;
                        lastNavPos = null;
                        preselectedStandPos = null;
                        navigationFailures = 0;
                        navBestDistSq = Double.MAX_VALUE;
                        noProgressTicks = 0;
                        LOGGER.info("[Select] overlap but found standPos={} for target={}",
                            currentStandPos, currentTarget);
                    } else if (placement.budgetLimited) {
                        // Pathfinding budget exhausted, retry next tick
                        // Don't reset allFilteredTicks — this wasn't real progress
                        remainingBlocks.addFirst(currentTarget);
                        currentTarget = null;
                        currentStandPos = null;
                        return TickResult.WORKING;
                    } else {
                        // No better position found — defer and bump overlap counter
                        // Don't reset allFilteredTicks — selecting then immediately deferring isn't progress
                        LOGGER.info("[Select] overlap, no stand pos, deferring target={} overlapDef={}",
                            currentTarget, consecutiveOverlapDeferrals + 1);
                        defer(currentTarget);
                        currentTarget = null;
                        currentStandPos = null;
                        consecutiveOverlapDeferrals++;
                        if (consecutiveOverlapDeferrals >= MAX_CONSECUTIVE_OVERLAP_DEFERRALS) {
                            flushDeferredToRemaining();
                            BlockPos repositionPos = findRepositionStandPos();
                            if (repositionPos == null) {
                                repositionPos = findRandomNearbyPosition(golem.blockPosition(), 6);
                            }
                            if (repositionPos != null) {
                                LOGGER.debug("Repositioning to {} after {} overlap deferrals",
                                    repositionPos, consecutiveOverlapDeferrals);
                                wanderTarget = repositionPos;
                                wanderTicks = 0;
                            } else {
                                tryEscapeHole();
                            }
                            consecutiveOverlapDeferrals = 0;
                        }
                        return TickResult.DEFERRED;
                    }
                } else {
                    allFilteredTicks = 0;  // Block in reach, no overlap — real progress
                    currentStandPos = golem.blockPosition();
                    navigatingToStandPos = false;
                    stuckTicks = 0;
                    lastNavPos = null;
                    preselectedStandPos = null;
                    navigationFailures = 0;
                    LOGGER.info("[Select] in reach, no overlap, placing from current pos: target={}", currentTarget);
                }
            } else {
                allFilteredTicks = 0;  // Target selected and not in reach — navigating is progress
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
                        LOGGER.debug("Using fallback teleport: target={} fallback={}", currentTarget, fallbackPos);
                        teleportToStandPosition(fallbackPos);
                        currentStandPos = fallbackPos;
                        navigatingToStandPos = false;
                        stuckTicks = 0;
                        lastNavPos = null;
                        navigationFailures = 0;
                    } else {
                        // No valid position at all - force place from current position
                        LOGGER.debug("No valid stand position, force placing: target={}", currentTarget);
                        currentStandPos = golem.blockPosition();
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
                    navBestDistSq = Double.MAX_VALUE;
                    noProgressTicks = 0;
                    LOGGER.debug("Selected standPos={} for target={}", currentStandPos, currentTarget);
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

            // Check if we're close enough to place — but keep navigating if the
            // block overlaps the golem at this position (we need to reach the stand pos)
            Vec3 golemPos = new Vec3(golem.getX(), golem.getEyeY(), golem.getZ());
            boolean inReach = currentTarget != null && isWithinReach(golemPos, currentTarget, MAX_REACH);
            boolean stillOverlaps = currentTarget != null && wouldOverlapGolem(currentTarget);
            if (inReach && !stillOverlaps) {
                navigatingToStandPos = false;
                stuckTicks = 0;
                golem.getNavigation().stop();
            } else {
                // Only (re)start navigation when idle — calling moveTo() every tick
                // recalculates the path and interrupts jumps/step-ups
                boolean idle = golem.getNavigation().isDone();
                if (idle) {
                    boolean started = golem.getNavigation().moveTo(
                            currentStandPos.getX() + 0.5, currentStandPos.getY(), currentStandPos.getZ() + 0.5, 1.1);
                    if (!started) {
                        navigationFailures++;
                        // Give navigation a few attempts before teleporting
                        if (navigationFailures >= 6) {
                            if (currentStandPos != null && !currentStandPos.equals(golem.blockPosition())) {
                                LOGGER.debug("Navigation failed {} times, teleporting: standPos={} target={}",
                                        navigationFailures, currentStandPos, currentTarget);
                                teleportToStandPosition(currentStandPos);
                            }
                            navigatingToStandPos = false;
                            stuckTicks = 0;
                            lastNavPos = null;
                            navigationFailures = 0;
                        } else {
                            LOGGER.debug("Navigation failed to start (attempt {}), will retry", navigationFailures);
                        }
                        return TickResult.WORKING;
                    }
                    navigationFailures = 0;
                }

                // Check if stuck — use progress toward standPos, not raw movement
                Vec3 now = new Vec3(golem.getX(), golem.getY(), golem.getZ());
                double distToStandSq = now.distanceToSqr(
                    currentStandPos.getX() + 0.5, currentStandPos.getY(), currentStandPos.getZ() + 0.5);
                double movedSq = lastNavPos == null ? Double.POSITIVE_INFINITY : now.distanceToSqr(lastNavPos);

                // Track best distance achieved — progress means getting closer
                if (distToStandSq < navBestDistSq - 0.1) {
                    navBestDistSq = distToStandSq;
                    noProgressTicks = 0;
                } else {
                    noProgressTicks++;
                }

                if ((idle || movedSq < MIN_MOVE_DIST_SQ) && !inReach) {
                    stuckTicks++;
                }

                // Teleport if no progress toward stand pos (catches jiggling)
                // or if raw stuck timer fires
                boolean shouldTeleportNav = noProgressTicks >= NO_PROGRESS_TELEPORT_TICKS
                    || stuckTicks >= STUCK_THRESHOLD_TICKS;

                if (shouldTeleportNav) {
                    // Validate destination is meaningfully different from current position
                    BlockPos teleportDest = currentStandPos;
                    double teleportDistSq = now.distanceToSqr(
                        teleportDest.getX() + 0.5, teleportDest.getY(), teleportDest.getZ() + 0.5);
                    if (teleportDistSq < 2.25) { // < 1.5 blocks away — too close, find somewhere better
                        BlockPos betterPos = findRandomNearbyPosition(golem.blockPosition(), 6);
                        if (betterPos != null) {
                            LOGGER.debug("Stand pos too close (dist²={}), using random position {} instead",
                                String.format("%.2f", teleportDistSq), betterPos);
                            teleportDest = betterPos;
                        } else if (tryEscapeHole()) {
                            // Hole escape initiated — clear state so we re-select from new position
                            if (currentTarget != null) {
                                remainingBlocks.addFirst(currentTarget);
                                currentTarget = null;
                                currentStandPos = null;
                            }
                            stuckTicks = 0;
                            noProgressTicks = 0;
                            navBestDistSq = Double.MAX_VALUE;
                            navigatingToStandPos = false;
                            lastNavPos = null;
                            return TickResult.WORKING;
                        }
                    }
                    LOGGER.debug("Nav stuck (noProgress={} stuckTicks={}), teleporting: dest={} target={}",
                        noProgressTicks, stuckTicks, teleportDest, currentTarget);
                    teleportToStandPosition(teleportDest);
                    stuckTicks = 0;
                    noProgressTicks = 0;
                    navBestDistSq = Double.MAX_VALUE;
                    navigatingToStandPos = false;
                    lastNavPos = null;
                }
                lastNavPos = now;

                return TickResult.WORKING;
            }
        }

        // We're at the stand position, place the block
        if (currentTarget != null) {
            Vec3 golemPos = new Vec3(golem.getX(), golem.getEyeY(), golem.getZ());
            boolean inReach = isWithinReach(golemPos, currentTarget, MAX_REACH);

            if (!inReach) {
                // Not in reach - try to find a better position and teleport
                BlockPos betterPos = findAnyStandPosition(currentTarget);
                if (betterPos != null && !betterPos.equals(golem.blockPosition())) {
                    LOGGER.debug("Teleporting to better position: target={} pos={}", currentTarget, betterPos);
                    teleportToStandPosition(betterPos);
                    return TickResult.WORKING;
                }
                // No better position - force place anyway
                LOGGER.debug("Force placing out of range: target={}", currentTarget);
            }

            // Check if placing would cause golem to overlap with the block (suffocation)
            if (wouldOverlapGolem(currentTarget)) {
                consecutiveOverlapDeferrals++;
                LOGGER.debug("Target {} overlaps golem, deferring (consecutive: {})", currentTarget, consecutiveOverlapDeferrals);

                defer(currentTarget);
                currentTarget = null;
                currentStandPos = null;

                // If stuck due to repeated overlap deferrals, flush deferred blocks
                // back to remaining and reposition to a stand where the pyramid
                // filter won't exclude the target block.
                if (consecutiveOverlapDeferrals >= MAX_CONSECUTIVE_OVERLAP_DEFERRALS) {
                    flushDeferredToRemaining();
                    BlockPos repositionPos = findRepositionStandPos();
                    if (repositionPos == null) {
                        repositionPos = findRandomNearbyPosition(golem.blockPosition(), 6);
                    }
                    if (repositionPos != null) {
                        LOGGER.debug("Repositioning to {} after {} overlap deferrals",
                            repositionPos, consecutiveOverlapDeferrals);
                        wanderTarget = repositionPos;
                        wanderTicks = 0;
                    } else {
                        tryEscapeHole();
                    }
                    consecutiveOverlapDeferrals = 0;
                }

                return TickResult.DEFERRED;
            }

            // Wait for placement pacing if needed
            if (!placingAllowed) {
                return TickResult.WORKING;
            }

            // Place the block (even if slightly out of range)
            BlockPos nextTarget = peekNextTarget();
            LOGGER.debug("Attempting to place block at target={} golemPos={} nextTarget={}",
                currentTarget, golem.blockPosition(), nextTarget);
            boolean placed = blockPlacer.placeBlock(currentTarget, nextTarget);
            if (placed) {
                LOGGER.debug("Successfully placed block at {} remaining={} deferred={}",
                    currentTarget, remainingBlocks.size(), deferredBlocks.size());
                remainingBlocks.remove(currentTarget);
                deferAttempts.remove(currentTarget);
                currentTarget = null;
                currentStandPos = null;
                navigationFailures = 0;
                consecutiveOverlapDeferrals = 0;  // Reset since we made progress
                ticksSinceLastPlacement = 0;
                return TickResult.PLACED_BLOCK;
            } else {
                // Couldn't place - defer the block and try another.
                // If this was a missing inventory issue, handleMissingBuildingBlock will have
                // already set buildingPaths=false, stopping the outer tick loop.
                // For other failures (mine actions, duplicates), deferring lets the planner
                // try other blocks instead of getting stuck on this one forever.
                LOGGER.debug("Block placer rejected, deferring: target={}", currentTarget);
                defer(currentTarget);
                currentTarget = null;
                currentStandPos = null;
                return TickResult.DEFERRED;
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

    /**
     * Callback interface for checking if a block is already correctly placed.
     * Used to skip blocks that don't need to be placed (e.g., when resuming a build).
     */
    @FunctionalInterface
    public interface BlockChecker {
        /**
         * Check if the correct block is already at the given position.
         * @param pos The position to check
         * @return true if the block is already correctly placed and should be skipped
         */
        boolean isAlreadyCorrect(BlockPos pos);
    }

    // ========== Private Methods ==========

    private BlockPos selectNextBlock() {
        selectionBlockedByBudget = false;
        preselectedStandPos = null;
        long now = golem.level().getGameTime();
        pruneSkipMap(now);
        boolean bypassFilter = allFilteredTicks >= ALL_FILTERED_TELEPORT_THRESHOLD;

        if (!deferredBlocks.isEmpty()) {
            if (remainingBlocks.isEmpty() || deferredRetryCountdown <= 0) {
                // Find first non-excluded deferred block
                DeferredBlock deferred = null;
                int checked = 0;
                int size = deferredBlocks.size();
                while (checked < size) {
                    DeferredBlock candidate = deferredBlocks.pollFirst();
                    if (candidate == null) break;
                    if (!bypassFilter && blockFilter != null && blockFilter.shouldExclude(candidate.pos)) {
                        deferredBlocks.addLast(candidate); // Put back at end
                        checked++;
                        continue;
                    }
                    deferred = candidate;
                    break;
                }
                if (deferred != null) {
                    deferredRetryCountdown = DEFERRED_RETRY_INTERVAL;
                    LOGGER.info("[SelectBlock] from deferred: {} (bypass={} checked={})", deferred.pos, bypassFilter, checked);
                    return deferred.pos;
                }
                LOGGER.info("[SelectBlock] all {} deferred blocks filtered (bypass={})", size, bypassFilter);
            } else {
                deferredRetryCountdown--;
            }
        }

        Vec3 golemEyePos = new Vec3(golem.getX(), golem.getEyeY(), golem.getZ());

        // PHASE 1: Prioritize blocks within reach to avoid unnecessary teleporting
        // This ensures we place ALL reachable blocks before moving elsewhere
        BlockPos inReachBlock = findBlockWithinReach(golemEyePos, now);
        if (inReachBlock != null) {
            LOGGER.info("[SelectBlock] phase1 in-reach: {}", inReachBlock);
            return inReachBlock;
        }

        // PHASE 2: No blocks in reach - re-sort by distance to current golem position
        // This ensures when we do teleport/pathfind, it's to the nearest cluster
        resortByDistanceToGolem();

        // PHASE 3: Collect up to NEIGHBOR_CANDIDATE_COUNT non-excluded candidates for scoring
        // If the filter has been blocking ALL candidates for several ticks, bypass it
        // so the golem can teleport to a new position where the filter yields different results
        if (bypassFilter) {
            LOGGER.debug("Bypassing block filter after {} all-filtered ticks to allow teleport", allFilteredTicks);
        }
        List<BlockPos> scoringCandidates = new ArrayList<>();
        int scanned = 0;
        while (scoringCandidates.size() < NEIGHBOR_CANDIDATE_COUNT && scanned < remainingBlocks.size()) {
            BlockPos pos = remainingBlocks.pollFirst();
            if (pos == null) break;
            scanned++;
            Long skipUntil = skipUntilTick.get(pos);
            if (skipUntil != null && skipUntil > now) {
                remainingBlocks.addLast(pos);
                continue;
            }
            if (!bypassFilter && blockFilter != null && blockFilter.shouldExclude(pos)) {
                remainingBlocks.addLast(pos);
                continue;
            }
            scoringCandidates.add(pos);
        }

        if (scoringCandidates.isEmpty() && scanned > 0) {
            LOGGER.info("[SelectBlock] phase3: all {} scanned blocks filtered (bypass={})", scanned, bypassFilter);
        } else if (!scoringCandidates.isEmpty()) {
            LOGGER.info("[SelectBlock] phase3: {} candidates from {} scanned (bypass={})",
                scoringCandidates.size(), scanned, bypassFilter);
        }

        // Sort candidates by scorer if set (descending score, distance as tiebreak)
        if (blockScorer != null && scoringCandidates.size() > 1) {
            Vec3 golemPos = new Vec3(golem.getX(), golem.getY(), golem.getZ());
            scoringCandidates.sort((a, b) -> {
                int sa = blockScorer.score(a);
                int sb = blockScorer.score(b);
                if (sa != sb) return Integer.compare(sb, sa); // descending
                double da = golemPos.distanceToSqr(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5);
                double db = golemPos.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
                return Double.compare(da, db);
            });
        }

        // Try candidates with pathfinding, tracking which have been consumed
        int attempts = 0;
        BlockPos fallback = null;
        Set<BlockPos> consumed = new HashSet<>();
        BlockPos selected = null;
        for (BlockPos pos : scoringCandidates) {
            if (attempts >= MAX_CANDIDATES_PER_TICK) {
                break; // Will be put back below
            }
            if (fallback == null) {
                fallback = pos;
            }

            // Double-check reach (golem might have moved slightly)
            if (isWithinReach(golemEyePos, pos, MAX_REACH)) {
                consumed.add(pos);
                selected = pos;
                break;
            }

            PlacementSearchResult placement = findPlacementResult(pos);
            if (placement.budgetLimited && placement.standPosition == null) {
                selectionBlockedByBudget = true;
                defer(pos);
                consumed.add(pos);
                break;
            }
            if (placement.standPosition != null) {
                preselectedStandPos = placement.standPosition;
                consumed.add(pos);
                selected = pos;
                break;
            }

            skipUntilTick.put(pos, now + SKIP_RETRY_TICKS);
            remainingBlocks.addLast(pos);
            consumed.add(pos);
            attempts++;
        }

        // Put unconsumed candidates back at the front of the queue
        for (int i = scoringCandidates.size() - 1; i >= 0; i--) {
            BlockPos pos = scoringCandidates.get(i);
            if (!consumed.contains(pos)) {
                remainingBlocks.addFirst(pos);
            }
        }

        if (selected != null) {
            return selected;
        }

        if (!selectionBlockedByBudget && fallback != null) {
            remainingBlocks.remove(fallback);
            return fallback;
        }

        if (!remainingBlocks.isEmpty() || !deferredBlocks.isEmpty()) {
             LOGGER.debug("PlacementPlanner: yielded no target. Remaining={}, Deferred={}, BudgetLimited={}",
                 remainingBlocks.size(), deferredBlocks.size(), selectionBlockedByBudget);
        }

        return null;
    }

    /**
     * Find any block within reach of the golem, respecting skip timers.
     * Scans the queue and returns the first reachable block found.
     * @return A block position within reach, or null if none found
     */
    private BlockPos findBlockWithinReach(Vec3 golemEyePos, long now) {
        int filtered = 0, skipped = 0, outOfReach = 0;
        for (Iterator<BlockPos> it = remainingBlocks.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            Long skipUntil = skipUntilTick.get(pos);
            if (skipUntil != null && skipUntil > now) {
                skipped++;
                continue;
            }
            if (blockFilter != null && blockFilter.shouldExclude(pos)) {
                filtered++;
                continue; // Don't remove — may become valid when golem moves
            }
            if (isWithinReach(golemEyePos, pos, MAX_REACH)) {
                // Skip blocks that overlap the golem — they need navigation to a
                // different stand position, which Phase 1 (stay-in-place) can't provide
                if (wouldOverlapGolem(pos)) {
                    continue;
                }
                it.remove();
                LOGGER.info("[Phase1] found in-reach block {} (filtered={} skipped={} outOfReach={})",
                    pos, filtered, skipped, outOfReach);
                return pos;
            }
            outOfReach++;
        }
        if (filtered > 0 || skipped > 0 || outOfReach > 0) {
            LOGGER.info("[Phase1] no in-reach block: filtered={} skipped={} outOfReach={} total={}",
                filtered, skipped, outOfReach, remainingBlocks.size());
        }
        return null;
    }

    /**
     * Re-sort remaining blocks by distance to a jittered golem position.
     * Uses a random offset (up to 4 blocks) to break deterministic fail loops
     * where the golem keeps selecting the same unreachable blocks.
     */
    private void resortByDistanceToGolem() {
        if (remainingBlocks.size() <= 1) return;

        // Add random offset to break deterministic selection of the same blocks
        var rng = golem.getRandom();
        double jitterX = (rng.nextDouble() - 0.5) * 8.0; // -4 to +4
        double jitterZ = (rng.nextDouble() - 0.5) * 8.0;
        Vec3 jitteredPos = new Vec3(golem.getX() + jitterX, golem.getY(), golem.getZ() + jitterZ);
        List<BlockPos> blocks = new ArrayList<>(remainingBlocks);
        blocks.sort(Comparator.comparingDouble(b ->
            jitteredPos.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)));
        remainingBlocks.clear();
        remainingBlocks.addAll(blocks);

        LOGGER.debug("Re-sorted {} blocks by distance to jittered pos ({}, {}) from golem at {}",
            blocks.size(), String.format("%.1f", jitteredPos.x), String.format("%.1f", jitteredPos.z),
            golem.blockPosition());
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

    /**
     * Check if placing a block at the given position would overlap with the golem's bounding box.
     */
    /**
     * Check if placing a block at pos would cause the golem to suffocate.
     * For entities narrower than 1 block, only blocks in the golem's own
     * column (same XZ, within height range) can cause suffocation — adjacent
     * blocks never enclose a sub-1-block entity.
     */
    private boolean wouldOverlapGolem(BlockPos pos) {
        if (golem.getBbWidth() <= 1.0) {
            BlockPos feet = golem.blockPosition();
            if (pos.getX() != feet.getX() || pos.getZ() != feet.getZ()) {
                return false;
            }
            int topY = feet.getY() + net.minecraft.util.Mth.ceil(golem.getBbHeight()) - 1;
            return pos.getY() >= feet.getY() && pos.getY() <= topY;
        }
        // For wider entities, use AABB with generous deflation
        var golemBox = golem.getBoundingBox().deflate(0.3);
        var blockBox = new net.minecraft.world.phys.AABB(
            pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0
        );
        return golemBox.intersects(blockBox);
    }

    /**
     * Check if the golem's body, hypothetically placed at standFeet,
     * would overlap the given block position. Same column-based logic
     * as wouldOverlapGolem for sub-1-block entities.
     */
    private boolean wouldOverlapAt(BlockPos standFeet, BlockPos block) {
        if (golem.getBbWidth() <= 1.0) {
            if (block.getX() != standFeet.getX() || block.getZ() != standFeet.getZ()) {
                return false;
            }
            int topY = standFeet.getY() + net.minecraft.util.Mth.ceil(golem.getBbHeight()) - 1;
            return block.getY() >= standFeet.getY() && block.getY() <= topY;
        }
        double halfW = golem.getBbWidth() / 2.0;
        double height = golem.getBbHeight();
        double cx = standFeet.getX() + 0.5;
        double cy = standFeet.getY();
        double cz = standFeet.getZ() + 0.5;
        var hypothetical = new net.minecraft.world.phys.AABB(
            cx - halfW, cy, cz - halfW,
            cx + halfW, cy + height, cz + halfW
        ).deflate(0.3);
        var blockBox = new net.minecraft.world.phys.AABB(
            block.getX(), block.getY(), block.getZ(),
            block.getX() + 1.0, block.getY() + 1.0, block.getZ() + 1.0
        );
        return hypothetical.intersects(blockBox);
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
        var world = golem.level();
        if (world.isClientSide()) {
            return false;
        }
        if (!golem.isInWall() && world.noCollision(golem)) {
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
            safePos = findNearestSafeStandPosition(golem.blockPosition(), SUFFOCATION_TELEPORT_RADIUS);
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
        Vec3 golemPos = new Vec3(golem.getX(), golem.getY(), golem.getZ());
        int targetY = target.getY();

        // Collect all valid stand positions, then sort by preference
        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos standPos = target.offset(dx, dy, dz);

                    // Skip positions that would place the block inside the golem
                    // (target at feet level or head level)
                    if (standPos.equals(target) || standPos.above().equals(target)) {
                        continue;
                    }

                    // Skip positions where the golem's bounding box would still
                    // overlap the target block (prevents overlap-defer loops)
                    if (wouldOverlapAt(standPos, target)) {
                        continue;
                    }

                    Vec3 standEye = new Vec3(standPos.getX() + 0.5, standPos.getY() + golem.getEyeHeight(golem.getPose()), standPos.getZ() + 0.5);

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

        // Sort candidates: prefer positions where the target is NOT in the pyramid exclusion zone,
        // then at or below target Y, then by Y distance, then by distance to golem
        candidates.sort((a, b) -> {
            // First, prefer positions where the target won't be pyramid-excluded
            boolean aPyramid = wouldPyramidExclude(target, a);
            boolean bPyramid = wouldPyramidExclude(target, b);
            if (aPyramid != bPyramid) {
                return aPyramid ? 1 : -1;  // Non-excluded comes first
            }
            // Then prefer positions at or below target Y (ground is guaranteed from previous layers)
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
            double distA = golemPos.distanceToSqr(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
            double distB = golemPos.distanceToSqr(b.getX() + 0.5, b.getY(), b.getZ() + 0.5);
            return Double.compare(distA, distB);
        });

        BlockPos fallback = candidates.get(0); // Best candidate (closest to target Y)
        boolean budgetLimited = false;

        // Debug: log candidate selection
        if (candidates.size() <= 5) {
            LOGGER.debug("Stand candidates for target={}: {}", target, candidates);
        } else {
            LOGGER.debug("Stand candidates for target={}: top5={}, total={}", target,
                candidates.subList(0, 5), candidates.size());
        }

        // Check pathability for candidates (in sorted order)
        for (BlockPos standPos : candidates) {
            Vec3 standEye = new Vec3(standPos.getX() + 0.5, standPos.getY() + golem.getEyeHeight(golem.getPose()), standPos.getZ() + 0.5);

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
            }
        }

        int golemY = golem.blockPosition().getY();

        // Key insight: if the fallback is significantly ABOVE the golem, pathfinding will fail
        // because entities can't walk up without stairs/ladders. Teleport immediately.
        int fallbackAboveGolem = fallback.getY() - golemY;
        if (fallbackAboveGolem >= 1) {
            LOGGER.debug("Fallback above golem, teleporting up: fallback={} golemY={} target={}",
                fallback, golemY, target);
            return new PlacementSearchResult(fallback, false, true);
        }

        // If target is above the golem, we're likely in tower mode - teleport immediately
        int targetAboveGolem = targetY - golemY;
        if (targetAboveGolem >= 2) {
            LOGGER.debug("Target above golem (tower mode), using fallback: fallback={} golemY={} target={}",
                fallback, golemY, target);
            return new PlacementSearchResult(fallback, false, true);
        }

        // Budget exhausted or no pathable positions — return the best candidate
        // anyway so the caller can attempt pathfinding or teleport. Returning null
        // here stalls the entire selection pipeline.
        LOGGER.debug("No pathable positions found (budgetLimited={}), using fallback: fallback={} target={}",
            budgetLimited, fallback, target);
        return new PlacementSearchResult(fallback, false, true);
    }

    private BlockPos findNearestSafeStandPosition(BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
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
     * Find a random standable position nearby, at least 2 blocks from origin.
     * Used to unstick the golem when all blocks are deferred due to overlap.
     */
    private BlockPos findRandomNearbyPosition(BlockPos origin, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) < 2) continue; // Must be at least 2 blocks away
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (canStandAt(pos)) {
                        candidates.add(pos);
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(golem.getRandom().nextInt(candidates.size()));
    }

    /**
     * Attempt to escape a hole by finding standable positions at increasing distances.
     * Unlike findRandomNearbyPosition, has NO minimum distance restriction, so it can
     * find positions right on top of adjacent wall blocks.
     * Tries pathfinding (wander) first at escalating radii 2-6, then teleports as last resort.
     * @return true if an escape action was initiated (wander or teleport)
     */
    private boolean tryEscapeHole() {
        BlockPos origin = golem.blockPosition();
        Vec3 golemPos = new Vec3(golem.getX(), golem.getY(), golem.getZ());
        int pathBudget = 15;

        // Escalating wander: try standable blocks at increasing distances
        for (int radius = 2; radius <= 6 && pathBudget > 0; radius++) {
            // For radius 2, include chebyshev 1-2; for larger radii, only the new shell
            int minCheby = (radius == 2) ? 1 : radius;

            List<BlockPos> candidates = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int cheby = Math.max(Math.abs(dx), Math.abs(dz));
                        if (cheby < minCheby) continue;
                        BlockPos pos = origin.offset(dx, dy, dz);
                        if (canStandAt(pos)) {
                            candidates.add(pos);
                        }
                    }
                }
            }
            if (candidates.isEmpty()) continue;

            // Sort by distance (try closest first)
            candidates.sort(Comparator.comparingDouble(p ->
                golemPos.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5)));

            for (BlockPos pos : candidates) {
                if (pathBudget <= 0) break;
                pathBudget--;
                Path path = golem.getNavigation().createPath(pos, 0);
                if (path != null && path.canReach()) {
                    boolean navStarted = golem.getNavigation().moveTo(
                        pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.1);
                    if (navStarted) {
                        LOGGER.debug("Hole escape: wandering to {} (radius={})", pos, radius);
                        wanderTarget = pos;
                        wanderTicks = 0;
                        return true;
                    }
                }
            }
        }

        // All pathfinding failed — teleport to any standable block > 4 manhattan distance away
        List<BlockPos> farCandidates = new ArrayList<>();
        for (int dx = -10; dx <= 10; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -10; dz <= 10; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= 4) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (canStandAt(pos)) {
                        farCandidates.add(pos);
                    }
                }
            }
        }
        if (!farCandidates.isEmpty()) {
            BlockPos tp = farCandidates.get(golem.getRandom().nextInt(farCandidates.size()));
            LOGGER.debug("Hole escape: teleporting to {} (far)", tp);
            teleportToStandPosition(tp);
            return true;
        }

        LOGGER.warn("Hole escape failed: no valid positions found near {}", origin);
        return false;
    }

    /**
     * Check if the inverted pyramid exclusion zone would exclude a block
     * when the golem is at the given feet position.
     * Mirrors the filter set by WallBuildStrategy.
     */
    private boolean wouldPyramidExclude(BlockPos block, BlockPos golemFeet) {
        int dy = block.getY() - golemFeet.getY();
        if (dy <= 0) return false;
        int chebyshev = Math.max(
            Math.abs(block.getX() - golemFeet.getX()),
            Math.abs(block.getZ() - golemFeet.getZ())
        );
        return chebyshev <= dy;
    }

    /**
     * Find a stand position near a remaining/deferred block where the block
     * won't be in the inverted pyramid exclusion zone from that position.
     * Returns a position the golem should walk to in order to unstick.
     */
    private BlockPos findRepositionStandPos() {
        List<BlockPos> allBlocks = new ArrayList<>();
        for (DeferredBlock db : deferredBlocks) allBlocks.add(db.pos);
        for (BlockPos pos : remainingBlocks) allBlocks.add(pos);

        if (allBlocks.isEmpty()) return null;

        Vec3 golemPos = new Vec3(golem.getX(), golem.getY(), golem.getZ());
        allBlocks.sort(Comparator.comparingDouble(b ->
            golemPos.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)));

        int reach = (int) Math.ceil(MAX_REACH);
        int blocksChecked = 0;

        for (BlockPos block : allBlocks) {
            if (blocksChecked >= 10) break;
            blocksChecked++;

            List<BlockPos> standCandidates = new ArrayList<>();

            for (int dx = -reach; dx <= reach; dx++) {
                for (int dy = -reach; dy <= reach; dy++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        BlockPos standPos = block.offset(dx, dy, dz);
                        if (standPos.equals(block) || standPos.above().equals(block)) continue;
                        if (wouldOverlapAt(standPos, block)) continue;
                        if (!canStandAt(standPos)) continue;

                        Vec3 standEye = new Vec3(standPos.getX() + 0.5,
                            standPos.getY() + golem.getEyeHeight(golem.getPose()),
                            standPos.getZ() + 0.5);
                        if (!isWithinReach(standEye, block, MAX_REACH)) continue;

                        if (!wouldPyramidExclude(block, standPos)) {
                            standCandidates.add(standPos);
                        }
                    }
                }
            }

            if (standCandidates.isEmpty()) continue;

            // Sort by distance to golem, prefer pathable
            standCandidates.sort(Comparator.comparingDouble(s ->
                golemPos.distanceToSqr(s.getX() + 0.5, s.getY(), s.getZ() + 0.5)));

            for (BlockPos stand : standCandidates) {
                PathCheckStatus status = canPathTo(stand);
                if (status == PathCheckStatus.PATHABLE) {
                    LOGGER.debug("Found pathable reposition: stand={} for block={}", stand, block);
                    return stand;
                }
            }

            // Fall back to closest (will teleport if navigation fails)
            LOGGER.debug("Using non-pathable reposition: stand={} for block={}", standCandidates.get(0), block);
            return standCandidates.get(0);
        }

        return null;
    }

    /**
     * Move all deferred blocks back into the remaining queue and reset defer tracking.
     * Used when the golem is stuck and needs to retry all blocks from a new position.
     */
    private void flushDeferredToRemaining() {
        if (deferredBlocks.isEmpty()) return;
        LOGGER.debug("Flushing {} deferred blocks back to remaining", deferredBlocks.size());
        for (DeferredBlock db : deferredBlocks) {
            remainingBlocks.addLast(db.pos);
        }
        deferredBlocks.clear();
        deferAttempts.clear();
        skipUntilTick.clear();
        pathCache.clear();
        resortByDistanceToGolem();
    }

    /**
     * Check if a position is within reach to place a block.
     */
    private boolean isWithinReach(Vec3 from, BlockPos target, double maxReach) {
        double dx = from.x - (target.getX() + 0.5);
        double dy = from.y - (target.getY() + 0.5);
        double dz = from.z - (target.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Check both overall reach and vertical reach separately
        return dist <= maxReach && Math.abs(dy) <= MAX_VERTICAL_REACH;
    }

    /**
     * Check if the golem can stand at a position.
     */
    private boolean canStandAt(BlockPos pos) {
        var world = golem.level();

        // Check for solid ground below
        BlockPos groundPos = pos.below();
        BlockState groundState = world.getBlockState(groundPos);
        if (!groundState.isRedstoneConductor(world, groundPos) && !groundState.entityCanStandOn(world, groundPos, golem)) {
            return false;
        }

        // Check for air at feet
        BlockState feetState = world.getBlockState(pos);
        if (!feetState.isAir()) {
            return false;
        }

        // Check for air at head level only if golem is tall enough
        if (golem.getBbHeight() > 1.0) {
            BlockState headState = world.getBlockState(pos.above());
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
        var world = golem.level();

        BlockPos bestGround = null;
        double bestGroundDist = Double.MAX_VALUE;
        BlockPos bestAir = null;
        double bestAirDist = Double.MAX_VALUE;
        int targetY = target.getY();

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos pos = target.offset(dx, dy, dz);

                    // Skip positions that would place the block inside the golem
                    if (pos.equals(target) || pos.above().equals(target)) {
                        continue;
                    }

                    // Skip positions where the golem would overlap the target
                    if (wouldOverlapAt(pos, target)) {
                        continue;
                    }

                    Vec3 standEye = new Vec3(pos.getX() + 0.5, pos.getY() + golem.getEyeHeight(golem.getPose()), pos.getZ() + 0.5);

                    if (!isWithinReach(standEye, target, MAX_REACH)) {
                        continue;
                    }

                    BlockState feetState = world.getBlockState(pos);
                    if (!feetState.isAir()) {
                        continue;
                    }

                    // Check head clearance
                    if (golem.getBbHeight() > 1.0) {
                        BlockState headState = world.getBlockState(pos.above());
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

        long now = golem.level().getGameTime();
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

        Path path = golem.getNavigation().createPath(pos, 0);
        boolean canPath = path != null && path.canReach();
        if (!canPath) {
            lastPathFailureTick = now;
        }
        pathCache.put(pos, new PathCheck(canPath, now + PATH_CACHE_TTL_TICKS));
        return canPath ? PathCheckStatus.PATHABLE : PathCheckStatus.NOT_PATHABLE;
    }

    /**
     * Teleport the golem to a stand position.
     * Delegates to the golem's teleportWithParticles method for consistent behavior.
     */
    private void teleportToStandPosition(BlockPos standPos) {
        golem.teleportWithParticles(standPos);
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
        consecutiveOverlapDeferrals = 0;
        allFilteredTicks = 0;
        wanderTarget = null;
        wanderTicks = 0;
        navBestDistSq = Double.MAX_VALUE;
        noProgressTicks = 0;
        ticksSinceLastPlacement = 0;
        blockFilter = null;
        blockScorer = null;
    }

    /**
     * Write state to NBT for persistence.
     */
    public void writeNbt(net.minecraft.nbt.CompoundTag nbt) {
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
    public void readNbt(net.minecraft.nbt.CompoundTag nbt) {
        remainingBlocks.clear();
        deferredBlocks.clear();
        deferAttempts.clear();
        skipUntilTick.clear();
        pathCache.clear();

        // Load current target
        if (nbt.contains("CurrentTargetX")) {
            currentTarget = new BlockPos(
                    nbt.getIntOr("CurrentTargetX", 0),
                    nbt.getIntOr("CurrentTargetY", 0),
                    nbt.getIntOr("CurrentTargetZ", 0)
            );
        }

        // Load current stand pos
        if (nbt.contains("CurrentStandX")) {
            currentStandPos = new BlockPos(
                    nbt.getIntOr("CurrentStandX", 0),
                    nbt.getIntOr("CurrentStandY", 0),
                    nbt.getIntOr("CurrentStandZ", 0)
            );
        }

        navigatingToStandPos = nbt.getBooleanOr("NavigatingToStandPos", false);
        stuckTicks = nbt.getIntOr("StuckTicks", 0);
        deferredRetryCountdown = 0;
        preselectedStandPos = null;
        selectionBlockedByBudget = false;
        navigationFailures = 0;
        lastPathFailureTick = Long.MIN_VALUE;
        lastPathBudgetTick = Long.MIN_VALUE;
        remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;
        consecutiveOverlapDeferrals = 0;
        allFilteredTicks = 0;
        wanderTarget = null;
        wanderTicks = 0;
        navBestDistSq = Double.MAX_VALUE;
        noProgressTicks = 0;
        ticksSinceLastPlacement = 0;

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

    public void writeView(ValueOutput view) {
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

    public void readView(ValueInput view) {
        remainingBlocks.clear();
        deferredBlocks.clear();
        deferAttempts.clear();
        skipUntilTick.clear();
        pathCache.clear();

        if (view.contains("CurrentTargetX")) {
            currentTarget = new BlockPos(
                    view.getIntOr("CurrentTargetX", 0),
                    view.getIntOr("CurrentTargetY", 0),
                    view.getIntOr("CurrentTargetZ", 0)
            );
        } else {
            currentTarget = null;
        }
        if (view.contains("CurrentStandX")) {
            currentStandPos = new BlockPos(
                    view.getIntOr("CurrentStandX", 0),
                    view.getIntOr("CurrentStandY", 0),
                    view.getIntOr("CurrentStandZ", 0)
            );
        } else {
            currentStandPos = null;
        }
        navigatingToStandPos = view.getBooleanOr("NavigatingToStandPos", false);
        stuckTicks = view.getIntOr("StuckTicks", 0);
        deferredRetryCountdown = 0;
        preselectedStandPos = null;
        selectionBlockedByBudget = false;
        navigationFailures = 0;
        lastPathFailureTick = Long.MIN_VALUE;
        lastPathBudgetTick = Long.MIN_VALUE;
        remainingPathfindBudget = MAX_PATHFINDS_PER_TICK;
        consecutiveOverlapDeferrals = 0;
        allFilteredTicks = 0;
        wanderTarget = null;
        wanderTicks = 0;
        navBestDistSq = Double.MAX_VALUE;
        noProgressTicks = 0;
        ticksSinceLastPlacement = 0;

        int[] remaining = view.getIntArray("RemainingBlocks").orElseGet(() -> new int[0]);
        int[] deferred = view.getIntArray("DeferredBlocks").orElseGet(() -> new int[0]);
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

    private void writeDeferAttempts(net.minecraft.nbt.CompoundTag nbt) {
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

    private void writeDeferAttempts(ValueOutput view) {
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

    private void readDeferAttempts(net.minecraft.nbt.CompoundTag nbt) {
        int[] posData = nbt.getIntArray("DeferAttemptPos").orElseGet(() -> new int[0]);
        int[] counts = nbt.getIntArray("DeferAttemptCounts").orElseGet(() -> new int[0]);
        loadDeferAttempts(posData, counts);
    }

    private void readDeferAttempts(ValueInput view) {
        int[] posData = view.getIntArray("DeferAttemptPos").orElseGet(() -> new int[0]);
        int[] counts = view.getIntArray("DeferAttemptCounts").orElseGet(() -> new int[0]);
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
        var world = golem.level();
        Vec3 start = new Vec3(golem.getX(), golem.getEyeY(), golem.getZ());
        Vec3 end = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        HitResult hit = world.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                golem
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean shouldTeleport() {
        long now = golem.level().getGameTime();
        if (navigationFailures < MIN_NAV_FAILURES_FOR_TELEPORT) {
            return false;
        }
        return now - lastPathFailureTick <= PATH_FAILURE_WINDOW_TICKS;
    }
}
