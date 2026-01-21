package ninja.trek.mc.goldgolem.world.entity.strategy.wall;

/**
 * Represents a join slice entry for wall modules.
 * Extracted from GoldGolemEntity inner class.
 */
public final class JoinEntry {
    public final int dy;
    public final int du;
    public final String id;

    public JoinEntry(int dy, int du, String id) {
        this.dy = dy;
        this.du = du;
        this.id = id == null ? "" : id;
    }
}
