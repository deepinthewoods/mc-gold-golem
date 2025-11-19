package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

public record TreeBlockGroupsS2CPayload(int entityId, List<Integer> groups) implements CustomPayload {
    public static final Id<TreeBlockGroupsS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "tree_block_groups"));

    public static final PacketCodec<RegistryByteBuf, TreeBlockGroupsS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TreeBlockGroupsS2CPayload::entityId,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), TreeBlockGroupsS2CPayload::groups,
            TreeBlockGroupsS2CPayload::new
    );

    @Override
    public Id<TreeBlockGroupsS2CPayload> getId() { return ID; }
}
