package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PathSurfacePlacementTest {
    private static final BlockPos GROUND = new BlockPos(0, 64, 0);
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private GoldGolemEntity golem;
    private String[] main;
    private String[] surface;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUpColumn() throws ReflectiveOperationException {
        Level world = mock(Level.class);
        golem = mock(GoldGolemEntity.class);
        main = new String[9];
        surface = new String[9];
        setGradientField("gradient", main);
        setGradientField("surfaceGradient", surface);
        setGradientField("stepGradient", new String[9]);
        blocks.put(GROUND, Blocks.STONE.defaultBlockState());

        when(golem.level()).thenReturn(world);
        when(golem.getBoundingBox()).thenReturn(new AABB(10, 65, 10, 11, 67, 11));
        when(golem.recordPlaced(anyLong())).thenReturn(true);
        when(golem.consumeBlockFromInventory(anyString())).thenReturn(true);
        when(world.getBlockState(any(BlockPos.class))).thenAnswer(call ->
                blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
        when(world.setBlock(any(BlockPos.class), any(BlockState.class), eq(3))).thenAnswer(call -> {
            blocks.put(((BlockPos) call.getArgument(0)).immutable(), call.getArgument(1));
            return true;
        });
        doCallRealMethod().when(golem).placeOffsetAt(
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyInt(), anyInt(), anyBoolean(), any(Direction.class));
    }

    @Test
    void placesBottomSlabAboveUnchangedGroundWithOnlySurfaceRowPopulated() {
        surface[0] = "minecraft:stone_slab";

        placeColumn();

        assertEquals(Blocks.STONE.defaultBlockState(), blocks.get(GROUND));
        assertEquals(Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM), blocks.get(GROUND.above()));
        verify(golem).consumeBlockFromInventory("minecraft:stone_slab");
        verify(golem, times(1)).consumeBlockFromInventory(anyString());
    }

    @Test
    void placesSurfaceWhenSampledMainSlotIsEmptyButOtherMainSlotsAreConfigured() {
        main[0] = "";
        main[1] = "minecraft:cobblestone";
        surface[0] = "minecraft:stone_slab";

        placeColumn();

        assertEquals(Blocks.STONE.defaultBlockState(), blocks.get(GROUND));
        assertEquals(Blocks.STONE_SLAB.defaultBlockState(), blocks.get(GROUND.above()));
        verify(golem, never()).consumeBlockFromInventory("minecraft:cobblestone");
    }

    @Test
    void stillReplacesGroundBeforePlacingSurfaceWhenBothRowsArePopulated() {
        main[0] = "minecraft:cobblestone";
        surface[0] = "minecraft:stone_slab";

        placeColumn();

        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), blocks.get(GROUND));
        assertEquals(Blocks.STONE_SLAB.defaultBlockState(), blocks.get(GROUND.above()));
        verify(golem).consumeBlockFromInventory("minecraft:cobblestone");
        verify(golem).consumeBlockFromInventory("minecraft:stone_slab");
    }

    @Test
    void leavesColumnUnchangedWhenAllRowsAreEmpty() {
        assertFalse(placeColumn());

        assertEquals(Map.of(GROUND, Blocks.STONE.defaultBlockState()), blocks);
        verify(golem, never()).consumeBlockFromInventory(anyString());
    }

    @Test
    void mainMiningActionStillSkipsSurfacePlacement() {
        main[0] = "gold-golem:mine/minecraft/iron_pickaxe";
        surface[0] = "minecraft:stone_slab";

        placeColumn();

        assertEquals(Map.of(GROUND, Blocks.STONE.defaultBlockState()), blocks);
        verify(golem).enqueuePathMine(GROUND);
        verify(golem, never()).consumeBlockFromInventory(anyString());
    }

    @Test
    void placesFloorButtonOnSolidGroundAndDoesNotSpendAnotherOnSecondPass() {
        surface[0] = "minecraft:stone_button";

        assertTrue(placeColumn());
        assertFalse(placeColumn());

        assertEquals(Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), blocks.get(GROUND.above()));
        verify(golem, times(1)).consumeBlockFromInventory("minecraft:stone_button");
    }

    @Test
    void doesNotReplaceOrResetAnExistingPoweredButton() {
        surface[0] = "minecraft:stone_button";
        BlockState button = Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.POWERED, true);
        blocks.put(GROUND.above(), button);

        placeColumn();

        assertEquals(button, blocks.get(GROUND.above()));
        verify(golem, never()).consumeBlockFromInventory(anyString());
    }

    @Test
    void skipsButtonWhenGroundWasReplacedWithBottomSlab() {
        surface[0] = "minecraft:stone_button";
        blocks.put(GROUND, Blocks.STONE_SLAB.defaultBlockState());
        blocks.put(GROUND.below(), Blocks.STONE.defaultBlockState());
        Map<BlockPos, BlockState> before = Map.copyOf(blocks);

        placeColumn();

        assertEquals(before, blocks);
        verify(golem, never()).consumeBlockFromInventory(anyString());
        verify(golem, never()).recordPlaced(anyLong());
    }

    @Test
    void skipsButtonWhenBottomSlabAlreadyCoversGround() {
        surface[0] = "minecraft:stone_button";
        blocks.put(GROUND.above(), Blocks.STONE_SLAB.defaultBlockState());
        Map<BlockPos, BlockState> before = Map.copyOf(blocks);

        placeColumn();

        assertEquals(before, blocks);
        verify(golem, never()).consumeBlockFromInventory(anyString());
    }

    @Test
    void allowsFloorButtonOnTopSlabWithValidSupport() {
        surface[0] = "minecraft:stone_button";
        blocks.put(GROUND, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
        blocks.put(GROUND.below(), Blocks.STONE.defaultBlockState());

        placeColumn();

        assertEquals(Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), blocks.get(GROUND.above()));
        verify(golem).consumeBlockFromInventory("minecraft:stone_button");
    }

    @Test
    void placesFloorButtonOnDeepSoulSand() {
        surface[0] = "minecraft:stone_button";
        for (int y = GROUND.getY() - 8; y <= GROUND.getY(); y++) {
            blocks.put(new BlockPos(GROUND.getX(), y, GROUND.getZ()), Blocks.SOUL_SAND.defaultBlockState());
        }

        placeColumn();

        assertEquals(Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), blocks.get(GROUND.above()));
        verify(golem).consumeBlockFromInventory("minecraft:stone_button");
    }

    @Test
    void groundTargetUsesSoulSandCollisionHeight() {
        blocks.put(GROUND, Blocks.SOUL_SAND.defaultBlockState());
        doCallRealMethod().when(golem).computeGroundTargetY(any(Vec3.class));

        double targetY = golem.computeGroundTargetY(new Vec3(0.5, GROUND.getY() + 1.0, 0.5));
        double expectedY = GROUND.getY() + Blocks.SOUL_SAND.defaultBlockState()
                .getCollisionShape(golem.level(), GROUND).max(Direction.Axis.Y);

        assertEquals(expectedY, targetY);
    }

    @Test
    void doesNotStackAnotherMatchingSlabOnRepeatedPasses() {
        surface[0] = "minecraft:stone_slab";

        placeColumn();
        placeColumn();

        assertEquals(Map.of(GROUND, Blocks.STONE.defaultBlockState(),
                GROUND.above(), Blocks.STONE_SLAB.defaultBlockState()), blocks);
        verify(golem, times(1)).consumeBlockFromInventory("minecraft:stone_slab");
    }

    @Test
    void doesNotStackAnotherMatchingFullBlockOnRepeatedPasses() {
        surface[0] = "minecraft:cobblestone";

        placeColumn();
        placeColumn();

        assertEquals(Map.of(GROUND, Blocks.STONE.defaultBlockState(),
                GROUND.above(), Blocks.COBBLESTONE.defaultBlockState()), blocks);
        verify(golem, times(1)).consumeBlockFromInventory("minecraft:cobblestone");
    }

    @Test
    void skipsOtherUnsupportedDecorationsWithoutSpendingItems() {
        surface[0] = "minecraft:dandelion";

        placeColumn();

        assertEquals(Map.of(GROUND, Blocks.STONE.defaultBlockState()), blocks);
        verify(golem, never()).consumeBlockFromInventory(anyString());
    }

    @Test
    void preservesNonReplaceableDecorationAtTarget() {
        surface[0] = "minecraft:stone_button";
        blocks.put(GROUND.above(), Blocks.TORCH.defaultBlockState());

        placeColumn();

        assertEquals(Blocks.TORCH.defaultBlockState(), blocks.get(GROUND.above()));
        verify(golem, never()).consumeBlockFromInventory(anyString());
    }

    @Test
    void replacesCrimsonRootsAtSurfaceTarget() {
        surface[0] = "minecraft:stone_button";
        blocks.put(GROUND.above(), Blocks.CRIMSON_ROOTS.defaultBlockState());

        placeColumn();

        assertEquals(Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), blocks.get(GROUND.above()));
        verify(golem).consumeBlockFromInventory("minecraft:stone_button");
    }

    @Test
    void replacesHangingVineAtSurfaceTarget() {
        surface[0] = "minecraft:stone_button";
        blocks.put(GROUND.above(), Blocks.WEEPING_VINES_PLANT.defaultBlockState());

        placeColumn();

        assertEquals(Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), blocks.get(GROUND.above()));
        verify(golem).consumeBlockFromInventory("minecraft:stone_button");
    }

    @Test
    void placesButtonAtGolemFeetWithoutSpendingAnotherOnRepeatedPass() {
        surface[0] = "minecraft:stone_button";
        when(golem.getBoundingBox()).thenReturn(new AABB(0.1, 65, 0.1, 0.9, 66, 0.9));

        placeColumn();
        placeColumn();

        assertEquals(Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), blocks.get(GROUND.above()));
        verify(golem, times(1)).consumeBlockFromInventory("minecraft:stone_button");
    }

    @Test
    void placesBottomSlabWhenOnlyEmptyUpperHalfOverlapsGolem() {
        surface[0] = "minecraft:stone_slab";
        when(golem.getBoundingBox()).thenReturn(new AABB(0.1, 65.5, 0.1, 0.9, 66.5, 0.9));

        placeColumn();

        assertEquals(Blocks.STONE_SLAB.defaultBlockState(), blocks.get(GROUND.above()));
        verify(golem).consumeBlockFromInventory("minecraft:stone_slab");
    }

    @Test
    void doesNotSpendOrRecordSurfaceBlockThatWouldCollideWithGolem() {
        surface[0] = "minecraft:cobblestone";
        when(golem.getBoundingBox()).thenReturn(new AABB(0.1, 65, 0.1, 0.9, 66, 0.9));

        placeColumn();

        assertEquals(Map.of(GROUND, Blocks.STONE.defaultBlockState()), blocks);
        verify(golem, never()).consumeBlockFromInventory(anyString());
        verify(golem, never()).recordPlaced(anyLong());
    }

    private boolean placeColumn() {
        return golem.placeOffsetAt(0.5, 65.05, 0.5, 0, 1, 3, 0, true, Direction.EAST);
    }

    private void setGradientField(String name, String[] slots) throws ReflectiveOperationException {
        var field = GoldGolemEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(golem, slots);
    }
}
