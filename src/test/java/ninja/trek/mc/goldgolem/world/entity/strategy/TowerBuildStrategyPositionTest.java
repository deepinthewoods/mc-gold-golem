package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TowerBuildStrategyPositionTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void towerStartsAtCapturedTemplateElevation() {
        BlockPos origin = new BlockPos(10, 64, 10);
        TowerModuleTemplate template = new TowerModuleTemplate(List.of(
                new TowerModuleTemplate.Voxel(
                        new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState())), 0, 0);
        TowerBuildStrategy strategy = new TowerBuildStrategy();

        assertEquals(List.of(origin), strategy.getLayerVoxels(null, template, origin, 0));
        assertEquals(0, strategy.getLayerY(template, origin, origin));
    }

    @Test
    void pyramidRetainsBaseInclusiveElevation() {
        BlockPos origin = new BlockPos(10, 64, 10);
        TowerModuleTemplate template = new TowerModuleTemplate(List.of(
                new TowerModuleTemplate.Voxel(
                        new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState())), 0, 0);
        PyramidBuildStrategy strategy = new PyramidBuildStrategy();

        assertEquals(63, strategy.getBuildBaseY(template, origin));
    }

    @Test
    void legacyReloadReconstructsWindowAroundCurrentLayer() {
        TowerBuildStrategy.LayerWindow window = TowerBuildStrategy.inferLegacyLayerWindow(7, true, 20);

        assertEquals(7, window.lowestLoadedY());
        assertEquals(9, window.highestLoadedY());
    }

    @Test
    void legacyReloadAtFinalLayerDoesNotWrapBackToBottom() {
        TowerBuildStrategy.LayerWindow window = TowerBuildStrategy.inferLegacyLayerWindow(20, true, 20);

        assertEquals(20, window.lowestLoadedY());
        assertEquals(19, window.highestLoadedY());
    }
}
