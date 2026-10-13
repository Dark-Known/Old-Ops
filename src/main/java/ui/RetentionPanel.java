package ui;

import service.BackgroundJobs;
import service.CommandQueueService;
import service.MaintenanceService;
import service.PurgePolicy;
import service.PurgeResult;
import service.TaskSchedulerService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings card for log/history retention: how many days and how many megabytes each database may
 * keep, whether the scheduler purges on its own (and how often), and a "Purge now" button.
 *
 * <p>All purging runs on worker threads: normally a <b>task worker</b> of whichever process is the
 * active scheduler (the Daemon, or this app), so it appears in the workers badge and the Event
 * Monitor. Nothing here touches a database on the Swing thread.
 */
final class RetentionPanel extends JPanel {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TaskSchedulerService scheduler; // may be null
    private final JSpinner bgDays = spinner(30, 0, 3650);
    private final JSpinner bgMb = spinner(50, 0, 1_000_000);
    private final JSpinner runDays = spinner(90, 0, 3650);
    private final JSpinner runMb = spinner(500, 0, 1_000_000);
    private final JCheckBox auto = new JCheckBox("Purge automatically");
    private final JSpinner everyHours = spinner(6, 1, 720);
    private final JButton purgeNow = new JButton("Save & purge now");
    private final JLabel status = new JLabel(" ");
    private final JLabel sizes = new JLabel(" ");
    private final Timer statusTimer;

    RetentionPanel(TaskSchedulerService scheduler) {
        this.scheduler = scheduler;
        setLayout(new GridBagLayout());
        setBorder(new EmptyBorder(4, 4, 4, 4));
        setOpaque(false);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 8);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        header(row++, c);
        line(row++, c, "Background log", "Events from the app's own worker threads (separate database)", bgDays, bgMb);
        line(row++, c, "Run history", "Task runs and the Event Monitor's activity feed", runDays, runMb);

        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.insets = new Insets(10, 4, 4, 8);
        JPanel sched = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sched.setOpaque(false);
        auto.setOpaque(false);
        sched.add(auto);
        sched.add(new JLabel("every"));
        sched.add(everyHours);
        sched.add(new JLabel("hours (runs as a scheduled job on a task worker)"));
        add(sched, c);
        row++;

        c.gridy = row++; c.insets = new Insets(4, 4, 4, 8);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(purgeNow);
        add(actions, c);

