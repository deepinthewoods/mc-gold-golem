package ninja.trek.mc.goldgolem.client.screen.layout;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Selectable;

/**
 * Functional interface for adding widgets to the parent screen.
 * Wraps the screen's addDrawableChild method for use by sections.
 */
@FunctionalInterface
public interface WidgetAdder {
    /**
     * Add a widget to the parent screen.
     *
     * @param widget The widget to add (must implement Drawable, Element, and Selectable)
     * @param <T> The widget type
     * @return The added widget
     */
    <T extends Element & Drawable & Selectable> T addWidget(T widget);
}
