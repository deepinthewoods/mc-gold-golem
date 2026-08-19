package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.world.level.block.Block;

import java.util.Map;

/** Useful topology and quota measurements for inspecting a plan. */
public record DungeonPlanStats(
        int roomCount,
        int connectionCount,
        int loopCount,
        int deadEndCount,
        int criticalPathLength,
        Map<Block, Integer> specialRoomCounts,
        double styleScore
) {
    public DungeonPlanStats {
        specialRoomCounts = specialRoomCounts == null ? Map.of() : Map.copyOf(specialRoomCounts);
    }
}
