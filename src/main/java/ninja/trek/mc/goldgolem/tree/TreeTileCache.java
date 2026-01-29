package ninja.trek.mc.goldgolem.tree;

import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * Cache of extracted tiles and their adjacency constraints for WFC algorithm.
 * Stores tiles along with rules about which tiles can be placed adjacent to each other.
 */
public final class TreeTileCache {
    public final int tileSize; // 3 or 5
    public final List<TreeTile> tiles; // all extracted tiles (including rotations)
    public final Map<String, Integer> tileIdToIndex; // tile ID -> index in tiles list
    // E1: Fixed hash collision - use nested maps instead of encoded long keys
    private final Map<String, Map<Direction, Set<String>>> adjacencyRules; // tileId -> direction -> valid neighbor tile IDs

    public TreeTileCache(int tileSize, List<TreeTile> tiles, Map<String, Map<Direction, Set<String>>> adjacencyRules) {
        this.tileSize = tileSize;
        this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));

        // Deep copy the adjacency rules to make them immutable
        Map<String, Map<Direction, Set<String>>> rulesCopy = new HashMap<>();
        for (Map.Entry<String, Map<Direction, Set<String>>> entry : adjacencyRules.entrySet()) {
            Map<Direction, Set<String>> dirMapCopy = new EnumMap<>(Direction.class);
            for (Map.Entry<Direction, Set<String>> dirEntry : entry.getValue().entrySet()) {
                dirMapCopy.put(dirEntry.getKey(), Collections.unmodifiableSet(new HashSet<>(dirEntry.getValue())));
            }
            rulesCopy.put(entry.getKey(), Collections.unmodifiableMap(dirMapCopy));
        }
        this.adjacencyRules = Collections.unmodifiableMap(rulesCopy);

        // Build index
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            index.put(tiles.get(i).id, i);
        }
        this.tileIdToIndex = Collections.unmodifiableMap(index);
    }

    /**
     * Gets the set of tile IDs that can be placed adjacent to the given tile in the given direction.
     */
    public Set<String> getValidNeighbors(String tileId, Direction direction) {
        Map<Direction, Set<String>> dirMap = adjacencyRules.get(tileId);
        if (dirMap == null) return Collections.emptySet();
        Set<String> neighbors = dirMap.get(direction);
        return neighbors != null ? neighbors : Collections.emptySet();
    }

    /**
     * Checks if two tiles can be adjacent in the given direction.
     */
    public boolean canBeAdjacent(String fromTileId, Direction direction, String toTileId) {
        Set<String> validNeighbors = getValidNeighbors(fromTileId, direction);
        return validNeighbors.contains(toTileId);
    }

    /**
     * Gets a tile by its ID.
     */
    public TreeTile getTile(String tileId) {
        Integer index = tileIdToIndex.get(tileId);
        return index != null ? tiles.get(index) : null;
    }

    /**
     * Gets all tile IDs.
     */
    public Set<String> getAllTileIds() {
        return tileIdToIndex.keySet();
    }

    /**
     * Adds an adjacency rule. Used during tile extraction.
     * Note: This modifies the rules map, so should only be used with a mutable builder map.
     */
    public static void addAdjacencyRule(Map<String, Map<Direction, Set<String>>> rules,
                                        String fromTileId, Direction direction, String toTileId) {
        rules.computeIfAbsent(fromTileId, k -> new EnumMap<>(Direction.class))
             .computeIfAbsent(direction, k -> new HashSet<>())
             .add(toTileId);
    }

    /**
     * Gets the number of tiles in this cache.
     */
    public int size() {
        return tiles.size();
    }

    /**
     * Checks if the cache is empty.
     */
    public boolean isEmpty() {
        return tiles.isEmpty();
    }
}
