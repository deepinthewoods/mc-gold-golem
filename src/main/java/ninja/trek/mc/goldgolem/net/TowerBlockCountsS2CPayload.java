package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

public record TowerBlockCountsS2CPayload(int entityId, List<String> blockIds, List<Integer> counts, int towerHeight) implements CustomPayload {

    public TowerBlockCountsS2CPayload {
        blockIds = PayloadValidator.validateList(blockIds, 0, "blockIds");
        counts = PayloadValidator.validateList(counts, 0, "counts");
        // blockIds and counts should have the same size
        if (blockIds != null && counts != null && blockIds.size() != counts.size()) {
            org.slf4j.LoggerFactory.getLogger(TowerBlockCountsS2CPayload.class)
                    .warn("Payload blockIds and counts have mismatched sizes: {} vs {}", blockIds.size(), counts.size());
        }
    }

    public static final Id<TowerBlockCountsS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "tower_block_counts"));

    public static final PacketCodec<RegistryByteBuf, TowerBlockCountsS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TowerBlockCountsS2CPayload::entityId,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), TowerBlockCountsS2CPayload::blockIds,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), TowerBlockCountsS2CPayload::counts,
            PacketCodecs.VAR_INT, TowerBlockCountsS2CPayload::towerHeight,
            TowerBlockCountsS2CPayload::new
    );

    @Override
    public Id<TowerBlockCountsS2CPayload> getId() { return ID; }
}
