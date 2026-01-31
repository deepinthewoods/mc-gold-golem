package ninja.trek.mc.goldgolem.client.screen.layout;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

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
    <T extends GuiEventListener & Renderable & NarratableEntry> T addWidget(T widget);
}
