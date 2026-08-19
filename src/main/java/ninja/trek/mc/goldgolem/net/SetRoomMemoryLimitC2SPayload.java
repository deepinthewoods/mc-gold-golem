package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetRoomMemoryLimitC2SPayload(int entityId, int limit) implements CustomPacketPayload {
    public SetRoomMemoryLimitC2SPayload {
        limit = PayloadValidator.clampInt(limit, 1, 1000, "limit");
    }

    public static final Type<SetRoomMemoryLimitC2SPayload> ID = new Type<>(
            Identifier.fromNamespaceAndPath("gold-golem", "set_room_memory_limit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetRoomMemoryLimitC2SPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SetRoomMemoryLimitC2SPayload::entityId,
                    ByteBufCodecs.VAR_INT, SetRoomMemoryLimitC2SPayload::limit,
                    SetRoomMemoryLimitC2SPayload::new);

    @Override
    public Type<SetRoomMemoryLimitC2SPayload> type() {
        return ID;
    }
}
