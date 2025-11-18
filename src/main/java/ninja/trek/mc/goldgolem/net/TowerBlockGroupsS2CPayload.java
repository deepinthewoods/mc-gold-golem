package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

public record TowerBlockGroupsS2CPayload(int entityId, List<Integer> groups) implements CustomPayload {
    public static final Id<TowerBlockGroupsS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "tower_block_groups"));

    public static final PacketCodec<RegistryByteBuf, TowerBlockGroupsS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TowerBlockGroupsS2CPayload::entityId,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), TowerBlockGroupsS2CPayload::groups,
            TowerBlockGroupsS2CPayload::new
    );

    @Override
    public Id<TowerBlockGroupsS2CPayload> getId() { return ID; }
}
