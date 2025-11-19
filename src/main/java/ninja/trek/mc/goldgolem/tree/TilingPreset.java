package ninja.trek.mc.goldgolem.tree;

/**
 * Tiling preset defining the NxNxN cube size for tile extraction.
 * Extensible to support additional preset sizes in the future.
 */
public enum TilingPreset {
    SMALL_3x3(3, "3x3"),
    LARGE_5x5(5, "5x5");

    private final int size;
    private final String displayName;

    TilingPreset(int size, String displayName) {
        this.size = size;
        this.displayName = displayName;
    }

    public int getSize() {
        return size;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static TilingPreset fromSize(int size) {
        for (TilingPreset preset : values()) {
            if (preset.size == size) {
                return preset;
            }
        }
        return SMALL_3x3; // default
    }

    public static TilingPreset fromOrdinal(int ordinal) {
        if (ordinal >= 0 && ordinal < values().length) {
            return values()[ordinal];
        }
        return SMALL_3x3; // default
    }
}
