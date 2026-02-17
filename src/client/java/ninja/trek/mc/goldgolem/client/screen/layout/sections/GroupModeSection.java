package ninja.trek.mc.goldgolem.client.screen.layout.sections;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen;
import ninja.trek.mc.goldgolem.client.screen.GroupModeStrategy;
import ninja.trek.mc.goldgolem.client.screen.layout.AbstractGuiSection;
import ninja.trek.mc.goldgolem.client.screen.layout.LayoutContext;
import ninja.trek.mc.goldgolem.util.GradientSlotUtil;

import java.util.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Section for group-based build modes (WALL, TOWER, TREE).
 * Owns all rendering, click handling, drag-drop, and icon management for group modes.
 */
public class GroupModeSection extends AbstractGuiSection {
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 6;
    private static final int ROW_SPACING = ROW_HEIGHT + ROW_GAP;
    private static final int SLOTS_PER_ROW = 9;
    private static final int SLOT_SIZE = 18;

    private final GolemHandledScreen screen;
    private final Font textRenderer;
    private int maxVisibleRows = 6;

    // Drag state
    private final List<IconHit> iconHits = new ArrayList<>();
    private String pendingAssignBlockId = null;
    private String draggingBlockId = null;
    private boolean draggingFromIcon = false;
    private int draggingStartX = 0;
    private int draggingStartY = 0;

    public GroupModeSection(GroupModeStrategy strategy, GolemHandledScreen screen, Font textRenderer) {
        this.screen = screen;
        this.textRenderer = textRenderer;
    }

    /** Always fetch the current strategy from the screen to avoid stale references. */
    private GroupModeStrategy getStrategy() {
        return screen.getGroupModeStrategy();
    }

    @Override
    public int calculateRequiredHeight(LayoutContext context) {
        GroupModeStrategy strategy = getStrategy();
        int totalGroups = strategy != null ? strategy.getVisibleGroups().size() : 0;
        if (totalGroups == 0) totalGroups = 1;
        return totalGroups * ROW_SPACING;
    }

    @Override
    public void setAllocatedHeight(int height) {
        super.setAllocatedHeight(height);
        maxVisibleRows = Math.max(1, height / ROW_SPACING);
    }

    @Override
    public boolean isPaginable() {
        return true;
    }

    // ============ RENDERING ============

    @Override
    public void renderBackground(GuiGraphics context, int guiX, int guiY) {
        // Group mode rendering is done entirely in renderForeground (renderLabels context)
        // because the old code rendered icons and rows in renderLabels with pre-translated coordinates
    }

