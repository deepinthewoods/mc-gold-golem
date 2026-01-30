package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SyncTunnelS2CPayload(int entityId, int width, int height, int oreMiningMode) implements CustomPayload {
    public static final Id<SyncTunnelS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "sync_tunnel"));
    public static final PacketCodec<RegistryByteBuf, SyncTunnelS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SyncTunnelS2CPayload::entityId,
            PacketCodecs.VAR_INT, SyncTunnelS2CPayload::width,
            PacketCodecs.VAR_INT, SyncTunnelS2CPayload::height,
            PacketCodecs.VAR_INT, SyncTunnelS2CPayload::oreMiningMode,
            SyncTunnelS2CPayload::new
    );
    @Override
    public Id<SyncTunnelS2CPayload> getId() { return ID; }
}
