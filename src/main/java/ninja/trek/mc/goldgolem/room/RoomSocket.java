package ninja.trek.mc.goldgolem.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.LinkedHashSet;
import java.util.Set;

/** A standardized gold-framed doorway in room-local coordinates. */
public record RoomSocket(
        BlockPos anchor,
        Direction facing,
        int apertureWidth,
        int apertureHeight,
        boolean floorEdge
) {
    public RoomSocket {
        if (anchor == null) throw new IllegalArgumentException("Room socket anchor is required");
        if (facing == null || facing == Direction.UP || facing == Direction.DOWN) {
            throw new IllegalArgumentException("Room socket facing must be horizontal");
        }
        if (apertureWidth < 1 || apertureHeight < 1) {
            throw new IllegalArgumentException("Room socket aperture dimensions must be positive");
        }
    }

    /** Direction across the aperture from its anchor, viewed from inside the room. */
    public Direction right() {
        return rightOf(facing);
    }

    public Set<BlockPos> aperturePositions() {
        Set<BlockPos> result = new LinkedHashSet<>();
        Direction right = right();
        for (int y = 0; y < apertureHeight; y++) {
            for (int u = 0; u < apertureWidth; u++) {
                result.add(anchor.relative(right, u).above(y));
            }
        }
        return Set.copyOf(result);
    }

    public Set<BlockPos> framePositions() {
        Set<BlockPos> result = new LinkedHashSet<>();
        Direction right = right();
        BlockPos leftSide = anchor.relative(right.getOpposite());
        BlockPos rightSide = anchor.relative(right, apertureWidth);
        for (int y = 0; y < apertureHeight; y++) {
            result.add(leftSide.above(y));
            result.add(rightSide.above(y));
        }
        for (int u = -1; u <= apertureWidth; u++) {
            result.add(anchor.relative(right, u).above(apertureHeight));
            if (floorEdge) result.add(anchor.relative(right, u).below());
        }
        return Set.copyOf(result);
    }

    /**
     * Complete seam that two rooms may share. U-frames include the ordinary floor line here even
     * though that line is not part of their gold frame.
     */
    public Set<BlockPos> connectionPositions() {
        Set<BlockPos> result = new LinkedHashSet<>(framePositions());
        result.addAll(aperturePositions());
        Direction right = right();
        for (int u = -1; u <= apertureWidth; u++) {
            result.add(anchor.relative(right, u).below());
        }
        return Set.copyOf(result);
    }

    public RoomSocket transformed(RoomTransform transform) {
        Direction transformedFacing = transform.apply(facing);
        Direction transformedRight = transform.apply(right());
        BlockPos transformedAnchor = transform.apply(anchor);
        Direction canonicalRight = rightOf(transformedFacing);
        if (transformedRight == canonicalRight.getOpposite()) {
            transformedAnchor = transform.apply(anchor.relative(right(), apertureWidth - 1));
        } else if (transformedRight != canonicalRight) {
            throw new IllegalStateException("Room transform produced a non-cardinal socket");
        }
        return new RoomSocket(
                transformedAnchor,
                transformedFacing,
                apertureWidth,
                apertureHeight,
                floorEdge
        );
    }

    public RoomSocket moved(BlockPos offset) {
        return new RoomSocket(anchor.offset(offset), facing, apertureWidth, apertureHeight, floorEdge);
    }

    public boolean compatibleWith(RoomSocket other) {
        return other != null
                && apertureWidth == other.apertureWidth
                && apertureHeight == other.apertureHeight;
    }

    public static Direction rightOf(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> throw new IllegalArgumentException("Direction must be horizontal: " + facing);
        };
    }
}
