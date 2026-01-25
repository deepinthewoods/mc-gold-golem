package ninja.trek.mc.goldgolem.client.screen.layout.sections;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen;
import ninja.trek.mc.goldgolem.client.screen.layout.AbstractGuiSection;
import ninja.trek.mc.goldgolem.client.screen.layout.LayoutContext;
import ninja.trek.mc.goldgolem.client.screen.layout.WidgetAdder;

/**
 * Section for gradient-based building modes (PATH and TERRAFORMING).
 * Renders gradient slots, sliders, and handles slot clicks.
 */
public class GradientsSection extends AbstractGuiSection {
    /**
     * Gradient mode type.
     */
    public enum GradientMode {
        PATH(2),        // 2 rows of gradients
        TERRAFORMING(3); // 3 rows of gradients

        public final int rows;

        GradientMode(int rows) {
            this.rows = rows;
        }
    }

    private static final int SLOT_SIZE = 18;
    private static final int SLOTS_PER_ROW = 9;
    private static final int ROW_SPACING = 18 + 6; // slot height + gap
    private static final int BORDER_COLOR = 0xFF555555;
    private static final int INNER_COLOR = 0xFF1C1C1C;

    private final GradientMode mode;
    private final GolemHandledScreen screen;
    private final TextRenderer textRenderer;
    private int scroll = 0; // For future pagination support

    // Gradient data (managed by parent screen)
    private String[][] gradientBlocks;
    private String[] rowLabels;

    /**
     * Create a new gradients section.
     *
     * @param mode Gradient mode (PATH or TERRAFORMING)
     * @param screen Parent screen
     * @param textRenderer Text renderer for labels
     */
    public GradientsSection(GradientMode mode, GolemHandledScreen screen, TextRenderer textRenderer) {
        this.mode = mode;
        this.screen = screen;
        this.textRenderer = textRenderer;

        // Initialize gradient blocks array
        this.gradientBlocks = new String[mode.rows][SLOTS_PER_ROW];
        for (int r = 0; r < mode.rows; r++) {
            for (int c = 0; c < SLOTS_PER_ROW; c++) {
                this.gradientBlocks[r][c] = "";
            }
        }

        // Set row labels based on mode
        if (mode == GradientMode.TERRAFORMING) {
            this.rowLabels = new String[]{"Vertical", "Horizontal", "Sloped"};
        }
    }

    @Override
    public int calculateRequiredHeight(LayoutContext context) {
        // Height = (number of rows * row spacing) + top margin
        // Top margin includes space for labels if in terraforming mode
        int topMargin = (mode == GradientMode.TERRAFORMING) ? 10 : 0;
        return topMargin + (mode.rows * ROW_SPACING);
    }

    @Override
    public boolean isPaginable() {
        // Currently not paginable, but could be extended for future modes
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int guiX, int guiY) {
        int slotsX = guiX + 8;
        int baseY = guiY + y;

        // Draw slot frames and items for all rows
        for (int row = 0; row < mode.rows; row++) {
            int slotY = baseY + (mode == GradientMode.TERRAFORMING ? 10 : 0) + row * ROW_SPACING;

            // Draw slot frames
            for (int col = 0; col < SLOTS_PER_ROW; col++) {
                int fx = slotsX + col * SLOT_SIZE;
                // Border
                context.fill(fx - 1, slotY - 1, fx + 17, slotY + 17, BORDER_COLOR);
                // Inner background
                context.fill(fx, slotY, fx + 16, slotY + 16, INNER_COLOR);
            }

            // Draw block items
            for (int col = 0; col < SLOTS_PER_ROW; col++) {
                String blockId = gradientBlocks[row][col];
                if (blockId != null && !blockId.isEmpty()) {
                    Identifier ident = Identifier.tryParse(blockId);
                    if (ident != null) {
                        var block = Registries.BLOCK.get(ident);
                        if (block != null) {
                            ItemStack stack = new ItemStack(block.asItem());
                            context.drawItem(stack, slotsX + col * SLOT_SIZE, slotY);
                        }
                    }
                }
            }
        }

        // Draw icons to the left (for PATH mode)
        if (mode == GradientMode.PATH) {
            int iconX = guiX - 20;
            int row0Y = baseY;
            int row1Y = baseY + ROW_SPACING;

            ItemStack iconMain = new ItemStack(net.minecraft.item.Items.OAK_PLANKS);
            ItemStack iconStep = new ItemStack(net.minecraft.item.Items.OAK_STAIRS);
            context.drawItem(iconMain, iconX, row0Y);
            context.drawItem(iconStep, iconX, row1Y);
        }
    }

    @Override
    public void renderForeground(DrawContext context, int guiX, int guiY, int mouseX, int mouseY) {
        // Draw row labels for TERRAFORMING mode
        if (mode == GradientMode.TERRAFORMING && rowLabels != null) {
            int labelX = 8; // Relative to GUI
            int baseY = y;

            for (int row = 0; row < mode.rows && row < rowLabels.length; row++) {
                int labelY = baseY + row * ROW_SPACING;
                context.drawText(textRenderer, Text.literal(rowLabels[row]),
                        labelX, labelY, 0xFFFFFFFF, false);
            }
        }
    }

    @Override
    public boolean handleClick(int mouseX, int mouseY, int button) {
        // Slot click handling is currently managed by GolemHandledScreen
        // This will be refactored in later phases
        return false;
    }

    /**
     * Check if a click is within the gradient slots area.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param guiX GUI X position
     * @param guiY GUI Y position
     * @return SlotPosition if clicked, null otherwise
     */
    public SlotPosition getSlotAt(int mouseX, int mouseY, int guiX, int guiY) {
        int slotsX = guiX + 8;
        int baseY = guiY + y + (mode == GradientMode.TERRAFORMING ? 10 : 0);

        for (int row = 0; row < mode.rows; row++) {
            int slotY = baseY + row * ROW_SPACING;
            if (mouseY >= slotY && mouseY < slotY + SLOT_SIZE) {
                int dx = mouseX - slotsX;
                if (dx >= 0) {
                    int col = dx / SLOT_SIZE;
                    if (col >= 0 && col < SLOTS_PER_ROW) {
                        int colX = slotsX + col * SLOT_SIZE;
                        if (mouseX >= colX && mouseX < colX + SLOT_SIZE) {
                            return new SlotPosition(row, col);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Update gradient blocks for a specific row.
     *
     * @param row Row index
     * @param blocks Block IDs for this row
     */
    public void setGradientRow(int row, String[] blocks) {
        if (row >= 0 && row < mode.rows && blocks != null) {
            System.arraycopy(blocks, 0, gradientBlocks[row], 0,
                    Math.min(blocks.length, SLOTS_PER_ROW));
        }
    }

    /**
     * Slot position result.
     */
    public static class SlotPosition {
        public final int row;
        public final int col;

        public SlotPosition(int row, int col) {
            this.row = row;
            this.col = col;
        }
    }
}
