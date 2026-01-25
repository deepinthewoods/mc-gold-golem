package ninja.trek.mc.goldgolem.client.screen.layout;

import net.minecraft.client.gui.DrawContext;

/**
 * Represents a self-contained UI section that knows its own size and can render itself.
 * Sections are positioned and sized by the LayoutManager, then render their contents independently.
 */
public interface GuiSection {
    /**
     * Calculate the minimum required height for this section given the current context.
     *
     * @param context Layout context containing screen dimensions and constants
     * @return Minimum height in pixels required to display this section's content
     */
    int calculateRequiredHeight(LayoutContext context);

    /**
     * Set the height allocated to this section by the LayoutManager.
     * The section should use this to determine if scrolling/pagination is needed.
     *
     * @param height Allocated height in pixels
     */
    void setAllocatedHeight(int height);

    /**
     * Set the Y position of this section relative to the GUI top.
     *
     * @param y Y position in pixels from GUI top
     */
    void setPosition(int y);

    /**
     * Initialize and register widgets for this section.
     * Called during GUI initialization to add interactive elements.
     *
     * @param widgetAdder Callback to register widgets with the screen
     */
    void initializeWidgets(WidgetAdder widgetAdder);

    /**
     * Render the background elements of this section (slots, borders, etc.).
     *
     * @param context Draw context for rendering
     * @param guiX X position of the GUI on screen
     * @param guiY Y position of the GUI on screen
     */
    void renderBackground(DrawContext context, int guiX, int guiY);

    /**
     * Render the foreground elements of this section (text, tooltips, etc.).
     *
     * @param context Draw context for rendering
     * @param guiX X position of the GUI on screen
     * @param guiY Y position of the GUI on screen
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     */
    void renderForeground(DrawContext context, int guiX, int guiY, int mouseX, int mouseY);

    /**
     * Handle mouse click events in this section.
     *
     * @param mouseX Mouse X position on screen
     * @param mouseY Mouse Y position on screen
     * @param button Mouse button pressed
     * @return true if the click was handled, false otherwise
     */
    boolean handleClick(int mouseX, int mouseY, int button);

    /**
     * Check if this section can be paginated (content scrollable when space is limited).
     * Non-paginable sections (settings, inventories) must always be fully visible.
     *
     * @return true if section supports pagination, false if must always be fully visible
     */
    boolean isPaginable();

    /**
     * Get the current Y position of this section.
     *
     * @return Y position in pixels from GUI top
     */
    int getY();

    /**
     * Get the allocated height of this section.
     *
     * @return Allocated height in pixels
     */
    int getAllocatedHeight();
}
