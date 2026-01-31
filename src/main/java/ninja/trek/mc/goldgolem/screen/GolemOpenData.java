package ninja.trek.mc.goldgolem.screen;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record GolemOpenData(int entityId, int gradientRows, int golemSlots, int slider, String jsonName) {
    public boolean sliderEnabled() { return slider == 1; }

    public static final StreamCodec<RegistryFriendlyByteBuf, GolemOpenData> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GolemOpenData::entityId,
            ByteBufCodecs.VAR_INT, GolemOpenData::gradientRows,
            ByteBufCodecs.VAR_INT, GolemOpenData::golemSlots,
            ByteBufCodecs.VAR_INT, GolemOpenData::slider,
            ByteBufCodecs.STRING_UTF8, GolemOpenData::jsonName,
            GolemOpenData::new
    );

    public static int computeControlsMargin(int gradientRows, int slider, int titleLine) {
        int headerH = 17;
        int titleGap = 6;
        int labelGap = 4;
        int ghostRowH = 16;
        int rowGap = 6;
        int sliderH = 12;
        int gridGap = 6;
        int controls = headerH + titleGap + titleLine + labelGap + (gradientRows * ghostRowH);
        if (slider == 1) controls += (gradientRows * sliderH) + rowGap;
        if (slider == 6) controls += sliderH + rowGap;
        controls += gridGap;
        return controls;
    }
}
