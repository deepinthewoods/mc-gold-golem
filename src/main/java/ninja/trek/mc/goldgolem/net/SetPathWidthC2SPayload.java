package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetPathWidthC2SPayload(int width) implements CustomPayload {
    public static final Id<SetPathWidthC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_path_width"));
    public static final PacketCodec<RegistryByteBuf, SetPathWidthC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetPathWidthC2SPayload::width,
            SetPathWidthC2SPayload::new
    );
    @Override
    public Id<SetPathWidthC2SPayload> getId() { return ID; }
}

