package ninja.trek.mc.goldgolem.client.screen.layout.sections;

import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import ninja.trek.mc.goldgolem.client.screen.layout.AbstractGuiSection;
import ninja.trek.mc.goldgolem.client.screen.layout.LayoutContext;
import ninja.trek.mc.goldgolem.client.screen.layout.WidgetAdder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Section for mode-specific settings (sliders, buttons, text fields).
 * Uses a builder pattern with widget factories for flexible widget addition.
 * This section is never paginable - all settings must always be visible.
 */
public class SettingsSection extends AbstractGuiSection {
    private final List<WidgetFactory> widgetFactories = new ArrayList<>();
    private final List<Element> createdWidgets = new ArrayList<>();
    private int totalHeight = 0;
    private int guiX = 0;
    private int guiY = 0;

    /**
     * Add a widget factory that will create the widget at initialization time.
     *
     * @param factory Function that takes (x, y) and returns a widget
     * @param height Height of the widget in pixels
     * @param gap Gap after this widget in pixels
     * @return this for method chaining
     */
    public <T extends Element & Drawable & Selectable> SettingsSection withWidgetFactory(
            BiFunction<Integer, Integer, T> factory, int height, int gap) {
        widgetFactories.add(new WidgetFactory(factory, height, gap));
        totalHeight += height + gap;
        return this;
    }

    /**
     * Add a widget factory with no gap.
     *
     * @param factory Function that takes (x, y) and returns a widget
     * @param height Height of the widget in pixels
     * @return this for method chaining
     */
    public <T extends Element & Drawable & Selectable> SettingsSection withWidgetFactory(
            BiFunction<Integer, Integer, T> factory, int height) {
        return withWidgetFactory(factory, height, 0);
    }

    @Override
    public int calculateRequiredHeight(LayoutContext context) {
        return totalHeight;
    }

    @Override
    public boolean isPaginable() {
        // Settings must always be fully visible
        return false;
    }

    /**
     * Set the GUI coordinates for widget positioning.
     * Must be called before initializeWidgets().
     *
     * @param guiX X position of the GUI
     * @param guiY Y position of the GUI
     */
    public void setGuiCoordinates(int guiX, int guiY) {
        this.guiX = guiX;
        this.guiY = guiY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initializeWidgets(WidgetAdder widgetAdder) {
        // Create and register all widgets using factories
        int currentY = guiY + y;
        createdWidgets.clear();

        for (WidgetFactory factory : widgetFactories) {
            Object widgetObj = factory.factory.apply(guiX, currentY);
            if (widgetObj instanceof Element) {
                Element widget = (Element) widgetObj;
                createdWidgets.add(widget);
                // The factory should only return widgets that implement all three interfaces
                widgetAdder.addWidget((Element & Drawable & Selectable) widget);
            }
            currentY += factory.height + factory.gap;
        }
    }

    /**
     * Get a created widget by index.
     *
     * @param index Widget index
     * @return The widget, or null if index is invalid
     */
    public Element getWidget(int index) {
        if (index >= 0 && index < createdWidgets.size()) {
            return createdWidgets.get(index);
        }
        return null;
    }

    /**
     * Internal class to track widget factory information.
     */
    private static class WidgetFactory {
        final BiFunction<Integer, Integer, ?> factory;
        final int height;
        final int gap;

        WidgetFactory(BiFunction<Integer, Integer, ?> factory, int height, int gap) {
            this.factory = factory;
            this.height = height;
            this.gap = gap;
        }
    }
}
