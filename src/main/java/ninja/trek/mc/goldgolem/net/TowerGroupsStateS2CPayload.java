package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

public record TowerGroupsStateS2CPayload(int entityId, List<Integer> windows, List<String> flatSlots) implements CustomPayload {
    public static final Id<TowerGroupsStateS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "tower_groups_state"));

    public static final PacketCodec<RegistryByteBuf, TowerGroupsStateS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TowerGroupsStateS2CPayload::entityId,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), TowerGroupsStateS2CPayload::windows,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), TowerGroupsStateS2CPayload::flatSlots,
            TowerGroupsStateS2CPayload::new
    );

    @Override
    public Id<TowerGroupsStateS2CPayload> getId() { return ID; }
}
