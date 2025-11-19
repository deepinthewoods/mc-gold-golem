package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

public record TreeGroupsStateS2CPayload(int entityId, int tilingPresetOrdinal, List<Integer> windows, List<String> flatSlots) implements CustomPayload {
    public static final Id<TreeGroupsStateS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "tree_groups_state"));

    public static final PacketCodec<RegistryByteBuf, TreeGroupsStateS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TreeGroupsStateS2CPayload::entityId,
            PacketCodecs.VAR_INT, TreeGroupsStateS2CPayload::tilingPresetOrdinal,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), TreeGroupsStateS2CPayload::windows,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), TreeGroupsStateS2CPayload::flatSlots,
            TreeGroupsStateS2CPayload::new
    );

    @Override
    public Id<TreeGroupsStateS2CPayload> getId() { return ID; }
}
