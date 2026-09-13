package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetExcavationHeightC2SPayload(int entityId, int height) implements CustomPacketPayload {

    public SetExcavationHeightC2SPayload {
        height = PayloadValidator.clampInt(height, 1, 10, "height");
    }

    public static final Type<SetExcavationHeightC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_excavation_height"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetExcavationHeightC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetExcavationHeightC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetExcavationHeightC2SPayload::height,
            SetExcavationHeightC2SPayload::new
    );
    @Override
    public Type<SetExcavationHeightC2SPayload> type() { return ID; }
}
