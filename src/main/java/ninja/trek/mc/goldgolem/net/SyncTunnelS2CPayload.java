package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SyncTunnelS2CPayload(int entityId, int width, int height, int oreMiningMode) implements CustomPacketPayload {
    public static final Type<SyncTunnelS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "sync_tunnel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTunnelS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncTunnelS2CPayload::entityId,
            ByteBufCodecs.VAR_INT, SyncTunnelS2CPayload::width,
            ByteBufCodecs.VAR_INT, SyncTunnelS2CPayload::height,
            ByteBufCodecs.VAR_INT, SyncTunnelS2CPayload::oreMiningMode,
            SyncTunnelS2CPayload::new
    );
    @Override
    public Type<SyncTunnelS2CPayload> type() { return ID; }
}
