package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetGradientWindowC2SPayload(int entityId, int row, int window) implements CustomPayload {
    public static final Id<SetGradientWindowC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_gradient_window"));
    public static final PacketCodec<RegistryByteBuf, SetGradientWindowC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetGradientWindowC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetGradientWindowC2SPayload::row,
            PacketCodecs.VAR_INT, SetGradientWindowC2SPayload::window,
            SetGradientWindowC2SPayload::new
    );

    @Override
    public Id<SetGradientWindowC2SPayload> getId() { return ID; }
}
