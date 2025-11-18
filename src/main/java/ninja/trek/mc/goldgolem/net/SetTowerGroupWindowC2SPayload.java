package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTowerGroupWindowC2SPayload(int entityId, int group, int window) implements CustomPayload {
    public static final Id<SetTowerGroupWindowC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tower_group_window"));

    public static final PacketCodec<RegistryByteBuf, SetTowerGroupWindowC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTowerGroupWindowC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTowerGroupWindowC2SPayload::group,
            PacketCodecs.VAR_INT, SetTowerGroupWindowC2SPayload::window,
            SetTowerGroupWindowC2SPayload::new
    );

    @Override
    public Id<SetTowerGroupWindowC2SPayload> getId() { return ID; }
}
