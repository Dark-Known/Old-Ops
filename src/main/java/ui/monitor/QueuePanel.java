package ui.monitor;

import ui.AppTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * "What is happening / about to happen" — the live state that has no row in
 * the history yet: tasks running right now, what is queued next, and how each
 * watcher is currently being triggered. Three compact tables in one panel,
 * each capped so a system with hundreds of tasks stays a glance, not a wall.
 *
 * <p>Fed from the in-process scheduler or, when the headless Daemon is the
 * active scheduler, from its exported status snapshot — the panel itself does
 * not care which (see {@link EventMonitorPanel}).
 */
public class QueuePanel extends JPanel {

    public record RunningRow(String taskName, String glyph, LocalDateTime startedAt, String note) {}
    public record PendingRow(String taskName, String glyph, LocalDateTime dueAt, int attempt, boolean immediate) {}
    /** {@code state}: INSTANT, POLLING or UNSUPPORTED. */
    public record WatchRow(String taskName, String glyph, String state, String detail) {}

    private static final int MAX_RUNNING = 6, MAX_PENDING = 6, MAX_WATCH = 8;

    private final Section<RunningRow> running = new Section<>("Running now", new String[]{"Task", "For"}, new int[]{0, 70});
    private final Section<PendingRow> pending = new Section<>("Up next", new String[]{"Task", "Due"}, new int[]{0, 90});
    private final Section<WatchRow> watchers = new Section<>("Watchers", new String[]{"Task", "Trigger"}, new int[]{0, 96});
    private final JLabel footnote = new JLabel(" ");

