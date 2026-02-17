package ninja.trek.mc.goldgolem.client.screen.layout;

import net.minecraft.client.gui.GuiGraphics;

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
    public void renderBackground(GuiGraphics context, int guiX, int guiY) {
        // Default: no background rendering
    }

    @Override
    public void renderForeground(GuiGraphics context, int guiX, int guiY, int mouseX, int mouseY) {
        // Default: no foreground rendering
    }

    @Override
    public boolean handleClick(int mouseX, int mouseY, int button) {
        return false;
    }

    @Override
    public boolean handleMouseRelease(int mouseX, int mouseY, int button) {
        return false;
    }

    @Override
    public boolean handleMouseScroll(double mouseX, double mouseY, double amount) {
        return false;
    }

    @Override
    public boolean isPaginable() {
        return false;
    }
}
