package ninja.trek.mc.goldgolem.net;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record TowerBlockCountsS2CPayload(int entityId, List<String> blockIds, List<Integer> counts, int towerHeight) implements CustomPacketPayload {

    public TowerBlockCountsS2CPayload {
        blockIds = PayloadValidator.validateList(blockIds, 0, "blockIds");
        counts = PayloadValidator.validateList(counts, 0, "counts");
        // blockIds and counts should have the same size
        if (blockIds != null && counts != null && blockIds.size() != counts.size()) {
            org.slf4j.LoggerFactory.getLogger(TowerBlockCountsS2CPayload.class)
                    .warn("Payload blockIds and counts have mismatched sizes: {} vs {}", blockIds.size(), counts.size());
        }
    }

    public static final Type<TowerBlockCountsS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "tower_block_counts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TowerBlockCountsS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TowerBlockCountsS2CPayload::entityId,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TowerBlockCountsS2CPayload::blockIds,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), TowerBlockCountsS2CPayload::counts,
            ByteBufCodecs.VAR_INT, TowerBlockCountsS2CPayload::towerHeight,
            TowerBlockCountsS2CPayload::new
    );

    @Override
    public Type<TowerBlockCountsS2CPayload> type() { return ID; }
}
