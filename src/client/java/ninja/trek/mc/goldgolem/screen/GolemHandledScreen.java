package ninja.trek.mc.goldgolem.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class GolemHandledScreen extends HandledScreen<GolemInventoryScreenHandler> {
    private static final Identifier GENERIC_CONTAINER_TEXTURE = Identifier.of("minecraft", "textures/gui/container/generic_54.png");
    private int gradientWindow = 1; // 0..9 (server synced)
    private int pathWidth = 3;      // server synced
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

    public GolemHandledScreen(GolemInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 176; // vanilla chest width
        this.backgroundHeight = handler.getControlsMargin() + handler.getGolemRows() * 18 + 94;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        // Vanilla chest-style background split into header/body/bottom slices from generic_54.png
        int left = this.x;
        int top = this.y;
        int rows = this.handler.getGolemRows();
        int margin = this.handler.getControlsMargin();

        int headerH = 17;            // chest header height
        int bodyH = rows * 18;       // golem rows area
        int bottomH = 96;            // player inventory + hotbar

        float texW = 256f;
        float texH = 256f;

        // Header strip (u:0..176, v:0..17)
        context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                left, top,
                left + this.backgroundWidth, top + headerH,
                0f / texW, 176f / texW,
                0f / texH, headerH / texH);

        // Filler from header to controls margin using a 1px band (prevents gaps if margin > 17)
        int fillerH = Math.max(0, margin - headerH);
        if (fillerH > 0) {
            float v1 = 10f / texH;
            float v2 = 11f / texH;
            context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                    left, top + headerH,
                    left + this.backgroundWidth, top + headerH + fillerH,
                    0f / texW, 176f / texW,
                    v1, v2);
        }

        // Body (golem inventory) starts at margin; source v starts at 17
        int bodyY = top + margin;
        if (bodyH > 0) {
            context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                    left, bodyY,
                    left + this.backgroundWidth, bodyY + bodyH,
                    0f / texW, 176f / texW,
                    17f / texH, (17f + bodyH) / texH);
        }

        // Bottom (player inventory + hotbar) slice at v=126
        int bottomY = bodyY + bodyH;
        context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                left, bottomY,
                left + this.backgroundWidth, bottomY + bottomH,
                0f / texW, 176f / texW,
                126f / texH, (126f + bottomH) / texH);

        // Draw gradient slot frames and items on top of background
        int slotsX = this.x + 8;
        int slotY = this.y + 26;
        // Frames: 18x18 area with 1px border and darker inner background
        int borderColor = 0xFF555555; // medium-dark border
        int innerColor = 0xFF1C1C1C;  // darker inner background
        for (int i = 0; i < 9; i++) {
            int fx = slotsX + i * 18;
            int fy = slotY;
            // outer border (18x18 around the 16x16 item area)
            context.fill(fx - 1, fy - 1, fx + 17, fy + 17, borderColor);
            // inner fill (16x16 item area)
            context.fill(fx, fy, fx + 16, fy + 16, innerColor);
        }
        for (int i = 0; i < 9; i++) {
            String id = (gradientBlocks != null && i < gradientBlocks.length) ? gradientBlocks[i] : "";
            if (id != null && !id.isEmpty()) {
                var ident = net.minecraft.util.Identifier.tryParse(id);
                if (ident != null) {
                    var block = Registries.BLOCK.get(ident);
                    if (block != null) {
                        ItemStack stack = new ItemStack(block.asItem());
                        context.drawItem(stack, slotsX + i * 18, slotY);
                    }
                }
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        // Place window slider in the controls margin area. Gradient slots are handled via mouse clicks, not buttons.
        int controlsTop = this.y + 8; // leave a small header gap
        // int slotsX = this.x + 8; // for reference
        // int slotY = controlsTop + 18; // below title area

        // Window slider to the right of the ghost grid
        int wx = this.x + 8 + 9 * 18 + 12;
        int wy = controlsTop + 18; // align top with gradient row
        int sliderWidth = 90;
        int sliderHeight = 20;
        int g = effectiveG();
        double norm = g <= 0 ? 0.0 : (double) Math.min(gradientWindow, g) / (double) g;
        windowSlider = new WindowSlider(wx, wy, sliderWidth, sliderHeight, norm);
        this.addDrawableChild(windowSlider);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int idx = gradientIndexAt((int) click.x(), (int) click.y());
        if (idx >= 0) {
            var mc = MinecraftClient.getInstance();
            var player = mc.player;
            if (player != null) {
                ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                ItemStack pick = cursor.isEmpty() ? player.getMainHandStack() : cursor;
                if (click.button() == 1) { // right-click to clear
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(idx, java.util.Optional.empty()));
                    return true;
                }
                if (pick.getItem() instanceof BlockItem bi) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private int gradientIndexAt(int mx, int my) {
        int slotsX = this.x + 8;
        int slotY = this.y + 26; // matches draw positioning
        int w = 16, h = 16, pad = 18;
        // Single row of 9 slots
        if (my >= slotY && my < slotY + h) {
            int dx = mx - slotsX;
            if (dx >= 0) {
                int col = dx / pad;
                if (col >= 0 && col < 9) {
                    int colX = slotsX + col * pad;
                    if (mx >= colX && mx < colX + w) return col;
                }
            }
        }
        return -1;
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Labels (foreground coordinates are relative to screen top-left)
        context.drawText(this.textRenderer, this.title, 8, 6, 0xFFFFFF, false);
        context.drawText(this.textRenderer, Text.literal("Gradient"), 8, 18, 0xA0A0A0, false);

        // Marker dots above the slider (drawn before mouseover tooltips)
        if (windowSlider != null) {
            int sx = windowSlider.getX() - this.x;
            int sy = windowSlider.getY() - this.y;
            int sw = windowSlider.getWidth();
            int dotY = sy - 4;
            int g = effectiveG();
            if (g > 0) {
                int gold = 0xFFFFCC00;
                for (int k = 0; k <= g; k++) {
                    double t = (double) k / (double) g;
                    int dx = (int) Math.round(sx + t * sw);
                    fillDot(context, this.x + dx, this.y + dotY, gold);
                }
                double deltaS = (double) (g - 1) / (double) Math.max(1, pathWidth - 1);
                if (deltaS > 1e-6) {
                    int cyan = 0xFF00FFFF;
                    for (int n = 1; ; n++) {
                        double w = n * deltaS;
                        if (w > g) break;
                        double t = w / (double) g;
                        int dx = (int) Math.round(sx + t * sw);
                        fillDot(context, this.x + dx, this.y + dotY - 4, cyan);
                    }
                }
            }
        }
    }

    public int getEntityId() {
        return this.handler.getEntityId();
    }

    public void applyServerSync(int width, int window, String[] blocks) {
        this.pathWidth = width;
        this.gradientWindow = window;
        if (blocks != null) {
            if (blocks.length != 9) this.gradientBlocks = new String[9];
            System.arraycopy(blocks, 0, this.gradientBlocks, 0, Math.min(9, blocks.length));
        }
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
        if (G == 0) G = 9; // fallback
        return G;
    }

    private static void fillDot(DrawContext ctx, int cx, int cy, int argb) {
        int r = 1;
        ctx.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, argb);
    }
}