        c.gridy = row++; c.insets = new Insets(2, 4, 0, 8);
        sizes.setFont(sizes.getFont().deriveFont(Font.PLAIN, 11.5f));
        add(sizes, c);
        c.gridy = row;
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11.5f));
        status.setForeground(new Color(0x888888));
        add(status, c);

        auto.addActionListener(e -> everyHours.setEnabled(auto.isSelected()));
        purgeNow.addActionListener(e -> purgeNow());
        purgeNow.setToolTipText("0 means no limit. When both limits are set, whichever is reached first removes the oldest entries.");

        statusTimer = new Timer(4000, e -> { if (isShowing()) refreshStatus(); });
        statusTimer.start();
    }

    private static JSpinner spinner(int v, int min, int max) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(v, min, max, 1));
        sp.setPreferredSize(new Dimension(96, sp.getPreferredSize().height));
        return sp;
    }

    private void header(int row, GridBagConstraints c) {
        c.gridy = row;
        c.gridx = 1; add(label("Keep at most (days, 0 = any age)", true), c);
        c.gridx = 2; add(label("Max size (MB, 0 = no cap)", true), c);
    }

    private void line(int row, GridBagConstraints c, String title, String hint, JSpinner days, JSpinner mb) {
        c.gridy = row;
        c.gridx = 0;
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 13f));
        JLabel h = label(hint, false);
        left.add(t);
        left.add(h);
        add(left, c);
        c.gridx = 1; add(days, c);
        c.gridx = 2; add(mb, c);
    }

    private static JLabel label(String text, boolean bold) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, 11f));
        l.setForeground(new Color(0x888888));
        return l;
    }

    // ── Load / save ──────────────────────────────────────────────────────────

    void load() {
        PurgePolicy bg = PurgePolicy.backgroundLog(), run = PurgePolicy.runHistory();
        bgDays.setValue(bg.keepDays());
        bgMb.setValue((int) bg.maxMb());
        runDays.setValue(run.keepDays());
        runMb.setValue((int) run.maxMb());
        auto.setSelected(PurgePolicy.scheduledEnabled());
        everyHours.setValue(PurgePolicy.intervalHours());
        everyHours.setEnabled(auto.isSelected());
        refreshStatus();
    }

    /** The settings keys this panel owns, for the Settings panel's single Save. */
    Map<String, String> values() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(PurgePolicy.KEY_BG_DAYS, String.valueOf(bgDays.getValue()));
        m.put(PurgePolicy.KEY_BG_MB, String.valueOf(bgMb.getValue()));
        m.put(PurgePolicy.KEY_RUN_DAYS, String.valueOf(runDays.getValue()));
        m.put(PurgePolicy.KEY_RUN_MB, String.valueOf(runMb.getValue()));
        m.put(PurgePolicy.KEY_SCHEDULED, String.valueOf(auto.isSelected()));
        m.put(PurgePolicy.KEY_INTERVAL_H, String.valueOf(everyHours.getValue()));
        return m;
    }

    // ── Purge now ────────────────────────────────────────────────────────────

    private void purgeNow() {
        if (scheduler == null) { setStatus("Purging needs the scheduler, which isn't available here.", true); return; }
        purgeNow.setEnabled(false);
        setStatus("Saving retention settings and queuing the purge…", false);
        Map<String, String> values = values();
        Async.call("Queue purge", () -> {
            // The purge reads the saved settings, so persist what is on screen first.
            if (!util.AppSettings.setAll(values)) return "Could not save the retention settings (database not connected).";
            if (scheduler.requestPurge("manual")) {
                return "Queued — a task worker in this app will run it (see Event Monitor → Background).";
            }
            java.io.File dataDir = scheduler.getStorage().getDataDir();
            AppFeeds.Daemon d = AppFeeds.daemon(dataDir.toPath().resolve("scheduler-status-daemon.dat")).latest();
            if (d != null && d.alive()) {
                boolean ok = CommandQueueService.enqueue(dataDir, MaintenanceService.JOB_PURGE,
                        CommandQueueService.Action.PURGE_NOW, "gui");
                return ok ? "Sent to the Daemon — one of its task workers will run it (see Event Monitor → Background)."
                          : "Could not send the request to the Daemon.";
            }
            // No scheduler is running anywhere: do it on a background thread rather than not at all.
            java.util.List<PurgeResult> rs = scheduler.getMaintenance().runPurge("manual, service not running", () -> false);
            StringBuilder sb = new StringBuilder("The background service isn't running, so this was done on a background thread: ");
            for (PurgeResult r : rs) sb.append(r.summary()).append("  ");
            return sb.toString().trim();
        }, msg -> { purgeNow.setEnabled(true); setStatus(msg, false); refreshStatus(); },
           err -> { purgeNow.setEnabled(true); setStatus("Purge failed: " + err.getMessage(), true); });
    }

    // ── Status line ──────────────────────────────────────────────────────────

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.setForeground(error ? new Color(0xC62828) : new Color(0x888888));
    }

    void refreshStatus() {
        if (scheduler == null) return;
        Async.latest(this, () -> {
            long bgSize = scheduler.getBackgroundLogService().sizeBytes();
            long bgRows = scheduler.getBackgroundLogService().rowCount();
            long runSize = scheduler.getRunHistoryService().sizeBytes();
            LocalDateTime last = scheduler.getMaintenance().lastPurgeAt();
            String sum = scheduler.getMaintenance().lastPurgeSummary();
            return new Object[]{ bgSize, bgRows, runSize, last, sum };
        }, r -> {
            sizes.setText("Now: background log " + PurgeResult.human((Long) r[0]) + " (" + r[1] + " entries)  ·  run history "
                    + PurgeResult.human((Long) r[2]) + "  ·  last purge "
                    + (r[3] == null ? "never" : ((LocalDateTime) r[3]).format(WHEN)));
            sizes.setToolTipText(r[4] == null ? null : (String) r[4]);
        }, e -> { });
    }

    void dispose() { statusTimer.stop(); }
}
