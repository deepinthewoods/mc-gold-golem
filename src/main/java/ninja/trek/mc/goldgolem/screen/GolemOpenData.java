package ninja.trek.mc.goldgolem.screen;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

public record GolemOpenData(int entityId, int gradientRows, int golemSlots, int slider) {
    public boolean sliderEnabled() { return slider != 0; }

    public static final PacketCodec<RegistryByteBuf, GolemOpenData> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, GolemOpenData::entityId,
            PacketCodecs.VAR_INT, GolemOpenData::gradientRows,
            PacketCodecs.VAR_INT, GolemOpenData::golemSlots,
            PacketCodecs.VAR_INT, GolemOpenData::slider,
            GolemOpenData::new
    );

    public static int computeControlsMargin(int gradientRows, boolean slider, int titleLine) {
        int headerH = 17;
        int titleGap = 6;
        int labelGap = 4;
        int ghostRowH = 16;
        int rowGap = 6;
        int sliderH = 12;
        int gridGap = 6;
        int controls = headerH + titleGap + titleLine + labelGap + (gradientRows * ghostRowH);
        if (slider) controls += sliderH + rowGap;
        controls += gridGap;
        return controls;
    }
}
