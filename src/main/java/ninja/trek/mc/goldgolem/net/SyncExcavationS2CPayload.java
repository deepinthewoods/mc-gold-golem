package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SyncExcavationS2CPayload(int entityId, int height, int depth, int oreMiningMode) implements CustomPacketPayload {
    public static final Type<SyncExcavationS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "sync_excavation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncExcavationS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncExcavationS2CPayload::entityId,
            ByteBufCodecs.VAR_INT, SyncExcavationS2CPayload::height,
            ByteBufCodecs.VAR_INT, SyncExcavationS2CPayload::depth,
            ByteBufCodecs.VAR_INT, SyncExcavationS2CPayload::oreMiningMode,
            SyncExcavationS2CPayload::new
    );
    @Override
    public Type<SyncExcavationS2CPayload> type() { return ID; }
}
