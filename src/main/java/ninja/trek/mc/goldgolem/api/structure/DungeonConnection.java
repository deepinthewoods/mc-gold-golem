package ninja.trek.mc.goldgolem.api.structure;

/** A paired socket edge in the planned room graph. */
public record DungeonConnection(int firstRoom, int firstSocket, int secondRoom, int secondSocket) {
    public DungeonConnection {
        if (firstRoom < 0 || secondRoom < 0 || firstSocket < 0 || secondSocket < 0
                || firstRoom == secondRoom) {
            throw new IllegalArgumentException("Invalid dungeon connection");
        }
    }
}
