package ui.monitor;

import javax.swing.*;
import java.awt.*;

/**
 * A {@link FlowLayout} that reports the right preferred height when its
 * contents wrap onto several lines.
 *
 * <p>A plain FlowLayout always reports the size of ONE row, so when a toolbar
 * is wider than its container the overflow wraps into space the parent never
 * allocated — those controls end up clipped or hidden behind whatever sits
 * below, and clicks aimed at them hit something else. This layout asks "how
 * many rows do I need at the width I've actually been given?" so the parent
 * grows the toolbar to fit and every control stays visible and clickable.
 */
final class WrapLayout extends FlowLayout {

    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension d = layoutSize(target, false);
        d.width -= getHgap() + 1;
        return d;
    }

    /**
     * Lays the rows out, then checks that the parent actually gave us the height those rows need. A
     * parent sizes this container from the width it had BEFORE the latest resize, so after the window
     * or a splitter changes width the wrapped rows can be taller than the space allotted — and the
     * overflow would draw over whatever sits below. When the height is wrong, ask the parent to lay
     * out again now that the new width is known.
     */
    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        if (!(target instanceof JComponent jc) || target.getHeight() <= 0) return;
        int need = layoutSize(target, true).height;
        if (need == target.getHeight()) {
            jc.putClientProperty(ASKED, null);
            return;
        }
        if (Integer.valueOf(need).equals(jc.getClientProperty(ASKED))) return;   // already asked for this; don't loop
        jc.putClientProperty(ASKED, need);
        SwingUtilities.invokeLater(() -> {
            target.invalidate();
            Container parent = target.getParent();
            if (parent != null) parent.revalidate();
        });
    }

    private static final String ASKED = "wrapLayout.askedHeight";

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // Before the first layout pass the target has no width yet; borrow the nearest ancestor's.
            Container c = target;
            while (c.getWidth() == 0 && c.getParent() != null) c = c.getParent();
            int targetWidth = c.getWidth();
            if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;

            int hgap = getHgap(), vgap = getVgap();
            Insets in = target.getInsets();
            int sideSpace = in.left + in.right + hgap * 2;
            int maxWidth = targetWidth == Integer.MAX_VALUE ? Integer.MAX_VALUE : targetWidth - sideSpace;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0, rowHeight = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowWidth > 0 && rowWidth + hgap + d.width > maxWidth) {
                    addRow(dim, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth > 0) rowWidth += hgap;
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(dim, rowWidth, rowHeight);

            dim.width += sideSpace;
            dim.height += in.top + in.bottom + vgap * 2;
            return dim;
        }
    }

    private void addRow(Dimension dim, int rowWidth, int rowHeight) {
        dim.width = Math.max(dim.width, rowWidth);
        if (dim.height > 0) dim.height += getVgap();
        dim.height += rowHeight;
    }
}
