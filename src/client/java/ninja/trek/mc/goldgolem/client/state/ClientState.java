package ninja.trek.mc.goldgolem.client.state;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.Vec3;

public final class ClientState {
    public static final class LineData {
        public final List<Vec3> points;
        public final java.util.Optional<Vec3> anchor;
        public final boolean noValid;
        public LineData(List<Vec3> pts, java.util.Optional<Vec3> anc, boolean noValid) {
            this.points = pts;
            this.anchor = anc == null ? java.util.Optional.empty() : anc;
            this.noValid = noValid;
        }
    }

    private static final Map<Integer, LineData> LINES = new ConcurrentHashMap<>();

    private ClientState() {}

    public static void setLines(int entityId, List<Vec3> points, java.util.Optional<Vec3> anchor, boolean noValid) {
        if (points == null || (points.isEmpty() && (anchor == null || anchor.isEmpty()) && !noValid)) {
            LINES.remove(entityId);
            return;
        }
        // Keep empty lists only when they carry an active preview or validation state.
        LINES.put(entityId, new LineData(points, anchor, noValid));
    }

    public static LineData getLineData(int entityId) {
        return LINES.get(entityId);
    }

    public static Map<Integer, LineData> getAllLineData() {
        return new java.util.HashMap<>(LINES);
    }
}
