package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTowerBlockGroupC2SPayload(int entityId, String blockId, int group) implements CustomPayload {
    public static final Id<SetTowerBlockGroupC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tower_block_group"));

    public static final PacketCodec<RegistryByteBuf, SetTowerBlockGroupC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTowerBlockGroupC2SPayload::entityId,
            PacketCodecs.STRING, SetTowerBlockGroupC2SPayload::blockId,
            PacketCodecs.VAR_INT, SetTowerBlockGroupC2SPayload::group,
            SetTowerBlockGroupC2SPayload::new
    );

    @Override
    public Id<SetTowerBlockGroupC2SPayload> getId() { return ID; }
}
