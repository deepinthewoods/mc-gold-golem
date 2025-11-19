package ninja.trek.mc.goldgolem.tree;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;

/**
 * Implements Wave Function Collapse as a flood-fill algorithm for Tree Mode.
 * Starts from a seed position and expands outward, stopping at boundaries (air/gold/ground).
 */
public final class TreeWFCBuilder {
    private final TreeTileCache tileCache;
    private final World world;
    private final Set<Block> stopBlocks; // blocks that act as boundaries
    private final Random random;

    // Wave function: for each position, track possible tile IDs
    private final Map<BlockPos, Set<String>> waveFunction;

    // Frontier: positions where we could place next
    private final Set<BlockPos> frontier;

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
        this.frontier = new HashSet<>();
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
        frontier.add(startPos);
    }

    /**
     * Performs one step of the WFC algorithm.
     * Returns true if there's more work to do, false if finished or failed.
     */
    public boolean step() {
        if (frontier.isEmpty()) {
            return false; // Done
        }

        // Find position with lowest entropy (fewest possible tiles)
        BlockPos minEntropyPos = null;
        int minEntropy = Integer.MAX_VALUE;

        for (BlockPos pos : frontier) {
            Set<String> possibleTiles = waveFunction.get(pos);
            if (possibleTiles == null || possibleTiles.isEmpty()) {
                // Contradiction - this position has no valid tiles
                frontier.remove(pos);
                return frontier.size() > 0;
            }

            int entropy = possibleTiles.size();
            if (entropy < minEntropy) {
                minEntropy = entropy;
                minEntropyPos = pos;
            }
        }

        if (minEntropyPos == null) {
            return false; // Done
        }

        // Collapse this position
        collapse(minEntropyPos);

        // Remove from frontier
        frontier.remove(minEntropyPos);

        // Propagate constraints and expand frontier
        propagate(minEntropyPos);
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
     * Propagates constraints from a collapsed position to its neighbors.
     */
    private void propagate(BlockPos pos) {
        String tileId = collapsed.get(pos);
        if (tileId == null) return;

        // Check each neighbor
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);

            // Skip if already collapsed
            if (collapsed.containsKey(neighborPos)) continue;

            // Skip if it's a stop block
            if (isStopBlock(neighborPos)) continue;

            // Get valid tiles for this neighbor based on adjacency constraints
            Set<String> validNeighbors = tileCache.getValidNeighbors(tileId, dir);

            if (validNeighbors.isEmpty()) {
                // No valid tiles in this direction - don't expand here
                continue;
            }

            // Update wave function for neighbor
            Set<String> currentPossible = waveFunction.get(neighborPos);
            if (currentPossible == null) {
                // First constraint for this position
                waveFunction.put(neighborPos, new HashSet<>(validNeighbors));
            } else {
                // Intersect with existing constraints
                currentPossible.retainAll(validNeighbors);
                if (currentPossible.isEmpty()) {
                    // Contradiction - remove from wave function
                    waveFunction.remove(neighborPos);
                }
            }
        }
    }

    /**
     * Expands the frontier by adding neighboring positions.
     */
    private void expandFrontier(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);

            // Skip if already collapsed or in frontier
            if (collapsed.containsKey(neighborPos) || frontier.contains(neighborPos)) {
                continue;
            }

            // Skip if it's a stop block
            if (isStopBlock(neighborPos)) {
                continue;
            }

            // Add to frontier if it has valid tiles
            Set<String> possibleTiles = waveFunction.get(neighborPos);
            if (possibleTiles != null && !possibleTiles.isEmpty()) {
                frontier.add(neighborPos);
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
        return frontier.isEmpty();
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
