package ninja.trek.mc.goldgolem.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import ninja.trek.mc.goldgolem.screen.GolemInventoryScreenHandler;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload;
import ninja.trek.mc.goldgolem.net.SetPathWidthC2SPayload;

@Environment(EnvType.CLIENT)
public class GolemScreen extends HandledScreen<GolemInventoryScreenHandler> {
    // Chest-like constants
    private static final int PANEL_LEFT = 8;
    private static final int PANEL_TOP = 18;
    private static final int SLOT_SIZE = 18;
    private static final int PLAYER_INV_HEIGHT = 96; // 3*18 + 18 (hotbar) + gaps
    private static final Identifier CHEST_TEX = Identifier.ofVanilla("textures/gui/container/generic_54.png");

    private int golemRows;
    private int topHeight;

    // Path state mirrored from server
    private int pathWidth = 3;
    private final String[] gradient = new String[9];

    public GolemScreen(GolemInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.golemRows = handler.getGolemRows();
        this.topHeight = 17 + this.golemRows * SLOT_SIZE; // rows*18 + 17
        this.backgroundWidth = 176; // standard chest width
        this.backgroundHeight = topHeight + 97 + handler.getControlsMargin();
    }

    public int getEntityId() { return this.handler.getEntityId(); }

    public void applyServerSync(int width, String[] blocks) {
        this.pathWidth = width;
        for (int i = 0; i < 9; i++) this.gradient[i] = (i < blocks.length && blocks[i] != null) ? blocks[i] : "";
    }