    public QueuePanel() {
        setLayout(new BorderLayout());
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBorder(new EmptyBorder(10, 12, 10, 12));
        for (Section<?> s : List.of(running, pending, watchers)) {
            s.setAlignmentX(Component.LEFT_ALIGNMENT);
            stack.add(s);
            stack.add(Box.createVerticalStrut(10));
        }
        footnote.setForeground(MonitorStyle.muted());
        footnote.setFont(footnote.getFont().deriveFont(11f));
        footnote.setAlignmentX(Component.LEFT_ALIGNMENT);
        stack.add(footnote);

        JPanel top = new WidthTrackingPanel(new BorderLayout());
        top.add(stack, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(top);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(14);
        add(sp, BorderLayout.CENTER);

        running.setRenderer((row, col, now) -> col == 0 ? row.glyph() + row.taskName()
                : MonitorStyle.duration(java.time.Duration.between(row.startedAt() != null ? row.startedAt() : now, now)), null);
        pending.setRenderer((row, col, now) -> {
            if (col == 0) return row.glyph() + row.taskName() + (row.attempt() > 0 ? "  (retry " + row.attempt() + ")" : "");
            long ms = java.time.Duration.between(now, row.dueAt()).toMillis();
            if (row.immediate() && ms <= 1500) return "now";
            return ms < 0 ? "overdue " + MonitorStyle.duration(-ms) : "in " + MonitorStyle.duration(ms);
        }, (row, now) -> java.time.Duration.between(now, row.dueAt()).toMillis() < -2000 ? AppTheme.SKIPPED_FG : null);
        watchers.setRenderer((row, col, now) -> col == 0 ? row.glyph() + row.taskName()
                : switch (row.state()) { case "INSTANT" -> "\u25CF Instant"; case "UNSUPPORTED" -> "\u25CF Unsupported"; default -> "\u25CF Polling"; },
                (row, now) -> "INSTANT".equals(row.state()) ? AppTheme.SUCCESS_FG : AppTheme.SKIPPED_FG);
        watchers.setTooltip((row, col) -> row.detail() == null || row.detail().isBlank() ? null
                : "<html><body style='width:300px'>" + MonitorStyle.htmlEscape(row.detail()) + "</body></html>");
    }

    public void update(List<RunningRow> run, List<PendingRow> pend, List<WatchRow> watch, String note) {
        LocalDateTime now = LocalDateTime.now();
        running.set(run, MAX_RUNNING, "Nothing is running", now);
        pending.set(pend, MAX_PENDING, "Nothing queued", now);
        // Surface watchers that are NOT instant first: those are the ones worth looking at.
        List<WatchRow> sorted = new java.util.ArrayList<>(watch);
        sorted.sort(java.util.Comparator.comparing((WatchRow w) -> "INSTANT".equals(w.state())).thenComparing(WatchRow::taskName, String.CASE_INSENSITIVE_ORDER));
        watchers.set(sorted, MAX_WATCH, "No watcher-enabled tasks", now);
        long polling = watch.stream().filter(w -> !"INSTANT".equals(w.state())).count();
        watchers.setCount(watch.size() == 0 ? "" : (watch.size() - polling) + " instant" + (polling > 0 ? " \u00b7 " + polling + " polling" : ""));
        footnote.setText(note == null || note.isBlank() ? " " : "<html><body style='width:260px'>" + MonitorStyle.htmlEscape(note) + "</body></html>");
    }

    // ── One titled mini-table ────────────────────────────────────────

    private interface CellText<T> { String text(T row, int col, LocalDateTime now); }
    private interface CellColor<T> { Color color(T row, LocalDateTime now); }
    private interface CellTip<T> { String tip(T row, int col); }

    private static final class Section<T> extends JPanel {
        private final JLabel heading = new JLabel();
        private final JLabel count = new JLabel();
        private final JLabel empty = new JLabel();
        private final String title;
        private final Model model;
        private final JTable table;
        private final JPanel tableHost = new JPanel(new BorderLayout());
        private CellText<T> text = (r, c, n) -> "";
        private CellColor<T> color = (r, n) -> null;
        private CellTip<T> tip = (r, c) -> null;
        private List<T> rows = List.of();
        private String extra = "";
        private int overflow;
        private LocalDateTime now = LocalDateTime.now();

        Section(String title, String[] cols, int[] widths) {
            this.title = title;
            setLayout(new BorderLayout(0, 4));
            setOpaque(false);
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, 12f));
            count.setForeground(MonitorStyle.muted());
            count.setFont(count.getFont().deriveFont(11f));
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.add(heading, BorderLayout.WEST);
            head.add(count, BorderLayout.EAST);

            model = new Model(cols);
            table = new JTable(model) {
                @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                    int r = rowAtPoint(e.getPoint()), c = columnAtPoint(e.getPoint());
                    return r >= 0 && r < rows.size() ? tip.tip(rows.get(r), c) : null;
                }
            };
            table.setRowHeight(24);
            table.setShowGrid(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setRowSelectionAllowed(false);
            table.setFocusable(false);
            table.setTableHeader(null);
            table.setOpaque(false);
            table.setFillsViewportHeight(false);
            for (int i = 0; i < widths.length; i++) {
                if (widths[i] > 0) {
                    table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
                    table.getColumnModel().getColumn(i).setMaxWidth(widths[i] + 40);
                }
            }
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                    super.getTableCellRendererComponent(t, v, false, false, r, c);
                    setBorder(new EmptyBorder(0, 2, 0, 2));
                    setOpaque(false);
                    setHorizontalAlignment(c == 0 ? LEFT : RIGHT);
                    Color col = r < rows.size() ? color.color(rows.get(r), now) : null;
                    setForeground(r >= rows.size() ? MonitorStyle.muted() : col != null && c > 0 ? MonitorStyle.onSurface(col) : t.getForeground());
                    return this;
                }
            });
            empty.setForeground(MonitorStyle.muted());
            empty.setFont(empty.getFont().deriveFont(11.5f));
            empty.setBorder(new EmptyBorder(2, 2, 2, 2));

            tableHost.setOpaque(false);
            tableHost.add(table, BorderLayout.CENTER);
            add(head, BorderLayout.NORTH);
            add(tableHost, BorderLayout.CENTER);
            heading.setText(title);
        }

        void setRenderer(CellText<T> t, CellColor<T> c) { this.text = t; if (c != null) this.color = c; }
        void setTooltip(CellTip<T> t)                    { this.tip = t; }
        void setCount(String s)                          { this.extra = s; refreshCount(); }

        void set(List<T> all, int max, String emptyText, LocalDateTime now) {
            this.now = now;
            this.overflow = Math.max(0, all.size() - max);
            this.rows = all.size() > max ? all.subList(0, max) : all;
            empty.setText(emptyText);
            boolean none = rows.isEmpty();
            tableHost.removeAll();
            tableHost.add(none ? empty : table, BorderLayout.CENTER);
            model.fire();
            table.setPreferredSize(new Dimension(10, table.getRowHeight() * model.getRowCount()));
            refreshCount();
            tableHost.revalidate();
            tableHost.repaint();
        }

        private void refreshCount() {
            String n = rows.isEmpty() && overflow == 0 ? "" : String.valueOf(rows.size() + overflow);
            count.setText(!extra.isEmpty() ? extra : n);
        }

        private final class Model extends AbstractTableModel {
            private final String[] cols;
            Model(String[] cols) { this.cols = cols; }
            void fire() { fireTableDataChanged(); }
            @Override public int getRowCount()    { return rows.size() + (overflow > 0 ? 1 : 0); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Object getValueAt(int r, int c) {
                if (r >= rows.size()) return c == 0 ? "+ " + overflow + " more" : "";
                return text.text(rows.get(r), c, now);
            }
        }
    }
}
