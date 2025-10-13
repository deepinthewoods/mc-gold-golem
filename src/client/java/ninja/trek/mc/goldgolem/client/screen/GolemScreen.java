package ninja.trek.mc.goldgolem.client.screen;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
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
        this.golemRows = (GolemInventoryScreenHandler.GOLEM_SLOT_COUNT + 8) / 9; // 3 rows for 27 slots
        this.topHeight = 17 + this.golemRows * SLOT_SIZE; // rows*18 + 17
        this.backgroundWidth = 176; // standard chest width
        this.backgroundHeight = topHeight + 97 + GolemInventoryScreenHandler.CONTROLS_MARGIN; // add margin for controls
    }

    public int getEntityId() { return this.handler.getEntityId(); }

    public void applyServerSync(int width, String[] blocks) {
        this.pathWidth = width;
        for (int i = 0; i < 9; i++) this.gradient[i] = (i < blocks.length && blocks[i] != null) ? blocks[i] : "";
    }

    @Override
    protected void init() {
        super.init();
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

        // Invisible buttons over ghost slots to handle clicks
        int ghostY = gradientY;
        int startX = this.x + PANEL_LEFT;
        for (int i = 0; i < 9; i++) {
            int idx = i;
            int gx = startX + i * SLOT_SIZE;
            this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                var player = this.client != null ? this.client.player : null;
                if (player == null) return;
                var held = player.getMainHandStack();
                if (held.getItem() instanceof net.minecraft.item.BlockItem bi) {
                    var id = Registries.BLOCK.getId(bi.getBlock());
                    ClientPlayNetworking.send(new SetGradientSlotC2SPayload(idx, java.util.Optional.of(id)));
                    this.gradient[idx] = id.toString();
                } else {
                    ClientPlayNetworking.send(new SetGradientSlotC2SPayload(idx, java.util.Optional.empty()));
                    this.gradient[idx] = "";
                }
            }).dimensions(gx, ghostY, 16, 14).build());
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int left = this.x;
        int top = this.y;
        int bodyHeight = this.golemRows * 18; // only body area (no header)
        // Controls band: draw chest header and filler (vanilla style)
        int margin = GolemInventoryScreenHandler.CONTROLS_MARGIN;
        float texW = 256f, texH = 256f;
        int headerH = 17;
        // Header (u=0,v=0,h=17)
        context.drawTexturedQuad(CHEST_TEX, left, top, left + this.backgroundWidth, top + headerH,
                0f / texW, 176f / texW, 0f / texH, headerH / texH);
        // Filler under header up to margin using neutral tone (avoid grid under controls)
        int fillerH = Math.max(0, margin - headerH);
        if (fillerH > 0) {
            context.fill(left, top + headerH, left + this.backgroundWidth, top + headerH + fillerH, 0xFF3A3A3A);
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
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        // Titles at the top of window
        int titleY = this.y + 6;
        context.drawText(this.textRenderer, this.title, this.x + 8, titleY, 0x404040, false);
        int bodyHeightForLabel = this.golemRows * 18;
        context.drawText(this.textRenderer, this.playerInventoryTitle, this.x + 8, this.y + GolemInventoryScreenHandler.CONTROLS_MARGIN + bodyHeightForLabel + 6, 0x404040, false);
        // Ghost gradient slots and previews
        int gradientY = titleY + this.textRenderer.fontHeight + 4;
        int ghostY = gradientY;
        int startX = this.x + PANEL_LEFT;
        for (int i = 0; i < 9; i++) {
            int gx = startX + i * SLOT_SIZE;
            context.fill(gx - 1, ghostY - 1, gx - 1 + 18, ghostY - 1 + 16, 0x40FFFFFF);
            context.fill(gx, ghostY, gx + 16, ghostY + 14, 0x40000000);
            String id = (i < gradient.length) ? gradient[i] : "";
            if (id != null && !id.isEmpty()) {
                var ident = Identifier.tryParse(id);
                if (ident != null) {
                    var block = Registries.BLOCK.get(ident);
                    if (block != null) {
                        var stack = new net.minecraft.item.ItemStack(block.asItem());
                        var player = this.client != null ? this.client.player : null;
                        if (player != null) {
                            context.drawItem(player, stack, gx, ghostY - 1, 0);
                        }
                    }
                }
            }
        }
        this.drawMouseoverTooltip(context, mouseX, mouseY);
    }

    // Input handled via invisible buttons in init() and standard widget routing.
}
