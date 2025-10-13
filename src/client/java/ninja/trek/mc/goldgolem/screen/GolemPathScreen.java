package ninja.trek.mc.goldgolem.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.BlockItem;
import net.minecraft.text.Text;
import ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload;
import ninja.trek.mc.goldgolem.net.SetPathWidthC2SPayload;

@Environment(EnvType.CLIENT)
public class GolemPathScreen extends Screen {
    private int pathWidth = 3;

    public GolemPathScreen() { super(Text.literal("Gold Golem Path")); }

    @Override
    protected void init() {
        int startX = this.width / 2 - 9 * 18 / 2;
        int y = this.height / 2 - 40;
        for (int i = 0; i < 9; i++) {
            int idx = i;
            int x = startX + i * 18;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("" + i), b -> {
                var player = MinecraftClient.getInstance().player;
                if (player == null) return;
                var held = player.getMainHandStack();
                if (held.getItem() instanceof BlockItem bi) {
                    ClientPlayNetworking.send(new SetGradientSlotC2SPayload(idx, java.util.Optional.of(net.minecraft.registry.Registries.BLOCK.getId(bi.getBlock()))));
                } else {
                    ClientPlayNetworking.send(new SetGradientSlotC2SPayload(idx, java.util.Optional.empty()));
                }
            }).dimensions(x, y, 16, 16).build());
        }

        int wy = y + 30;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
            if (pathWidth > 1) pathWidth--;
            ClientPlayNetworking.send(new SetPathWidthC2SPayload(pathWidth));
        }).dimensions(this.width / 2 - 40, wy, 20, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
            if (pathWidth < 9) pathWidth++;
            ClientPlayNetworking.send(new SetPathWidthC2SPayload(pathWidth));
        }).dimensions(this.width / 2 + 20, wy, 20, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> MinecraftClient.getInstance().setScreen(null))
                .dimensions(this.width / 2 - 30, wy + 30, 60, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Avoid blur: draw translucent dark background directly
        context.fill(0, 0, this.width, this.height, 0xB0000000);
        super.render(context, mouseX, mouseY, delta);
        context.drawText(this.textRenderer, "Width: " + pathWidth, this.width / 2 - 15, this.height / 2 - 10, 0xFFFFFF, false);
    }
}