    @Override
    protected void init() {
        super.init();
        // Align labels like vanilla relative to this screen's background
        this.titleX = 8;
        this.titleY = 6;
        this.playerInventoryTitleX = 8;
        this.playerInventoryTitleY = this.backgroundHeight - 96 + 2 - 18; // shift up by one slot height
        // Compute anchors
        int titleY = this.y + 6;
        int gradientY = titleY + this.textRenderer.fontHeight + 4;
        int wy = gradientY + 16 + 6;
        int sliderW = 90;
        int sliderX = this.x + this.backgroundWidth - 8 - sliderW;
        SliderWidget slider = new SliderWidget(sliderX, wy, sliderW, 12, Text.empty(), (pathWidth - 1) / 8.0) {
            @Override
            protected void updateMessage() {
                int w = 1 + (int)Math.round(this.value * 8.0);
                this.setMessage(Text.literal("W:" + w));
            }
            @Override
            protected void applyValue() {
                int w = 1 + (int)Math.round(this.value * 8.0);
                if (w != pathWidth) {
                    pathWidth = w;
                    ClientPlayNetworking.send(new SetPathWidthC2SPayload(pathWidth));
                }
            }
        };
        this.addDrawableChild(slider);

        // Add transparent click regions over the gradient slots for input handling
        int ghostY = gradientY;
        int startX = this.x + PANEL_LEFT;
        for (int i = 0; i < 9; i++) {
            int idx = i;
            int gx = startX + i * SLOT_SIZE;
            var btn = ButtonWidget.builder(Text.empty(), b -> {
                var player = this.client != null ? this.client.player : null;
                if (player == null) return;
                // Prefer cursor stack in GUI, fall back to main hand
                net.minecraft.item.ItemStack cursor = this.handler.getCursorStack();
                net.minecraft.item.ItemStack held = cursor.isEmpty() ? player.getMainHandStack() : cursor;
                if (held != null && !held.isEmpty() && held.getItem() instanceof net.minecraft.item.BlockItem bi) {
                    var id = Registries.BLOCK.getId(bi.getBlock());
                    ClientPlayNetworking.send(new SetGradientSlotC2SPayload(idx, java.util.Optional.of(id)));
                    this.gradient[idx] = id.toString();
                } else {
                    ClientPlayNetworking.send(new SetGradientSlotC2SPayload(idx, java.util.Optional.empty()));
                    this.gradient[idx] = "";
                }
            }).dimensions(gx, ghostY, 18, 18).build();
            btn.setAlpha(0f);
            this.addDrawableChild(btn);
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int left = this.x;
        int top = this.y;
        int bodyHeight = this.golemRows * 18; // only body area (no header)
        // Controls band: draw chest header and filler (vanilla style)
        int margin = handler.getControlsMargin();
        float texW = 256f, texH = 256f;
        int headerH = 17;
        // Header (u=0,v=0,h=17)
        context.drawTexturedQuad(CHEST_TEX, left, top, left + this.backgroundWidth, top + headerH,
                0f / texW, 176f / texW, 0f / texH, headerH / texH);
        // Filler under header up to margin using a 1px band from the vanilla header (stretched)
        int fillerH = Math.max(0, margin - headerH);
        if (fillerH > 0) {
            float v1 = 10f / texH;
            float v2 = 11f / texH;
            context.drawTexturedQuad(CHEST_TEX, left, top + headerH, left + this.backgroundWidth, top + headerH + fillerH,
                    0f / texW, 176f / texW, v1, v2);
        }
        // Texture is 256x256; draw body (v=17) and bottom (v=126)
        int bodyY = top + margin; // start body exactly at margin
        context.drawTexturedQuad(CHEST_TEX, left, bodyY, left + this.backgroundWidth, bodyY + bodyHeight,
                0f / texW, 176f / texW, 17f / texH, (17f + bodyHeight) / texH);
        // Bottom slice at v=126, below body
        int bottomY = bodyY + bodyHeight;
        int bottomV = 126;
        int bottomH = 96;
        context.drawTexturedQuad(CHEST_TEX, left, bottomY, left + this.backgroundWidth, bottomY + bottomH,
                0f / texW, 176f / texW, bottomV / texH, (bottomV + bottomH) / texH);

        // Draw gradient slot frames in background so items and cursor draw above
        int titleY = this.y + 6;
        int gradientY = titleY + this.textRenderer.fontHeight + 4;
        int startX = this.x + PANEL_LEFT;
        for (int i = 0; i < 9; i++) {
            int gx = startX + i * SLOT_SIZE;
            int gy = gradientY;
            int bg = 0xFF2B2B2B;    // darker fill
            int border = 0xFF000000; // dark outline
            // Outer border
            context.fill(gx, gy, gx + 18, gy + 18, border);
            // Inner background (16x16 area inset by 1px)
            context.fill(gx + 1, gy + 1, gx + 17, gy + 17, bg);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        // Titles are drawn by HandledScreen (foreground) using titleX/Y fields
        int titleY = this.y + 6;
        // Gradient items preview (draw at high Z to ensure on top of children)
        int gradientY = titleY + this.textRenderer.fontHeight + 4;
        int slotY = gradientY;
        int startX = this.x + PANEL_LEFT;
        for (int i = 0; i < 9; i++) {
            int gx = startX + i * SLOT_SIZE;
            int gy = slotY;
            String id = (i < gradient.length) ? gradient[i] : "";
            if (id != null && !id.isEmpty()) {
                var ident = Identifier.tryParse(id);
                if (ident != null) {
                    net.minecraft.item.ItemStack stack = net.minecraft.item.ItemStack.EMPTY;
                    var item = Registries.ITEM.get(ident);
                    if (item != net.minecraft.item.Items.AIR) {
                        stack = new net.minecraft.item.ItemStack(item);
                    } else {
                        var block = Registries.BLOCK.get(ident);
                        if (block != null) stack = new net.minecraft.item.ItemStack(block.asItem());
                    }
                    if (!stack.isEmpty()) {
                        // Render item within the slot (offset by 1px to center in 16x16 area)
                        context.drawItem(stack, gx + 1, gy + 1);
                    }
                }
            }
        }
        this.drawMouseoverTooltip(context, mouseX, mouseY);
    }

    // Use default drawForeground to render titles at configured positions

    // Input handled by transparent widgets added in init().
}
