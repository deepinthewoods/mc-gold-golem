package ninja.trek.mc.goldgolem.wall;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Applies the state transform used when a captured wall module is placed. */
public final class WallStateTransform {
    private WallStateTransform() {}

    public static BlockState forPlacement(BlockState state, int rotation, boolean mirror) {
        Rotation blockRotation = switch (rotation & 3) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };

        BlockState transformed = state.rotate(blockRotation);
        if (mirror) transformed = transformed.mirror(Mirror.FRONT_BACK);
        if (transformed.hasProperty(BlockStateProperties.WATERLOGGED)) {
            transformed = transformed.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE);
        }
        return transformed;
    }
}
