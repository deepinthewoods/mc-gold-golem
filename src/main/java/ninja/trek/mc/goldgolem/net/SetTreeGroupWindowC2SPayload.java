package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTreeGroupWindowC2SPayload(int entityId, int group, int window) implements CustomPayload {
    public static final Id<SetTreeGroupWindowC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tree_group_window"));

    public static final PacketCodec<RegistryByteBuf, SetTreeGroupWindowC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTreeGroupWindowC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTreeGroupWindowC2SPayload::group,
            PacketCodecs.VAR_INT, SetTreeGroupWindowC2SPayload::window,
            SetTreeGroupWindowC2SPayload::new
    );

    @Override
    public Id<SetTreeGroupWindowC2SPayload> getId() { return ID; }
}
