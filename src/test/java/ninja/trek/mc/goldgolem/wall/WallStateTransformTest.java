package ninja.trek.mc.goldgolem.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WallStateTransformTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void transformsDirectionalPropertiesUsingThePlacementTransform() {
        BlockState original = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);

        BlockState transformed = WallStateTransform.forPlacement(original, 1, true);

        assertEquals(original.rotate(Rotation.CLOCKWISE_90).mirror(Mirror.FRONT_BACK), transformed);
    }

    @Test
    void clearsWaterloggedToMatchPlacedState() {
        BlockState waterlogged = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, Boolean.TRUE);

        BlockState transformed = WallStateTransform.forPlacement(waterlogged, 0, false);

        assertFalse(transformed.getValue(BlockStateProperties.WATERLOGGED));
    }
}
