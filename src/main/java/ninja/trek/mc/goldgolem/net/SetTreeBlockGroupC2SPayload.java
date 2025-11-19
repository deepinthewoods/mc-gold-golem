package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTreeBlockGroupC2SPayload(int entityId, String blockId, int group) implements CustomPayload {
    public static final Id<SetTreeBlockGroupC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tree_block_group"));

    public static final PacketCodec<RegistryByteBuf, SetTreeBlockGroupC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTreeBlockGroupC2SPayload::entityId,
            PacketCodecs.STRING, SetTreeBlockGroupC2SPayload::blockId,
            PacketCodecs.VAR_INT, SetTreeBlockGroupC2SPayload::group,
            SetTreeBlockGroupC2SPayload::new
    );

    @Override
    public Id<SetTreeBlockGroupC2SPayload> getId() { return ID; }
}
