package ui.monitor;

import javax.swing.*;
import java.awt.*;

/**
 * A panel that always matches its scroll viewport's width. Without this, a
 * panel inside a {@link JScrollPane} is laid out at its natural width, so long
 * text is clipped on the right instead of wrapping when the pane is narrow.
 */
final class WidthTrackingPanel extends JPanel implements Scrollable {

    WidthTrackingPanel(LayoutManager layout) {
        super(layout);
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 14; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(40, r.height - 40); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
