package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetExcavationHeightC2SPayload(int entityId, int height) implements CustomPayload {
    public static final Id<SetExcavationHeightC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_excavation_height"));
    public static final PacketCodec<RegistryByteBuf, SetExcavationHeightC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetExcavationHeightC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetExcavationHeightC2SPayload::height,
            SetExcavationHeightC2SPayload::new
    );
    @Override
    public Id<SetExcavationHeightC2SPayload> getId() { return ID; }
}
