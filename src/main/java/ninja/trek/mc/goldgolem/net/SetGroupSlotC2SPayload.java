package ninja.trek.mc.goldgolem.net;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetGroupSlotC2SPayload(BuildMode mode, int entityId, int group, int slot, Optional<Identifier> block) implements CustomPacketPayload {
    public static final Type<SetGroupSlotC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_group_slot"));
    private static final BuildMode[] BUILD_MODE_VALUES = BuildMode.values();

    public static final StreamCodec<RegistryFriendlyByteBuf, SetGroupSlotC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> i >= 0 && i < BUILD_MODE_VALUES.length ? BUILD_MODE_VALUES[i] : BUILD_MODE_VALUES[0], BuildMode::ordinal),
            SetGroupSlotC2SPayload::mode,
            ByteBufCodecs.VAR_INT, SetGroupSlotC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetGroupSlotC2SPayload::group,
            ByteBufCodecs.VAR_INT, SetGroupSlotC2SPayload::slot,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), SetGroupSlotC2SPayload::block,
            SetGroupSlotC2SPayload::new
    );

    @Override
    public Type<SetGroupSlotC2SPayload> type() { return ID; }
}
