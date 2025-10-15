package ninja.trek.mc.goldgolem.client.state;

import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientState {
    public static final class LineData {
        public final List<Vec3d> points;
        public final java.util.Optional<Vec3d> anchor;
        public LineData(List<Vec3d> pts, java.util.Optional<Vec3d> anc) {
            this.points = pts;
            this.anchor = anc == null ? java.util.Optional.empty() : anc;
        }
    }

    private static final Map<Integer, LineData> LINES = new ConcurrentHashMap<>();

    private ClientState() {}

    public static void setLines(int entityId, List<Vec3d> points, java.util.Optional<Vec3d> anchor) {
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
