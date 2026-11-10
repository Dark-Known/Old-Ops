package ui;

import javax.swing.*;
import java.awt.*;
import java.util.function.Supplier;

/**
 * A page that is only constructed the first time it is shown. A page's tables, models, timers and listeners
 * cost memory from the moment they exist, so pages nobody has opened yet should not exist yet.
 */
final class LazyPanel<T extends JComponent> extends JPanel {
    private final Supplier<T> factory;
    private final java.util.function.Function<T, JComponent> wrapper;
    private T page;

    LazyPanel(Supplier<T> factory, java.util.function.Function<T, JComponent> wrapper) {
        super(new BorderLayout());
        setOpaque(false);
        this.factory = factory;
        this.wrapper = wrapper;
    }

    /**
     * Builds the page on first call; later calls return the same instance.
     */
    T get() {
        if (page == null) {
            page = factory.get();
            add(wrapper.apply(page), BorderLayout.CENTER);
            revalidate();
        }
        return page;
    }

    /**
     * The page if it has been built, else null (nothing to refresh yet).
     */
    T builtOrNull() {
        return page;
    }
}
