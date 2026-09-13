package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetTunnelHeightC2SPayload(int entityId, int height) implements CustomPacketPayload {

    public SetTunnelHeightC2SPayload {
        height = PayloadValidator.clampInt(height, 2, 6, "height");
    }

    public static final Type<SetTunnelHeightC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_tunnel_height"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTunnelHeightC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTunnelHeightC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetTunnelHeightC2SPayload::height,
            SetTunnelHeightC2SPayload::new
    );
    @Override
    public Type<SetTunnelHeightC2SPayload> type() { return ID; }
}
