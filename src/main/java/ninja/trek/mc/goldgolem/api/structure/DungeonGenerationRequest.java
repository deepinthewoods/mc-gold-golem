package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, position-independent dungeon planning request. */
public final class DungeonGenerationRequest {
    private final long seed;
    private final RoomCountGoal roomCount;
    private final Block entranceMarker;
    private final DungeonBossRule bossRule;
    private final List<SpecialRoomQuota> specialRooms;
    private final DungeonLayoutProfile layout;
    private final int maxAttempts;
    private final int maxSearchSteps;

    private DungeonGenerationRequest(Builder builder) {
        seed = builder.seed;
        roomCount = Objects.requireNonNull(builder.roomCount, "roomCount");
        entranceMarker = Objects.requireNonNull(builder.entranceMarker, "entranceMarker");
        bossRule = Objects.requireNonNull(builder.bossRule, "bossRule");
        layout = Objects.requireNonNull(builder.layout, "layout");
        specialRooms = List.copyOf(builder.specialRooms);
        maxAttempts = builder.maxAttempts;
        maxSearchSteps = builder.maxSearchSteps;
        if (entranceMarker == bossRule.marker()) {
            throw new IllegalArgumentException("Entrance and boss markers must be different blocks");
        }
        Set<Block> seen = new HashSet<>();
        seen.add(entranceMarker);
        seen.add(bossRule.marker());
        for (SpecialRoomQuota quota : specialRooms) {
            if (!seen.add(quota.marker())) {
                throw new IllegalArgumentException("Dungeon marker blocks must be unique");
            }
            if (quota.maximumRooms() > roomCount.maximum()) {
                throw new IllegalArgumentException("Special-room maximum exceeds the dungeon room maximum");
            }
        }
        if (bossRule.minimumEntranceDistance() >= roomCount.maximum()) {
            throw new IllegalArgumentException("Boss entrance distance must be smaller than the room maximum");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public long seed() { return seed; }
    public RoomCountGoal roomCount() { return roomCount; }
    public Block entranceMarker() { return entranceMarker; }
    public DungeonBossRule bossRule() { return bossRule; }
    public List<SpecialRoomQuota> specialRooms() { return specialRooms; }
    public DungeonLayoutProfile layout() { return layout; }
    public int maxAttempts() { return maxAttempts; }
    public int maxSearchSteps() { return maxSearchSteps; }

    public static final class Builder {
        private long seed;
        private RoomCountGoal roomCount;
        private Block entranceMarker;
        private DungeonBossRule bossRule;
        private final List<SpecialRoomQuota> specialRooms = new ArrayList<>();
        private DungeonLayoutProfile layout = DungeonLayoutProfile.defaults(DungeonLayoutStyle.ORGANIC);
        private int maxAttempts = 24;
        private int maxSearchSteps = 250_000;

        public Builder seed(long value) { seed = value; return this; }
        public Builder roomCount(RoomCountGoal value) { roomCount = value; return this; }
        public Builder entranceMarker(Block value) { entranceMarker = value; return this; }
        public Builder bossRule(DungeonBossRule value) { bossRule = value; return this; }
        public Builder addSpecialRoom(SpecialRoomQuota value) {
            specialRooms.add(Objects.requireNonNull(value, "value"));
            return this;
        }
        public Builder specialRooms(List<SpecialRoomQuota> value) {
            specialRooms.clear();
            if (value != null) specialRooms.addAll(value);
            return this;
        }
        public Builder layout(DungeonLayoutProfile value) { layout = value; return this; }
        public Builder searchLimits(int attempts, int stepsPerAttempt) {
            if (attempts < 1 || attempts > 256 || stepsPerAttempt < 100 || stepsPerAttempt > 5_000_000) {
                throw new IllegalArgumentException("Search limits are outside supported bounds");
            }
            maxAttempts = attempts;
            maxSearchSteps = stepsPerAttempt;
            return this;
        }
        public DungeonGenerationRequest build() { return new DungeonGenerationRequest(this); }
    }
}
