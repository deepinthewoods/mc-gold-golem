package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.BuildMode;

import java.util.Optional;

public record SetGroupSlotC2SPayload(BuildMode mode, int entityId, int group, int slot, Optional<Identifier> block) implements CustomPayload {
    public static final Id<SetGroupSlotC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_group_slot"));
    private static final BuildMode[] BUILD_MODE_VALUES = BuildMode.values();

    public static final PacketCodec<RegistryByteBuf, SetGroupSlotC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.indexed(i -> i >= 0 && i < BUILD_MODE_VALUES.length ? BUILD_MODE_VALUES[i] : BUILD_MODE_VALUES[0], BuildMode::ordinal),
            SetGroupSlotC2SPayload::mode,
            PacketCodecs.VAR_INT, SetGroupSlotC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetGroupSlotC2SPayload::group,
            PacketCodecs.VAR_INT, SetGroupSlotC2SPayload::slot,
            PacketCodecs.optional(Identifier.PACKET_CODEC), SetGroupSlotC2SPayload::block,
            SetGroupSlotC2SPayload::new
    );

    @Override
    public Id<SetGroupSlotC2SPayload> getId() { return ID; }
}
