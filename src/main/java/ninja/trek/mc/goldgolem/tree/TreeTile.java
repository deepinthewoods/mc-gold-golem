package ninja.trek.mc.goldgolem.tree;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;

/**
 * Represents a single NxNxN tile pattern extracted from an input module.
 * Stores block states in a 3D array and supports rotation around the Y-axis.
 */
public final class TreeTile {
    public final String id; // unique identifier for this tile pattern (includes rotation)
    public final int size; // N (e.g., 3 or 5)
    public final BlockState[][][] blocks; // [x][y][z] array of block states
    public final int rotation; // 0, 90, 180, 270 degrees around Y-axis

    public TreeTile(String id, int size, BlockState[][][] blocks, int rotation) {
        this.id = id;
        this.size = size;
        this.blocks = blocks;
        this.rotation = rotation % 360;
    }

    /**
     * Gets the block state at the specified position within the tile.
     * Coordinates are 0-indexed from the tile's origin.
     */
    public BlockState getBlock(int x, int y, int z) {
        if (x < 0 || x >= size || y < 0 || y >= size || z < 0 || z >= size) {
            return Blocks.AIR.getDefaultState();
        }
        return blocks[x][y][z];
    }

    /**
     * Creates a rotated copy of this tile.
     * Rotation is around the Y-axis (clockwise when viewed from above).
     */
    public TreeTile rotateY(int degrees) {
        int newRotation = (rotation + degrees) % 360;
        int times = (degrees / 90) % 4;
        if (times < 0) times += 4;

        BlockState[][][] newBlocks = new BlockState[size][size][size];

        for (int i = 0; i < times; i++) {
            // Rotate 90 degrees clockwise: (x, y, z) -> (z, y, size-1-x)
            BlockState[][][] temp = new BlockState[size][size][size];
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        BlockState state = (i == 0) ? blocks[x][y][z] : newBlocks[x][y][z];
                        temp[z][y][size - 1 - x] = state;
                    }
                }
            }
            newBlocks = temp;
        }

        String newId = id.replaceFirst("_r\\d+$", "") + "_r" + newRotation;
        return new TreeTile(newId, size, newBlocks, newRotation);
    }

    /**
     * Checks if this tile contains only air blocks.
     */
    public boolean isEmpty() {
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    if (!blocks[x][y][z].isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Generates a hash code based on the block pattern (ignoring tile ID and rotation).
     */
    public int patternHash() {
        int hash = size;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    hash = hash * 31 + blocks[x][y][z].hashCode();
                }
            }
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TreeTile other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TreeTile{id='" + id + "', size=" + size + ", rotation=" + rotation + "}";
    }
}
