package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTunnelWidthC2SPayload(int entityId, int width) implements CustomPayload {

    public SetTunnelWidthC2SPayload {
        width = PayloadValidator.clampInt(width, 1, 9, "width");
    }

    public static final Id<SetTunnelWidthC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tunnel_width"));
    public static final PacketCodec<RegistryByteBuf, SetTunnelWidthC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTunnelWidthC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTunnelWidthC2SPayload::width,
            SetTunnelWidthC2SPayload::new
    );
    @Override
    public Id<SetTunnelWidthC2SPayload> getId() { return ID; }
}
