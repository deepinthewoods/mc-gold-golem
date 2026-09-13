package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetTunnelWidthC2SPayload(int entityId, int width) implements CustomPacketPayload {

    public SetTunnelWidthC2SPayload {
        width = PayloadValidator.clampInt(width, 1, 9, "width");
    }

    public static final Type<SetTunnelWidthC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_tunnel_width"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTunnelWidthC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTunnelWidthC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetTunnelWidthC2SPayload::width,
            SetTunnelWidthC2SPayload::new
    );
    @Override
    public Type<SetTunnelWidthC2SPayload> type() { return ID; }
}
