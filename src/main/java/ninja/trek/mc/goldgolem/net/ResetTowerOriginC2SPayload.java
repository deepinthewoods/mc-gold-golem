package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ResetTowerOriginC2SPayload(int entityId) implements CustomPayload {
    public static final Id<ResetTowerOriginC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "reset_tower_origin"));
    public static final PacketCodec<RegistryByteBuf, ResetTowerOriginC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, ResetTowerOriginC2SPayload::entityId,
            ResetTowerOriginC2SPayload::new
    );
    @Override
    public Id<ResetTowerOriginC2SPayload> getId() { return ID; }
}
