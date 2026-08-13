package ninja.trek.mc.goldgolem.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

class WallJoinSliceTest {
    private static final WallJoinSlice.Point ORIGIN = new WallJoinSlice.Point(0, 0);

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void profileComparisonIncludesDirectionalBlockProperties() {
        WallJoinSlice north = slice(stairs(Direction.NORTH));
        WallJoinSlice south = slice(stairs(Direction.SOUTH));

        assertFalse(north.profileEquals(south));
    }

    @Test
    void transformedProfileUsesTheSameStateTransformAsPlacement() {
        BlockState originalState = stairs(Direction.NORTH);
        WallJoinSlice original = slice(originalState);

        WallJoinSlice transformed = original.transformed(1, true);

        BlockState expected = originalState.rotate(Rotation.CLOCKWISE_90).mirror(Mirror.FRONT_BACK);
        assertEquals(WallJoinSlice.Axis.X_THICK, transformed.axis);
        assertEquals(expected, transformed.blockStates.get(ORIGIN));
        assertTrue(original.matches(transformed));
    }

    @Test
    void legacyIdOnlyProfilesRemainCompatibleWithFullStateProfiles() {
        WallJoinSlice full = slice(stairs(Direction.WEST));
        WallJoinSlice legacy = WallJoinSlice.fromData(WallJoinSlice.Axis.Z_THICK,
                Set.of(ORIGIN), Map.of(ORIGIN, "minecraft:oak_stairs"));

        assertTrue(full.profileEquals(legacy));
    }

    private static WallJoinSlice slice(BlockState state) {
        return WallJoinSlice.fromData(WallJoinSlice.Axis.Z_THICK, Set.of(ORIGIN),
                Map.of(ORIGIN, WallJoinSlice.serializeState(state)));
    }

    private static BlockState stairs(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }
}
