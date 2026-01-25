package ninja.trek.mc.goldgolem.client.screen.layout.sections;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.client.screen.layout.AbstractGuiSection;
import ninja.trek.mc.goldgolem.client.screen.layout.LayoutContext;

/**
 * Section for rendering golem and player inventories.
 * This section is never paginable - inventories must always be fully visible.
 */
public class InventoriesSection extends AbstractGuiSection {
    private static final Identifier GENERIC_CONTAINER_TEXTURE =
            Identifier.of("minecraft", "textures/gui/container/generic_54.png");

    private final int golemRows;
    private final TextRenderer textRenderer;
    private final Text playerInventoryTitle;

    /**
     * Create a new inventories section.
     *
     * @param golemRows Number of rows in the golem inventory
     * @param textRenderer Text renderer for drawing labels
     * @param playerInventoryTitle Title text for player inventory
     */
    public InventoriesSection(int golemRows, TextRenderer textRenderer, Text playerInventoryTitle) {
        this.golemRows = golemRows;
        this.textRenderer = textRenderer;
        this.playerInventoryTitle = playerInventoryTitle;
    }

    @Override
    public int calculateRequiredHeight(LayoutContext context) {
        // Golem inventory: golemRows * 18 pixels per row
        // Player inventory: 94 pixels fixed (3 rows of 18 + hotbar + margins)
        return golemRows * 18 + 94;
    }

    @Override
    public boolean isPaginable() {
        // Inventories must always be fully visible
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int guiX, int guiY) {
        int left = guiX;
        int top = guiY + y; // y is the section position from top of GUI
        int bodyH = golemRows * 18; // golem inventory height
        int bottomH = 96; // player inventory + hotbar height

        float texW = 256f;
        float texH = 256f;

        // Body (golem inventory) - source v starts at 17
        if (bodyH > 0) {
            context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                    left, top,
                    left + 176, top + bodyH,
                    0f / texW, 176f / texW,
                    17f / texH, (17f + bodyH) / texH);
        }

        // Bottom (player inventory + hotbar) slice at v=126
        int bottomY = top + bodyH;
        context.drawTexturedQuad(GENERIC_CONTAINER_TEXTURE,
                left, bottomY,
                left + 176, bottomY + bottomH,
                0f / texW, 176f / texW,
                126f / texH, (126f + bottomH) / texH);
    }

    @Override
    public void renderForeground(DrawContext context, int guiX, int guiY, int mouseX, int mouseY) {
        // Player inventory label (foreground coordinates are relative to GUI top-left)
        // Position is relative to this section's Y position
        int labelY = y + golemRows * 18 + 2; // 2px below golem inventory
        context.drawText(textRenderer, playerInventoryTitle, 8, labelY, 0xFF404040, false);
    }
}
