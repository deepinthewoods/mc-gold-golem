package ninja.trek.mc.goldgolem.api.structure;

import java.util.List;

/** Optional contour-following material surrounding the union of room bounds. */
public record DungeonEnvelope(List<DungeonEnvelopeLayer> layers) {
    public static final DungeonEnvelope NONE = new DungeonEnvelope(List.of());

    public DungeonEnvelope {
        layers = layers == null ? List.of() : List.copyOf(layers);
        int total = layers.stream().mapToInt(DungeonEnvelopeLayer::thickness).sum();
        if (total > 64) throw new IllegalArgumentException("Total envelope thickness cannot exceed 64");
    }

    public static DungeonEnvelope of(DungeonEnvelopeLayer... layers) {
        return new DungeonEnvelope(layers == null ? List.of() : List.of(layers));
    }

    public boolean enabled() {
        return !layers.isEmpty();
    }
}
