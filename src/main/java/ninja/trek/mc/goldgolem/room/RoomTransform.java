package ninja.trek.mc.goldgolem.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import ninja.trek.mc.goldgolem.wall.WallStateTransform;

/** Cardinal rotation followed by an optional X-axis mirror. */
public record RoomTransform(int rotation, boolean mirror) {
    public RoomTransform {
        rotation = Math.floorMod(rotation, 4);
    }

    public BlockPos apply(BlockPos position) {
        int x = position.getX();
        int z = position.getZ();
        int rotatedX;
        int rotatedZ;
        switch (rotation) {
            case 1 -> {
                rotatedX = -z;
                rotatedZ = x;
            }
            case 2 -> {
                rotatedX = -x;
                rotatedZ = -z;
            }
            case 3 -> {
                rotatedX = z;
                rotatedZ = -x;
            }
            default -> {
                rotatedX = x;
                rotatedZ = z;
            }
        }
        if (mirror) rotatedX = -rotatedX;
        return new BlockPos(rotatedX, position.getY(), rotatedZ);
    }

    public Direction apply(Direction direction) {
        BlockPos vector = apply(new BlockPos(direction.getStepX(), direction.getStepY(), direction.getStepZ()));
        if (vector.getY() > 0) return Direction.UP;
        if (vector.getY() < 0) return Direction.DOWN;
        if (vector.getX() > 0) return Direction.EAST;
        if (vector.getX() < 0) return Direction.WEST;
        if (vector.getZ() > 0) return Direction.SOUTH;
        if (vector.getZ() < 0) return Direction.NORTH;
        throw new IllegalArgumentException("Cannot transform a zero direction vector");
    }

    public BlockState apply(BlockState state) {
        return WallStateTransform.forPlacement(state, rotation, mirror);
    }
}
