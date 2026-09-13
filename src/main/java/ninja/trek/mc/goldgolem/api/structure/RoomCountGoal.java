package ninja.trek.mc.goldgolem.api.structure;

/** Preferred dungeon size with an allowed fallback range. */
public record RoomCountGoal(int minimum, int target, int maximum) {
    public RoomCountGoal {
        if (minimum < 2 || target < minimum || maximum < target || maximum > 150) {
            throw new IllegalArgumentException("Room counts must satisfy 2 <= minimum <= target <= maximum <= 150");
        }
    }
}
