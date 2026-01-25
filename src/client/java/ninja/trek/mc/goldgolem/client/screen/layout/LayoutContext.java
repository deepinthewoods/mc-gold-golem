package ninja.trek.mc.goldgolem.client.screen.layout;

/**
 * Context data for layout calculations.
 * Contains screen dimensions and common constants used across sections.
 */
public class LayoutContext {
    /** Total screen width in pixels */
    public final int screenWidth;

    /** Total screen height in pixels */
    public final int screenHeight;

    /** GUI background texture width (constant) */
    public final int guiWidth;

    /** Header height (constant: 17px) */
    public final int headerHeight;

    /** Title area height (constant: 6 + 10 + 4 = 20px) */
    public final int titleAreaHeight;

    /** Top margin for GUI positioning */
    public final int topMargin;

    /** Bottom margin for GUI positioning */
    public final int bottomMargin;

    /**
     * Create a new layout context.
     *
     * @param screenWidth Total screen width
     * @param screenHeight Total screen height
     */
    public LayoutContext(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.guiWidth = 176;
        this.headerHeight = 17;
        this.titleAreaHeight = 20; // 6 + 10 + 4
        this.topMargin = 20;
        this.bottomMargin = 20;
    }

    /**
     * Calculate the maximum GUI height that can fit on screen.
     *
     * @return Maximum GUI height in pixels
     */
    public int getMaxGuiHeight() {
        return screenHeight - topMargin - bottomMargin;
    }

    /**
     * Calculate the fixed header + title height.
     *
     * @return Fixed header height in pixels
     */
    public int getFixedHeaderHeight() {
        return headerHeight + titleAreaHeight;
    }
}
