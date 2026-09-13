package ninja.trek.mc.goldgolem.world.entity.strategy.path;

import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LineSegPlacementTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void consumesAllNoOpCellsWithinProgressBoundInOneCall() {
        Level world = mock(Level.class);
        GoldGolemEntity golem = mock(GoldGolemEntity.class);
        when(golem.getPathWidth()).thenReturn(1);
        when(golem.level()).thenReturn(world);
        when(world.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
        when(golem.placeOffsetAt(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyInt(), anyInt(), anyBoolean(), any())).thenReturn(false);

        LineSeg line = new LineSeg(new Vec3(0.5, 65, 0.5), new Vec3(3.5, 65, 0.5));
        line.begin(golem);

        assertNull(line.placeNextBlock(golem, line.cells.size() - 1));
        assertTrue(line.isFullyProcessed());
        verify(golem, times(line.totalBits)).placeOffsetAt(anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyInt(), anyInt(), anyBoolean(), any());
    }
}
