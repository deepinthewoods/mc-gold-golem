package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTunnelHeightC2SPayload(int entityId, int height) implements CustomPayload {

    public SetTunnelHeightC2SPayload {
        height = PayloadValidator.clampInt(height, 2, 6, "height");
    }

    public static final Id<SetTunnelHeightC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tunnel_height"));
    public static final PacketCodec<RegistryByteBuf, SetTunnelHeightC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTunnelHeightC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTunnelHeightC2SPayload::height,
            SetTunnelHeightC2SPayload::new
    );
    @Override
    public Id<SetTunnelHeightC2SPayload> getId() { return ID; }
}
