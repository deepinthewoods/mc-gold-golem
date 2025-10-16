package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record WallGroupsStateS2CPayload(int entityId, List<Integer> windows, List<String> flatSlots) implements CustomPayload {
    public static final Id<WallGroupsStateS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "wall_groups_state"));

    public static final PacketCodec<RegistryByteBuf, WallGroupsStateS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, WallGroupsStateS2CPayload::entityId,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), WallGroupsStateS2CPayload::windows,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), WallGroupsStateS2CPayload::flatSlots,
            WallGroupsStateS2CPayload::new
    );

    @Override
    public Id<WallGroupsStateS2CPayload> getId() { return ID; }
}

