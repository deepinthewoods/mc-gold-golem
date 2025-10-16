package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

public record WallBlockGroupsS2CPayload(int entityId, List<Integer> groups) implements CustomPayload {
    public static final Id<WallBlockGroupsS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "wall_block_groups"));

    public static final PacketCodec<RegistryByteBuf, WallBlockGroupsS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, WallBlockGroupsS2CPayload::entityId,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), WallBlockGroupsS2CPayload::groups,
            WallBlockGroupsS2CPayload::new
    );

    @Override
    public Id<WallBlockGroupsS2CPayload> getId() { return ID; }
}

