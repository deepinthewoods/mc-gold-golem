package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.BuildMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        List<String> flatSlots,
        Map<String, Object> extraData
) implements CustomPayload {
    public static final Id<GroupModeStateS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "group_mode_state"));

    // Codec for extra data - serialize as string keys and primitive values
    // For simplicity in this refactoring, we'll use a string-based map approach
    private static final PacketCodec<RegistryByteBuf, Map<String, Object>> EXTRA_DATA_CODEC = new PacketCodec<RegistryByteBuf, Map<String, Object>>() {
        @Override
        public Map<String, Object> decode(RegistryByteBuf buf) {
            Map<String, Object> map = new HashMap<>();
            int size = buf.readVarInt();
            for (int i = 0; i < size; i++) {
                String key = buf.readString();
                String type = buf.readString();
                Object value = switch (type) {
                    case "int" -> buf.readVarInt();
                    case "string" -> buf.readString();
                    case "block_counts" -> {
                        int count = buf.readVarInt();
                        Map<String, Integer> counts = new HashMap<>();
                        for (int j = 0; j < count; j++) {
                            String id = buf.readString();
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
        public void encode(RegistryByteBuf buf, Map<String, Object> value) {
            buf.writeVarInt(value.size());
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                buf.writeString(entry.getKey());
                Object v = entry.getValue();
                if (v instanceof Integer) {
                    buf.writeString("int");
                    buf.writeVarInt((Integer) v);
                } else if (v instanceof String) {
                    buf.writeString("string");
                    buf.writeString((String) v);
                } else if (v instanceof Map<?, ?> counts) {
                    buf.writeString("block_counts");
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> typedCounts = (Map<String, Integer>) counts;
                    buf.writeVarInt(typedCounts.size());
                    for (Map.Entry<String, Integer> countEntry : typedCounts.entrySet()) {
                        buf.writeString(countEntry.getKey());
                        buf.writeVarInt(countEntry.getValue());
                    }
                }
            }
        }
    };

    public static final PacketCodec<RegistryByteBuf, GroupModeStateS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, GroupModeStateS2CPayload::entityId,
            BuildMode.PACKET_CODEC, GroupModeStateS2CPayload::mode,
            PacketCodecs.FLOAT.collect(PacketCodecs.toList()), GroupModeStateS2CPayload::windows,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), GroupModeStateS2CPayload::flatSlots,
            EXTRA_DATA_CODEC, GroupModeStateS2CPayload::extraData,
            GroupModeStateS2CPayload::new
    );

    @Override
    public Id<GroupModeStateS2CPayload> getId() { return ID; }

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
}
