package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.tree.TilingPreset;
import ninja.trek.mc.goldgolem.wall.WallJoinSlice;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StructureGeneratorsTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void towerRepeatsModuleToRequestedBlockHeight() {
        TowerModuleTemplate module = new TowerModuleTemplate(
                List.of(
                        new TowerModuleTemplate.Voxel(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState()),
                        new TowerModuleTemplate.Voxel(new BlockPos(0, 1, 0), Blocks.OAK_PLANKS.defaultBlockState())
                ),
                0,
                1
        );
        GoldGolemTemplate template = towerTemplate(TemplateMode.TOWER, module);

        StructureGenerators.GeneratedPlan plan = StructureGenerators.generate(
                null, template, new TowerBuildRequest(new BlockPos(10, 64, 10), 5));

        assertEquals(5, plan.blocks().size());
        assertEquals(Blocks.STONE, plan.blocks().get(new BlockPos(10, 63, 10)).getBlock());
        assertEquals(Blocks.OAK_PLANKS, plan.blocks().get(new BlockPos(10, 64, 10)).getBlock());
        assertEquals(Blocks.STONE, plan.blocks().get(new BlockPos(10, 67, 10)).getBlock());
        assertFalse(plan.capped());
    }

    @Test
    void pyramidProducesEveryRequestedLayer() {
        TowerModuleTemplate module = new TowerModuleTemplate(
                List.of(new TowerModuleTemplate.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState())),
                0,
                0
        );
        GoldGolemTemplate template = towerTemplate(TemplateMode.PYRAMID, module);

        StructureGenerators.GeneratedPlan plan = StructureGenerators.generate(
                null, template, new PyramidBuildRequest(new BlockPos(0, 80, 0), 3, 0));

        assertEquals(3, plan.blocks().size());
    }

    @Test
    void towerAppliesGradientAndTreatsMineAsNoPlacement() {
        TowerModuleTemplate module = new TowerModuleTemplate(
                List.of(new TowerModuleTemplate.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState())),
                0,
                0
        );
        GradientConfig gradient = new GradientConfig(
                java.util.Map.of("minecraft:stone", 0),
                List.of(List.of("minecraft:oak_planks", "gold-golem:mine/minecraft/iron_pickaxe")),
                List.of(0.0f),
                List.of(1)
        );
        GoldGolemTemplate template = new GoldGolemTemplate(
                1, TemplateMode.TOWER, List.of(), module, List.of(), TilingPreset.SMALL_3x3,
                null, List.of(), gradient, 2, 0);

        StructureGenerators.GeneratedPlan plan = StructureGenerators.generate(
                null, template, new TowerBuildRequest(new BlockPos(0, 64, 0), 2));

        assertEquals(1, plan.blocks().size());
        assertEquals(Blocks.OAK_PLANKS, plan.blocks().get(new BlockPos(0, 63, 0)).getBlock());
    }

    @Test
    void wallFollowsOrderedGuideWithCompatibleModules() {
        WallModuleTemplate module = new WallModuleTemplate(
                BlockPos.ZERO,
                new BlockPos(2, 0, 0),
                List.of(
                        new WallModuleTemplate.Voxel(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState()),
                        new WallModuleTemplate.Voxel(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState()),
                        new WallModuleTemplate.Voxel(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState())
                ),
                0,
                WallJoinSlice.Axis.X_THICK,
                WallJoinSlice.Axis.X_THICK
        );
        GoldGolemTemplate template = new GoldGolemTemplate(
                1, TemplateMode.WALL, List.of(module), null, List.of(), TilingPreset.SMALL_3x3,
                null, List.of(), 1, 0);

        StructureGenerators.GeneratedPlan plan = StructureGenerators.generate(
                null,
                template,
                new WallBuildRequest(List.of(new BlockPos(0, 64, 0), new BlockPos(7, 64, 0)), 4)
        );

        assertEquals(7, plan.blocks().size());
        assertEquals(Blocks.STONE, plan.blocks().get(new BlockPos(6, 64, 0)).getBlock());
    }

    private static GoldGolemTemplate towerTemplate(TemplateMode mode, TowerModuleTemplate module) {
        return new GoldGolemTemplate(
                1, mode, List.of(), module, List.of(), TilingPreset.SMALL_3x3,
                null, List.of("minecraft:stone"), 4, 0);
    }
}
