package ninja.trek.mc.goldgolem.tower;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyramidResamplerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void linearSquareShrinksToApex() {
        TowerModuleTemplate template = square(5);
        assertEquals(3, PyramidResampler.defaultHeight(template));
        var base = PyramidResampler.buildLayer(template, BlockPos.ZERO, 0, 3, 0, List.of("minecraft:stone"));
        var middle = PyramidResampler.buildLayer(template, BlockPos.ZERO, 1, 3, 0, List.of("minecraft:stone"));
        var apex = PyramidResampler.buildLayer(template, BlockPos.ZERO, 2, 3, 0, List.of("minecraft:stone"));
        assertEquals(25, base.size());
        assertTrue(middle.size() < base.size());
        assertEquals(1, apex.size());
    }

    @Test
    void oneLayerBuildPreservesCapturedBase() {
        TowerModuleTemplate template = square(5);
        var onlyLayer = PyramidResampler.buildLayer(template, BlockPos.ZERO, 0, 1, 0,
                List.of("minecraft:stone"));
        assertEquals(25, onlyLayer.size());
    }

    @Test
    void heightIncludesSupportingBaseLayer() {
        TowerModuleTemplate template = square(3);
        BlockPos origin = new BlockPos(10, 64, -3);

        var base = PyramidResampler.buildLayer(template, origin, 0, 3, 0, List.of("minecraft:stone"));
        var top = PyramidResampler.buildLayer(template, origin, 2, 3, 0, List.of("minecraft:stone"));

        assertTrue(base.keySet().stream().allMatch(pos -> pos.getY() == 63));
        assertTrue(top.keySet().stream().allMatch(pos -> pos.getY() == 65));
    }

    @Test
    void priorityBreaksEqualMaterialVote() {
        List<TowerModuleTemplate.Voxel> voxels = List.of(
                new TowerModuleTemplate.Voxel(new BlockPos(-1, 0, 0), Blocks.STONE.defaultBlockState()),
                new TowerModuleTemplate.Voxel(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState()));
        TowerModuleTemplate template = new TowerModuleTemplate(voxels, 0, 0);
        var layer = PyramidResampler.buildLayer(template, BlockPos.ZERO, 1, 2, 0,
                List.of("minecraft:dirt", "minecraft:stone"));
        assertEquals(1, layer.size());
        assertTrue(layer.values().stream().allMatch(state -> state.is(Blocks.DIRT)));
    }

    @Test
    void positiveCurvatureStaysWiderThanNegativeCurvature() {
        TowerModuleTemplate template = square(9);
        int dome = PyramidResampler.buildLayer(template, BlockPos.ZERO, 2, 5, 100,
                List.of("minecraft:stone")).size();
        int pointy = PyramidResampler.buildLayer(template, BlockPos.ZERO, 2, 5, -100,
                List.of("minecraft:stone")).size();
        assertTrue(dome > pointy);
    }

    @Test
    void evenFootprintKeepsItsSymmetricSmallestApex() {
        List<TowerModuleTemplate.Voxel> voxels = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                voxels.add(new TowerModuleTemplate.Voxel(new BlockPos(x, 0, z),
                        Blocks.STONE.defaultBlockState()));
            }
        }
        TowerModuleTemplate template = new TowerModuleTemplate(voxels, 0, 0);
        var apex = PyramidResampler.buildLayer(template, BlockPos.ZERO, 2, 3, 0,
                List.of("minecraft:stone"));
        assertEquals(4, apex.size());
    }

    @Test
    void sourceSlicesRepeatWithoutResettingTaper() {
        TowerModuleTemplate template = new TowerModuleTemplate(List.of(
                new TowerModuleTemplate.Voxel(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState()),
                new TowerModuleTemplate.Voxel(new BlockPos(0, 1, 0), Blocks.DIRT.defaultBlockState())), 0, 1);
        var layer0 = PyramidResampler.buildLayer(template, BlockPos.ZERO, 0, 4, 0,
                List.of("minecraft:stone", "minecraft:dirt"));
        var layer1 = PyramidResampler.buildLayer(template, BlockPos.ZERO, 1, 4, 0,
                List.of("minecraft:stone", "minecraft:dirt"));
        var layer2 = PyramidResampler.buildLayer(template, BlockPos.ZERO, 2, 4, 0,
                List.of("minecraft:stone", "minecraft:dirt"));
        assertTrue(layer0.values().stream().allMatch(state -> state.is(Blocks.STONE)));
        assertTrue(layer1.values().stream().allMatch(state -> state.is(Blocks.DIRT)));
        assertTrue(layer2.values().stream().allMatch(state -> state.is(Blocks.STONE)));
    }

    private static TowerModuleTemplate square(int size) {
        List<TowerModuleTemplate.Voxel> voxels = new ArrayList<>();
        int half = size / 2;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                voxels.add(new TowerModuleTemplate.Voxel(new BlockPos(x, 0, z),
                        Blocks.STONE.defaultBlockState()));
            }
        }
        return new TowerModuleTemplate(voxels, 0, 0);
    }
}
