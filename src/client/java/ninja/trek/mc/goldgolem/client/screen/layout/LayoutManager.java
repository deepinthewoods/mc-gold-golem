package ninja.trek.mc.goldgolem.client.screen.layout;

import java.util.List;
import java.util.ArrayList;

/**
 * Manages the layout calculation for all GUI sections.
 * Implements the algorithm for positioning and sizing sections to fit within screen constraints.
 */
public class LayoutManager {
    private final LayoutContext context;
    private final List<GuiSection> sections;

    /**
     * Create a new layout manager.
     *
     * @param context Layout context with screen dimensions
     * @param sections List of sections to layout
     */
    public LayoutManager(LayoutContext context, List<GuiSection> sections) {
        this.context = context;
        this.sections = sections;
    }

    /**
     * Calculate the layout for all sections.
     *
     * Algorithm:
     * 1. Calculate fixed heights (header, title, non-paginable sections)
     * 2. Calculate available space for paginable sections
     * 3. Allocate space to paginable sections (may enable pagination)
     * 4. Set positions for all sections
     *
     * @return LayoutResult containing total GUI height and section positions
     */
    public LayoutResult calculateLayout() {
        // Step 1: Calculate fixed heights
        int fixedHeight = context.getFixedHeaderHeight();

        // Calculate required heights for all sections
        List<SectionHeight> sectionHeights = new ArrayList<>();
        for (GuiSection section : sections) {
            int requiredHeight = section.calculateRequiredHeight(context);
            sectionHeights.add(new SectionHeight(section, requiredHeight, section.isPaginable()));
        }

        // Sum up non-paginable section heights
        int nonPaginableHeight = 0;
        int paginableRequiredHeight = 0;
        for (SectionHeight sh : sectionHeights) {
            if (sh.isPaginable) {
                paginableRequiredHeight += sh.requiredHeight;
            } else {
                nonPaginableHeight += sh.requiredHeight;
            }
        }

        // Step 2: Calculate available space
        int maxGuiHeight = context.getMaxGuiHeight();
        int availableForContent = maxGuiHeight - fixedHeight;
        int availableForPaginable = availableForContent - nonPaginableHeight;

        // Step 3: Allocate heights to sections
        // If paginable sections fit, use their required height
        // Otherwise, split available space proportionally
        for (SectionHeight sh : sectionHeights) {
            if (sh.isPaginable) {
                if (paginableRequiredHeight <= availableForPaginable) {
                    // All paginable content fits - use required height
                    sh.allocatedHeight = sh.requiredHeight;
                } else {
                    // Paginable content doesn't fit - split available space proportionally
                    // For now, just use all available space (assuming single paginable section)
                    // TODO: If multiple paginable sections, split proportionally
                    sh.allocatedHeight = Math.max(40, availableForPaginable); // Min 40px for pagination
                }
            } else {
                // Non-paginable sections always get their required height
                sh.allocatedHeight = sh.requiredHeight;
            }
            sh.section.setAllocatedHeight(sh.allocatedHeight);
        }

        // Step 4: Set positions
        int currentY = 0;
        for (SectionHeight sh : sectionHeights) {
            sh.section.setPosition(currentY);
            currentY += sh.allocatedHeight;
        }

        // Calculate total GUI height
        int totalGuiHeight = fixedHeight + currentY;

        return new LayoutResult(totalGuiHeight, sections);
    }

    /**
     * Internal class to track section height calculations.
     */
    private static class SectionHeight {
        final GuiSection section;
        final int requiredHeight;
        final boolean isPaginable;
        int allocatedHeight;

        SectionHeight(GuiSection section, int requiredHeight, boolean isPaginable) {
            this.section = section;
            this.requiredHeight = requiredHeight;
            this.isPaginable = isPaginable;
            this.allocatedHeight = requiredHeight; // Default to required
        }
    }

    /**
     * Result of layout calculation.
     */
    public static class LayoutResult {
        /** Total GUI height including header */
        public final int guiHeight;

        /** List of sections with positions and heights set */
        public final List<GuiSection> sections;

        public LayoutResult(int guiHeight, List<GuiSection> sections) {
            this.guiHeight = guiHeight;
            this.sections = sections;
        }
    }
}
