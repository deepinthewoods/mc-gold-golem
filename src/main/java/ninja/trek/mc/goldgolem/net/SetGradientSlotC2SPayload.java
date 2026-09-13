package ninja.trek.mc.goldgolem.net;

import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetGradientSlotC2SPayload(int entityId, int row, int slot, Optional<Identifier> block) implements CustomPacketPayload {
    public static final Type<SetGradientSlotC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_gradient_slot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetGradientSlotC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetGradientSlotC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetGradientSlotC2SPayload::row,
            ByteBufCodecs.VAR_INT, SetGradientSlotC2SPayload::slot,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), SetGradientSlotC2SPayload::block,
            SetGradientSlotC2SPayload::new
    );

    @Override
    public Type<SetGradientSlotC2SPayload> type() { return ID; }
}
