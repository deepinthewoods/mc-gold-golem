package ninja.trek.mc.goldgolem.client.state;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.Vec3;

public final class ClientState {
    public static final class LineData {
        public final List<Vec3> points;
        public final java.util.Optional<Vec3> anchor;
        public LineData(List<Vec3> pts, java.util.Optional<Vec3> anc) {
            this.points = pts;
            this.anchor = anc == null ? java.util.Optional.empty() : anc;
        }
    }

    private static final Map<Integer, LineData> LINES = new ConcurrentHashMap<>();

    private ClientState() {}

    public static void setLines(int entityId, List<Vec3> points, java.util.Optional<Vec3> anchor) {
        if (points == null) {
            LINES.remove(entityId);
            return;
        }
        // Store even empty lists so the renderer can draw previews
        LINES.put(entityId, new LineData(points, anchor));
    }

    public static LineData getLineData(int entityId) {
        return LINES.get(entityId);
    }

    public static Map<Integer, LineData> getAllLineData() {
        return new java.util.HashMap<>(LINES);
    }
}
