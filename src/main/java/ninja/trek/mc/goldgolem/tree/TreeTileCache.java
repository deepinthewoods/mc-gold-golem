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
    public final Map<Long, Set<String>> adjacencyRules; // adjacency key -> valid tile IDs

    public TreeTileCache(int tileSize, List<TreeTile> tiles, Map<Long, Set<String>> adjacencyRules) {
        this.tileSize = tileSize;
        this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));
        this.adjacencyRules = Collections.unmodifiableMap(new HashMap<>(adjacencyRules));

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
        long key = encodeAdjacencyKey(tileId, direction);
        return adjacencyRules.getOrDefault(key, Collections.emptySet());
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
     * Encodes a tile ID and direction into a unique key for adjacency lookup.
     */
    public static long encodeAdjacencyKey(String tileId, Direction direction) {
        // Simple hash-based encoding
        long tileHash = tileId.hashCode() & 0xFFFFFFFFL;
        long dirOrdinal = direction.ordinal() & 0xFFL;
        return (tileHash << 8) | dirOrdinal;
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
