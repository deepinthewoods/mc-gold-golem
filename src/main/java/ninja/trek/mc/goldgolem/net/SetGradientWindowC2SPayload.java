package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetGradientWindowC2SPayload(int entityId, int row, float window, int scale) implements CustomPayload {

    public SetGradientWindowC2SPayload {
        row = PayloadValidator.clampInt(row, 0, 2, "row");
        window = PayloadValidator.clampFloat(window, 0.0f, 9.0f, "window");
        scale = PayloadValidator.clampInt(scale, 1, 16, "scale");
    }

    public static final Id<SetGradientWindowC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_gradient_window"));
    public static final PacketCodec<RegistryByteBuf, SetGradientWindowC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetGradientWindowC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetGradientWindowC2SPayload::row,
            PacketCodecs.FLOAT, SetGradientWindowC2SPayload::window,
            PacketCodecs.VAR_INT, SetGradientWindowC2SPayload::scale,
            SetGradientWindowC2SPayload::new
    );

    @Override
    public Id<SetGradientWindowC2SPayload> getId() { return ID; }
}
