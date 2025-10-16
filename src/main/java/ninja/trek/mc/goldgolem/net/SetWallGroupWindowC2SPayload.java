package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetWallGroupWindowC2SPayload(int entityId, int group, int window) implements CustomPayload {
    public static final Id<SetWallGroupWindowC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_wall_group_window"));

    public static final PacketCodec<RegistryByteBuf, SetWallGroupWindowC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetWallGroupWindowC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetWallGroupWindowC2SPayload::group,
            PacketCodecs.VAR_INT, SetWallGroupWindowC2SPayload::window,
            SetWallGroupWindowC2SPayload::new
    );

    @Override
    public Id<SetWallGroupWindowC2SPayload> getId() { return ID; }
}