    @Override
    public void renderForeground(GuiGraphics context, int guiX, int guiY, int mouseX, int mouseY) {
        iconHits.clear();

        GroupModeStrategy strategy = getStrategy();
        if (strategy == null) return;

        List<Integer> vis = strategy.getVisibleGroups();
        int rows = vis.size();
        int drawRows = Math.min(Math.max(0, rows - strategy.getScroll()), maxVisibleRows);
        int startY = y; // relative to GUI top
        int gridX = 8; // relative to GUI left
        int iconXOff = strategy.getIconXOffset();
        boolean showCounts = strategy.shouldShowBlockCounts();

        List<String> uniqueBlocks = strategy.getUniqueBlocks();
        List<Integer> blockGroups = strategy.getBlockGroups();
        List<String> flatSlots = strategy.getGroupFlatSlots();

        // Build group-to-blocks map
        Map<Integer, List<String>> groupToBlocks = new HashMap<>();
        if (uniqueBlocks != null && blockGroups != null) {
            int n = Math.min(uniqueBlocks.size(), blockGroups.size());
            for (int i = 0; i < n; i++) {
                int g = blockGroups.get(i);
                if (g >= 0) {
                    groupToBlocks.computeIfAbsent(g, k -> new ArrayList<>()).add(uniqueBlocks.get(i));
                }
            }
        }

        // Draw icons and group rows
        for (int r = 0; r < drawRows; r++) {
            int visIndex = r + strategy.getScroll();
            if (visIndex < 0 || visIndex >= vis.size()) continue;
            int groupIdx = vis.get(visIndex);
            int rowY = startY + r * ROW_SPACING;

            // Icons to the left
            List<String> blocksInGroup = groupToBlocks.getOrDefault(groupIdx, Collections.emptyList());
            for (int i = 0; i < blocksInGroup.size(); i++) {
                String id = blocksInGroup.get(i);
                var ident = Identifier.tryParse(id);
                if (ident == null) continue;
                var block = BuiltInRegistries.BLOCK.getValue(ident);
                if (block == null) continue;
                ItemStack icon = new ItemStack(block.asItem());
                int ix = iconXOff - i * 18;
                context.renderItem(icon, ix, rowY);

                // Block counts (Tower mode)
                if (showCounts) {
                    Map<String, Integer> counts = strategy.getBlockCounts();
                    int count = counts.getOrDefault(id, 0);
                    int textX = ix + 24;
                    int textY = rowY + 4;
                    context.drawString(textRenderer, "x" + count, textX, textY, 0xFFFFFFFF, true);
                }

                // Track icon hit area (absolute screen coordinates)
                iconHits.add(new IconHit(id, groupIdx, guiX + ix, guiY + rowY, 16, 16));
            }

            // Group row slots
            for (int c = 0; c < SLOTS_PER_ROW; c++) {
                int x = gridX + c * SLOT_SIZE;
                int col = 0xFF404040;
                int ix1 = x, iy1 = rowY, ix2 = x + 16, iy2 = rowY + 16;
                context.fill(ix1, iy1, ix2, iy2, 0x80000000);
                // Border
                context.fill(ix1 - 1, iy1 - 1, ix2 + 1, iy1, col);
                context.fill(ix1 - 1, iy2, ix2 + 1, iy2 + 1, col);
                context.fill(ix1 - 1, iy1, ix1, iy2, col);
                context.fill(ix2, iy1, ix2 + 1, iy2, col);
                int flatIndex = groupIdx * SLOTS_PER_ROW + c;
                if (flatIndex >= 0 && flatSlots != null && flatIndex < flatSlots.size()) {
                    String bid = flatSlots.get(flatIndex);
                    if (bid != null && !bid.isEmpty()) {
                        if (GradientSlotUtil.isMineAction(bid)) {
                            var toolItem = GradientSlotUtil.getToolItem(bid);
                            if (toolItem != null) {
                                context.renderItem(new ItemStack(toolItem), x, rowY);
                            }
                        } else {
                            var ident2 = Identifier.tryParse(bid);
                            if (ident2 != null) {
                                var block2 = BuiltInRegistries.BLOCK.getValue(ident2);
                                if (block2 != null) {
                                    context.renderItem(new ItemStack(block2.asItem()), x, rowY);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Tower totals preview
        if (showCounts) {
            renderTowerTotals(context);
        }

        // Drag cursor visual (follows mouse)
        if (draggingFromIcon && draggingBlockId != null) {
            var ident = Identifier.tryParse(draggingBlockId);
            if (ident != null) {
                var block = BuiltInRegistries.BLOCK.getValue(ident);
                if (block != null) {
                    int relX = mouseX - guiX - 8;
                    int relY = mouseY - guiY - 8;
                    context.renderItem(new ItemStack(block.asItem()), relX, relY);
                }
            }
        }
    }

    private void renderTowerTotals(GuiGraphics context) {
        GroupModeStrategy strategy = getStrategy();
        if (strategy == null) return;
        Map<String, Integer> blockCounts = strategy.getBlockCounts();
        int totalBlocks = 0;
        for (Integer count : blockCounts.values()) {
            if (count > 0) totalBlocks += count;
        }
        int totalStacks = (int) Math.ceil((double) totalBlocks / 64.0);
        StringBuilder preview = new StringBuilder();
        preview.append(totalStacks).append("st");
        if (totalStacks > 27) {
            double shulkerBoxes = Math.ceil((double) totalStacks / 27.0 * 10.0) / 10.0;
            preview.append(" ").append(String.format("%.1f", shulkerBoxes)).append("sb");
        }
        int wx2 = 8 + 9 * 18 + 12;
        int w2 = 70;
        int gap2 = 6;
        int s2 = 50;
        int previewX = wx2 + w2 + gap2 + s2 + 8;
        int previewY = y;
        context.drawString(textRenderer, Component.literal(preview.toString()), previewX, previewY, 0xFFFFFFFF, true);
    }

    // ============ CLICK HANDLING ============

    @Override
    public boolean handleClick(int mouseX, int mouseY, int button) {
        if (button != 0) return false;

        GroupModeStrategy strategy = getStrategy();
        if (strategy == null) return false;

        int guiX = screen.getGuiX();
        int guiY = screen.getGuiY();
        BuildMode mode = strategy.getMode();

        // Check icon clicks first (start drag)
        for (IconHit ih : iconHits) {
            if (ih.contains(mouseX, mouseY)) {
                pendingAssignBlockId = ih.blockId;
                draggingBlockId = ih.blockId;
                draggingFromIcon = true;
                draggingStartX = mouseX;
                draggingStartY = mouseY;
                return true;
            }
        }

        // Check slot area clicks
        int absStartY = guiY + y;
        int absGridX = guiX + 8;
        List<Integer> vis = strategy.getVisibleGroups();
        int rows = vis.size();
        int rLocal = (mouseY - absStartY) / ROW_SPACING;
        int rIdx = rLocal + strategy.getScroll();

        if (rLocal >= 0 && rLocal < maxVisibleRows && rIdx >= 0 && rIdx < rows) {
            int c = (mouseX - absGridX) / SLOT_SIZE;
            if (c >= 0 && c < SLOTS_PER_ROW) {
                if (pendingAssignBlockId != null) {
                    int groupIdx = vis.get(rIdx);
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(
                            screen.getEntityId(), mode, pendingAssignBlockId, groupIdx));
                    updateLocalBlockGroup(pendingAssignBlockId, groupIdx);
                    pendingAssignBlockId = null;
                    return true;
                }
                // Regular slot click - set/clear block
                handleSlotClick(rLocal, c);
                return true;
            }
        }

        // Click below rows in icon area → new group
        if (pendingAssignBlockId != null) {
            int bottomY = absStartY + Math.min(maxVisibleRows, Math.max(0, rows - strategy.getScroll())) * ROW_SPACING;
            int iconAreaRight = guiX + strategy.getIconXOffset() + 16;
            if (mouseY >= bottomY && mouseX >= 0 && mouseX < iconAreaRight) {
                ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(
                        screen.getEntityId(), strategy.getMode(), pendingAssignBlockId, -1));
                pendingAssignBlockId = null;
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean handleMouseRelease(int mouseX, int mouseY, int button) {
        if (!draggingFromIcon || draggingBlockId == null) return false;

        GroupModeStrategy strategy = getStrategy();
        if (strategy == null) {
            draggingFromIcon = false;
            draggingBlockId = null;
            return false;
        }

        int guiX = screen.getGuiX();
        int guiY = screen.getGuiY();
        BuildMode mode = strategy.getMode();
        boolean handled = false;

        int dx = Math.abs(mouseX - draggingStartX);
        int dy = Math.abs(mouseY - draggingStartY);
        boolean moved = (dx + dy) > 4;

        if (moved) {
            // Drop onto another icon → combine groups
            for (IconHit ih : iconHits) {
                if (ih.contains(mouseX, mouseY)) {
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(
                            screen.getEntityId(), mode, draggingBlockId, ih.group));
                    updateLocalBlockGroup(draggingBlockId, ih.group);
                    handled = true;
                    pendingAssignBlockId = null;
                    break;
                }
            }

            if (!handled) {
                int absStartY = guiY + y;
                List<Integer> vis = strategy.getVisibleGroups();
                int rows = vis.size();
                int rLocal = (mouseY - absStartY) / ROW_SPACING;
                int rIdx = rLocal + strategy.getScroll();

                if (rLocal >= 0 && rLocal < maxVisibleRows && rIdx >= 0 && rIdx < rows) {
                    int groupIdx = vis.get(rIdx);
                    ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(
                            screen.getEntityId(), mode, draggingBlockId, groupIdx));
                    updateLocalBlockGroup(draggingBlockId, groupIdx);
                    handled = true;
                    pendingAssignBlockId = null;
                } else {
                    int bottomY = absStartY + Math.min(maxVisibleRows, Math.max(0, rows - strategy.getScroll())) * ROW_SPACING;
                    int iconAreaRight = guiX + strategy.getIconXOffset() + 16;
                    if (mouseY >= bottomY && mouseX >= 0 && mouseX < iconAreaRight) {
                        ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeBlockGroupC2SPayload(
                                screen.getEntityId(), mode, draggingBlockId, -1));
                        handled = true;
                        pendingAssignBlockId = null;
                    }
                }
            }
        }

        draggingFromIcon = false;
        draggingBlockId = null;
        return handled;
    }

    private void handleSlotClick(int visualRow, int col) {
        GroupModeStrategy strategy = getStrategy();
        if (strategy == null) return;
        List<Integer> vis = strategy.getVisibleGroups();
        int scroll = strategy.getScroll();
        int actualRow = visualRow + scroll;
        if (actualRow >= 0 && actualRow < vis.size()) {
            int groupIdx = vis.get(actualRow);
            ClientPlayNetworking.send(new ninja.trek.mc.goldgolem.net.SetGroupModeSlotC2SPayload(
                    screen.getEntityId(), strategy.getMode(), groupIdx, col, screen.getCursorBlockId()));
        }
    }

    private void updateLocalBlockGroup(String blockId, int groupIdx) {
        GroupModeStrategy strategy = getStrategy();
        if (strategy == null) return;
        List<String> uniqueBlocks = strategy.getUniqueBlocks();
        List<Integer> blockGroups = strategy.getBlockGroups();
        for (int i = 0; i < uniqueBlocks.size(); i++) {
            if (uniqueBlocks.get(i).equals(blockId)) {
                if (i < blockGroups.size()) {
                    blockGroups.set(i, groupIdx);
                }
                break;
            }
        }
        screen.onGroupDataChanged();
    }

    // ============ ACCESSORS ============

    public int getMaxVisibleRows() {
        return maxVisibleRows;
    }

    public boolean isDragging() {
        return draggingFromIcon;
    }

    public String getDraggingBlockId() {
        return draggingBlockId;
    }

    public static class IconHit {
        public final String blockId;
        public final int group;
        public final int x, y, w, h;

        public IconHit(String blockId, int group, int x, int y, int w, int h) {
            this.blockId = blockId;
            this.group = group;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
