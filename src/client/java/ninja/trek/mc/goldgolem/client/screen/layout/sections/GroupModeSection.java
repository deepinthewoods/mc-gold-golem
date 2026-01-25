package ninja.trek.mc.goldgolem.client.screen.layout.sections;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.client.screen.GroupModeStrategy;
import ninja.trek.mc.goldgolem.client.screen.layout.AbstractGuiSection;
import ninja.trek.mc.goldgolem.client.screen.layout.LayoutContext;

import java.util.*;

/**
 * Section for group-based build modes (WALL, TOWER, TREE).
 * Supports dynamic row count based on allocated space and pagination.
 */
public class GroupModeSection extends AbstractGuiSection {
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 6;
    private static final int ROW_SPACING = ROW_HEIGHT + ROW_GAP;
    private static final int SLOTS_PER_ROW = 9;
    private static final int SLOT_SIZE = 18;
    private static final int SCROLL_BUTTON_HEIGHT = 12;

    private final GroupModeStrategy strategy;
    private final TextRenderer textRenderer;
    private int maxVisibleRows = 6; // Default, will be calculated dynamically

    /**
     * Create a new group mode section.
     *
     * @param strategy Group mode strategy (Wall, Tower, or Tree)
     * @param textRenderer Text renderer for block counts
     */
    public GroupModeSection(GroupModeStrategy strategy, TextRenderer textRenderer) {
        this.strategy = strategy;
        this.textRenderer = textRenderer;
    }

    @Override
    public int calculateRequiredHeight(LayoutContext context) {
        // Ideal height: all groups visible without scrolling
        int totalGroups = strategy.getVisibleGroups().size();
        if (totalGroups == 0) totalGroups = 1; // Minimum 1 row even if empty

        return totalGroups * ROW_SPACING + SCROLL_BUTTON_HEIGHT;
    }

    @Override
    public void setAllocatedHeight(int height) {
        super.setAllocatedHeight(height);

        // Calculate how many rows can fit in allocated space
        int availableForRows = height - SCROLL_BUTTON_HEIGHT;
        maxVisibleRows = Math.max(1, availableForRows / ROW_SPACING);
    }

    @Override
    public boolean isPaginable() {
        // This section supports pagination via scrolling
        return true;
    }

    @Override
    public void renderBackground(DrawContext context, int guiX, int guiY) {
        int baseX = guiX + 8;
        int baseY = guiY + y;

        List<Integer> visibleGroups = strategy.getVisibleGroups();
        int scroll = strategy.getScroll();
        int totalGroups = visibleGroups.size();

        // Calculate visible row range
        int startRow = scroll;
        int endRow = Math.min(startRow + maxVisibleRows, totalGroups);

        // Render visible rows
        for (int r = 0; r < maxVisibleRows && (startRow + r) < totalGroups; r++) {
            int groupIdx = visibleGroups.get(startRow + r);
            int rowY = baseY + r * ROW_SPACING;

            renderGroupRow(context, guiX, guiY, baseX, rowY, groupIdx, r);
        }

        // Render scroll buttons if needed
        if (totalGroups > maxVisibleRows) {
            renderScrollButtons(context, guiX, guiY);
        }
    }

    /**
     * Render a single group row.
     */
    private void renderGroupRow(DrawContext context, int guiX, int guiY,
                                  int baseX, int rowY, int groupIdx, int visualRow) {
        // Render icons on the left
        renderGroupIcons(context, guiX, rowY, groupIdx);

        // Render block slots
        renderBlockSlots(context, baseX, rowY, groupIdx);

        // Render block counts (Tower mode only)
        if (strategy.shouldShowBlockCounts()) {
            renderBlockCounts(context, guiX, rowY, groupIdx);
        }
    }

    /**
     * Render group icons (blocks assigned to this group).
     */
    private void renderGroupIcons(DrawContext context, int guiX, int rowY, int groupIdx) {
        int iconX = guiX + strategy.getIconXOffset();

        // Build map of blocks to groups
        Map<Integer, List<String>> groupToBlocks = new HashMap<>();
        List<String> uniqueBlocks = strategy.getUniqueBlocks();
        List<Integer> blockGroups = strategy.getBlockGroups();

        for (int i = 0; i < uniqueBlocks.size() && i < blockGroups.size(); i++) {
            int g = blockGroups.get(i);
            groupToBlocks.computeIfAbsent(g, k -> new ArrayList<>()).add(uniqueBlocks.get(i));
        }

        // Render icons for this group
        List<String> blocks = groupToBlocks.getOrDefault(groupIdx, Collections.emptyList());
        for (int i = 0; i < blocks.size(); i++) {
            String blockId = blocks.get(i);
            Identifier ident = Identifier.tryParse(blockId);
            if (ident != null) {
                var block = Registries.BLOCK.get(ident);
                if (block != null) {
                    ItemStack icon = new ItemStack(block.asItem());
                    int ix = iconX - i * 18; // Stack leftward
                    context.drawItem(icon, ix, rowY);
                }
            }
        }
    }

