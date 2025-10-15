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
    private int gradientWindowMain = 1; // 0..9 (server synced)
    private int gradientWindowStep = 1; // 0..9 (server synced)
    private int pathWidth = 3;      // server synced
    private String[] gradientMainBlocks = new String[9];
    private String[] gradientStepBlocks = new String[9];

    private WindowSlider windowSliderMain;
    private WindowSlider windowSliderStep;
    private WidthSlider widthSlider;
    private boolean isDragging = false;
    private int dragButton = -1;
    private java.util.Set<Integer> dragVisited = new java.util.HashSet<>();

    private class WindowSlider extends SliderWidget {
        private final int row; // 0 = main, 1 = step
        public WindowSlider(int x, int y, int width, int height, double norm) {
            this(x, y, width, height, norm, 0);
        }
        public WindowSlider(int x, int y, int width, int height, double norm, int row) {
            super(x, y, width, height, Text.literal("Window"), norm);
            this.row = row;
        }
        @Override
        protected void updateMessage() {
            int g = effectiveG(row);
            int w = (g <= 0) ? 0 : (int) Math.round(this.value * g);
            w = Math.max(0, Math.min(g, w));
            this.setMessage(Text.literal("Window: " + w));
        }
        @Override
        protected void applyValue() {
            int g = effectiveG(row);
            int w = (g <= 0) ? 0 : (int) Math.round(this.value * g);
            w = Math.max(0, Math.min(g, w));
            int current = (row == 0) ? gradientWindowMain : gradientWindowStep;
            if (w != current) {
                if (row == 0) gradientWindowMain = w; else gradientWindowStep = w;
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientWindowC2SPayload(getEntityId(), row, w));
            }
        }
        public void syncTo(int row, int g, int window) {
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
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetPathWidthC2SPayload(getEntityId(), pathWidth));
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

        // Draw gradient slot frames and items on top of background (two rows)
        int slotsX = this.x + 8;
        int slotY0 = this.y + 26; // first row
        int slotY1 = slotY0 + 18 + 6; // second row below with small gap
        // Frames: 18x18 area with 1px border and darker inner background
        int borderColor = 0xFF555555; // medium-dark border
        int innerColor = 0xFF1C1C1C;  // darker inner background
        for (int i = 0; i < 9; i++) {
            int fx = slotsX + i * 18;
            int fy = slotY0;
            // outer border (18x18 around the 16x16 item area)
            context.fill(fx - 1, fy - 1, fx + 17, fy + 17, borderColor);
            // inner fill (16x16 item area)
            context.fill(fx, fy, fx + 16, fy + 16, innerColor);
        }
        for (int i = 0; i < 9; i++) {
            int fx = slotsX + i * 18;
            int fy = slotY1;
            // outer border (18x18 around the 16x16 item area)
            context.fill(fx - 1, fy - 1, fx + 17, fy + 17, borderColor);
            // inner fill (16x16 item area)
            context.fill(fx, fy, fx + 16, fy + 16, innerColor);
        }
        for (int i = 0; i < 9; i++) {
            String id = (gradientMainBlocks != null && i < gradientMainBlocks.length) ? gradientMainBlocks[i] : "";
            if (id != null && !id.isEmpty()) {
                var ident = net.minecraft.util.Identifier.tryParse(id);
                if (ident != null) {
                    var block = Registries.BLOCK.get(ident);
                    if (block != null) {
                        ItemStack stack = new ItemStack(block.asItem());
                        context.drawItem(stack, slotsX + i * 18, slotY0);
                    }
                }
            }
        }
        for (int i = 0; i < 9; i++) {
            String id = (gradientStepBlocks != null && i < gradientStepBlocks.length) ? gradientStepBlocks[i] : "";
            if (id != null && !id.isEmpty()) {
                var ident = net.minecraft.util.Identifier.tryParse(id);
                if (ident != null) {
                    var block = Registries.BLOCK.get(ident);
                    if (block != null) {
                        ItemStack stack = new ItemStack(block.asItem());
                        context.drawItem(stack, slotsX + i * 18, slotY1);
                    }
                }
            }
        }
        // Icons to the left (outside the window), aligned with each row
        var items = net.minecraft.item.Items.class; // ref to resolve
        ItemStack iconMain = new ItemStack(net.minecraft.item.Items.OAK_PLANKS);
        ItemStack iconStep = new ItemStack(net.minecraft.item.Items.OAK_STAIRS);
        int iconX = this.x - 20;
        context.drawItem(iconMain, iconX, slotY0);
        context.drawItem(iconStep, iconX, slotY1);
    }

    @Override
    protected void init() {
        super.init();
        // Place window slider in the controls margin area. Gradient slots are handled via mouse clicks, not buttons.
        int controlsTop = this.y + 8; // leave a small header gap
        // int slotsX = this.x + 8; // for reference
        // int slotY = controlsTop + 18; // below title area

        // Window sliders to the right of the ghost grids
        int wx = this.x + 8 + 9 * 18 + 12;
        int wy0 = controlsTop + 18; // align with first gradient row
        int wy1 = wy0 + 18 + 6;     // second row
        int sliderWidth = 90;
        int sliderHeight = 20;
        int g0 = effectiveG(0);
        double norm0 = g0 <= 0 ? 0.0 : (double) Math.min(gradientWindowMain, g0) / (double) g0;
        windowSliderMain = new WindowSlider(wx, wy0, sliderWidth, sliderHeight, norm0, 0);
        this.addDrawableChild(windowSliderMain);
        int g1 = effectiveG(1);
        double norm1 = g1 <= 0 ? 0.0 : (double) Math.min(gradientWindowStep, g1) / (double) g1;
        windowSliderStep = new WindowSlider(wx, wy1, sliderWidth, sliderHeight, norm1, 1);
        this.addDrawableChild(windowSliderStep);
        // Transparent buttons over gradient slots for reliable clicks (both rows)
        int slotsX = this.x + 8;
        int slotY0 = this.y + 26;
        int slotY1 = slotY0 + 18 + 6;
        for (int i = 0; i < 9; i++) {
            final int idx = i;
            int gx = slotsX + i * 18;
            var btn0 = ButtonWidget.builder(Text.empty(), b -> {
                var mc = MinecraftClient.getInstance();
                var player = mc.player;
                if (player == null) return;
                // Always use the item on the mouse cursor; never fall back to hotbar
                ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                ItemStack held = cursor;
                boolean clear = held.isEmpty() || !(held.getItem() instanceof BlockItem);
                if (clear) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), 0, idx, java.util.Optional.empty()));
                } else {
                    BlockItem bi = (BlockItem) held.getItem();
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), 0, idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                }
            }).dimensions(gx, slotY0, 18, 18).build();
            btn0.setAlpha(0f);
            this.addDrawableChild(btn0);

            var btn1 = ButtonWidget.builder(Text.empty(), b -> {
                var mc = MinecraftClient.getInstance();
                var player = mc.player;
                if (player == null) return;
                ItemStack cursor = player.currentScreenHandler != null ? player.currentScreenHandler.getCursorStack() : ItemStack.EMPTY;
                ItemStack held = cursor;
                boolean clear = held.isEmpty() || !(held.getItem() instanceof BlockItem);
                if (clear) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), 1, idx, java.util.Optional.empty()));
                } else {
                    BlockItem bi = (BlockItem) held.getItem();
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), 1, idx, java.util.Optional.of(Registries.BLOCK.getId(bi.getBlock()))));
                }
            }).dimensions(gx, slotY1, 18, 18).build();
            btn1.setAlpha(0f);
            this.addDrawableChild(btn1);
        }

        // Width slider under the gradient row (right-aligned)
        int wsliderW = 90;
        int wsliderH = 12;
        int slotTop = this.y + 26; // top of first gradient row
        int wsliderY = slotTop + (18 + 6) + 18 + 6; // below second row
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
            RowCol rc = gradientIndexAt((int) click.x(), (int) click.y());
            if (rc != null) {
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(getEntityId(), rc.row, rc.col, java.util.Optional.empty()));
                return true;
            }
        }
        return super.mouseClicked(click, traced);
    }

    private static class RowCol { final int row; final int col; RowCol(int r, int c){row=r;col=c;} }
    private RowCol gradientIndexAt(int mx, int my) {
        int slotsX = this.x + 8;
        int slotY0 = this.y + 26; // first row
        int slotY1 = slotY0 + 18 + 6; // second row
        int w = 18, h = 18, pad = 18;
        // First row
        if (my >= slotY0 && my < slotY0 + h) {
            int dx = mx - slotsX;
            if (dx >= 0) {
                int col = dx / pad;
                if (col >= 0 && col < 9) {
                    int colX = slotsX + col * pad;
                    if (mx >= colX && mx < colX + w) return new RowCol(0, col);
                }
            }
        }
        // Second row
        if (my >= slotY1 && my < slotY1 + h) {
            int dx = mx - slotsX;
            if (dx >= 0) {
                int col = dx / pad;
                if (col >= 0 && col < 9) {
                    int colX = slotsX + col * pad;
                    if (mx >= colX && mx < colX + w) return new RowCol(1, col);
                }
            }
        }
        return null;
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

        // Marker dots above each window slider
        drawSliderMarkers(context, windowSliderMain, effectiveG(0));
        drawSliderMarkers(context, windowSliderStep, effectiveG(1));
    }

    public int getEntityId() {
        return this.handler.getEntityId();
    }

    public void applyServerSync(int width, int windowMain, int windowStep, String[] blocksMain, String[] blocksStep) {
        this.pathWidth = width;
        this.gradientWindowMain = windowMain;
        this.gradientWindowStep = windowStep;
        if (blocksMain != null) {
            if (blocksMain.length != 9) this.gradientMainBlocks = new String[9];
            System.arraycopy(blocksMain, 0, this.gradientMainBlocks, 0, Math.min(9, blocksMain.length));
        }
        if (blocksStep != null) {
            if (blocksStep.length != 9) this.gradientStepBlocks = new String[9];
            System.arraycopy(blocksStep, 0, this.gradientStepBlocks, 0, Math.min(9, blocksStep.length));
        }
        if (this.windowSliderMain != null) {
            int g = effectiveG(0);
            this.windowSliderMain.syncTo(0, g, gradientWindowMain);
        }
        if (this.windowSliderStep != null) {
            int g = effectiveG(1);
            this.windowSliderStep.syncTo(1, g, gradientWindowStep);
        }
        if (this.widthSlider != null) {
            this.widthSlider.syncTo(this.pathWidth);
        }
    }

    private int effectiveG(int row) {
        String[] arr = row == 0 ? gradientMainBlocks : gradientStepBlocks;
        int G = 0;
        for (int i = arr.length - 1; i >= 0; i--) {
            String s = arr[i];
            if (s != null && !s.isEmpty()) { G = i + 1; break; }
        }
        if (G == 0) G = 9; // fallback
        return G;
    }

    private static void fillDot(DrawContext ctx, int cx, int cy, int argb) {
        int r = 1;
        ctx.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, argb);
    }

    private void drawSliderMarkers(DrawContext context, SliderWidget slider, int g) {
        if (slider == null) return;
        int sx = slider.getX() - this.x;
        int sy = slider.getY() - this.y;
        int sw = slider.getWidth();
        int dotY = sy - 4;
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
