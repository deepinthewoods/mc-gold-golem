package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetPathWidthC2SPayload(int entityId, int width) implements CustomPacketPayload {

    public SetPathWidthC2SPayload {
        width = PayloadValidator.clampInt(width, 1, 9, "width");
    }

    public static final Type<SetPathWidthC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_path_width"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetPathWidthC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetPathWidthC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetPathWidthC2SPayload::width,
            SetPathWidthC2SPayload::new
    );
    @Override
    public Type<SetPathWidthC2SPayload> type() { return ID; }
}
