package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/** Immutable, world-independent dungeon solution. */
public final class DungeonPlan {
    private final GoldGolemTemplate template;
    private final DungeonGenerationRequest request;
    private final List<PlannedDungeonRoom> rooms;
    private final List<DungeonConnection> connections;
    private final int entranceRoom;
    private final int bossRoom;
    private final BlockPos entranceMarker;
    private final DungeonBounds bounds;
    private final DungeonPlanStats stats;

    DungeonPlan(GoldGolemTemplate template, DungeonGenerationRequest request,
                List<PlannedDungeonRoom> rooms, List<DungeonConnection> connections,
                int entranceRoom, int bossRoom, BlockPos entranceMarker,
                DungeonBounds bounds, DungeonPlanStats stats) {
        this.template = Objects.requireNonNull(template, "template");
        this.request = Objects.requireNonNull(request, "request");
        this.rooms = List.copyOf(rooms);
        this.connections = List.copyOf(connections);
        this.entranceRoom = entranceRoom;
        this.bossRoom = bossRoom;
        this.entranceMarker = entranceMarker.immutable();
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    public List<PlannedDungeonRoom> rooms() { return rooms; }
    public List<DungeonConnection> connections() { return connections; }
    public int entranceRoom() { return entranceRoom; }
    public int bossRoom() { return bossRoom; }
    public BlockPos entranceMarker() { return entranceMarker; }
    public DungeonBounds bounds() { return bounds; }
    public DungeonPlanStats stats() { return stats; }
    GoldGolemTemplate template() { return template; }
    DungeonGenerationRequest request() { return request; }
}
