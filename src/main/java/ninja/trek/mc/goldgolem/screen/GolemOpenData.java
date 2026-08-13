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

    /** Check if the slider value represents a group mode (Wall=0, Tree=5, Tower=6). */
    public static boolean isGroupMode(int slider) {
        return slider == 0 || slider == 5 || slider == 6 || slider == 8;
    }

    public static int computeControlsMargin(int gradientRows, int slider, int titleLine) {
        int headerH = 17;
        int titleGap = 6;
        int labelGap = 4;
        int gridGap = 6;

        if (isGroupMode(slider)) {
            int groupRowH = 24; // 18px slot + 6px gap
            int scrollH = 12;
            int controls = headerH + titleGap + titleLine + labelGap
                    + (gradientRows * groupRowH) + scrollH + gridGap;
            if (slider == 6 || slider == 8) { // Tower/Pyramid: extra space for height controls
                controls += 12 + 6; // layersFieldH + gap
            }
            if (slider == 8) controls += 12 + 6; // Pyramid curvature slider
            return controls;
        }

        int ghostRowH = 16;
        int rowGap = 6;
        int sliderH = 12;
        int controls = headerH + titleGap + titleLine + labelGap + (gradientRows * ghostRowH);
        if (slider == 1) controls += (gradientRows * sliderH) + rowGap;
        controls += gridGap;
        return controls;
    }
}
