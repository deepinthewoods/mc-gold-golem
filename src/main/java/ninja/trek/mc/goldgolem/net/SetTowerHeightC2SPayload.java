package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTowerHeightC2SPayload(int entityId, int height) implements CustomPayload {
    public static final Id<SetTowerHeightC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tower_height"));
    public static final PacketCodec<RegistryByteBuf, SetTowerHeightC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTowerHeightC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTowerHeightC2SPayload::height,
            SetTowerHeightC2SPayload::new
    );
    @Override
    public Id<SetTowerHeightC2SPayload> getId() { return ID; }
}
