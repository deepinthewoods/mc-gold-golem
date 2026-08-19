package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetExcavationDepthC2SPayload(int entityId, int depth) implements CustomPacketPayload {

    public SetExcavationDepthC2SPayload {
        depth = PayloadValidator.clampInt(depth, 1, 64, "depth");
    }

    public static final Type<SetExcavationDepthC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_excavation_depth"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetExcavationDepthC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetExcavationDepthC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetExcavationDepthC2SPayload::depth,
            SetExcavationDepthC2SPayload::new
    );
    @Override
    public Type<SetExcavationDepthC2SPayload> type() { return ID; }
}
