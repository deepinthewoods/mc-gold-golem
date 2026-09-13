package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetGradientWindowC2SPayload(int entityId, int row, float window, int scale) implements CustomPacketPayload {

    public SetGradientWindowC2SPayload {
        row = PayloadValidator.clampInt(row, 0, 2, "row");
        window = PayloadValidator.clampFloat(window, 0.0f, 9.0f, "window");
        scale = PayloadValidator.clampInt(scale, 1, 16, "scale");
    }

    public static final Type<SetGradientWindowC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_gradient_window"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetGradientWindowC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetGradientWindowC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetGradientWindowC2SPayload::row,
            ByteBufCodecs.FLOAT, SetGradientWindowC2SPayload::window,
            ByteBufCodecs.VAR_INT, SetGradientWindowC2SPayload::scale,
            SetGradientWindowC2SPayload::new
    );

    @Override
    public Type<SetGradientWindowC2SPayload> type() { return ID; }
}
