package ui.monitor;

import model.EventKind;
import ui.AppTheme;
import ui.components.PillBadge;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * The event feed: a filter toolbar above a tree-table of events, with a
 * status line below.
 *
 * <p>What keeps it calm while still capturing everything:
 * <ul>
 *   <li>Events that belong to one execution are <b>folded into a single run
 *       row</b> that expands in place ({@link FeedBuilder}).</li>
 *   <li>Consecutive identical uneventful runs collapse to <b>"Run ×N"</b>.</li>
 *   <li>Low-level queue bookkeeping is recorded but hidden at the default
 *       <b>level</b>; one dropdown reveals it.</li>
 *   <li>Colour is reserved for state: a status pill on every row and a thin
 *       edge on rows that need attention — never a full-row tint.</li>
 *   <li>Updates never yank the view: selection and scroll position are
 *       anchored by row identity, and the feed can be paused.</li>
 * </ul>
 * All methods must be called on the EDT.
 */
public class EventFeedPanel extends JPanel {

    /** Combo item for the task filter. */
    private record TaskItem(String id, String label) {
        @Override public String toString() { return label; }
    }

    private static final int COL_TIME = 0, COL_STATUS = 1, COL_TASK = 2, COL_EVENT = 3, COL_SUMMARY = 4,
            COL_FILES = 5, COL_TRIGGER = 6, COL_DURATION = 7;
    private static final String[] COLUMNS = {"Time", "Status", "Task", "Event", "Summary", "Files", "Trigger", "Took"};
    private static final int FLASH_MS = 1600;
    private static final int INDENT = 14;

    private final MonitorPrefs prefs;
    private final FeedModel model = new FeedModel();
    private final JTable table = new JTable(model);

    // toolbar
    private final JTextField search = new JTextField();
    private final JComboBox<TaskItem> taskCombo = new JComboBox<>();
    private final JComboBox<String> rangeCombo = new JComboBox<>();
    private final JComboBox<String> levelCombo = new JComboBox<>();
    private final Map<EventKind.Category, JToggleButton> chips = new EnumMap<>(EventKind.Category.class);
    private final JToggleButton pauseBtn = new JToggleButton("Pause");
    private final JButton resetBtn = new JButton("Reset filters");
    private final JLabel banner = new JLabel();
    private final JPanel bannerHost = new JPanel(new BorderLayout());
    private final JLabel statusLeft = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel();
    private final JButton emptyReset = new JButton("Reset filters");

    // state
    private List<MonitorEvent> latestEvents = List.of();   // newest data received
    private List<MonitorEvent> shownEvents = List.of();    // what the table is built from (frozen while paused)
    private boolean hitLimit;
    private int attentionErrors, attentionWarnings;
    private Set<String> runningIds = Set.of();
    private final Set<String> expanded = new HashSet<>();
    private final Set<EventKind.Category> activeCategories = EnumSet.allOf(EventKind.Category.class);
    private String taskFilterId;
    private final Set<String> seenKeys = new HashSet<>();
    private boolean baseline;
    private final Set<String> flashKeys = new HashSet<>();
    private int pendingWhilePaused;
    private FeedBuilder.Result result = new FeedBuilder.Result(List.of(), List.of(), new EnumMap<>(EventKind.Category.class), 0, 0);
    private String selectedKey;
    private FeedRow selectedRow;
    private boolean adjusting;
    private Consumer<FeedRow> selectionListener = r -> {};
    private Consumer<String> taskFilterListener = id -> {};
    private final javax.swing.Timer searchDebounce;
    private long lastRebuildMs;

    public EventFeedPanel(MonitorPrefs prefs) {
        this.prefs = prefs;
        setLayout(new BorderLayout());

        searchDebounce = new javax.swing.Timer(220, e -> filterChanged());
        searchDebounce.setRepeats(false);

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        installKeys();
        prefs.addListener(this::onPrefsChanged);
    }

    // ── Construction ────────────────────────────────────────────────

