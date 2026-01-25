package ninja.trek.mc.goldgolem.client.screen.layout;

import net.minecraft.client.gui.DrawContext;

/**
 * Base implementation of GuiSection providing common functionality.
 * Concrete sections can extend this to avoid boilerplate.
 */
public abstract class AbstractGuiSection implements GuiSection {
    protected int y = 0;
    protected int allocatedHeight = 0;

    @Override
    public void setPosition(int y) {
        this.y = y;
    }

    @Override
    public void setAllocatedHeight(int height) {
        this.allocatedHeight = height;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getAllocatedHeight() {
        return allocatedHeight;
    }

    @Override
    public void initializeWidgets(WidgetAdder widgetAdder) {
        // Default: no widgets
    }

    @Override
    public void renderBackground(DrawContext context, int guiX, int guiY) {
        // Default: no background rendering
    }

    @Override
    public void renderForeground(DrawContext context, int guiX, int guiY, int mouseX, int mouseY) {
        // Default: no foreground rendering
    }

    @Override
    public boolean handleClick(int mouseX, int mouseY, int button) {
        // Default: no click handling
        return false;
    }

    @Override
    public boolean isPaginable() {
        // Default: not paginable
        return false;
    }
}