    /**
     * Render block slots for a group.
     */
    private void renderBlockSlots(DrawContext context, int baseX, int rowY, int groupIdx) {
        List<String> flatSlots = strategy.getGroupFlatSlots();
        int borderColor = 0xFF555555;
        int innerColor = 0xFF1C1C1C;

        // Draw slot frames and items
        for (int col = 0; col < SLOTS_PER_ROW; col++) {
            int slotX = baseX + col * SLOT_SIZE;

            // Draw frame
            context.fill(slotX - 1, rowY - 1, slotX + 17, rowY + 17, borderColor);
            context.fill(slotX, rowY, slotX + 16, rowY + 16, innerColor);

            // Draw block item if present
            int slotIndex = groupIdx * SLOTS_PER_ROW + col;
            if (slotIndex >= 0 && slotIndex < flatSlots.size()) {
                String blockId = flatSlots.get(slotIndex);
                if (blockId != null && !blockId.isEmpty()) {
                    Identifier ident = Identifier.tryParse(blockId);
                    if (ident != null) {
                        var block = Registries.BLOCK.get(ident);
                        if (block != null) {
                            ItemStack stack = new ItemStack(block.asItem());
                            context.drawItem(stack, slotX, rowY);
                        }
                    }
                }
            }
        }
    }

    /**
     * Render block counts next to icons (Tower mode).
     */
    private void renderBlockCounts(DrawContext context, int guiX, int rowY, int groupIdx) {
        int iconX = guiX + strategy.getIconXOffset();
        Map<String, Integer> blockCounts = strategy.getBlockCounts();

        // Build group to blocks map
        Map<Integer, List<String>> groupToBlocks = new HashMap<>();
        List<String> uniqueBlocks = strategy.getUniqueBlocks();
        List<Integer> blockGroups = strategy.getBlockGroups();

        for (int i = 0; i < uniqueBlocks.size() && i < blockGroups.size(); i++) {
            int g = blockGroups.get(i);
            groupToBlocks.computeIfAbsent(g, k -> new ArrayList<>()).add(uniqueBlocks.get(i));
        }

        List<String> blocks = groupToBlocks.getOrDefault(groupIdx, Collections.emptyList());
        for (int i = 0; i < blocks.size(); i++) {
            String blockId = blocks.get(i);
            int count = blockCounts.getOrDefault(blockId, 0);
            String countText = "x" + count;

            int ix = iconX - i * 18;
            int textX = ix + 24; // Shifted right to avoid overlap
            int textY = rowY + 4;

            context.drawText(textRenderer, Text.literal(countText), textX, textY, 0xFFFFFFFF, true);
        }
    }

    /**
     * Render scroll buttons.
     */
    private void renderScrollButtons(DrawContext context, int guiX, int guiY) {
        // Scroll buttons would be rendered here
        // For now, placeholder - will be implemented with actual button widgets
    }

    @Override
    public void renderForeground(DrawContext context, int guiX, int guiY, int mouseX, int mouseY) {
        // No foreground rendering needed currently
    }

    @Override
    public boolean handleClick(int mouseX, int mouseY, int button) {
        // Click handling will be delegated back to parent screen for now
        // This will be refactored in Phase 8
        return false;
    }

    /**
     * Get the maximum number of visible rows based on allocated height.
     */
    public int getMaxVisibleRows() {
        return maxVisibleRows;
    }

    /**
     * Check if a click is within a group slot.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param guiX GUI X position
     * @param guiY GUI Y position
     * @return SlotPosition if clicked, null otherwise
     */
    public SlotPosition getSlotAt(int mouseX, int mouseY, int guiX, int guiY) {
        int baseX = guiX + 8;
        int baseY = guiY + y;
        int scroll = strategy.getScroll();

        for (int r = 0; r < maxVisibleRows; r++) {
            int rowY = baseY + r * ROW_SPACING;
            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                int dx = mouseX - baseX;
                if (dx >= 0) {
                    int col = dx / SLOT_SIZE;
                    if (col >= 0 && col < SLOTS_PER_ROW) {
                        int colX = baseX + col * SLOT_SIZE;
                        if (mouseX >= colX && mouseX < colX + SLOT_SIZE) {
                            int visualRow = r;
                            int actualRow = scroll + visualRow;
                            return new SlotPosition(visualRow, actualRow, col);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Slot position result for group mode.
     */
    public static class SlotPosition {
        public final int visualRow; // Row on screen (0-5)
        public final int actualRow; // Row in data (accounting for scroll)
        public final int col;

        public SlotPosition(int visualRow, int actualRow, int col) {
            this.visualRow = visualRow;
            this.actualRow = actualRow;
            this.col = col;
        }
    }
}
