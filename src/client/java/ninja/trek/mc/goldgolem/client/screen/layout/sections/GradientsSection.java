package ninja.trek.mc.goldgolem.client.screen.layout.sections;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen;
import ninja.trek.mc.goldgolem.util.GradientSlotUtil;
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
        PATH(3),        // 3 rows of gradients (surface, main, step)
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
    private final Font textRenderer;
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
    public GradientsSection(GradientMode mode, GolemHandledScreen screen, Font textRenderer) {
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
        return mode.rows * ROW_SPACING;
    }

    @Override
    public boolean isPaginable() {
        // Currently not paginable, but could be extended for future modes
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics context, int guiX, int guiY) {
        int slotsX = guiX + 8;
        int baseY = guiY + y;

        // Draw slot frames and items for all rows
        for (int row = 0; row < mode.rows; row++) {
            int slotY = baseY + row * ROW_SPACING;

            // Draw slot frames
            for (int col = 0; col < SLOTS_PER_ROW; col++) {
                int fx = slotsX + col * SLOT_SIZE;
                // Border
                context.fill(fx - 1, slotY - 1, fx + 17, slotY + 17, BORDER_COLOR);
                // Inner background
                context.fill(fx, slotY, fx + 16, slotY + 16, INNER_COLOR);
            }

            // Draw block items (or tool icon for mine slots)
            for (int col = 0; col < SLOTS_PER_ROW; col++) {
                String blockId = gradientBlocks[row][col];
                if (blockId != null && !blockId.isEmpty()) {
                    if (GradientSlotUtil.isMineAction(blockId)) {
                        var toolItem = GradientSlotUtil.getToolItem(blockId);
                        if (toolItem != null) {
                            context.renderItem(new ItemStack(toolItem), slotsX + col * SLOT_SIZE, slotY);
                        }
                    } else {
                        Identifier ident = Identifier.tryParse(blockId);
                        if (ident != null) {
                            var block = BuiltInRegistries.BLOCK.getValue(ident);
                            if (block != null) {
                                ItemStack stack = new ItemStack(block.asItem());
                                context.renderItem(stack, slotsX + col * SLOT_SIZE, slotY);
                            }
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
            int row2Y = baseY + ROW_SPACING * 2;

            ItemStack iconSurface = new ItemStack(net.minecraft.world.item.Items.SHORT_GRASS);
            ItemStack iconMain = new ItemStack(net.minecraft.world.item.Items.OAK_PLANKS);
            ItemStack iconStep = new ItemStack(net.minecraft.world.item.Items.OAK_STAIRS);
            context.renderItem(iconSurface, iconX, row0Y);
            context.renderItem(iconMain, iconX, row1Y);
            context.renderItem(iconStep, iconX, row2Y);
        }
    }

    @Override
    public void renderForeground(GuiGraphics context, int guiX, int guiY, int mouseX, int mouseY) {
        // Draw row labels for TERRAFORMING mode
        if (mode == GradientMode.TERRAFORMING && rowLabels != null) {
            int labelX = 8; // Relative to GUI
            int baseY = y;

            for (int row = 0; row < mode.rows && row < rowLabels.length; row++) {
                int labelY = baseY + row * ROW_SPACING - 10; // 10px above slot
                context.drawString(textRenderer, Component.literal(rowLabels[row]),
                        labelX, labelY, 0xFFFFFFFF, false);
            }
        }
    }

    @Override
    public boolean handleClick(int mouseX, int mouseY, int button) {
        if (button != 0) return false;
        int guiX = screen.getGuiX();
        int guiY = screen.getGuiY();
        SlotPosition pos = getSlotAt(mouseX, mouseY, guiX, guiY);
        if (pos != null) {
            var blockId = screen.getCursorBlockId();
            if (mode == GradientMode.PATH) {
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGradientSlotC2SPayload(
                        screen.getEntityId(), pos.row, pos.col, blockId));
            } else if (mode == GradientMode.TERRAFORMING) {
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetTerraformingGradientSlotC2SPayload(
                        screen.getEntityId(), pos.row, pos.col, blockId));
            }
            return true;
        }
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
        int baseY = guiY + y;

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
