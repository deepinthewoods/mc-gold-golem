package ninja.trek.mc.goldgolem.net;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Generic payload for syncing group-based mode state (Wall, Tower, Tree).
 * Replaces WallGroupsStateS2CPayload, TowerGroupsStateS2CPayload, TreeGroupsStateS2CPayload.
 *
 * Extra data contains mode-specific information serialized as alternating key-value pairs:
 * - TOWER: "blockCounts" -> (size, then alternating id,count), "height" -> int
 * - TREE: "tilingPresetOrdinal" -> int
 */
public record GroupModeStateS2CPayload(
        int entityId,
        BuildMode mode,
        List<Float> windows,
        List<Integer> noiseScales,
        List<String> flatSlots,
        Map<String, Object> extraData
) implements CustomPacketPayload {

    public GroupModeStateS2CPayload {
        windows = PayloadValidator.validateList(windows, 0, "windows");
        noiseScales = PayloadValidator.validateList(noiseScales, 0, "noiseScales");
        flatSlots = PayloadValidator.validateList(flatSlots, 0, "flatSlots");
        if (extraData == null) {
            extraData = Map.of();
        }
    }

    public static final Type<GroupModeStateS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "group_mode_state"));

    // Codec for extra data - serialize as string keys and primitive values
    // For simplicity in this refactoring, we'll use a string-based map approach
    private static final StreamCodec<RegistryFriendlyByteBuf, Map<String, Object>> EXTRA_DATA_CODEC = new StreamCodec<RegistryFriendlyByteBuf, Map<String, Object>>() {
        @Override
        public Map<String, Object> decode(RegistryFriendlyByteBuf buf) {
            Map<String, Object> map = new HashMap<>();
            int size = buf.readVarInt();
            for (int i = 0; i < size; i++) {
                String key = buf.readUtf();
                String type = buf.readUtf();
                Object value = switch (type) {
                    case "int" -> buf.readVarInt();
                    case "string" -> buf.readUtf();
                    case "block_counts" -> {
                        int count = buf.readVarInt();
                        Map<String, Integer> counts = new HashMap<>();
                        for (int j = 0; j < count; j++) {
                            String id = buf.readUtf();
                            int blockCount = buf.readVarInt();
                            counts.put(id, blockCount);
                        }
                        yield counts;
                    }
                    default -> null;
                };
                if (value != null) {
                    map.put(key, value);
                }
            }
            return map;
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, Map<String, Object> value) {
            buf.writeVarInt(value.size());
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                buf.writeUtf(entry.getKey());
                Object v = entry.getValue();
                if (v instanceof Integer) {
                    buf.writeUtf("int");
                    buf.writeVarInt((Integer) v);
                } else if (v instanceof String) {
                    buf.writeUtf("string");
                    buf.writeUtf((String) v);
                } else if (v instanceof Map<?, ?> counts) {
                    buf.writeUtf("block_counts");
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> typedCounts = (Map<String, Integer>) counts;
                    buf.writeVarInt(typedCounts.size());
                    for (Map.Entry<String, Integer> countEntry : typedCounts.entrySet()) {
                        buf.writeUtf(countEntry.getKey());
                        buf.writeVarInt(countEntry.getValue());
                    }
                }
            }
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, GroupModeStateS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GroupModeStateS2CPayload::entityId,
            BuildMode.PACKET_CODEC, GroupModeStateS2CPayload::mode,
            ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), GroupModeStateS2CPayload::windows,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), GroupModeStateS2CPayload::noiseScales,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), GroupModeStateS2CPayload::flatSlots,
            EXTRA_DATA_CODEC, GroupModeStateS2CPayload::extraData,
            GroupModeStateS2CPayload::new
    );

    @Override
    public Type<GroupModeStateS2CPayload> type() { return ID; }

    /**
     * Helper to extract block counts from extra data (TOWER mode).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> getBlockCounts() {
        Object counts = extraData.get("blockCounts");
        return counts instanceof Map ? (Map<String, Integer>) counts : Map.of();
    }

    /**
     * Helper to extract tower height from extra data (TOWER mode).
     */
    public int getTowerHeight() {
        Object height = extraData.get("height");
        return height instanceof Integer ? (Integer) height : 1;
    }

    public int getPyramidCurvature() {
        Object curvature = extraData.get("curvature");
        return curvature instanceof Integer ? (Integer) curvature : 0;
    }

    public int getRoomMemoryLimit() {
        Object limit = extraData.get("memoryLimit");
        return limit instanceof Integer ? Math.max(1, Math.min(1000, (Integer) limit)) : 100;
    }

    /**
     * Helper to extract tiling preset ordinal from extra data (TREE mode).
     */
    public int getTilingPresetOrdinal() {
        Object preset = extraData.get("tilingPresetOrdinal");
        return preset instanceof Integer ? (Integer) preset : 0;
    }

    /**
     * Create extra data map for Tower mode.
     */
    public static Map<String, Object> createTowerExtraData(Map<String, Integer> blockCounts, int height) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("blockCounts", blockCounts);
        extra.put("height", height);
        return extra;
    }

    public static Map<String, Object> createPyramidExtraData(Map<String, Integer> blockCounts, int height,
                                                              int curvature) {
        Map<String, Object> extra = new HashMap<>(createTowerExtraData(blockCounts, height));
        extra.put("curvature", curvature);
        return extra;
    }

    /**
     * Create extra data map for Tree mode.
     */
    public static Map<String, Object> createTreeExtraData(int tilingPresetOrdinal) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("tilingPresetOrdinal", tilingPresetOrdinal);
        return extra;
    }

    /**
     * Create empty extra data map for Wall mode.
     */
    public static Map<String, Object> createWallExtraData() {
        return Map.of();
    }

    public static Map<String, Object> createRoomExtraData(int memoryLimit) {
        return Map.of("memoryLimit", Math.max(1, Math.min(1000, memoryLimit)));
    }
}
