package ui;

import model.FileSelection;
import model.FileSelection.*;
import service.TransferLedger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The "File Selection" section of the task dialog: lets the operator say which files of the source
 * folder a transfer takes — by name (ends with / starts with / contains / exactly / wildcard / regex /
 * extension list, each as include or exclude), by size and age, and which of the matches to send
 * (all, or the newest / oldest / largest / smallest N). Edits a {@link FileSelection}; the dialog
 * reads it back with {@link #getSelection()} when saving.
 *
 * <p>A one-line summary and a "try a file name" box show what the conditions mean without having to
 * run the task.
 */
public class FileSelectionPanel extends JPanel {

    private static final String[] SIZE_UNITS = {"bytes", "KB", "MB", "GB"};
    private static final long[] SIZE_MULT = {1L, 1L << 10, 1L << 20, 1L << 30};
    private static final String[] AGE_UNITS = {"minutes", "hours", "days"};
    private static final long[] AGE_MIN = {1L, 60L, 1440L};

    private final JPanel rulesBox = new JPanel();
    private final List<RuleRow> rows = new ArrayList<>();
    private final JComboBox<MatchMode> cbMatch = new JComboBox<>(MatchMode.values());

    private final JSpinner spMinSize = spinner(0, 1_000_000);
    private final JComboBox<String> cbMinUnit = new JComboBox<>(SIZE_UNITS);
    private final JSpinner spMaxSize = spinner(0, 1_000_000);
    private final JComboBox<String> cbMaxUnit = new JComboBox<>(SIZE_UNITS);
    private final JSpinner spWithin = spinner(0, 1_000_000);
    private final JComboBox<String> cbWithinUnit = new JComboBox<>(AGE_UNITS);
    private final JSpinner spSettled = spinner(0, 86_400);
    private final JCheckBox cbToday = new JCheckBox("Only files changed today");
    private final JCheckBox cbSkipTemp = new JCheckBox("Ignore hidden / temporary files  (.tmp  .part  ~$…  .hidden)");

    private final JComboBox<Pick> cbPick = new JComboBox<>(Pick.values());
    private final JSpinner spCount = spinner(1, 100_000);
    private final JCheckBox cbTies = new JCheckBox("also those tied with the last one");
    private final JCheckBox cbFail = new JCheckBox("Treat \"no file matched\" as a failure  (default: the run is just skipped)");

    private final JComboBox<SentPolicy> cbSent = new JComboBox<>(SentPolicy.values());
    private final JLabel lblLedger = new JLabel(" ");
    private final JButton btnForget = new JButton("Forget sent files");
    private final JComboBox<AfterAction> cbAfter = new JComboBox<>(AfterAction.values());
    private final JTextField tfAfterFolder = new JTextField(24);
    private final JButton btnAfterBrowse = new JButton("Browse\u2026");
    private final JLabel lblAfterNote = new JLabel(" ");
    private final JCheckBox cbAlert = new JCheckBox("Alert me when a run finds no files");
    private final JCheckBox cbSilence = new JCheckBox("Alert me if no new file arrives for");
    private final JSpinner spSilence = spinner(10, 100_000);
    private final JSpinner spAlertEvery = spinner(60, 100_000);
    private final JLabel lblAlertNote = new JLabel(" ");
    private final JLabel lblAfterFolder = new JLabel("Move to folder");
    private final JTextField tfTest = new JTextField(24);
    private final JLabel lblTest = new JLabel(" ");
    private final JLabel lblSummary = new JLabel(" ");
    private final JLabel lblModeNote = new JLabel(
            "<html><body style='width:420px'>Not used in SPECIFIC_FILE mode — that mode transfers the one named file.</body></html>");
    private final JButton btnAdd = new JButton("+ Add condition");
    private final JLabel lblWatcherNote = new JLabel(
            "<html><body style='width:420px'>The watcher is on: it sends every new matching file and remembers what it sent, "
                    + "so \"Send newest N\", \"Files sent before\" and the per-run no-files alert are not used (\"no new file for N minutes\" still works).</body></html>");
    /**
     * Starts a titled group; everything added with {@link #put} afterwards goes into it.
     */
    private final java.util.Map<String, JPanel> sections = new java.util.HashMap<>();
    private JPanel sink;
    private int outerY;
    private Runnable remoteBrowser;
    private String taskId;
    private java.io.File dataDir;
    private boolean inbound;
    private boolean modeAllows = true;
    private boolean watcherActive;
    private boolean loading;
    private JComponent introNote, pickRowPanel;

    // ── Public API ───────────────────────────────────────────────────

    public FileSelectionPanel(FileSelection initial) {
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                BorderFactory.createCompoundBorder(
                        new TitledBorder("File Selection  (optional — which files to take)"),
                        new EmptyBorder(8, 10, 10, 10))));
        setBackground(UIManager.getColor("Panel.background"));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        rulesBox.setLayout(new BoxLayout(rulesBox, BoxLayout.Y_AXIS));
        rulesBox.setOpaque(false);

        sink = this;
        lblModeNote.setFont(lblModeNote.getFont().deriveFont(Font.BOLD, 11f));
        lblModeNote.setVisible(false);
        put(lblModeNote, gbc(0, outerY++, 3, 1.0));
        lblWatcherNote.setFont(lblWatcherNote.getFont().deriveFont(Font.BOLD, 11f));
        lblWatcherNote.setVisible(false);
        put(lblWatcherNote, gbc(0, outerY++, 3, 1.0));

        int y = 0;
        section("Which files to take");
        introNote = note("<html><body style='width:420px'>Leave empty to use the Transfer Mode alone. Conditions look at the <b>file name</b> only "
                + "(not the folder). Text may contain <code>{today}</code>, <code>{yesterday}</code>, "
                + "<code>{tomorrow}</code> or e.g. <code>{today:yyyyMMdd}</code> — replaced by that date on every run. "
                + "A wildcard accepts several patterns separated by <code>;</code>, e.g. <code>*.csv;report_??.txt</code>.</body></html>");
        put(introNote, gbc(0, y++, 3, 1.0));

        put(rulesBox, gbc(0, y++, 3, 1.0));

        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addRow.setOpaque(false);
        addRow.add(btnAdd);
        addRow.add(new JLabel("   A file is taken when it satisfies"));
        cbMatch.setToolTipText("With ALL, every include condition must match. With ANY, one is enough. "
                + "An exclude condition that matches always rejects the file.");
        addRow.add(cbMatch);
        put(addRow, gbc(0, y++, 3, 1.0));

        put(Box.createVerticalStrut(6), gbc(0, y++, 3, 0));

        y = optionRow(y, "Size at least", spMinSize, cbMinUnit, "0 = no minimum. Use 1 byte to skip empty files.");
        y = optionRow(y, "Size at most", spMaxSize, cbMaxUnit, "0 = no maximum.");
        y = optionRow(y, "Only files changed in the last", spWithin, cbWithinUnit, "0 = any age.");
        y = optionRow(y, "Skip files changed in the last", spSettled, new JLabel("seconds"),
                "0 = off. A file changed a moment ago may still be being written; this waits until it has been quiet for that long.");
        put(cbToday, gbc(0, y++, 3, 1.0));
        put(cbSkipTemp, gbc(0, y++, 3, 1.0));

        put(Box.createVerticalStrut(6), gbc(0, y++, 3, 0));

        JPanel pickRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pickRow.setOpaque(false);
        pickRow.add(new JLabel("Send"));
        cbPick.setToolTipText("<html>As the Transfer Mode says: ENTIRE_FOLDER sends every matching file; LATEST_ONLY sends the<br>"
                + "newest matching file (and any others with exactly the same timestamp).<br>"
                + "With the watcher on, every new matching file is sent and this choice is not used.</html>");
        pickRow.add(cbPick);
        pickRow.add(spCount);
        pickRow.add(cbTies);
        pickRowPanel = pickRow;
        put(pickRow, gbc(0, y++, 3, 1.0));
        put(cbFail, gbc(0, y++, 3, 1.0));

        put(Box.createVerticalStrut(8), gbc(0, y++, 3, 0));

        // ── Files sent before ────────────────────────────────────────
        y = 0;
        section("Files sent before");
        JPanel sentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sentRow.setOpaque(false);
        sentRow.add(new JLabel("Skip"));
        cbSent.setToolTipText("<html>Send again every run: the old behaviour.<br>"
                + "Skip files already sent (unchanged): a file counts as sent when its name, size and last-changed time are all the same;<br>"
                + "&nbsp;&nbsp;an edited file is sent again.<br>"
                + "Skip any file name already sent: a name is sent only once, ever.<br>"
                + "Files are remembered only after the whole transfer succeeded, and forgotten after "
                + TransferLedger.RETENTION_DAYS + " days.</html>");
        sentRow.add(cbSent);
        btnForget.setToolTipText("Clears the list of files this task remembers as sent, so they can be sent again.");
        sentRow.add(btnForget);
        put(sentRow, gbc(0, y++, 3, 1.0));
        lblLedger.setFont(lblLedger.getFont().deriveFont(Font.PLAIN, 11f));
        put(lblLedger, gbc(0, y++, 3, 1.0));

        // ── After a successful transfer ──────────────────────────────
        y = 0;
        section("After a successful transfer");
        JPanel afterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        afterRow.setOpaque(false);
        afterRow.add(new JLabel("What to do with the originals"));
        afterRow.add(cbAfter);
        put(afterRow, gbc(0, y++, 3, 1.0));
        JPanel folderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        folderRow.setOpaque(false);
        folderRow.add(lblAfterFolder);
        folderRow.add(tfAfterFolder);
        folderRow.add(btnAfterBrowse);
        put(folderRow, gbc(0, y++, 3, 1.0));
        lblAfterNote.setFont(lblAfterNote.getFont().deriveFont(Font.PLAIN, 11f));
        put(lblAfterNote, gbc(0, y++, 3, 1.0));

        put(Box.createVerticalStrut(8), gbc(0, y++, 3, 0));

        // ── Alerts ───────────────────────────────────────────────────
        y = 0;
        section("Alerts");
        put(cbAlert, gbc(0, y++, 3, 1.0));
        JPanel silenceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        silenceRow.setOpaque(false);
        silenceRow.add(cbSilence);
        silenceRow.add(spSilence);
        silenceRow.add(new JLabel("minutes"));
        cbSilence.setToolTipText("<html>For tasks that get files regularly (say every 2 minutes): if nothing arrives for this long,<br>"
                + "an alert is raised \u2014 also for watcher tasks. Time while this program is not running does not count.</html>");
        put(silenceRow, gbc(0, y++, 3, 1.0));
        JPanel alertRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        alertRow.setOpaque(false);
        alertRow.add(new JLabel("Do not repeat the alert for"));
        alertRow.add(spAlertEvery);
        alertRow.add(new JLabel("minutes  (0 = alert on every run)"));
        put(alertRow, gbc(0, y++, 3, 1.0));
        lblAlertNote.setFont(lblAlertNote.getFont().deriveFont(Font.PLAIN, 11f));
        put(lblAlertNote, gbc(0, y++, 3, 1.0));

        y = 0;
        section("Check your conditions");
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        testRow.setOpaque(false);
        testRow.add(new JLabel("Try a file name:"));
        tfTest.setToolTipText("Type a file name to see whether the name conditions above would take it.");
        testRow.add(tfTest);
        put(testRow, gbc(0, y++, 3, 1.0));
        lblTest.setFont(lblTest.getFont().deriveFont(Font.PLAIN, 11f));
        put(lblTest, gbc(0, y++, 3, 1.0));

        lblSummary.setFont(lblSummary.getFont().deriveFont(Font.ITALIC, 11f));
        lblSummary.setForeground(UIManager.getColor("Label.disabledForeground"));
        put(lblSummary, gbc(0, y++, 3, 1.0));


        btnForget.addActionListener(e -> forgetSentFiles());
        btnAfterBrowse.addActionListener(e -> browseAfterFolder());
        cbAlert.addActionListener(e -> ch0());
        cbSilence.addActionListener(e -> ch0());
        spSilence.addChangeListener(e -> ch0());
        spAlertEvery.addChangeListener(e -> ch0());
        cbSent.addActionListener(e -> ch0());
        cbAfter.addActionListener(e -> ch0());
        tfAfterFolder.getDocument().addDocumentListener(docListener(this::ch0));
        btnAdd.addActionListener(e -> {
            addRow(new Rule(false, Op.ENDS_WITH, "", true));
            changed();
        });
        Runnable ch = this::changed;
        for (JSpinner s : new JSpinner[]{spMinSize, spMaxSize, spWithin, spSettled, spCount})
            s.addChangeListener(e -> ch.run());
        for (JComboBox<?> c : new JComboBox<?>[]{cbMatch, cbMinUnit, cbMaxUnit, cbWithinUnit, cbPick})
            c.addActionListener(e -> ch.run());
        for (JCheckBox c : new JCheckBox[]{cbToday, cbSkipTemp, cbTies, cbFail}) c.addActionListener(e -> ch.run());
        tfTest.getDocument().addDocumentListener(docListener(ch));

        setSelection(initial == null ? new FileSelection() : initial);
    }

    private static void setSized(JSpinner sp, JComboBox<String> unit, long bytes) {
        int u = 0;
        for (int i = SIZE_MULT.length - 1; i > 0; i--)
            if (bytes > 0 && bytes % SIZE_MULT[i] == 0) {
                u = i;
                break;
            }
        unit.setSelectedIndex(u);
        sp.setValue(bytes / SIZE_MULT[u]);
    }

    private static void setAged(JSpinner sp, JComboBox<String> unit, long minutes) {
        int u = 0;
        for (int i = AGE_MIN.length - 1; i > 0; i--)
            if (minutes > 0 && minutes % AGE_MIN[i] == 0) {
                u = i;
                break;
            }
        unit.setSelectedIndex(u);
        sp.setValue(minutes / AGE_MIN[u]);
    }

    private static JSpinner spinner(long value, long max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, 0L, max, 1L));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(7);
        return s;
    }

    /** SPECIFIC_FILE transfers one named file, so these options do not apply there. */

    private static long num(JSpinner s) {
        return ((Number) s.getValue()).longValue();
    }

    private static JLabel note(String html) {
        JLabel l = new JLabel(html);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        return l;
    }

    // ── Loading ──────────────────────────────────────────────────────

    private static GridBagConstraints gbc(int x, int y, int w, double weightx) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = w;
        c.weightx = weightx;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 2, 2, 2);
        return c;
    }

    private static DocumentListener docListener(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        };
    }

    private static void setEnabledDeep(Component c, boolean on) {
        c.setEnabled(on);
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) setEnabledDeep(k, on);
    }

    // ── Condition rows ───────────────────────────────────────────────

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void ch0() {
        changed();
    }

    /**
     * For an existing task: which task's sent-files memory this is, and where it lives (enables "Forget sent files").
     */
    public void setTaskContext(String taskId, java.io.File dataDir) {
        this.taskId = taskId;
        this.dataDir = dataDir;
        refreshLedgerLabel();
        updateEnabledState();
    }

    // ── Live feedback ────────────────────────────────────────────────

    /**
     * INBOUND downloads from the server (originals are on the server); OUTBOUND uploads from this PC.
     */
    public void setInbound(boolean inbound) {
        this.inbound = inbound;
        updateEnabledState();
        changed();
    }

    /**
     * The conditions as currently entered. Condition rows left blank are ignored.
     */
    public FileSelection getSelection() {
        FileSelection s = new FileSelection().setMatchMode((MatchMode) cbMatch.getSelectedItem());
        for (RuleRow r : rows) {
            Rule rule = r.toRule();
            if (!rule.value.trim().isEmpty()) s.addRule(rule);
        }
        s.setMinSizeBytes(num(spMinSize) * SIZE_MULT[cbMinUnit.getSelectedIndex()]);
        s.setMaxSizeBytes(num(spMaxSize) * SIZE_MULT[cbMaxUnit.getSelectedIndex()]);
        s.setModifiedWithinMinutes(num(spWithin) * AGE_MIN[cbWithinUnit.getSelectedIndex()]);
        s.setSettledSeconds(num(spSettled));
        s.setModifiedToday(cbToday.isSelected());
        s.setSkipTempFiles(cbSkipTemp.isSelected());
        s.setPick((Pick) cbPick.getSelectedItem());
        s.setPickCount((int) Math.max(1, num(spCount)));
        s.setKeepTies(cbTies.isSelected());
        s.setFailIfNoMatch(cbFail.isSelected());
        s.setSilenceMinutes(cbSilence.isSelected() ? (int) Math.max(1, num(spSilence)) : 0);
        s.setAlertNoFiles(cbAlert.isSelected());
        s.setAlertRepeatMinutes((int) Math.max(0, num(spAlertEvery)));
        s.setSentPolicy((SentPolicy) cbSent.getSelectedItem());
        s.setAfterAction((AfterAction) cbAfter.getSelectedItem());
        s.setAfterFolder(tfAfterFolder.getText());
        return s;
    }

    private void setSelection(FileSelection s) {
        loading = true;
        try {
            rulesBox.removeAll();
            rows.clear();
            for (Rule r : s.getRules()) addRow(r);
            cbMatch.setSelectedItem(s.getMatchMode());
            setSized(spMinSize, cbMinUnit, s.getMinSizeBytes());
            setSized(spMaxSize, cbMaxUnit, s.getMaxSizeBytes());
            setAged(spWithin, cbWithinUnit, s.getModifiedWithinMinutes());
            spSettled.setValue(s.getSettledSeconds());
            cbToday.setSelected(s.isModifiedToday());
            cbSkipTemp.setSelected(s.isSkipTempFiles());
            cbPick.setSelectedItem(s.getPick());
            spCount.setValue(s.getPickCount());
            cbTies.setSelected(s.isKeepTies());
            cbFail.setSelected(s.isFailIfNoMatch());
            cbSilence.setSelected(s.getSilenceMinutes() > 0);
            spSilence.setValue((long) (s.getSilenceMinutes() > 0 ? s.getSilenceMinutes() : 10));
            cbAlert.setSelected(s.isAlertNoFiles());
            spAlertEvery.setValue((long) s.getAlertRepeatMinutes());
            cbSent.setSelectedItem(s.getSentPolicy());
            cbAfter.setSelectedItem(s.getAfterAction());
            tfAfterFolder.setText(s.getAfterFolder());
        } finally {
            loading = false;
        }
        changed();
    }

    /**
     * Problems that would stop the entered conditions from working, in plain words; empty when fine.
     */
    public List<String> validateInput() {
        return modeAllows ? getSelection().validate() : new ArrayList<>();
    }

    /**
     * The watcher sends every new matching file itself, so "Send newest N…" and "Files sent before" do not apply.
     */
    public void setWatcherActive(boolean active) {
        this.watcherActive = active;
        updateEnabledState();
    }

    public void setTransferMode(String mode) {
        modeAllows = !"SPECIFIC_FILE".equals(mode);
        setEnabledDeep(this, modeAllows);
        lblModeNote.setVisible(!modeAllows);
        lblModeNote.setEnabled(true);
        if (modeAllows) updateEnabledState();
    }

    private void addRow(Rule r) {
        RuleRow row = new RuleRow(r);
        rows.add(row);
        rulesBox.add(row);
        rulesBox.revalidate();
        rulesBox.repaint();
    }

    // ── Small helpers ────────────────────────────────────────────────

    private void removeRow(RuleRow row) {
        rows.remove(row);
        rulesBox.remove(row);
        rulesBox.revalidate();
        rulesBox.repaint();
        changed();
    }

    private void changed() {
        if (loading) return;
        updateEnabledState();
        FileSelection s = getSelection();
        List<String> problems = s.validate();
        if (!problems.isEmpty()) {
            lblSummary.setForeground(new Color(0xB00020));
            lblSummary.setText("<html><body style='width:420px'>" + escape(String.join(" ", problems)) + "</body></html>");
        } else {
            lblSummary.setForeground(UIManager.getColor("Label.disabledForeground"));
            lblSummary.setText("<html><body style='width:420px'>Summary: " + escape(s.describe()) + "</body></html>");
        }
        updateTester(s);
    }

    private void updateEnabledState() {
        if (!modeAllows) return;
        Pick p = (Pick) cbPick.getSelectedItem();
        boolean n = p != Pick.BY_MODE && p != Pick.ALL;
        spCount.setEnabled(n && !watcherActive);
        cbTies.setEnabled(n && !watcherActive);
        cbPick.setEnabled(!watcherActive);
        cbSent.setEnabled(!watcherActive);
        lblWatcherNote.setVisible(watcherActive);
        cbAlert.setEnabled(!watcherActive);
        spSilence.setEnabled(cbSilence.isSelected());
        spAlertEvery.setEnabled((cbAlert.isSelected() && !watcherActive) || cbSilence.isSelected());
        String looked = inbound ? "on the server" : "in this PC's folder";
        lblAlertNote.setText("<html><body style='width:420px'>When a scheduled or manual run looks " + looked
                + " and finds nothing to " + (inbound ? "receive" : "send") + " (empty, nothing matches the conditions, or "
                + "everything was already sent), an alert appears in the Events list and as a pop-up, saying where it looked and why. "
                + "Not used with the watcher: there, \"nothing changed\" is normal \u2014 use the \"no new file for N minutes\" alert instead, "
                + "which also works for watcher tasks.</body></html>");
        boolean move = cbAfter.getSelectedItem() == AfterAction.MOVE;
        tfAfterFolder.setEnabled(move);
        btnAfterBrowse.setEnabled(move && (!inbound || remoteBrowser != null));
        lblAfterFolder.setText(inbound ? "Folder on the server" : "Folder on this PC");
        btnForget.setEnabled(taskId != null && dataDir != null);
        String where = inbound ? "the file on the server" : "the file on this PC";
        AfterAction a = (AfterAction) cbAfter.getSelectedItem();
        String text;
        if (a == AfterAction.DELETE) text = "Deletes " + where + " once the whole transfer has succeeded.";
        else if (a == AfterAction.MOVE)
            text = "Moves " + where + " into that folder (created if missing; an archived file is never overwritten \u2014 a timestamp is added to the name instead).";
        else text = "The original (" + where + ") stays where it is.";
        if (a != AfterAction.KEEP) {
            text += " A failed run touches nothing; if the cleanup itself fails the transfer still counts as done and the log says the originals are still there.";
            if (inbound) text += " Only files whose copy on this PC exists at the right size are touched.";
        }
        lblAfterNote.setText("<html><body style='width:420px'>" + escape(text) + "</body></html>");
    }

    private void refreshLedgerLabel() {
        if (taskId == null || dataDir == null) {
            setLedgerText("Nothing is remembered yet \u2014 this is a new task.");
            return;
        }
        final String id = taskId;
        final java.io.File dir = dataDir;
        setLedgerText("Counting remembered files\u2026");
        // Opening the SQLite file can take a moment: keep it off the dialog's own thread.
        Async.latest("Sent files count", () -> {
            try {
                return new TransferLedger(dir).count(id) + " file(s) remembered for this task.";
            } catch (Exception e) {
                return "Could not read the sent-files memory: " + e.getMessage();
            }
        }, this::setLedgerText, e -> setLedgerText("Could not read the sent-files memory: " + e.getMessage()));
    }

    private void setLedgerText(String t) {
        lblLedger.setText("<html><body style='width:420px'>" + escape(t) + "</body></html>");
    }

    private void forgetSentFiles() {
        if (taskId == null || dataDir == null) return;
        int n;
        try {
            n = new TransferLedger(dataDir).count(taskId);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not read the sent-files memory:\n" + e.getMessage(),
                    "Forget sent files", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                n + " file(s) are remembered as sent by this task.\n\nForget them? Files that match will be sent again on the next run.",
                "Forget sent files", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        try {
            int removed = new TransferLedger(dataDir).clear(taskId);
            JOptionPane.showMessageDialog(this, "Forgot " + removed + " file(s).", "Forget sent files", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not clear the sent-files memory:\n" + e.getMessage(),
                    "Forget sent files", JOptionPane.ERROR_MESSAGE);
        }
        refreshLedgerLabel();
    }

    private void browseAfterFolder() {
        if (inbound && remoteBrowser != null) {
            remoteBrowser.run();
            return;
        }
        JFileChooser fc = new JFileChooser(tfAfterFolder.getText().trim().isEmpty() ? null : new java.io.File(tfAfterFolder.getText().trim()));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder for the originals after a successful transfer");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            tfAfterFolder.setText(fc.getSelectedFile().getAbsolutePath());
    }

    /**
     * Says, condition by condition, whether a typed file name would be taken (name conditions only).
     */
    private void updateTester(FileSelection full) {
        String name = tfTest.getText().trim();
        if (name.isEmpty()) {
            lblTest.setText(" ");
            return;
        }
        FileSelection probe = new FileSelection().setMatchMode(full.getMatchMode()).setSkipTempFiles(full.isSkipTempFiles());
        StringBuilder detail = new StringBuilder();
        int i = 0;
        for (Rule r : full.getRules()) {
            probe.addRule(r);
            i++;
            boolean hit = new FileSelection().addRule(new Rule(false, r.op, r.value, r.ignoreCase))
                    .filter().test(name, 0, 0);
            detail.append("  #").append(i).append(r.exclude ? " (exclude) " : " (include) ").append(hit ? "matches" : "no match");
        }
        boolean taken = probe.filter().test(name, 0, 0);
        boolean sizeOrAge = full.getMinSizeBytes() > 0 || full.getMaxSizeBytes() > 0 || full.getModifiedWithinMinutes() > 0
                || full.getSettledSeconds() > 0 || full.isModifiedToday();
        lblTest.setForeground(taken ? new Color(0x1B7F3B) : new Color(0xB00020));
        lblTest.setText("<html><body style='width:420px'><b>" + (taken ? "Name accepted" : "Name rejected") + "</b>"
                + escape(detail.toString())
                + (sizeOrAge ? " &nbsp;<i>(size/age conditions not checked here)</i>" : "") + "</body></html>");
    }

    /**
     * Adds to the section being built (or to the panel itself before the first section).
     */
    private void put(Component c, GridBagConstraints g) {
        sink.add(c, g);
    }

    private void section(String title) {
        JPanel sec = new JPanel(new GridBagLayout());
        sections.put(title, sec);
        sec.setOpaque(false);
        sec.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                new EmptyBorder(2, 8, 8, 8)));
        GridBagConstraints g = gbc(0, outerY++, 3, 1.0);
        g.fill = GridBagConstraints.HORIZONTAL;
        super.add(sec, g);
        sink = sec;
    }

    /**
     * "Watch a folder" tasks transfer nothing: only the name conditions, the settle time and the "no new file" alert apply, so the
     * pick, "files sent before" and "after a successful transfer" settings are hidden.
     */
    public void setWatchMode(boolean watch) {
        for (String t : new String[]{"Files sent before", "After a successful transfer"}) {
            JPanel sec = sections.get(t);
            if (sec != null) sec.setVisible(!watch);
        }
        if (introNote != null) introNote.setVisible(!watch);
        if (pickRowPanel != null) pickRowPanel.setVisible(!watch);
        cbFail.setVisible(!watch);
        cbAlert.setVisible(!watch);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                BorderFactory.createCompoundBorder(
                        new TitledBorder(watch ? "Which files to watch  (optional \u2014 leave empty to watch every file)"
                                : "File Selection  (optional \u2014 which files to take)"),
                        new EmptyBorder(8, 10, 10, 10))));
        revalidate();
        repaint();
    }

    /**
     * INBOUND: lets the dialog open its server-folder browser for the "move to" folder.
     */
    public void setRemoteBrowser(Runnable browser) {
        this.remoteBrowser = browser;
    }

    /**
     * The "move to" folder field, so a browser can fill it in.
     */
    public JTextField afterFolderField() {
        return tfAfterFolder;
    }

    private int optionRow(int y, String label, JSpinner sp, JComponent unit, String tip) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        r.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setToolTipText(tip);
        sp.setToolTipText(tip);
        r.add(l);
        r.add(sp);
        r.add(unit);
        put(r, gbc(0, y, 3, 1.0));
        return y + 1;
    }

    private final class RuleRow extends JPanel {
        private final JComboBox<String> kind = new JComboBox<>(new String[]{"Include", "Exclude"});
        private final JComboBox<Op> op = new JComboBox<>(Op.values());
        private final JTextField text = new JTextField(12);
        private final JCheckBox ignoreCase = new JCheckBox("ignore case");
        private final JButton remove = new JButton("−");

        RuleRow(Rule r) {
            super(new FlowLayout(FlowLayout.LEFT, 6, 2));
            setOpaque(false);
            kind.setPrototypeDisplayValue("Exclude");
            kind.setSelectedIndex(r.exclude ? 1 : 0);
            op.setSelectedItem(r.op);
            text.setText(r.value);
            ignoreCase.setSelected(r.ignoreCase);
            text.setToolTipText("<html>Examples: <code>.csv</code> &nbsp; <code>LDM_</code> &nbsp; <code>*.txt;*.log</code> &nbsp; "
                    + "<code>^\\d{8}</code> &nbsp; <code>{today}</code><br>"
                    + "wildcard: <code>*</code> any text, <code>?</code> one character, <code>;</code> between several patterns<br>"
                    + "extension is one of: <code>csv, txt</code></html>");
            remove.setMargin(new Insets(0, 6, 0, 6));
            remove.setToolTipText("Remove this condition");
            remove.addActionListener(e -> removeRow(this));
            kind.addActionListener(e -> changed());
            op.addActionListener(e -> changed());
            ignoreCase.addActionListener(e -> changed());
            text.getDocument().addDocumentListener(docListener(FileSelectionPanel.this::changed));
            add(kind);
            add(new JLabel("name"));
            add(op);
            add(text);
            add(ignoreCase);
            add(remove);
        }

        Rule toRule() {
            return new Rule(kind.getSelectedIndex() == 1, (Op) op.getSelectedItem(), text.getText().trim(), ignoreCase.isSelected());
        }
    }
}