    private JComponent buildToolbar() {
        search.putClientProperty("JTextField.placeholderText", "Search task, message, file name, error\u2026");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setColumns(14);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { searchDebounce.restart(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { searchDebounce.restart(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { searchDebounce.restart(); }
        });

        for (MonitorPrefs.Range r : MonitorPrefs.RANGES) rangeCombo.addItem(r.label());
        rangeCombo.setSelectedIndex(prefs.rangeIndex());
        rangeCombo.setToolTipText("How far back to show events");
        rangeCombo.addActionListener(e -> { if (!adjusting) prefs.setRangeIndex(rangeCombo.getSelectedIndex()); });

        for (MonitorPrefs.Level l : MonitorPrefs.LEVELS) levelCombo.addItem(l.label());
        levelCombo.setSelectedIndex(prefs.levelIndex());
        levelCombo.setToolTipText("Normal hides low-level queue bookkeeping; choose Everything to see it");
        levelCombo.addActionListener(e -> { if (!adjusting) prefs.setLevelIndex(levelCombo.getSelectedIndex()); });

        taskCombo.addItem(new TaskItem(null, "All tasks"));
        taskCombo.setToolTipText("Show only one task");
        taskCombo.addActionListener(e -> {
            if (adjusting) return;
            TaskItem it = (TaskItem) taskCombo.getSelectedItem();
            taskFilterId = it != null ? it.id() : null;
            taskFilterListener.accept(taskFilterId);
            filterChanged();
        });

        pauseBtn.setToolTipText("Freeze the list while you read it (Ctrl+P). Events keep being recorded.");
        pauseBtn.addActionListener(e -> setPaused(pauseBtn.isSelected()));

        JButton options = new JButton("Options \u25BE");
        options.addActionListener(e -> buildOptionsMenu().show(options, 0, options.getHeight()));
        JButton export = new JButton("Export CSV");
        export.setToolTipText("Save the events matching the current filters");
        export.addActionListener(e -> exportCsv());

        // Row 1: what to look for (search + the three dropdowns). Search takes whatever width is spare.
        JPanel row1 = new JPanel(new GridBagLayout());
        row1.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(0, 0, 0, 6);
        g.gridx = 0; g.weightx = 1; search.setMinimumSize(new Dimension(120, 10)); row1.add(search, g);
        g.weightx = 0;
        g.gridx = 1; row1.add(taskCombo, g);
        g.gridx = 2; row1.add(rangeCombo, g);
        g.gridx = 3; g.insets = new Insets(0, 0, 0, 0); row1.add(levelCombo, g);

        // Row 2: category chips on the left, view actions on the right.
        JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chipRow.setOpaque(false);
        JLabel show = new JLabel("Show:");
        show.setForeground(MonitorStyle.muted());
        chipRow.add(show);
        for (EventKind.Category c : EventKind.Category.values()) {
            JToggleButton chip = new JToggleButton(c.label());
            chip.setSelected(true);
            chip.putClientProperty("JButton.buttonType", "roundRect");
            chip.setFocusable(false);
            chip.addActionListener(e -> {
                if (chip.isSelected()) activeCategories.add(c); else activeCategories.remove(c);
                filterChanged();
            });
            chips.put(c, chip);
            chipRow.add(chip);
        }
        resetBtn.putClientProperty("JButton.buttonType", "borderless");
        resetBtn.addActionListener(e -> resetFilters());
        resetBtn.setVisible(false);
        chipRow.add(resetBtn);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actionRow.setOpaque(false);
        actionRow.add(pauseBtn);
        actionRow.add(options);
        actionRow.add(export);

        JPanel row2 = new JPanel(new BorderLayout());
        row2.setOpaque(false);
        row2.add(chipRow, BorderLayout.CENTER);
        row2.add(actionRow, BorderLayout.EAST);

        banner.setOpaque(true);
        banner.setBorder(new EmptyBorder(6, 10, 6, 10));
        bannerHost.setOpaque(false);
        bannerHost.setBorder(new EmptyBorder(0, 0, 6, 0));
        bannerHost.add(banner, BorderLayout.CENTER);
        bannerHost.setVisible(false);

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(new EmptyBorder(0, 0, 6, 0));
        JComponent[] parts = {bannerHost, row1, row2};
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlignmentX(Component.LEFT_ALIGNMENT);
            box.add(parts[i]);
            if (i == 1) box.add(Box.createVerticalStrut(6));
        }
        return box;
    }

    private JComponent buildCenter() {
        table.setRowHeight(27);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFont(table.getFont().deriveFont(12f));
        table.setDefaultRenderer(Object.class, new RowRenderer());

        int[] widths = {116, 98, 154, 128, 300, 76, 80, 64};
        int[] mins   = {100, 90, 90,  84,  140, 56, 64, 48};
        for (int i = 0; i < COLUMNS.length; i++) {
            TableColumn tc = table.getColumnModel().getColumn(i);
            tc.setPreferredWidth(widths[i]);
            tc.setMinWidth(mins[i]);
        }

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || adjusting) return;
            int r = table.getSelectedRow();
            selectedRow = r >= 0 ? model.rowAt(r) : null;
            selectedKey = selectedRow != null ? selectedRow.key : null;
            selectionListener.accept(selectedRow);
        });

        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            @Override public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                if (r < 0 || !SwingUtilities.isLeftMouseButton(e)) return;
                FeedRow row = model.rowAt(r);
                if (!row.expandable) return;
                boolean onTree = table.columnAtPoint(e.getPoint()) == COL_TIME
                        && e.getX() <= table.getCellRect(r, COL_TIME, false).x + row.depth * INDENT + INDENT + 8;
                if (onTree || e.getClickCount() == 2) toggle(row);
            }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int r = table.rowAtPoint(e.getPoint());
                if (r >= 0) table.setRowSelectionInterval(r, r);
                buildRowMenu(r >= 0 ? model.rowAt(r) : null).show(table, e.getX(), e.getY());
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") != null
                ? UIManager.getColor("Component.borderColor") : Color.LIGHT_GRAY));
        scroll.getVerticalScrollBar().setUnitIncrement(27);

        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setForeground(MonitorStyle.muted());
        emptyReset.addActionListener(e -> resetFilters());
        JPanel empty = new JPanel(new GridBagLayout());
        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyReset.setAlignmentX(Component.CENTER_ALIGNMENT);
        stack.add(emptyLabel);
        stack.add(Box.createVerticalStrut(10));
        stack.add(emptyReset);
        empty.add(stack);

        cardHost.add(scroll, "table");
        cardHost.add(empty, "empty");
        return cardHost;
    }

    private JComponent buildStatusBar() {
        statusLeft.setForeground(MonitorStyle.muted());
        statusLeft.setFont(statusLeft.getFont().deriveFont(11.5f));
        statusRight.setFont(statusRight.getFont().deriveFont(11.5f));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(6, 2, 0, 2));
        p.add(statusLeft, BorderLayout.CENTER);
        p.add(statusRight, BorderLayout.EAST);
        return p;
    }

    private void installKeys() {
        InputMap in = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap act = getActionMap();
        bind(in, act, "focusSearch", KeyStroke.getKeyStroke(KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                () -> { search.requestFocusInWindow(); search.selectAll(); });
        bind(in, act, "togglePause", KeyStroke.getKeyStroke(KeyEvent.VK_P, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                () -> setPaused(!pauseBtn.isSelected()));
        bind(in, act, "refreshNow", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), () -> rebuild());

        InputMap tin = table.getInputMap(WHEN_FOCUSED);
        ActionMap tact = table.getActionMap();
        bind(tin, tact, "expand", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), () -> {
            if (selectedRow != null && selectedRow.expandable && !selectedRow.expanded) toggle(selectedRow);
        });
        bind(tin, tact, "collapse", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), () -> {
            if (selectedRow != null && selectedRow.expandable && selectedRow.expanded) toggle(selectedRow);
        });
        bind(tin, tact, "toggleRow", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), () -> {
            if (selectedRow != null && selectedRow.expandable) toggle(selectedRow);
        });
        bind(tin, tact, "copyRow", KeyStroke.getKeyStroke(KeyEvent.VK_C, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                () -> { if (selectedRow != null) copy(rowText(selectedRow)); });
        bind(tin, tact, "clearSel", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> table.clearSelection());
        search.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSearch");
        search.getActionMap().put("clearSearch", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { search.setText(""); table.requestFocusInWindow(); }
        });
    }

    private static void bind(InputMap in, ActionMap act, String name, KeyStroke ks, Runnable r) {
        in.put(ks, name);
        act.put(name, new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        });
    }

    // ── Public API ──────────────────────────────────────────────────

    public void setSelectionListener(Consumer<FeedRow> l)  { this.selectionListener = l != null ? l : r -> {}; }
    public void setTaskFilterListener(Consumer<String> l)  { this.taskFilterListener = l != null ? l : id -> {}; }
    public FeedRow getSelectedRow()                        { return selectedRow; }
    public List<MonitorEvent> getMatchedEvents()           { return result.matchedEvents(); }
    public boolean isPaused()                              { return pauseBtn.isSelected(); }
    /**
     * Errors / warnings anywhere in the selected time range — deliberately NOT narrowed by the search
     * box, task or category filters, so the "Attention" summary never says "all clear" just because
     * the operator happens to be looking at something else.
     */
    public int getErrorCount()                             { return attentionErrors; }
    public int getWarningCount()                           { return attentionWarnings; }

    /** Shows or hides the slim notice above the toolbar (e.g. "No scheduler is running"). */
    public void setBanner(String text, boolean problem) {
        if (text == null || text.isBlank()) { bannerHost.setVisible(false); return; }
        banner.setText(text);
        banner.setForeground(problem ? AppTheme.FAILED_FG : AppTheme.SKIPPED_FG);
        banner.setBackground(problem ? AppTheme.FAILED_BG : AppTheme.SKIPPED_BG);
        bannerHost.setVisible(true);
    }

    /** Offers the live tasks in the task filter, plus any task that only appears in history. */
    public void setTaskChoices(Map<String, String> liveTasks) {
        Map<String, String> choices = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> byName = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : liveTasks.entrySet()) byName.put(e.getKey(), e.getValue());
        for (MonitorEvent ev : latestEvents) {
            if (ev.kind().category() == EventKind.Category.SYSTEM || ev.taskId() == null) continue;
            byName.putIfAbsent(ev.taskId(), ev.taskName());
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(byName.entrySet());
        sorted.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));

        List<String> current = new ArrayList<>();
        for (int i = 1; i < taskCombo.getItemCount(); i++) current.add(taskCombo.getItemAt(i).id());
        List<String> wanted = new ArrayList<>();
        for (Map.Entry<String, String> e : sorted) wanted.add(e.getKey());
        if (current.equals(wanted)) return;   // unchanged — don't disturb an open dropdown

        adjusting = true;
        try {
            taskCombo.removeAllItems();
            taskCombo.addItem(new TaskItem(null, "All tasks"));
            TaskItem sel = null;
            for (Map.Entry<String, String> e : sorted) {
                TaskItem it = new TaskItem(e.getKey(), e.getValue());
                taskCombo.addItem(it);
                if (e.getKey().equals(taskFilterId)) sel = it;
            }
            taskCombo.setSelectedIndex(0);
            if (sel != null) taskCombo.setSelectedItem(sel);
            else if (taskFilterId != null) taskFilterId = null;
        } finally {
            adjusting = false;
        }
    }

    /** Restricts the feed to one task (also called from the detail pane's "Only this task" link). */
    public void showOnlyTask(String taskId) {
        adjusting = true;
        try {
            taskFilterId = taskId;
            boolean found = false;
            for (int i = 0; i < taskCombo.getItemCount(); i++) {
                if (Objects.equals(taskCombo.getItemAt(i).id(), taskId)) { taskCombo.setSelectedIndex(i); found = true; break; }
            }
            if (!found) taskCombo.setSelectedIndex(0);
        } finally {
            adjusting = false;
        }
        taskFilterListener.accept(taskFilterId);
        filterChanged();
    }

    public void setRunningTaskIds(Set<String> ids) {
        this.runningIds = ids != null ? ids : Set.of();
    }

    /** Supplies freshly loaded events. {@code limited} is true when the loader hit its row cap. */
    public void setEvents(List<MonitorEvent> events, boolean limited) {
        Set<String> newKeys = new HashSet<>();
        // Only genuinely fresh events flash — widening the time range loads old events that were
        // merely not in memory before, and those must not light up as if they just happened.
        LocalDateTime freshAfter = LocalDateTime.now().minusSeconds(60);
        for (MonitorEvent e : events) {
            if (!seenKeys.contains(e.key()) && e.time().isAfter(freshAfter)) newKeys.add(e.key());
        }
        if (!baseline) {
            newKeys.clear();           // first load: everything is "already there", nothing flashes
            baseline = true;
        }
        seenKeys.clear();
        for (MonitorEvent e : events) seenKeys.add(e.key());

        latestEvents = events;
        hitLimit = limited;
        if (pauseBtn.isSelected()) {
            pendingWhilePaused += newKeys.size();
            updateStatus();
            return;
        }
        shownEvents = events;
        if (prefs.highlightNew() && !newKeys.isEmpty()) startFlash(newKeys);
        rebuild();
    }

    /** Re-evaluates run states (elapsed time, "running" → "unfinished") without new data. Cheap; call each second. */
    public void tick() {
        if (pauseBtn.isSelected()) return;
        boolean hasRunning = false;
        for (FeedRow r : result.rows()) if (r.outcome == FeedRow.Outcome.RUNNING) { hasRunning = true; break; }
        long now = System.currentTimeMillis();
        // Also re-run periodically so events age out of a relative time range on their own.
        if (hasRunning || now - lastRebuildMs > 5000) rebuild();
    }

    public void setPaused(boolean paused) {
        pauseBtn.setSelected(paused);
        pauseBtn.setText(paused ? "Resume" : "Pause");
        if (!paused) {
            pendingWhilePaused = 0;
            shownEvents = latestEvents;
            rebuild();
        } else {
            updateStatus();
        }
    }

    // ── Filtering / rebuilding ──────────────────────────────────────

    private EventFilter currentFilter() {
        MonitorPrefs.Range range = prefs.range();
        LocalDateTime since = range.window() != null ? LocalDateTime.now().minus(range.window()) : null;
        return new EventFilter(search.getText(), taskFilterId, EnumSet.copyOf(activeCategories.isEmpty()
                        ? EnumSet.noneOf(EventKind.Category.class) : activeCategories),
                prefs.level().min(), since);
    }

    private void filterChanged() {
        rebuild();
    }

    private void resetFilters() {
        search.setText("");
        activeCategories.addAll(EnumSet.allOf(EventKind.Category.class));
        for (JToggleButton c : chips.values()) c.setSelected(true);
        showOnlyTask(null);
    }

    private void onPrefsChanged() {
        adjusting = true;
        try {
            rangeCombo.setSelectedIndex(prefs.rangeIndex());
            levelCombo.setSelectedIndex(prefs.levelIndex());
        } finally {
            adjusting = false;
        }
        rebuild();
    }

    private void startFlash(Set<String> keys) {
        flashKeys.addAll(keys);
        javax.swing.Timer t = new javax.swing.Timer(FLASH_MS, e -> { flashKeys.removeAll(keys); table.repaint(); });
        t.setRepeats(false);
        t.start();
    }

    private void rebuild() {
        lastRebuildMs = System.currentTimeMillis();
        EventFilter filter = currentFilter();
        if (activeCategories.isEmpty()) filter = filter.withCategories(EnumSet.noneOf(EventKind.Category.class));

        result = FeedBuilder.build(shownEvents, filter, prefs.groupRuns(), prefs.collapseRepeats(),
                runningIds, expanded, LocalDateTime.now());
        int errs = 0, warns = 0;
        for (MonitorEvent e : shownEvents) {
            if (filter.since() != null && e.time().isBefore(filter.since())) continue;
            if (e.severity() == EventKind.Severity.ERROR) errs++;
            else if (e.severity() == EventKind.Severity.WARN) warns++;
        }
        attentionErrors = errs;
        attentionWarnings = warns;

        // Anchor the view: remember which row sits at the top of the viewport (and how far into it),
        // so new rows arriving above don't shove what the operator is reading off-screen.
        JViewport vp = (JViewport) table.getParent();
        Point pos = vp.getViewPosition();
        String anchorKey = null;
        int anchorOffset = 0;
        if (pos.y > 0 && model.getRowCount() > 0) {
            int ar = table.rowAtPoint(new Point(0, pos.y));
            if (ar >= 0) {
                anchorKey = model.rowAt(ar).key;
                anchorOffset = pos.y - table.getCellRect(ar, 0, true).y;
            }
        }

        FeedRow previousSelected = selectedRow;
        adjusting = true;
        try {
            model.setRows(result.rows());
            int idx = selectedKey != null ? model.indexOfKey(selectedKey) : -1;
            if (idx >= 0) table.setRowSelectionInterval(idx, idx); else table.clearSelection();
            if (anchorKey != null) {
                int ni = model.indexOfKey(anchorKey);
                if (ni >= 0) vp.setViewPosition(new Point(0, Math.max(0, table.getCellRect(ni, 0, true).y + anchorOffset)));
            }
        } finally {
            adjusting = false;
        }

        FeedRow nowSelected = selectedKey != null && model.indexOfKey(selectedKey) >= 0
                ? model.rowAt(model.indexOfKey(selectedKey)) : null;
        selectedRow = nowSelected;
        if (nowSelected == null && previousSelected != null) { selectedKey = null; selectionListener.accept(null); }
        else if (nowSelected != null && changedForDetail(previousSelected, nowSelected)) selectionListener.accept(nowSelected);

        updateChips();
        updateStatus();
        boolean anyFilter = !filter.isDefault();
        resetBtn.setVisible(anyFilter);
        cards.show(cardHost, result.rows().isEmpty() ? "empty" : "table");
        if (result.rows().isEmpty()) {
            boolean noEventsAtAll = shownEvents.isEmpty();
            emptyLabel.setText("<html><div style='text-align:center'>" + (noEventsAtAll
                    ? "<b>No events recorded yet</b><br>Events appear here as tasks run, watchers fire and settings change."
                    : "<b>Nothing matches the current filters</b><br>"
                    + (hitLimit ? "" : result.categoryCounts().values().stream().mapToInt(i -> i).sum() + " event(s) are hidden by your filters."))
                    + "</div></html>");
            emptyReset.setVisible(!noEventsAtAll && anyFilter);
        }
    }

    private static boolean changedForDetail(FeedRow a, FeedRow b) {
        if (a == null || b == null) return a != b;
        return a.outcome != b.outcome || a.count != b.count || !Objects.equals(a.summary, b.summary)
                || a.events.size() != b.events.size()
                || (a.outcome != FeedRow.Outcome.RUNNING && a.durationMs != b.durationMs);
    }

    private void updateChips() {
        for (EventKind.Category c : EventKind.Category.values()) {
            JToggleButton chip = chips.get(c);
            int n = result.categoryCounts().getOrDefault(c, 0);
            chip.setText(c.label() + (n > 0 ? "  " + n : ""));
        }
    }

    private void updateStatus() {
        int shownRows = result.rows().size();
        int events = result.matchedEvents().size();
        StringBuilder sb = new StringBuilder();
        sb.append(events).append(events == 1 ? " event" : " events");
        if (prefs.groupRuns() && shownRows != events) sb.append(" in ").append(shownRows).append(shownRows == 1 ? " row" : " rows");
        if (result.errors() > 0) sb.append("  \u00b7  ").append(result.errors()).append(result.errors() == 1 ? " error" : " errors");
        if (result.warnings() > 0) sb.append("  \u00b7  ").append(result.warnings()).append(result.warnings() == 1 ? " warning" : " warnings");
        if (hitLimit) sb.append("  \u00b7  showing the newest events only \u2014 narrow the time range to see older ones");
        statusLeft.setText(sb.toString());

        if (pauseBtn.isSelected()) {
            statusRight.setText("\u23F8 Paused" + (pendingWhilePaused > 0 ? " \u2014 " + pendingWhilePaused + " new" : ""));
            statusRight.setForeground(MonitorStyle.onSurface(AppTheme.SKIPPED_FG));
        } else {
            statusRight.setText("\u25CF Live");
            statusRight.setForeground(MonitorStyle.onSurface(AppTheme.SUCCESS_FG));
        }
    }

    // ── Row actions ─────────────────────────────────────────────────

    private void toggle(FeedRow row) {
        if (!row.expandable) return;
        if (!expanded.remove(row.key)) expanded.add(row.key);
        selectedKey = row.key;
        rebuild();
    }

    private void setAllExpanded(boolean open) {
        expanded.clear();
        if (open) {
            for (FeedRow r : result.rows()) if (r.expandable) expanded.add(r.key);
            // Buckets hide their member runs, so a second pass reveals those too.
            for (FeedRow r : FeedBuilder.build(shownEvents, currentFilter(), prefs.groupRuns(), prefs.collapseRepeats(),
                    runningIds, expanded, LocalDateTime.now()).rows()) {
                if (r.expandable) expanded.add(r.key);
            }
        }
        rebuild();
    }

    private JPopupMenu buildRowMenu(FeedRow row) {
        JPopupMenu m = new JPopupMenu();
        if (row != null) {
            if (row.expandable) {
                JMenuItem t = new JMenuItem(row.expanded ? "Collapse" : "Expand");
                t.addActionListener(e -> toggle(row));
                m.add(t);
            }
            JMenuItem copy = new JMenuItem("Copy as text");
            copy.addActionListener(e -> copy(rowText(row)));
            m.add(copy);
            if (row.taskId != null && row.head.kind().category() != EventKind.Category.SYSTEM) {
                JMenuItem only = new JMenuItem("Show only \u201C" + row.taskName + "\u201D");
                only.addActionListener(e -> showOnlyTask(row.taskId));
                m.add(only);
            }
            m.addSeparator();
        }
        JMenuItem all = new JMenuItem("Expand all");
        all.addActionListener(e -> setAllExpanded(true));
        JMenuItem none = new JMenuItem("Collapse all");
        none.addActionListener(e -> setAllExpanded(false));
        m.add(all);
        m.add(none);
        return m;
    }

    private JPopupMenu buildOptionsMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(check("Group events by run", prefs.groupRuns(), prefs::setGroupRuns));
        m.add(check("Collapse identical repeated runs", prefs.collapseRepeats(), prefs::setCollapseRepeats));
        m.add(check("Highlight new events", prefs.highlightNew(), prefs::setHighlightNew));
        m.addSeparator();
        m.add(check("Show detail pane", prefs.showDetail(), prefs::setShowDetail));
        m.add(check("Show queue & watchers", prefs.showQueue(), prefs::setShowQueue));
        m.addSeparator();
        JMenu toasts = new JMenu("Pop-up notifications");
        ButtonGroup g = new ButtonGroup();
        for (MonitorPrefs.ToastMode tm : MonitorPrefs.ToastMode.values()) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(tm.label(), prefs.toastMode() == tm);
            it.addActionListener(e -> prefs.setToastMode(tm));
            g.add(it);
            toasts.add(it);
        }
        m.add(toasts);
        return m;
    }

    private static JCheckBoxMenuItem check(String text, boolean on, java.util.function.Consumer<Boolean> set) {
        JCheckBoxMenuItem it = new JCheckBoxMenuItem(text, on);
        it.addActionListener(e -> set.accept(it.isSelected()));
        return it;
    }

    private void exportCsv() {
        List<MonitorEvent> events = result.matchedEvents();
        if (events.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no events matching the current filters to export.",
                    "Export CSV", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("events-" + java.time.LocalDate.now() + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        try {
            EventCsv.write(out, events);
            JOptionPane.showMessageDialog(this, "Saved " + events.size() + " events to\n" + out,
                    "Export CSV", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save the file:\n" + ex.getMessage(),
                    "Export CSV", JOptionPane.ERROR_MESSAGE);
        }
    }

    static String rowText(FeedRow r) {
        return MonitorStyle.fullTime(r.time) + "  " + r.outcome.label() + "  " + r.taskName + "  "
                + r.label + (r.summary.isEmpty() ? "" : "  \u2014  " + r.summary)
                + (r.durationMs >= 0 ? "  (" + MonitorStyle.duration(r.durationMs) + ")" : "");
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    // ── Table model ─────────────────────────────────────────────────

    private static final class FeedModel extends AbstractTableModel {
        private List<FeedRow> rows = List.of();
        private Map<String, Integer> index = Map.of();

        void setRows(List<FeedRow> r) {
            rows = r;
            Map<String, Integer> idx = new HashMap<>(r.size() * 2);
            for (int i = 0; i < r.size(); i++) idx.putIfAbsent(r.get(i).key, i);
            index = idx;
            fireTableDataChanged();
        }
        FeedRow rowAt(int i)              { return rows.get(i); }
        int indexOfKey(String key)        { return index.getOrDefault(key, -1); }
        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int c) { return COLUMNS[c]; }
        @Override public Object getValueAt(int r, int c) { return rows.get(r); }
    }

    // ── Rendering ───────────────────────────────────────────────────

    private boolean isFlashing(FeedRow r) {
        if (flashKeys.isEmpty()) return false;
        for (MonitorEvent e : r.events) if (flashKeys.contains(e.key())) return true;
        return false;
    }

    private final class RowRenderer extends DefaultTableCellRenderer {
        private final PillBadge pill = new PillBadge("", Color.BLACK, Color.WHITE);
        private final JPanel pillHost = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        private Color edge;
        private int expander;          // 0 none, 1 collapsed, 2 expanded
        private int expanderX;
        private Color dot;

        RowRenderer() {
            pillHost.add(pill);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
            FeedRow r = (FeedRow) value;
            super.getTableCellRendererComponent(t, "", selected, false, row, col);
            setIcon(null);
            edge = null;
            expander = 0;
            dot = null;
            setHorizontalAlignment(LEFT);
            setBorder(new EmptyBorder(0, 6, 0, 6));
            setToolTipText(null);

            Color rowBg = selected ? t.getSelectionBackground() : r.depth > 0 ? AppTheme.surface2() : t.getBackground();
            if (!selected && prefs.highlightNew() && isFlashing(r)) {
                rowBg = r.outcome == FeedRow.Outcome.FAILED || r.outcome == FeedRow.Outcome.ERROR ? AppTheme.FAILED_BG
                        : r.outcome == FeedRow.Outcome.WARNING ? AppTheme.SKIPPED_BG : AppTheme.SUCCESS_BG;
            }
            Color fg = selected ? t.getSelectionForeground()
                    : r.outcome == FeedRow.Outcome.DEBUG ? MonitorStyle.muted() : t.getForeground();
            Color muted = selected ? t.getSelectionForeground() : MonitorStyle.muted();
            setBackground(rowBg);
            setForeground(fg);

            switch (col) {
                case COL_TIME -> {
                    setText(MonitorStyle.time(r.time));
                    setForeground(muted);
                    setBorder(new EmptyBorder(0, 8 + r.depth * INDENT + INDENT, 0, 4));
                    expanderX = 8 + r.depth * INDENT;
                    if (r.expandable) expander = r.expanded ? 2 : 1;
                    edge = selected ? null : MonitorStyle.edge(r.outcome);
                    if (selected) edge = MonitorStyle.edge(r.outcome);
                    setToolTipText(MonitorStyle.fullTime(r.time) + (r.type != FeedRow.Type.EVENT
                            && r.firstTime != null && !r.firstTime.equals(r.time) ? "  (began " + MonitorStyle.time(r.firstTime) + ")" : ""));
                }
                case COL_STATUS -> {
                    Color[] c = MonitorStyle.pill(r.outcome);
                    pill.setText(r.outcome.label());
                    pill.setColors(c[0], c[1]);
                    pillHost.setBackground(rowBg);
                    pillHost.setOpaque(true);
                    return pillHost;
                }
                case COL_TASK -> {
                    setText(r.head.kind().category() == EventKind.Category.SYSTEM
                            ? "\u2699 " + r.taskName : MonitorStyle.directionGlyph(r.direction) + r.taskName);
                    setToolTipText(r.taskName + (r.taskType != null ? "  \u00b7  " + r.taskType : ""));
                }
                case COL_EVENT -> {
                    setText(r.label);
                    if (r.type == FeedRow.Type.RUN) setToolTipText(r.count + " events \u2014 click the arrow to expand");
                    if (r.type == FeedRow.Type.EVENT) dot = MonitorStyle.categoryColor(r.head.kind().category());
                    if (dot != null) setBorder(new EmptyBorder(0, 20, 0, 4));
                    if (r.type != FeedRow.Type.EVENT || r.head.isOutcome()) setForeground(muted == fg ? fg : fg);
                    if (r.type != FeedRow.Type.RUN) setToolTipText(r.head.kind().category().label() + " \u2014 " + r.head.kind().label());
                }
                case COL_SUMMARY -> {
                    setText(r.summary);
                    setToolTipText(r.summary.length() > 60 ? "<html><body style='width:420px'>"
                            + MonitorStyle.htmlEscape(r.summary) + "</body></html>" : null);
                    if (r.type == FeedRow.Type.BUCKET) {
                        setText(r.summary + "   \u00b7   " + MonitorStyle.time(r.firstTime) + " \u2192 " + MonitorStyle.time(r.time));
                        setToolTipText(r.count + " identical runs between " + MonitorStyle.fullTime(r.firstTime)
                                + " and " + MonitorStyle.fullTime(r.time));
                    }
                }
                case COL_FILES -> { setText(r.files != null ? r.files : ""); setForeground(muted); }
                case COL_TRIGGER -> { setText(MonitorStyle.triggerLabel(r.trigger)); setForeground(muted); }
                case COL_DURATION -> {
                    String d = MonitorStyle.duration(r.durationMs);
                    if (r.type == FeedRow.Type.BUCKET && !d.isEmpty()) d = "\u00f8 " + d;
                    setText(d);
                    setForeground(muted);
                    setHorizontalAlignment(RIGHT);
                }
                default -> {}
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (edge != null) {
                g2.setColor(edge);
                g2.fillRect(0, 0, 3, getHeight());
            }
            if (expander != 0) {
                int cy = getHeight() / 2;
                g2.setColor(MonitorStyle.muted());
                Polygon p = new Polygon();
                if (expander == 1) {          // ▸
                    p.addPoint(expanderX + 3, cy - 4); p.addPoint(expanderX + 3, cy + 4); p.addPoint(expanderX + 8, cy);
                } else {                      // ▾
                    p.addPoint(expanderX, cy - 2); p.addPoint(expanderX + 8, cy - 2); p.addPoint(expanderX + 4, cy + 3);
                }
                g2.fillPolygon(p);
            }
            if (dot != null) {
                g2.setColor(dot);
                g2.fillOval(7, getHeight() / 2 - 3, 7, 7);
            }
            g2.dispose();
        }
    }
}
