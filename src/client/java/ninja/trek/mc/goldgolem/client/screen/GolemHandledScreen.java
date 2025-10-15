package ninja.trek.mc.goldgolem.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import ninja.trek.mc.goldgolem.screen.GolemInventoryScreenHandler;

public class GolemHandledScreen extends HandledScreen<GolemInventoryScreenHandler> {
    private static final Identifier GENERIC_CONTAINER_TEXTURE = Identifier.of("minecraft", "textures/gui/container/generic_54.png");
    private int gradientWindow = 1; // 0..9 (server synced)
    private int pathWidth = 3;      // server synced
    private String[] gradientBlocks = new String[9];

    private WindowSlider windowSlider;
    private WidthSlider widthSlider;
    private boolean isDragging = false;
    private int dragButton = -1;
    private java.util.Set<Integer> dragVisited = new java.util.HashSet<>();

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

    private static int clampOdd(int w) {
        w = Math.max(1, Math.min(9, w));
        if ((w & 1) == 0) w = (w < 9) ? (w + 1) : (w - 1);
        return w;
    }

    private class WidthSlider extends SliderWidget {
        public WidthSlider(int x, int y, int width, int height, int initialWidth) {
            super(x, y, width, height, Text.literal("Width"), toValueInit(initialWidth));
        }
        private static double toValueInit(int w) { return (clampOdd(w) - 1) / 8.0; }
        private static int toWidth(double v) { return clampOdd(1 + (int)Math.round(v * 8.0)); }
        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Width: " + toWidth(this.value)));
        }
        @Override
        protected void applyValue() {
            int w = toWidth(this.value);
            if (w != pathWidth) {
                pathWidth = w;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetPathWidthC2SPayload(pathWidth));
                updateMessage();
            }
        }
        public void syncTo(int w) {
            this.value = toValueInit(w);
            updateMessage();
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
        // Transparent buttons over gradient slots for reliable clicks
        int slotsX = this.x + 8;
        int slotY = this.y + 26;
        for (int i = 0; i < 9; i++) {
            final int idx = i;
            int gx = slotsX + i * 18;
            var btn = ButtonWidget.builder(Text.empty(), b -> {
                var mc = MinecraftClient.getInstance();
                var player = mc.player;
                if (player == null) return;
                // Always use the item on the mouse cursor; never fall back to hotbar
                ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                ItemStack held = cursor;
                boolean clear = held.isEmpty() || !(held.getItem() instanceof BlockItem);
                if (clear) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(idx, java.util.Optional.empty()));
                } else {
                    BlockItem bi = (BlockItem) held.getItem();
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                }
            }).dimensions(gx, slotY, 18, 18).build();
            btn.setAlpha(0f);
            this.addDrawableChild(btn);
        }

        // Width slider under the gradient row (right-aligned)
        int wsliderW = 90;
        int wsliderH = 12;
        int slotTop = this.y + 26; // matches gradient slot Y used in drawing
        int wsliderY = slotTop + 18 + 6; // below the 18px slot row with a small gap
        int wsliderX = this.x + this.backgroundWidth - 8 - wsliderW;
        widthSlider = new WidthSlider(wsliderX, wsliderY, wsliderW, wsliderH, pathWidth);
        this.addDrawableChild(widthSlider);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    // Gradient clicks handled by transparent buttons; no custom mouse overrides.
    @Override
    public boolean mouseClicked(Click click, boolean traced) {
        // Right-click on any gradient slot clears it regardless of cursor contents
        if (click.button() == 1) { // right mouse button
            int idx = gradientIndexAt((int) click.x(), (int) click.y());
            if (idx >= 0) {
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(idx, java.util.Optional.empty()));
                return true;
            }
        }
        return super.mouseClicked(click, traced);
    }

    private int gradientIndexAt(int mx, int my) {
        int slotsX = this.x + 8;
        int slotY = this.y + 26; // matches draw positioning
        int w = 18, h = 18, pad = 18;
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
        // Player inventory label (match vanilla placement)
        int invY = this.backgroundHeight - 96 + 2;
        context.drawText(this.textRenderer, this.playerInventoryTitle, 8, invY, 0x404040, false);
        context.drawText(this.textRenderer, Text.literal("Gradient"), 8, 18, 0xA0A0A0, false);
        // Width label near the slider
        if (widthSlider != null) {
            int lx = widthSlider.getX() - this.x;
            int ly = widthSlider.getY() - this.y - 10;
            context.drawText(this.textRenderer, Text.literal("Width: " + this.pathWidth), lx, ly, 0xFFFFFF, false);
        }

        // Marker dots above the slider (use foreground-local coordinates)
        if (windowSlider != null) {
            int sx = windowSlider.getX() - this.x; // convert to local coords
            int sy = windowSlider.getY() - this.y;
            int sw = windowSlider.getWidth();
            int dotY = sy - 4;
            int g = effectiveG();
            if (g > 0) {
                int gold = 0xFFFFCC00;
                for (int k = 0; k <= g; k++) {
                    double t = (double) k / (double) g;
                    int dx = (int) Math.round(sx + t * sw);
                    fillDot(context, dx, dotY, gold);
                }
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
        if (this.widthSlider != null) {
            this.widthSlider.syncTo(this.pathWidth);
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
