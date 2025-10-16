package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetWallBlockGroupC2SPayload(int entityId, String blockId, int group) implements CustomPayload {
    public static final Id<SetWallBlockGroupC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_wall_block_group"));

    public static final PacketCodec<RegistryByteBuf, SetWallBlockGroupC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetWallBlockGroupC2SPayload::entityId,
            PacketCodecs.STRING, SetWallBlockGroupC2SPayload::blockId,
            PacketCodecs.VAR_INT, SetWallBlockGroupC2SPayload::group,
            SetWallBlockGroupC2SPayload::new
    );

    @Override
    public Id<SetWallBlockGroupC2SPayload> getId() { return ID; }
}

