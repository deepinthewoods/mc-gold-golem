package ninja.trek.mc.goldgolem.api.structure;

import java.util.Objects;

/** A preset plus normalized scoring goals used to choose between valid layouts. */
public record DungeonLayoutProfile(
        DungeonLayoutStyle style,
        double compactness,
        double branchiness,
        double loopiness,
        double deadEndFrequency,
        double criticalPathLength
) {
    public DungeonLayoutProfile {
        Objects.requireNonNull(style, "style");
        checkUnit("compactness", compactness);
        checkUnit("branchiness", branchiness);
        checkUnit("loopiness", loopiness);
        checkUnit("deadEndFrequency", deadEndFrequency);
        checkUnit("criticalPathLength", criticalPathLength);
    }

    public static Builder builder(DungeonLayoutStyle style) {
        return new Builder(style);
    }

    public static DungeonLayoutProfile defaults(DungeonLayoutStyle style) {
        return builder(style).build();
    }

    private static void checkUnit(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }

    public static final class Builder {
        private final DungeonLayoutStyle style;
        private double compactness;
        private double branchiness;
        private double loopiness;
        private double deadEndFrequency;
        private double criticalPathLength;

        private Builder(DungeonLayoutStyle style) {
            this.style = Objects.requireNonNull(style, "style");
            switch (style) {
                case LOOP_WITH_SPURS -> set(0.70, 0.30, 0.65, 0.25, 0.55);
                case BRANCHING -> set(0.40, 0.80, 0.10, 0.65, 0.65);
                case BRAIDED -> set(0.70, 0.65, 0.90, 0.10, 0.55);
                case HUB_AND_SPOKE -> set(0.65, 0.75, 0.45, 0.35, 0.60);
                case GAUNTLET -> set(0.35, 0.15, 0.15, 0.20, 0.85);
                case ORGANIC -> set(0.50, 0.50, 0.40, 0.35, 0.60);
            }
        }

        private void set(double compactness, double branchiness, double loopiness,
                         double deadEndFrequency, double criticalPathLength) {
            this.compactness = compactness;
            this.branchiness = branchiness;
            this.loopiness = loopiness;
            this.deadEndFrequency = deadEndFrequency;
            this.criticalPathLength = criticalPathLength;
        }

        public Builder compactness(double value) {
            compactness = value;
            return this;
        }

        public Builder branchiness(double value) {
            branchiness = value;
            return this;
        }

        public Builder loopiness(double value) {
            loopiness = value;
            return this;
        }

        public Builder deadEndFrequency(double value) {
            deadEndFrequency = value;
            return this;
        }

        public Builder criticalPathLength(double value) {
            criticalPathLength = value;
            return this;
        }

        public DungeonLayoutProfile build() {
            return new DungeonLayoutProfile(style, compactness, branchiness, loopiness,
                    deadEndFrequency, criticalPathLength);
        }
    }
}
