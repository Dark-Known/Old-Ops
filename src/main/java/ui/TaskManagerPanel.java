package ui;

import model.ScheduledTask;
import service.TaskSchedulerService;
import service.TransferService;
import service.XmlStorageService;
import model.ScheduledTask.*;
import ui.components.IconTile;
import ui.components.PillBadge;
import ui.components.StatCard;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TaskManagerPanel extends JPanel {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Status colors now live centrally on AppTheme (RUNNING_FG/BG, FAILED_FG/BG,
    // SUCCESS_FG/BG, SKIPPED_FG/BG) — PillBadge uses those directly, so the
    // per-panel constants that used to live here are gone.

    private final XmlStorageService storage;
    private final TaskSchedulerService scheduler;
    // Same cross-process pattern EventMonitorPanel/MainWindow already use:
    // the Daemon (if running) is the authoritative scheduler, and it drops
    // its live state — including, now, per-task watch mode — to this file
    // every 2s. watchModeTagHtml() prefers a fresh read of this over asking
    // this process's own (possibly-inactive) scheduler, so the tag reflects
    // reality regardless of whether the GUI or the Daemon is actually
    // running the task.
    private final java.nio.file.Path daemonStatusFile;
    private static final long DAEMON_STALE_MS = service.queue.SchedulerStatusSnapshot.DEFAULT_STALE_MS;
    private DefaultListModel<ScheduledTask> listModel;
    private JList<ScheduledTask> taskList;
    // Full, unfiltered snapshot from the last refresh() — the filter box
    // rebuilds listModel's contents from this rather than hiding rows (JList
    // has no TableRowSorter row-filter equivalent), so filtering never needs
    // a view/model index translation the way the old sortable JTable did.
    private List<ScheduledTask> allTasksCache = new ArrayList<>();
    private JTextField txtFilter;
    private StatCard cardTotal;
    private StatCard cardRunning;
    private StatCard cardFailed;
    private StatCard cardSuccessRate;
    private JTextArea logArea;
    private JScrollPane logScrollPane;
    private JLabel lblSelectedTask;
    private JLabel lblActiveSessions;

    // ── Watcher fingerprint status bar ────────────────────────────────────────
    // Surfaces the epoch + size baseline for the selected task.
    // Refreshed on selection change AND after every log line (which fires after
    // a run completes and the service may have persisted a new baseline).
    private JLabel lblWatcherFingerprint;
    private JPanel watcherBar; // kept as a field so refresh() can reach it directly

    // taskId -> accumulated in-memory log
    private final Map<String, StringBuilder> taskLogs = new HashMap<>();

    // Cap how much log text we retain per task so long-running listings
    // (e.g. `ls` over huge remote directories) don't grow memory unboundedly.
    // Oldest text is trimmed off once a task's log exceeds this.
    private static final int MAX_LOG_CHARS_PER_TASK = 500_000; // ~500 KB per task

    // Log lines arrive on a background (scheduler) thread. Instead of scheduling
    // one SwingUtilities.invokeLater(...) per line — which floods the EDT queue
    // and makes every append+getText() call redo work against a huge document —
    // we queue lines here and drain/coalesce them on a timer.
    private final ConcurrentLinkedQueue<String[]> pendingLogLines = new ConcurrentLinkedQueue<>();

    public TaskManagerPanel(XmlStorageService storage, TaskSchedulerService scheduler) {
        this.storage   = storage;
        this.scheduler = scheduler;
        this.daemonStatusFile = storage.getDataDir().toPath().resolve("scheduler-status-daemon.dat");
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        buildUI();

        // Register log callback — called by the scheduler on every emitted log line.
        // Just enqueue; no Swing/UI work happens on the caller's thread.
        scheduler.setLogCallback((taskId, line) -> pendingLogLines.add(new String[]{taskId, line}));

        // Drain queued log lines in batches instead of one EDT task per line.
        // 150ms is frequent enough to feel live, but coalesces bursts (e.g.
        // thousands of `ls` result lines) into a handful of UI updates.
        Timer logFlushTimer = new Timer(150, e -> flushPendingLogLines());
        logFlushTimer.start();

        // Separate lightweight timer for the active-session count: a plain
        // map lookup, so no need to coalesce like the log lines above — but
        // it DOES need its own tick, since it must keep updating even when
        // no new log line has arrived (sessions opening/closing doesn't
        // itself produce a log line at the exact moment the count changes).
        Timer sessionCountTimer = new Timer(500, e -> refreshActiveSessionLabel());
        sessionCountTimer.start();

        // Separate slow timer purely for the "live watch mode" tag in the
        // watcher fingerprint bar — native-watch/remote-push registration
        // happens asynchronously on the scheduler's reconcile cadence (~30s
        // worst case, usually much sooner), so this needs its own tick to
        // pick that transition up without waiting for the user to reselect
        // the row or a log line to arrive. 2s is frequent enough to feel
        // live without adding meaningful load from the storage.loadTasks()
        // call inside updateWatcherFingerprintBar().
        Timer watchModeTimer = new Timer(2000, e -> updateWatcherFingerprintBar());
        watchModeTimer.start();

        // The live-feeling update above (pendingLogLines/flushPendingLogLines)
        // only ever fires when THIS process is the one actually executing the
        // task — logCallback is an in-process callback, so it's simply never
        // invoked when the Daemon (a separate JVM) is the active scheduler
        // and does the executing instead. In that case the log view shown
        // here is a one-time snapshot from whenever the row was selected,
        // with no further updates — hence needing a manual reselect/refresh
        // to see anything the Daemon has since appended.
        //
        // Fix: periodically re-read the same on-disk log file both processes
        // share (service.TaskLogService writes there regardless of which
        // process is running) and refresh the view if it's grown. Harmless
        // and redundant-but-cheap when THIS process is the executor (the
        // callback already keeps it current; this just confirms no drift),
        // and it's the ONLY thing that keeps the view live when the Daemon is.
        Timer liveLogPollTimer = new Timer(1500, e -> refreshSelectedTaskLogIfChanged());
        liveLogPollTimer.start();
    }

    /**
     * Re-reads the selected task's on-disk log and refreshes {@code logArea}
     * only if it actually changed — so this can run on a timer without
     * fighting the user's scroll position or selection on every tick. Scroll
     * position is preserved unless the view was already pinned to the
     * bottom, in which case it stays pinned as new lines arrive.
     */
    private void refreshSelectedTaskLogIfChanged() {
        try {
            ScheduledTask t = getSelectedTask();
            if (t == null) return;
            String id = t.getId();
            String name = t.getName();

            List<String> logs = showingLatestOnly
                    ? scheduler.getLogService().getTaskLogsLastN(id, name, 50)
                    : scheduler.getLogService().getTaskLogs(id, name);
            String fresh = logs.isEmpty() ? "" : String.join("\n", logs);
            String current = logArea.getText();
            // getTaskLogs() returns "" for a placeholder message too — only treat
            // a genuinely different, non-empty disk read as new content, so an
            // empty disk read never stomps a "No log entries yet..." placeholder
            // or an in-memory (taskLogs) fallback that showLogForSelected() may
            // have shown instead.
            if (fresh.isEmpty() || fresh.equals(current)) return;

            JScrollBar vbar = logScrollPane.getVerticalScrollBar();
            boolean wasAtBottom = vbar == null
                    || vbar.getValue() + vbar.getVisibleAmount() >= vbar.getMaximum() - 8;
            logArea.setText(fresh);
            if (wasAtBottom) {
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        } catch (Exception e) {
            // Never let a hiccup here (e.g. a log file mid-rotation) silently
            // and repeatedly no-op this timer tick after tick with nothing
            // visible to the user — surface it once to stderr instead.
            System.err.println("Live log poll failed: " + e);
        }
    }

    private void refreshActiveSessionLabel() {
        String taskId = getSelectedTaskId();
        if (taskId == null) {
            lblActiveSessions.setText(" ");
            return;
        }
        int count = scheduler.getActiveSessionCount(taskId);
        lblActiveSessions.setText(count > 0
                ? "● " + count + " session" + (count == 1 ? "" : "s") + " open"
                : " ");
    }

    private void flushPendingLogLines() {
        if (pendingLogLines.isEmpty()) return;

        Map<String, StringBuilder> batchByTask = new HashMap<>();
        boolean sawTerminalLine = false;

        // Cap how much we drain in one tick so a huge burst still yields
        // control back to the EDT promptly (remaining lines flush next tick).
        String[] entry;
        int drained = 0;
        while (drained < 5000 && (entry = pendingLogLines.poll()) != null) {
            String taskId = entry[0];
            String line = entry[1];
            batchByTask.computeIfAbsent(taskId, k -> new StringBuilder()).append(line).append('\n');
            if (line.contains("=== Task") && line.contains("finished")) sawTerminalLine = true;
            drained++;
        }
        if (batchByTask.isEmpty()) return;

        String selectedTaskId = getSelectedTaskId();
        boolean selectedHasRow = selectedTaskId != null;
        boolean appendedToVisibleArea = false;

        for (Map.Entry<String, StringBuilder> e : batchByTask.entrySet()) {
            String taskId = e.getKey();
            String chunk = e.getValue().toString();

            StringBuilder taskLog = taskLogs.computeIfAbsent(taskId, k -> new StringBuilder());
            taskLog.append(chunk);
            if (taskLog.length() > MAX_LOG_CHARS_PER_TASK) {
                taskLog.delete(0, taskLog.length() - MAX_LOG_CHARS_PER_TASK);
            }

            if (selectedHasRow && taskId.equals(selectedTaskId)) {
                logArea.append(chunk);
                appendedToVisibleArea = true;
            }
        }

        if (appendedToVisibleArea) {
            // Document.getLength() is O(1) — unlike getText().length(), which
            // copies the entire document into a new String on every call.
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        // Only refresh table on terminal lines — not every log line.
        // This prevents UI thrash that breaks the Refresh button and next-run calculation.
        if (sawTerminalLine) {
            Timer timer = new Timer(600, e -> {
                refresh();
                updateWatcherFingerprintBar();
            });
            timer.setRepeats(false);
            timer.start();
        }
    }

    private void buildUI() {
        // ── Toolbar ──────────────────────────────────────────────────────────
        // Only "New Task" is accent-styled now — one primary action per view,
        // same restraint principle a well-designed toolbar follows: everything
        // else (including the previously-gradient Delete/Run Now/Restart/View
        // Logs buttons) is now a plain, quiet button so the eye isn't pulled
        // in five directions at once. Delete keeps a red-tinted label as its
        // only distinguishing mark — enough to read as destructive without a
        // loud color block sitting one click from "Run Now".
        JButton btnNew        = new GradientButton("New Task");
        JButton btnEdit       = new JButton("Edit");
        JButton btnDelete     = new JButton("Delete");
        JButton btnRunNow     = new JButton("Run Now");
        JButton btnRestart    = new JButton("Restart Task");
        JButton btnEnable     = new JButton("Enable/Disable");
        JButton btnViewLogs   = new JButton("View Logs");
        JButton btnLatestLogs = new JButton("Latest Logs");
        JButton btnRefresh    = new JButton();
        btnRefresh.setIcon(null);
        btnRefresh.setText("\u21BB"); // ↻ — compact icon-style refresh, no label needed in a toolbar this size
        btnRefresh.setToolTipText("Refresh");
        btnRefresh.setMargin(new Insets(2, 8, 2, 8));

        styleBtn(btnNew, AppTheme.EARTH_MOSS);
        btnDelete.setForeground(AppTheme.EARTH_RUST);

        btnNew.addActionListener(e        -> newTask());
        btnEdit.addActionListener(e       -> editTask());
        btnDelete.addActionListener(e     -> deleteTask());
        btnRunNow.addActionListener(e     -> runNow());
        btnRestart.addActionListener(e    -> restartTask());
        btnEnable.addActionListener(e     -> toggleEnable());
        btnRefresh.addActionListener(e    -> refresh());
        btnViewLogs.addActionListener(e   -> showLogForSelected());
        btnLatestLogs.addActionListener(e -> showLatestLogsForSelected());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.add(btnNew);    toolbar.add(btnEdit);    toolbar.add(btnDelete);
        toolbar.add(new JSeparator(JSeparator.VERTICAL));
        toolbar.add(btnRunNow); toolbar.add(btnRestart); toolbar.add(btnEnable);
        toolbar.add(new JSeparator(JSeparator.VERTICAL));
        toolbar.add(btnViewLogs); toolbar.add(btnLatestLogs);

        // ── Filter box ───────────────────────────────────────────────────────
        // Filters the table live by name or type — cheap to add now that
        // column sorting already needs a TableRowSorter; the same sorter
        // handles both.
        JLabel lblFilter = new JLabel("Filter:");
        lblFilter.setFont(lblFilter.getFont().deriveFont(Font.PLAIN, 12f));
        txtFilter = new JTextField(16);
        txtFilter.setToolTipText("Filter by task name or type");
        txtFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });

        // ── Legend ───────────────────────────────────────────────────────────
        // Was a permanently-visible row of color swatches, taking up space
        // every time regardless of whether anyone needed reminding what the
        // colors meant. Collapsed to a "?" button — the legend text is now a
        // tooltip, reference material you check occasionally rather than
        // something the layout pays rent for permanently.
        JButton btnLegend = new JButton("?");
        btnLegend.setToolTipText(legendTooltipHtml());
        btnLegend.setMargin(new Insets(2, 8, 2, 8));

        JPanel toolbarRow = new JPanel(new BorderLayout());
        toolbarRow.add(toolbar, BorderLayout.WEST);
        JPanel filterAndLegend = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filterAndLegend.add(lblFilter);
        filterAndLegend.add(txtFilter);
        filterAndLegend.add(btnLegend);
        filterAndLegend.add(btnRefresh);
        toolbarRow.add(filterAndLegend, BorderLayout.EAST);

        JLabel banner = new JLabel(
            "<html><b>Scheduled Tasks</b> — file transfers, mail fetches, and backup jobs run on a schedule.<br>"
            + "<span style='color:gray'>Select a task below to view its execution log, or use the buttons to"
            + " create, edit, run, or manage tasks.</span></html>");
        banner.setBorder(new EmptyBorder(0, 0, 8, 0));

        // ── Summary strip ────────────────────────────────────────────────────
        cardTotal       = new StatCard("Tasks", "0", null);
        cardRunning     = new StatCard("Running", "0", AppTheme.RUNNING_FG);
        cardFailed      = new StatCard("Failed", "0", AppTheme.FAILED_FG);
        cardSuccessRate = new StatCard("Success rate", "—", AppTheme.SUCCESS_FG);
        JPanel statsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statsRow.setOpaque(false);
        statsRow.add(cardTotal);
        statsRow.add(cardRunning);
        statsRow.add(cardFailed);
        statsRow.add(cardSuccessRate);

        JPanel headerPanel = new JPanel(new BorderLayout(0, 8));
        headerPanel.add(banner, BorderLayout.NORTH);
        JPanel headerMiddle = new JPanel(new BorderLayout(0, 6));
        headerMiddle.add(statsRow, BorderLayout.NORTH);
        headerMiddle.add(toolbarRow, BorderLayout.SOUTH);
        headerPanel.add(headerMiddle, BorderLayout.CENTER);
        add(headerPanel, BorderLayout.NORTH);

        // ── Task list ─────────────────────────────────────────────────────────
        // JList + a custom card renderer, not JTable: a card row (icon tile,
        // two-line text block, status pill) needs full control over its own
        // layout per row, which TableCellRenderer's single-JLabel-per-cell
        // model can't give cleanly. Binding directly to ScheduledTask objects
        // also removes the parallel taskIds/row-index bookkeeping the JTable
        // version needed — the selected list value IS the task, no lookup.
        listModel = new DefaultListModel<>();
        taskList = new JList<>(listModel);
        taskList.setCellRenderer(new TaskRowRenderer());
        taskList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskList.setFixedCellHeight(52);

        taskList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showLogForSelected();
                updateWatcherFingerprintBar();
            }
        });

        // Clicking directly on a watcher-enabled row pops up its live status
        // (mode + reason + a manual Reconnect action) — see WatcherInfoPopup.
        // Non-watcher rows are unaffected; this is purely additive on top of
        // the existing selection-driven log/fingerprint-bar behavior above.
        taskList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = taskList.locationToIndex(e.getPoint());
                if (index < 0 || !taskList.getCellBounds(index, index).contains(e.getPoint())) return;
                ScheduledTask task = listModel.getElementAt(index);
                if (task.getTaskType() != ScheduledTask.TaskType.FILE_TRANSFER || !task.isWatcherEnabled()) {
                    return; // not a watcher task — normal selection/log behavior only
                }
                Point screenPoint = new Point(e.getPoint());
                SwingUtilities.convertPointToScreen(screenPoint, taskList);
                WatcherInfoPopup.show(taskList, screenPoint, task, storage, scheduler);
            }
        });

        JScrollPane tableScroll = new JScrollPane(taskList);
        tableScroll.setPreferredSize(new Dimension(900, 220));

        // ── Log panel ─────────────────────────────────────────────────────────
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(0x1E1E1E));
        logArea.setForeground(new Color(0xD4D4D4));
        logArea.setCaretColor(Color.WHITE);
        logScrollPane = new JScrollPane(logArea);

        lblSelectedTask = new JLabel("Select a task to view its execution log");
        lblSelectedTask.setFont(lblSelectedTask.getFont().deriveFont(Font.BOLD));
        lblSelectedTask.setBorder(new EmptyBorder(4, 2, 4, 0));

        JButton btnClearLog = new JButton("Clear Log");
        btnClearLog.addActionListener(e -> {
            logArea.setText("");
            String id = getSelectedTaskId();
            if (id != null) taskLogs.remove(id);
        });

        JButton btnExportLog    = new JButton("Export");
        JButton btnViewArchives = new JButton("Archives");
        btnExportLog.addActionListener(e    -> exportLogsForSelected());
        btnViewArchives.addActionListener(e -> viewLogArchives());

        JPanel logButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        logButtonPanel.add(btnViewArchives);
        logButtonPanel.add(btnExportLog);
        logButtonPanel.add(btnClearLog);

        // Live count of open WinSCP/SFTP sessions for the selected task.
        // Normally 0 (idle) or 1; can show >1 when Settings' "Batches/files
        // to run at once" is raised above 1 and several batches are running
        // in parallel — this is what lets you actually see that concurrency
        // setting doing something, rather than just inferring it from
        // overlapping "Batch N/Total" log lines.
        lblActiveSessions = new JLabel(" ");
        lblActiveSessions.setFont(lblActiveSessions.getFont().deriveFont(Font.PLAIN, 11f));
        lblActiveSessions.setForeground(AppTheme.EARTH_SIENNA);
        lblActiveSessions.setBorder(new EmptyBorder(4, 10, 4, 0));

        JPanel logHeader = new JPanel(new BorderLayout());
        logHeader.add(lblSelectedTask, BorderLayout.WEST);
        JPanel logHeaderRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        logHeaderRight.add(lblActiveSessions);
        logHeaderRight.add(logButtonPanel);
        logHeader.add(logHeaderRight, BorderLayout.EAST);

        // ── Watcher fingerprint bar ───────────────────────────────────────────
        // Thin amber-tinted strip shown below the log header whenever the selected
        // task is an INBOUND FILE_TRANSFER with the watcher enabled.
        // Surfaces three states: no baseline, baseline with size, legacy (no size).
        // Colour coding mirrors TaskDialog's refreshWatcherStatusLabel:
        //   grey  = no baseline  |  green = healthy  |  amber = legacy/needs reset
        lblWatcherFingerprint = new JLabel();
        lblWatcherFingerprint.setFont(lblWatcherFingerprint.getFont().deriveFont(Font.PLAIN, 11f));

        watcherBar = new JPanel(new BorderLayout());
        watcherBar.setBackground(new Color(0xFBF3E3));
        watcherBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(0xD9A441)),
            new EmptyBorder(3, 6, 3, 6)));
        watcherBar.add(lblWatcherFingerprint, BorderLayout.WEST);
        watcherBar.setVisible(false);

        // Small "Reset Baseline" link inside the bar so ops can reset without
        // opening the Edit dialog when they spot an amber / stale baseline.
        JButton btnBarReset = new JButton("Reset Baseline");
        btnBarReset.setFont(btnBarReset.getFont().deriveFont(Font.PLAIN, 11f));
        btnBarReset.setForeground(AppTheme.EARTH_OCHRE);
        btnBarReset.setBorderPainted(false);
        btnBarReset.setContentAreaFilled(false);
        btnBarReset.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnBarReset.setToolTipText(
            "Clears the stored epoch and size so the next watcher run treats everything as new.");
        btnBarReset.addActionListener(e -> resetBaselineForSelected());
        watcherBar.add(btnBarReset, BorderLayout.EAST);

        // Stack: logHeader → watcherBar → logScroll
        JPanel logPanel = new JPanel(new BorderLayout(4, 0));
        logPanel.setBorder(new EmptyBorder(8, 0, 0, 0));

        JPanel logTop = new JPanel(new BorderLayout(0, 2));
        logTop.add(logHeader, BorderLayout.NORTH);
        logTop.add(watcherBar, BorderLayout.SOUTH);

        logPanel.add(logTop,      BorderLayout.NORTH);
        logPanel.add(logScrollPane,   BorderLayout.CENTER);

        logScrollPane.setPreferredSize(new Dimension(900, 200));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, logPanel);
        split.setResizeWeight(0.55);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        refresh();
    }

    // ── Watcher fingerprint bar ────────────────────────────────────────────────

    /**
     * Refreshes the watcher fingerprint bar for the currently selected task.
     *
     * Visible only when:
     *   taskType == FILE_TRANSFER AND direction == INBOUND AND watcherEnabled.
     *
     * Three states (matching TaskDialog and TaskManagerPanel original implementation):
     *
     *   epoch == 0 (no baseline):
     *     Grey — "Watcher: no baseline stored — first run will always transfer."
     *
     *   epoch > 0, size >= 0 (healthy):
     *     Green — "Watcher baseline: last seen YYYY-MM-DD HH:mm:ss | size: N bytes"
     *
     *   epoch > 0, size == -1 (legacy — size not tracked):
     *     Amber — prompts the operator to open Edit → Reset Baseline.
     *
     * This is reloaded from storage on every call so it reflects the latest epoch
     * persisted by TransferService after each successful watcher run.
     */
    private void updateWatcherFingerprintBar() {
        String taskId = getSelectedTaskId();
        if (taskId == null) {
            watcherBar.setVisible(false);
            return;
        }

        ScheduledTask task = storage.loadTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst().orElse(null);

        if (task == null
                || task.getTaskType() != TaskType.FILE_TRANSFER
                || !task.isWatcherEnabled()) {  // removed direction check — show for both directions
            watcherBar.setVisible(false);
            return;
        }

        long epoch = task.getLastKnownRemoteFileEpoch();
        long size  = task.getLastKnownRemoteFileSize();

        String html;
        if (epoch <= 0) {
            html = "<html><b style='color:#757575'>Watcher:</b> "
                    + "<i style='color:#757575'>no baseline stored - first run will always transfer.</i>"
                    + watchModeTagHtml(task) + "</html>";
        } else {
            String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(epoch));
            if (size < 0) {
                html = String.format(
                        "<html><b style='color:#E65100'>Watcher baseline:</b> "
                                + "<i style='color:#E65100'>last seen %s &nbsp;|&nbsp; size: not tracked "
                                + "(reset baseline to fix)</i>%s</html>", dateStr, watchModeTagHtml(task));
            } else {
                html = String.format(
                        "<html><b style='color:#2E7D32'>Watcher baseline:</b> "
                                + "<i style='color:#2E7D32'>last seen %s &nbsp;|&nbsp; size: %,d bytes</i>%s</html>",
                        dateStr, size, watchModeTagHtml(task));
            }
        }

        lblWatcherFingerprint.setText(html);
        watcherBar.setVisible(true);
        watcherBar.revalidate();
        watcherBar.repaint();
    }

    /**
     * Small trailing HTML fragment (no surrounding &lt;html&gt; tags) showing
     * how this watcher-enabled task is currently being triggered — an instant
     * OS-level directory watch, an instant remote SSH push, or plain polling
     * on its configured interval.
     *
     * <p>Prefers the headless Daemon's own exported status (same cross-process
     * file EventMonitorPanel/MainWindow already read) when it's alive and
     * fresh, since the Daemon — not this GUI process — is the one actually
     * running the task in the normal "Daemon is primary" setup (see
     * MainWindow's constructor). Falls back to asking this process's own
     * scheduler directly, which is correct when the GUI itself is the active
     * scheduler (Daemon not running) or simply hasn't found the task in the
     * Daemon's export yet (e.g. right after the Daemon adds a brand-new task,
     * before its next 2s export tick).
     */
    private String watchModeTagHtml(ScheduledTask task) {
        String label;
        String color;

        String modeName = readDaemonWatchMode(task.getId());
        if (modeName != null) {
            TaskSchedulerService.WatchMode mode;
            try {
                mode = TaskSchedulerService.WatchMode.valueOf(modeName);
            } catch (IllegalArgumentException e) {
                mode = TaskSchedulerService.WatchMode.NOT_APPLICABLE; // forward-compat: unknown mode name
            }
            switch (mode) {
                case NATIVE_WATCH -> { label = "\u26A1 Live (native watch) \u2022 daemon"; color = "#2E7D32"; }
                case REMOTE_PUSH -> { label = "\u26A1 Live (remote push) \u2022 daemon"; color = "#2E7D32"; }
                case POLLING_ONLY_UNSUPPORTED -> {
                    label = "Polling only \u2014 remote push unavailable on this host \u2022 daemon";
                    color = "#E65100";
                }
                case POLLING_ONLY -> { label = "Polling only \u2022 daemon"; color = "#757575"; }
                default -> { return ""; }
            }
            return "&nbsp;&nbsp;&nbsp;<b style='color:" + color + "'>" + label + "</b>";
        }

        // No fresh Daemon export mentioning this task — fall back to this
        // process's own scheduler (correct when the GUI itself is primary).
        TaskSchedulerService.WatchMode mode = scheduler.getWatchMode(task);
        switch (mode) {
            case NATIVE_WATCH -> { label = "\u26A1 Live (native watch)"; color = "#2E7D32"; }
            case REMOTE_PUSH -> { label = "\u26A1 Live (remote push)"; color = "#2E7D32"; }
            case POLLING_ONLY_UNSUPPORTED -> {
                label = "Polling only \u2014 remote push unavailable on this host";
                color = "#E65100";
            }
            case POLLING_ONLY -> { label = "Polling only"; color = "#757575"; }
            default -> { return ""; }
        }
        return "&nbsp;&nbsp;&nbsp;<b style='color:" + color + "'>" + label + "</b>";
    }

    /**
     * Returns the raw {@code TaskSchedulerService.WatchMode} name the Daemon
     * last exported for {@code taskId}, or {@code null} if the Daemon isn't
     * alive right now, its export is stale, or it doesn't mention this task
     * (e.g. task isn't watcher-enabled, or was created after its last tick).
     */
    private String readDaemonWatchMode(String taskId) {
        if (!service.queue.SchedulerStatusSnapshot.isAlive(daemonStatusFile, DAEMON_STALE_MS)) return null;
        service.queue.SchedulerStatusSnapshot snap = service.queue.SchedulerStatusSnapshot.read(daemonStatusFile);
        if (snap == null) return null;
        for (service.queue.SchedulerStatusSnapshot.WatchEntry w : snap.getWatchEntries()) {
            if (w.taskId().equals(taskId)) return w.mode();
        }
        return null;
    }

    /**
     * Quick baseline reset directly from the fingerprint bar's "Reset Baseline" button.
     * Clears epoch and size on the task, persists it, then refreshes the bar.
     * Avoids making the operator open the Edit dialog just to clear a stale baseline.
     */
    private void resetBaselineForSelected() {
        String id = getSelectedTaskId();
        if (id == null) return;

        storage.loadTasks().stream()
            .filter(t -> t.getId().equals(id))
            .findFirst()
            .ifPresent(task -> {
                int ok = JOptionPane.showConfirmDialog(this,
                    "Reset watcher baseline for \"" + task.getName() + "\"?\n\n"
                    + "The next watcher run will treat all files in the source directory as new "
                    + "and transfer everything it finds.",
                    "Reset Watcher Baseline", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
                if (ok != JOptionPane.YES_OPTION) return;

                task.setLastKnownRemoteFileEpoch(0L);
                task.setLastKnownRemoteFileSize(-1L);
                storage.saveTask(task);
                updateWatcherFingerprintBar();
                JOptionPane.showMessageDialog(this,
                    "Baseline cleared. The next watcher run will start fresh.",
                    "Baseline Reset", JOptionPane.INFORMATION_MESSAGE);
            });
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    /** HTML shown as the "?" button's tooltip — the same status key that used
     *  to be a permanently-visible row of swatches, now reference material
     *  you check occasionally rather than something the layout pays rent for
     *  permanently. Uses the same paired tokens {@link PillBadge} paints
     *  status pills with, so the key always matches what's actually shown. */
    private String legendTooltipHtml() {
        return "<html><b>Status colors</b><br>"
            + legendRow(AppTheme.RUNNING_FG, AppTheme.RUNNING_BG, "Running")
            + legendRow(AppTheme.SUCCESS_FG, AppTheme.SUCCESS_BG, "Success")
            + legendRow(AppTheme.SKIPPED_FG, AppTheme.SKIPPED_BG, "Skipped (no new file)")
            + legendRow(AppTheme.FAILED_FG, AppTheme.FAILED_BG, "Failed")
            + legendRow(AppTheme.NEUTRAL_FG, null, "Disabled")
            + "</html>";
    }

    private String legendRow(Color fg, Color bg, String label) {
        String swatch = bg != null
            ? "<span style='background:" + toHex(bg) + ";color:" + toHex(fg) + "'>&nbsp;&nbsp;&nbsp;&nbsp;</span>"
            : "<span style='color:" + toHex(fg) + "'>\u25CF</span>";
        return swatch + "&nbsp;" + label + "<br>";
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ── Status color mapping ──────────────────────────────────────────────────

    /** {fg, bg} pair for a status pill — {@code bg} may be {@code null}, meaning
     *  "plain muted text, no pill background" (e.g. Disabled) rather than a
     *  colored chip, since a chip for every single status (including neutral
     *  ones) would be noise, not signal. */
    private Color[] statusColors(String status, String lastResult) {
        if (status == null) return new Color[]{ AppTheme.NEUTRAL_FG, null };
        switch (status) {
            case "RUNNING":  return new Color[]{ AppTheme.RUNNING_FG, AppTheme.RUNNING_BG };
            case "FAILED":   return new Color[]{ AppTheme.FAILED_FG, AppTheme.FAILED_BG };
            case "DISABLED": return new Color[]{ AppTheme.NEUTRAL_FG, null };
            case "SUCCESS":
                if (lastResult != null && lastResult.contains("SKIPPED")) {
                    return new Color[]{ AppTheme.SKIPPED_FG, AppTheme.SKIPPED_BG };
                }
                return new Color[]{ AppTheme.SUCCESS_FG, AppTheme.SUCCESS_BG };
            default:
                if (lastResult != null && lastResult.contains("SKIPPED")) {
                    return new Color[]{ AppTheme.SKIPPED_FG, AppTheme.SKIPPED_BG };
                }
                return new Color[]{ AppTheme.NEUTRAL_FG, null };
        }
    }

    // ── Row view-model / renderer ─────────────────────────────────────────────

    /** Precomputed per-task display strings, refreshed once per {@link #refresh()}
     *  cycle rather than recomputed on every single row repaint — in particular
     *  the watcher-live-vs-polling determination reads the Daemon's exported
     *  status file, which is cheap once per refresh but not something to redo
     *  on every JList repaint. */
    private record TaskRowInfo(String typeCell, String scheduleCell, String lastRunCell, String resultLine) {}

    private final Map<String, TaskRowInfo> rowInfoByTaskId = new HashMap<>();

    private static final TaskRowInfo FALLBACK_ROW_INFO =
            new TaskRowInfo("", "", "", "");

    /**
     * Card-style row renderer: an {@link IconTile} (task type/direction glyph),
     * a two-line text block (name + meta line), and a status {@link PillBadge}
     * on the trailing edge. Reused across rows (standard {@code ListCellRenderer}
     * pattern) — only the icon tile is rebuilt per call, since its colors
     * depend on per-row data and it has no public setter for that; the text
     * labels and badge are mutated in place.
     */
    private class TaskRowRenderer extends JPanel implements ListCellRenderer<ScheduledTask> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();
        private final PillBadge statusBadge = new PillBadge("", AppTheme.NEUTRAL_FG, null);
        private IconTile currentIcon;

        TaskRowRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(6, 10, 6, 10));

            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
            metaLabel.setFont(metaLabel.getFont().deriveFont(Font.PLAIN, 11f));
            metaLabel.setForeground(new Color(0x8A8378));

            JPanel textBlock = new JPanel();
            textBlock.setOpaque(false);
            textBlock.setLayout(new BoxLayout(textBlock, BoxLayout.Y_AXIS));
            nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            textBlock.add(nameLabel);
            textBlock.add(metaLabel);
            add(textBlock, BorderLayout.CENTER);

            JPanel right = new JPanel(new GridBagLayout());
            right.setOpaque(false);
            right.add(statusBadge);
            add(right, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ScheduledTask> list, ScheduledTask t,
                                                        int index, boolean isSelected, boolean cellHasFocus) {
            nameLabel.setText(t.getName());

            String glyph;
            Color tileBg;
            if (t.getTaskType() == TaskType.FILE_TRANSFER) {
                glyph = t.getTransferDirection() == TransferDirection.INBOUND ? "\u2B07" : "\u2B06";
                tileBg = AppTheme.EARTH_SIENNA;
            } else if (t.getTaskType() == TaskType.OUTLOOK_MAIL) {
                glyph = "\u2709";
                tileBg = AppTheme.EARTH_TEAL;
            } else {
                glyph = "\u21BB";
                tileBg = AppTheme.EARTH_OCHRE;
            }
            if (currentIcon != null) remove(currentIcon);
            currentIcon = new IconTile(glyph, Color.WHITE, tileBg);
            add(currentIcon, BorderLayout.WEST);

            TaskRowInfo info = rowInfoByTaskId.getOrDefault(t.getId(), FALLBACK_ROW_INFO);
            metaLabel.setText(info.typeCell() + "  \u00b7  " + info.scheduleCell()
                    + "  \u00b7  Last run: " + info.lastRunCell() + info.resultLine());

            statusBadge.setText(t.getStatus().name());
            String lastResult = t.getLastRunResult();
            Color[] colors = statusColors(t.getStatus().name(), lastResult);
            statusBadge.setColors(colors[0], colors[1]);

            setBackground(isSelected ? list.getSelectionBackground()
                    : (index % 2 == 0 ? list.getBackground() : AppTheme.surface2()));
            setOpaque(true);
            revalidate();
            return this;
        }
    }

    // ── Refresh / list population ─────────────────────────────────────────────

    public void refresh() {
        String selectedId = getSelectedTaskId();

        List<ScheduledTask> tasks = storage.loadTasks();
        allTasksCache = tasks;
        rowInfoByTaskId.clear();

        // Read the Daemon's exported watch state once per refresh (not once
        // per row) — same cross-process preference the fingerprint bar and
        // WatcherInfoPopup already use, just batched here since this loop
        // may touch many watcher-enabled rows at once.
        Map<String, service.queue.SchedulerStatusSnapshot.WatchEntry> daemonWatchEntries = new HashMap<>();
        if (service.queue.SchedulerStatusSnapshot.isAlive(daemonStatusFile, DAEMON_STALE_MS)) {
            service.queue.SchedulerStatusSnapshot snap = service.queue.SchedulerStatusSnapshot.read(daemonStatusFile);
            if (snap != null) {
                for (service.queue.SchedulerStatusSnapshot.WatchEntry w : snap.getWatchEntries()) {
                    daemonWatchEntries.put(w.taskId(), w);
                }
            }
        }

        int runningCount = 0, failedCount = 0, totalRunCount = 0, successCount = 0;

        for (ScheduledTask t : tasks) {
            String schedDesc  = buildScheduleDescription(t);
            String lastResult = t.getLastRunResult() != null ? t.getLastRunResult() : "";

            // Type/Direction/Mode merged into one line — almost always short
            // and directly related, so three separate fields just for this
            // was mostly wasted space. E.g. "File Transfer — Outbound
            // (Latest Only)", or plain "Outlook Mail" / "Backup" for task
            // types that don't have a direction/mode at all.
            String typeCell = t.getTaskType().name().replace("_", " ");
            if (t.getTaskType() == TaskType.FILE_TRANSFER) {
                String direction = t.getTransferDirection().name();
                String mode = t.getTransferMode() != null
                        ? " (" + t.getTransferMode().name().replace("_", " ") + ")" : "";
                typeCell += " \u2014 " + direction + mode;
            }

            // Watch status: prefer the Daemon's live cross-process reading,
            // fall back to this process's own scheduler when the Daemon
            // hasn't mentioned this task (not alive, or task too new). Full
            // detail text is intentionally not shown inline at all — clicking
            // any watcher-enabled row already opens WatcherInfoPopup with it
            // (mode, reason, a manual Reconnect action).
            String watchMode = "NOT_APPLICABLE";
            if (t.getTaskType() == TaskType.FILE_TRANSFER && t.isWatcherEnabled()) {
                service.queue.SchedulerStatusSnapshot.WatchEntry daemonEntry = daemonWatchEntries.get(t.getId());
                watchMode = daemonEntry != null ? daemonEntry.mode() : scheduler.getWatchStatus(t).mode().name();
            }
            boolean watcherLive = watchMode.equals("NATIVE_WATCH") || watchMode.equals("REMOTE_PUSH");

            // Schedule + Next Run merged — while push is live these would
            // otherwise show the same "watcher-driven"/"—" pair for no
            // reason; the moment a watcher task falls back to polling (or was
            // never eligible for push), this goes back to the real schedule
            // and next-due time, since at that point the schedule genuinely
            // is what's driving execution again.
            String scheduleCell = watcherLive
                    ? "\u26A1 Watcher-driven"
                    : schedDesc + " \u00b7 next: " + calculateNextRun(t);

            String lastRunCell = t.getLastRunAt() != null ? t.getLastRunAt().format(DT) : "Never";
            String resultLine = lastResult.isEmpty() ? ""
                    : " \u00b7 " + ("SKIPPED".equals(lastResult) ? "Skipped" : lastResult);

            rowInfoByTaskId.put(t.getId(), new TaskRowInfo(typeCell, scheduleCell, lastRunCell, resultLine));

            if (t.getStatus() == TaskStatus.RUNNING) runningCount++;
            if ("FAILED".equals(lastResult)) failedCount++;
            if (t.getLastRunAt() != null) {
                totalRunCount++;
                if ("SUCCESS".equals(lastResult) || "SKIPPED".equals(lastResult)) successCount++;
            }
        }

        cardTotal.setValue(String.valueOf(tasks.size()));
        cardRunning.setValue(String.valueOf(runningCount));
        cardFailed.setValue(String.valueOf(failedCount));
        cardSuccessRate.setValue(totalRunCount == 0 ? "\u2014"
                : Math.round(100.0 * successCount / totalRunCount) + "%");

        applyFilter();

        // Re-select previously selected task, if it's still present (and
        // still passes the current filter).
        if (selectedId != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (selectedId.equals(listModel.get(i).getId())) {
                    taskList.setSelectedIndex(i);
                    break;
                }
            }
        }

        updateWatcherFingerprintBar();
    }

    /**
     * Rebuilds {@code listModel}'s contents from {@code allTasksCache} filtered
     * by the current filter box text (matched against name or type, case
     * insensitive). {@code JList} has no built-in row-filter the way
     * {@code TableRowSorter} does for {@code JTable}, so filtering here means
     * swapping the model's contents rather than hiding rows in place.
     * Preserves the current selection where the selected task still matches.
     */
    private void applyFilter() {
        if (listModel == null || allTasksCache == null) return;
        String filter = txtFilter != null ? txtFilter.getText().trim().toLowerCase() : "";
        String selectedId = getSelectedTaskId();

        listModel.clear();
        for (ScheduledTask t : allTasksCache) {
            if (filter.isEmpty()
                    || t.getName().toLowerCase().contains(filter)
                    || t.getTaskType().name().toLowerCase().replace("_", " ").contains(filter)) {
                listModel.addElement(t);
            }
        }

        if (selectedId != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (selectedId.equals(listModel.get(i).getId())) {
                    taskList.setSelectedIndex(i);
                    return;
                }
            }
        }
    }

    // ── Schedule description / next-run calculation ───────────────────────────

    private String buildScheduleDescription(ScheduledTask t) {
        switch (t.getScheduleType()) {
            case RUN_NOW:          return "Run Now";
            case ONCE:             return "Once @ " + (t.getScheduledAt() != null ? t.getScheduledAt().format(DT) : "?");
            case DAILY:            return "Daily @ " + (t.getCronExpression() != null ? t.getCronExpression() : "?");
            case WEEKLY:           return "Weekly " + (t.getCronExpression() != null ? t.getCronExpression() : "?");
            case INTERVAL_MINUTES: return "Every " + t.getIntervalMinutes() + " min";
            case INTERVAL_SECONDS: return "Every " + t.getIntervalSeconds() + " sec";
            default:               return "?";
        }
    }

    private String calculateNextRun(ScheduledTask t) {
        if (t.getStatus() == TaskStatus.DISABLED) return "Disabled";
        LocalDateTime now = LocalDateTime.now();
        switch (t.getScheduleType()) {
            case RUN_NOW:
                return "On demand";
            case ONCE:
                if (t.getScheduledAt() == null) return "Invalid";
                if (t.getScheduledAt().isBefore(now)) return "Overdue";
                return t.getScheduledAt().format(DT);
            case DAILY:
                if (t.getCronExpression() == null) return "Invalid";
                try {
                    LocalTime target = LocalTime.parse(t.getCronExpression(),
                        DateTimeFormatter.ofPattern("HH:mm"));
                    LocalDateTime next = now.withHour(target.getHour())
                        .withMinute(target.getMinute()).withSecond(0);
                    if (!next.isAfter(now)) next = next.plusDays(1);
                    return next.format(DT);
                } catch (Exception e) { return "Invalid"; }
            case WEEKLY:
                if (t.getCronExpression() == null) return "Invalid";
                try {
                    String[] parts = t.getCronExpression().split(" ");
                    if (parts.length < 2) return "Invalid";
                    LocalTime target = LocalTime.parse(parts[1],
                        DateTimeFormatter.ofPattern("HH:mm"));
                    DayOfWeek targetDay = null;
                    for (DayOfWeek dw : DayOfWeek.values()) {
                        if (dw.name().startsWith(parts[0].toUpperCase()
                                .substring(0, Math.min(3, parts[0].length())))) {
                            targetDay = dw;
                            break;
                        }
                    }
                    if (targetDay == null) return "Invalid";
                    LocalDateTime next = now.with(TemporalAdjusters.next(targetDay))
                        .withHour(target.getHour()).withMinute(target.getMinute()).withSecond(0);
                    if (now.getDayOfWeek() == targetDay) {
                        LocalDateTime today = now.withHour(target.getHour())
                            .withMinute(target.getMinute()).withSecond(0);
                        if (today.isAfter(now)) next = today;
                    }
                    return next.format(DT);
                } catch (Exception e) { return "Invalid"; }
            case INTERVAL_MINUTES:
                if (t.getIntervalMinutes() <= 0) return "Invalid";
                return (t.getLastRunAt() != null
                    ? t.getLastRunAt().plusMinutes(t.getIntervalMinutes()) : now).format(DT);
            case INTERVAL_SECONDS:
                if (t.getIntervalSeconds() <= 0) return "Invalid";
                return (t.getLastRunAt() != null
                    ? t.getLastRunAt().plusSeconds(t.getIntervalSeconds()) : now).format(DT);
            default: return "Unknown";
        }
    }

    // ── Task actions ──────────────────────────────────────────────────────────

    private void newTask() {
        try {
            TaskDialog dlg = new TaskDialog(
                (Frame) SwingUtilities.getWindowAncestor(this), storage, null);
            dlg.setVisible(true);
            if (dlg.getResult() != null) refresh();
        } catch (Throwable ex) {
            JOptionPane.showMessageDialog(this,
                "Failed to open New Task dialog:\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
        }
    }

    private void editTask() {
        String id = getSelectedTaskId();
        if (id == null) { JOptionPane.showMessageDialog(this, "Select a task to edit."); return; }
        storage.loadTasks().stream().filter(t -> t.getId().equals(id)).findFirst()
            .ifPresentOrElse(t -> {
                try {
                    TaskDialog dlg = new TaskDialog(
                        (Frame) SwingUtilities.getWindowAncestor(this), storage, t);
                    dlg.setVisible(true);
                    if (dlg.getResult() != null) refresh();
                } catch (Throwable ex) {
                    JOptionPane.showMessageDialog(this,
                        "Failed to open Edit Task dialog:\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                    ex.printStackTrace();
                }
            }, () -> JOptionPane.showMessageDialog(this,
                "Selected task could not be found.", "Error", JOptionPane.ERROR_MESSAGE));
    }

    private void deleteTask() {
        ScheduledTask selected = getSelectedTask();
        if (selected == null) { JOptionPane.showMessageDialog(this, "Select a task to delete."); return; }
        String name = selected.getName();
        String id   = selected.getId();

        int ok = JOptionPane.showConfirmDialog(this,
            "Delete task \"" + name + "\"?",
            "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            storage.deleteTask(id);
            try { scheduler.cancelTask(id); } catch (Exception ignored) {}
            try { scheduler.refresh();      } catch (Exception ignored) {}
            taskLogs.remove(id);
            refresh();
        }
    }

    private void runNow() {
        ScheduledTask selected = getSelectedTask();
        if (selected == null) { JOptionPane.showMessageDialog(this, "Select a task to run."); return; }
        String id   = selected.getId();
        String name = selected.getName();

        int ok = JOptionPane.showConfirmDialog(this,
            "Run task \"" + name + "\" immediately?",
            "Confirm Run Now", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            scheduler.runNow(id);
            JOptionPane.showMessageDialog(this,
                "Task \"" + name + "\" queued for immediate execution.");
            refresh();
        }
    }

    private void toggleEnable() {
        String id = getSelectedTaskId();
        if (id == null) { JOptionPane.showMessageDialog(this, "Select a task."); return; }

        storage.loadTasks().stream().filter(t -> t.getId().equals(id)).findFirst().ifPresent(t -> {
            t.setStatus(t.getStatus() == TaskStatus.DISABLED
                ? TaskStatus.PENDING : TaskStatus.DISABLED);
            storage.saveTask(t);
            try { if (t.getStatus() == TaskStatus.DISABLED) scheduler.cancelTask(t.getId()); }
            catch (Exception ignored) {}
            try { scheduler.refresh(); } catch (Exception ignored) {}
            refresh();
        });
    }

    private void restartTask() {
        ScheduledTask selected = getSelectedTask();
        if (selected == null) { JOptionPane.showMessageDialog(this, "Select a task to restart."); return; }
        String id = selected.getId();
        String name = selected.getName();

        ScheduledTask task = storage.loadTasks().stream()
            .filter(t -> t.getId().equals(id)).findFirst().orElse(null);
        if (task == null) { JOptionPane.showMessageDialog(this, "Selected task could not be found."); return; }

        int ok = JOptionPane.showConfirmDialog(this,
            "Restart task \"" + name + "\" now?",
            "Confirm Restart", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        task.setStatus(TaskStatus.PENDING);
        storage.saveTask(task);
        try { scheduler.cancelTask(task.getId()); scheduler.refresh(); } catch (Exception ignored) {}
        scheduler.runNow(task.getId());
        JOptionPane.showMessageDialog(this, "Task \"" + name + "\" has been restarted.");
        refresh();
    }

    // ── Log display ───────────────────────────────────────────────────────────

    // Which of the two log views (full vs latest-50) was last requested for
    // the currently selected row — so refreshSelectedTaskLogIfChanged() below
    // re-reads with the matching one instead of always comparing against the
    // full log (which would never match a "latest 50" view and just fight it
    // on every tick).
    private volatile boolean showingLatestOnly = false;

    private void showLogForSelected() {
        showingLatestOnly = false;
        ScheduledTask selected = getSelectedTask();
        if (selected == null) {
            lblSelectedTask.setText("Select a task to view its execution log");
            logArea.setText("");
            return;
        }
        String name = selected.getName();
        String id   = selected.getId();

        String lastResult = selected.getLastRunResult();
        if (lastResult != null && lastResult.contains("SKIPPED")) {
            lblSelectedTask.setText("Execution log: " + name
                + "  ⏭ Last run was skipped (no new file detected)");
            lblSelectedTask.setForeground(AppTheme.EARTH_OCHRE);
        } else {
            lblSelectedTask.setText("Execution log: " + name);
            lblSelectedTask.setForeground(UIManager.getColor("Label.foreground"));
        }

        List<String> logs = scheduler.getLogService().getTaskLogs(id, name);
        if (logs.isEmpty()) {
            StringBuilder sb = taskLogs.getOrDefault(id, new StringBuilder());
            logArea.setText(sb.length() > 0
                ? sb.toString()
                : "No log entries yet. Run the task to see output here.");
        } else {
            logArea.setText(String.join("\n", logs));
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showLatestLogsForSelected() {
        showingLatestOnly = true;
        ScheduledTask selected = getSelectedTask();
        if (selected == null) {
            lblSelectedTask.setText("Select a task to view logs");
            logArea.setText(""); return;
        }
        String name = selected.getName();
        String id   = selected.getId();
        lblSelectedTask.setText("Latest 50 lines - Execution log: " + name);
        lblSelectedTask.setForeground(UIManager.getColor("Label.foreground"));

        List<String> logs = scheduler.getLogService().getTaskLogsLastN(id, name, 50);
        if (logs.isEmpty()) {
            StringBuilder sb = taskLogs.getOrDefault(id, new StringBuilder());
            logArea.setText(sb.length() > 0 ? sb.toString()
                : "No log entries yet. Run the task to see output here.");
        } else {
            logArea.setText(String.join("\n", logs));
        }
        logArea.setCaretPosition(
            Math.min(logArea.getText().length(), logArea.getDocument().getLength()));
    }

    // ── Export / archives ─────────────────────────────────────────────────────

    private void exportLogsForSelected() {
        ScheduledTask selectedTask = getSelectedTask();
        if (selectedTask == null) {
            JOptionPane.showMessageDialog(this, "Please select a task first.",
                "No Task Selected", JOptionPane.WARNING_MESSAGE); return;
        }
        String name = selectedTask.getName();
        String id   = selectedTask.getId();

        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(name + "_logs.txt"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File selected = fc.getSelectedFile();
            try (java.io.FileWriter fw = new java.io.FileWriter(selected)) {
                for (String logLine : scheduler.getLogService().getTaskLogs(id, name))
                    fw.write(logLine + "\n");
                JOptionPane.showMessageDialog(this,
                    "Logs exported to:\n" + selected.getAbsolutePath(),
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                    "Failed to export logs: " + e.getMessage(),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void viewLogArchives() {
        ScheduledTask selectedTask = getSelectedTask();
        if (selectedTask == null) {
            JOptionPane.showMessageDialog(this, "Please select a task first.",
                "No Task Selected", JOptionPane.WARNING_MESSAGE); return;
        }
        String name = selectedTask.getName();
        String id   = selectedTask.getId();

        List<java.io.File> archives = scheduler.getLogService().getTaskLogArchives(id, name);
        if (archives.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No archived logs found for this task.",
                "No Archives", JOptionPane.INFORMATION_MESSAGE); return;
        }

        String[] archiveNames = archives.stream().map(java.io.File::getName).toArray(String[]::new);
        String selected = (String) JOptionPane.showInputDialog(this,
            "Select an archived log file to view:", "View Log Archives",
            JOptionPane.QUESTION_MESSAGE, null, archiveNames,
            archiveNames.length > 0 ? archiveNames[0] : null);

        if (selected != null) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(
                    archives.stream().filter(f -> f.getName().equals(selected))
                        .findFirst().orElseThrow().toPath());
                logArea.setText(String.join("\n", lines));
                lblSelectedTask.setText("Archive: " + selected + " - " + name);
                lblSelectedTask.setForeground(UIManager.getColor("Label.foreground"));
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                    "Failed to read archive: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Currently selected task, or {@code null} if none — the JList's selected
     *  value IS the task object directly, no row-index/taskIds lookup needed
     *  (the JTable version needed one; binding the list straight to
     *  ScheduledTask objects removes that bookkeeping entirely). */
    private ScheduledTask getSelectedTask() {
        return taskList != null ? taskList.getSelectedValue() : null;
    }

    private String getSelectedTaskId() {
        ScheduledTask t = getSelectedTask();
        return t != null ? t.getId() : null;
    }

    private void styleBtn(JButton b, Color bg) {
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
    }
}