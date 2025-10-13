package ninja.trek.mc.goldgolem.client.state;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientState {
    private static final Map<Integer, List<BlockPos>> LINES = new ConcurrentHashMap<>();

    private ClientState() {}

    public static void setLines(int entityId, List<BlockPos> points) {
        if (points == null || points.isEmpty()) {
            LINES.remove(entityId);
        } else {
            LINES.put(entityId, points);
        }
    }

    public static List<BlockPos> getLines(int entityId) {
        return LINES.get(entityId);
    }

    public static Map<Integer, List<BlockPos>> getAllLines() {
        return new java.util.HashMap<>(LINES);
    }
}
