package ninja.trek.mc.goldgolem.tree;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Implements Wave Function Collapse as a flood-fill algorithm for Tree Mode.
 * Starts from a seed position and expands outward, stopping at boundaries (air/gold/ground).
 */
public final class TreeWFCBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(TreeWFCBuilder.class);

    private final TreeTileCache tileCache;
    private final World world;
    private final Set<Block> stopBlocks; // blocks that act as boundaries
    private final Random random;

    // Wave function: for each position, track possible tile IDs
    private final Map<BlockPos, Set<String>> waveFunction;

    // E5: Priority queue for entropy search - positions with lowest entropy at front
    // Using a Set for O(1) contains checks, and rebuilding priority when needed
    private final Set<BlockPos> frontierSet;
    private final PriorityQueue<BlockPos> frontierQueue;

    // Already collapsed/placed positions
    private final Map<BlockPos, String> collapsed;

    // Queue of positions to process for building
    private final ArrayDeque<BlockPos> buildQueue;

    public TreeWFCBuilder(TreeTileCache tileCache, World world, BlockPos startPos, Set<Block> stopBlocks, Random random) {
        this.tileCache = tileCache;
        this.world = world;
        this.stopBlocks = new HashSet<>(stopBlocks);
        this.random = random;

        this.waveFunction = new HashMap<>();
        this.frontierSet = new HashSet<>();
        // E5: Priority queue comparator - lower entropy (fewer possible tiles) = higher priority
        this.frontierQueue = new PriorityQueue<>(Comparator.comparingInt(pos -> {
            Set<String> tiles = waveFunction.get(pos);
            return tiles != null ? tiles.size() : Integer.MAX_VALUE;
        }));
        this.collapsed = new HashMap<>();
        this.buildQueue = new ArrayDeque<>();

        // Initialize with start position
        initialize(startPos);
    }

    /**
     * Initializes the WFC algorithm with the starting position.
     */
    private void initialize(BlockPos startPos) {
        // Start position can have any tile
        Set<String> allTiles = new HashSet<>(tileCache.getAllTileIds());
        waveFunction.put(startPos, allTiles);
        addToFrontier(startPos);
    }

    /**
     * E5: Add a position to the frontier (both set and priority queue).
     */
    private void addToFrontier(BlockPos pos) {
        if (frontierSet.add(pos)) {
            frontierQueue.add(pos);
        }
    }

    /**
     * E5: Remove a position from the frontier.
     */
    private void removeFromFrontier(BlockPos pos) {
        if (frontierSet.remove(pos)) {
            frontierQueue.remove(pos);
        }
    }

    /**
     * E5: Update frontier priority for a position (when its entropy changes).
     */
    private void updateFrontierPriority(BlockPos pos) {
        if (frontierSet.contains(pos)) {
            frontierQueue.remove(pos);
            frontierQueue.add(pos);
        }
    }

    /**
     * Performs one step of the WFC algorithm.
     * Returns true if there's more work to do, false if finished or failed.
     * E3: Fixed ConcurrentModificationException by collecting positions to remove first.
     * E5: Uses priority queue for O(log n) entropy search instead of O(n) linear scan.
     */
    public boolean step() {
        if (frontierSet.isEmpty()) {
            return false; // Done
        }

        // E3: Collect positions to remove first (those with no valid tiles)
        List<BlockPos> toRemove = new ArrayList<>();
        BlockPos minEntropyPos = null;

        // E5: Use priority queue to find minimum entropy position
        // First, clean up any invalid positions from the queue
        while (!frontierQueue.isEmpty()) {
            BlockPos candidate = frontierQueue.peek();

            // Check if still in frontier set (may have been removed)
            if (!frontierSet.contains(candidate)) {
                frontierQueue.poll();
                continue;
            }

            Set<String> possibleTiles = waveFunction.get(candidate);
            if (possibleTiles == null || possibleTiles.isEmpty()) {
                // Contradiction - this position has no valid tiles, mark for removal
                frontierQueue.poll();
                toRemove.add(candidate);
                continue;
            }

            // This is our minimum entropy position
            minEntropyPos = frontierQueue.poll();
            break;
        }

        // E3: Remove invalid positions after iteration
        for (BlockPos pos : toRemove) {
            frontierSet.remove(pos);
        }

        if (minEntropyPos == null) {
            return !frontierSet.isEmpty(); // May have more work if queue was just out of sync
        }

        // Remove from frontier set
        frontierSet.remove(minEntropyPos);

        // Collapse this position
        collapse(minEntropyPos);

        // E2: Propagate constraints using iterative arc consistency
        String chosenTile = collapsed.get(minEntropyPos);
        propagate(minEntropyPos, chosenTile);

        // Expand frontier
        expandFrontier(minEntropyPos);

        return true;
    }

    /**
     * Collapses a position by randomly selecting one of its possible tiles.
     */
    private void collapse(BlockPos pos) {
        Set<String> possibleTiles = waveFunction.get(pos);
        if (possibleTiles == null || possibleTiles.isEmpty()) {
            return; // Can't collapse
        }

        // Pick a random tile (TODO: could weight by frequency in input)
        List<String> tileList = new ArrayList<>(possibleTiles);
        String chosenTile = tileList.get(random.nextInt(tileList.size()));

        // Collapse to this single tile
        collapsed.put(pos, chosenTile);
        waveFunction.put(pos, Collections.singleton(chosenTile));

        // Add to build queue
        buildQueue.add(pos);
    }

    /**
     * E2: Propagates constraints from a collapsed position using iterative arc consistency.
     * Uses a worklist algorithm to propagate changes until no more constraints can be applied.
     */
    private void propagate(BlockPos collapsedPos, String chosenTile) {
        if (chosenTile == null) return;

        // E2: Use a worklist for iterative arc consistency
        Queue<BlockPos> worklist = new LinkedList<>();
        worklist.add(collapsedPos);

        // Track which positions we've already processed in this propagation wave
        // to avoid redundant processing
        Set<BlockPos> inWorklist = new HashSet<>();
        inWorklist.add(collapsedPos);

        while (!worklist.isEmpty()) {
            BlockPos current = worklist.poll();
            inWorklist.remove(current);

            // Get the tile(s) at current position
            String currentTile = collapsed.get(current);
            Set<String> currentPossible = null;
            if (currentTile == null) {
                currentPossible = waveFunction.get(current);
                if (currentPossible == null || currentPossible.isEmpty()) {
                    continue;
                }
            }

            // Check each neighbor
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = current.offset(dir);

                // Skip if already collapsed
                if (collapsed.containsKey(neighborPos)) continue;

                // Skip if it's a stop block
                if (isStopBlock(neighborPos)) continue;

                // Compute valid neighbors based on current position's possibilities
                Set<String> validNeighbors = new HashSet<>();
                if (currentTile != null) {
                    // Collapsed position - single tile
                    validNeighbors.addAll(tileCache.getValidNeighbors(currentTile, dir));
                } else {
                    // Uncollapsed position - union of all possible tiles' neighbors
                    for (String possibleTile : currentPossible) {
                        validNeighbors.addAll(tileCache.getValidNeighbors(possibleTile, dir));
                    }
                }

                if (validNeighbors.isEmpty()) {
                    // No valid tiles in this direction - don't expand here
                    continue;
                }

                // Update wave function for neighbor
                Set<String> neighborPossible = waveFunction.get(neighborPos);
                if (neighborPossible == null) {
                    // First constraint for this position
                    waveFunction.put(neighborPos, new HashSet<>(validNeighbors));
                } else {
                    int sizeBefore = neighborPossible.size();
                    neighborPossible.retainAll(validNeighbors);

                    if (neighborPossible.isEmpty()) {
                        // Contradiction - remove from wave function and log warning
                        waveFunction.remove(neighborPos);
                        removeFromFrontier(neighborPos);
                        LOGGER.debug("WFC contradiction at {} - no valid tiles remain", neighborPos);
                    } else if (neighborPossible.size() < sizeBefore) {
                        // Constraints changed - add to worklist to propagate further
                        if (!inWorklist.contains(neighborPos)) {
                            worklist.add(neighborPos);
                            inWorklist.add(neighborPos);
                        }
                        // E5: Update priority since entropy changed
                        updateFrontierPriority(neighborPos);
                    }
                }
            }
        }
    }

    /**
     * Expands the frontier by adding neighboring positions.
     * E5: Uses the new addToFrontier method for priority queue management.
     */
    private void expandFrontier(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);

            // Skip if already collapsed or in frontier
            if (collapsed.containsKey(neighborPos) || frontierSet.contains(neighborPos)) {
                continue;
            }

            // Skip if it's a stop block
            if (isStopBlock(neighborPos)) {
                continue;
            }

            // Add to frontier if it has valid tiles
            Set<String> possibleTiles = waveFunction.get(neighborPos);
            if (possibleTiles != null && !possibleTiles.isEmpty()) {
                addToFrontier(neighborPos);
            }
        }
    }

    /**
     * Checks if a position contains a stop block (boundary).
     */
    private boolean isStopBlock(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        // Air is always a stop block
        if (state.isAir()) return true;

        // Check against configured stop blocks
        return stopBlocks.contains(block);
    }

    /**
     * Gets the next block position to build, or null if none available.
     */
    public BlockPos getNextBuildPosition() {
        return buildQueue.poll();
    }

    /**
     * Gets the tile ID for a collapsed position.
     */
    public String getCollapsedTile(BlockPos pos) {
        return collapsed.get(pos);
    }

    /**
     * Gets a tile by ID from the cache.
     */
    public TreeTile getTile(String tileId) {
        return tileCache.getTile(tileId);
    }

    /**
     * Checks if the builder has finished (frontier is empty).
     */
    public boolean isFinished() {
        return frontierSet.isEmpty();
    }

    /**
     * Checks if there are blocks waiting to be built.
     */
    public boolean hasPendingBlocks() {
        return !buildQueue.isEmpty();
    }

    /**
     * Gets the total number of collapsed positions.
     */
    public int getCollapsedCount() {
        return collapsed.size();
    }

    /**
     * Runs the WFC algorithm until completion or max steps reached.
     * Returns the number of steps taken.
     */
    public int runUntilComplete(int maxSteps) {
        int steps = 0;
        while (step() && steps < maxSteps) {
            steps++;
        }
        return steps;
    }
}
