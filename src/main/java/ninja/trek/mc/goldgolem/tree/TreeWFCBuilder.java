package ninja.trek.mc.goldgolem.tree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Implements Wave Function Collapse as a flood-fill algorithm for Tree Mode.
 * Starts from a seed position and expands outward, stopping at boundaries (air/gold/ground).
 */
public final class TreeWFCBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(TreeWFCBuilder.class);

    private final TreeTileCache tileCache;
    private final Level world;
    private final Set<Block> stopBlocks; // blocks that act as boundaries
    private final Set<Block> groundBlocks; // ground blocks for initial candidate filtering
    private final Set<BlockPos> nonStopOverrides; // positions that bypass stop-block checks
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

    // Shadow map: positions that will be filled by collapsed tiles (prevents premature air-boundary stops)
    private final Set<BlockPos> plannedBlocks;

    public TreeWFCBuilder(TreeTileCache tileCache, Level world, BlockPos startPos, Set<Block> stopBlocks, Random random) {
        this(tileCache, world, startPos, stopBlocks, Collections.emptySet(), random, Collections.emptySet());
    }

    public TreeWFCBuilder(TreeTileCache tileCache, Level world, BlockPos startPos, Set<Block> stopBlocks,
                          Set<Block> groundBlocks, Random random) {
        this(tileCache, world, startPos, stopBlocks, groundBlocks, random, Collections.emptySet());
    }

    public TreeWFCBuilder(TreeTileCache tileCache, Level world, BlockPos startPos, Set<Block> stopBlocks,
                          Set<Block> groundBlocks, Random random, Set<BlockPos> nonStopOverrides) {
        this.tileCache = tileCache;
        this.world = world;
        this.stopBlocks = new HashSet<>(stopBlocks);
        this.groundBlocks = new HashSet<>(groundBlocks);
        this.nonStopOverrides = new HashSet<>(nonStopOverrides);
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
        this.plannedBlocks = new HashSet<>();

        // Initialize with start position
        initialize(startPos);
    }

    /**
     * Initializes the WFC algorithm with the starting position.
     * When ground blocks are present around the seed, filters initial candidates
     * to "base" tiles that sit on ground.
     */
    private void initialize(BlockPos startPos) {
        Set<String> candidates;
        if (!groundBlocks.isEmpty()) {
            // Check if ground is present below the start position
            boolean groundBelow = false;
            int tileSize = tileCache.tileSize;
            for (int dx = 0; dx < tileSize && !groundBelow; dx++) {
                for (int dz = 0; dz < tileSize && !groundBelow; dz++) {
                    BlockState below = world.getBlockState(startPos.offset(dx, -1, dz));
                    if (groundBlocks.contains(below.getBlock())) {
                        groundBelow = true;
                    }
                }
            }
            if (groundBelow) {
                Set<String> baseTiles = tileCache.getBottomGroundTileIds();
                candidates = baseTiles.isEmpty()
                        ? new HashSet<>(tileCache.getAllTileIds())
                        : new HashSet<>(baseTiles);
            } else {
                candidates = new HashSet<>(tileCache.getAllTileIds());
            }
        } else {
            candidates = new HashSet<>(tileCache.getAllTileIds());
        }
        waveFunction.put(startPos, candidates);
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

        // Register non-air positions in shadow map so frontier expansion
        // doesn't treat them as air boundaries before they're actually built.
        // Skip GROUND_MARKER positions — ground is already placed and shouldn't
        // prevent stop-block detection.
        TreeTile tile = tileCache.getTile(chosenTile);
        if (tile != null) {
            for (int dx = 0; dx < tile.size; dx++) {
                for (int dy = 0; dy < tile.size; dy++) {
                    for (int dz = 0; dz < tile.size; dz++) {
                        BlockState bs = tile.blocks[dx][dy][dz];
                        if (!bs.isAir() && bs != TreeTileExtractor.GROUND_MARKER) {
                            plannedBlocks.add(pos.offset(dx, dy, dz));
                        }
                    }
                }
            }
        }

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
                BlockPos neighborPos = current.relative(dir);

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
            BlockPos neighborPos = pos.relative(dir);

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
        // Non-stop overrides: positions that should never be treated as boundaries
        // (e.g. gold blocks from partial scan, whether still present or already mined to air)
        if (nonStopOverrides.contains(pos)) return false;

        // Shadow map: position will be filled by a collapsed tile, not a real boundary
        if (plannedBlocks.contains(pos)) return false;

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
