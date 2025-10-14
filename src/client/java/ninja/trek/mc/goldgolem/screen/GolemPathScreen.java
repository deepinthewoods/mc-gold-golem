package ninja.trek.mc.goldgolem.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.item.BlockItem;
import net.minecraft.text.Text;
import ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload;
import ninja.trek.mc.goldgolem.net.SetPathWidthC2SPayload;

@Environment(EnvType.CLIENT)
public class GolemPathScreen extends Screen {
    private int pathWidth = 3;
    private int gradientWindow = 1; // 0..9
    private String[] gradientBlocks = new String[9];

    private WindowSlider windowSlider;

    private class WindowSlider extends SliderWidget {
        public WindowSlider(int x, int y, int width, int height, double norm) {
            super(x, y, width, height, Text.literal("Window"), norm);
        }
        @Override
        protected void updateMessage() {
            int g = effectiveG();
            int w = (g <= 0) ? 0 : (int) Math.round(this.value * g);
            w = Math.max(0, Math.min(g, w));
            this.setMessage(Text.literal("Window: " + w));
        }
        @Override
        protected void applyValue() {
            int g = effectiveG();
            int w = (g <= 0) ? 0 : (int) Math.round(this.value * g);
            w = Math.max(0, Math.min(g, w));
            if (w != gradientWindow) {
                gradientWindow = w;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientWindowC2SPayload(gradientWindow));
            }
        }
        public void syncTo(int g, int window) {
            double norm = g <= 0 ? 0.0 : (double) Math.min(window, g) / (double) g;
            this.value = norm;
            this.updateMessage();
            this.applyValue();
        }
    }

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

        // Gradient window controls to the right of the grid
        int wx = startX + 9 * 18 + 12;
        int wy2 = y;
        int sliderWidth = 80;
        int sliderHeight = 20;
        int g = effectiveG();
        double norm = g <= 0 ? 0.0 : (double) Math.min(gradientWindow, g) / (double) g;
        windowSlider = new WindowSlider(wx, wy2, sliderWidth, sliderHeight, norm);
        this.addDrawableChild(windowSlider);
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> MinecraftClient.getInstance().setScreen(null))
                .dimensions(this.width / 2 - 30, wy + 30, 60, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Avoid blur: draw translucent dark background directly
        context.fill(0, 0, this.width, this.height, 0xB0000000);
        super.render(context, mouseX, mouseY, delta);
        context.drawText(this.textRenderer, "Width: " + pathWidth, this.width / 2 - 15, this.height / 2 - 10, 0xFFFFFF, false);
        // Marker dots above the slider
        if (windowSlider != null) {
            int sx = windowSlider.getX();
            int sy = windowSlider.getY();
            int sw = windowSlider.getWidth();
            int dotY = sy - 4;
            int g = effectiveG();
            if (g > 0) {
                // Gradient detents: integers 0..G (gold)
                int gold = 0xFFFFCC00;
                for (int k = 0; k <= g; k++) {
                    double t = (double) k / (double) g;
                    int dx = (int) Math.round(sx + t * sw);
                    fillDot(context, dx, dotY, gold);
                }
                // Block detents: multiples of Δs up to G (cyan)
                double deltaS = (double) (g - 1) / (double) Math.max(1, pathWidth - 1);
                if (deltaS > 1e-6) {
                    int cyan = 0xFF00FFFF;
                    for (int n = 1; ; n++) {
                        double w = n * deltaS;
                        if (w > g) break;
                        double t = w / (double) g;
                        int dx = (int) Math.round(sx + t * sw);
                        fillDot(context, dx, dotY - 4, cyan);
                    }
                }
            }
        }
    }

    public void applyServerSync(int width, int window, String[] blocks) {
        this.pathWidth = width;
        this.gradientWindow = window;
        if (blocks != null) {
            if (blocks.length != 9) this.gradientBlocks = new String[9];
            System.arraycopy(blocks, 0, this.gradientBlocks, 0, Math.min(9, blocks.length));
        }
        // Update slider position to reflect server
        if (this.windowSlider != null) {
            int g = effectiveG();
            this.windowSlider.syncTo(g, gradientWindow);
        }
    }

    private int effectiveG() {
        int G = 0;
        for (int i = gradientBlocks.length - 1; i >= 0; i--) {
            String s = gradientBlocks[i];
            if (s != null && !s.isEmpty()) { G = i + 1; break; }
        }
        if (G == 0) G = 9; // fallback if not synced yet
        return G;
    }

    private static void fillDot(DrawContext ctx, int cx, int cy, int argb) {
        int r = 1;
        ctx.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, argb);
    }
}
