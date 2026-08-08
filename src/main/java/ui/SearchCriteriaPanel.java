package ui;

import util.ImapSearchCriteria;
import util.ImapSearchCriteria.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UI Panel for building mail search criteria with a hybrid approach:
 * - Quick preset dropdowns for common criteria
 * - Additional criteria type selection
 * - Argument input fields
 * - Display of selected criteria as removable tags
 *
 * NOTE: the criteria strings this panel builds use IMAP RFC 3501 tokens
 * (FROM/SUBJECT/SINCE/etc.) for continuity with the existing type/value
 * builder below, but they are consumed by GraphMailService's best-effort
 * translator to a Microsoft Graph $filter — not sent as literal IMAP. There
 * used to be a free-text "raw IMAP" box here; it was removed because typing
 * arbitrary IMAP syntax that Graph doesn't understand was misleading (Graph
 * is not IMAP and doesn't support the full RFC 3501 grammar). Use the
 * builder above for anything beyond the quick presets.
 */
public class SearchCriteriaPanel extends JPanel {

    private JComboBox<SimpleCriteria> cbSimpleCriteria;
    private JComboBox<Object> cbAdvancedType;
    private JTextField tfArgument;
    private JButton btnAdd;
    private JPanel pnlCriteria;
    private List<CriterionTag> selectedCriteria = new ArrayList<>();
    private ActionListener onCriteriaChanged;

    public SearchCriteriaPanel() {
        setLayout(new BorderLayout(5, 5));
        setBorder(new EmptyBorder(5, 5, 5, 5));
        initComponents();
    }

    private void initComponents() {
        // ── Top panel: preset criteria ────────────────────────────────────────
        JPanel pnlPresets = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        pnlPresets.setBorder(BorderFactory.createTitledBorder("Quick Presets"));

        cbSimpleCriteria = new JComboBox<>(SimpleCriteria.values());
        cbSimpleCriteria.setSelectedIndex(0);
        cbSimpleCriteria.addActionListener(e -> {
            if (cbSimpleCriteria.getSelectedItem() != null) {
                addSimpleCriteria((SimpleCriteria) cbSimpleCriteria.getSelectedItem());
                cbSimpleCriteria.setSelectedIndex(0);
            }
        });

        pnlPresets.add(new JLabel("Add:"));
        pnlPresets.add(cbSimpleCriteria);

        // ── Middle panel: advanced criteria builder ───────────────────────────
        JPanel pnlBuilder = new JPanel(new BorderLayout(5, 5));
        pnlBuilder.setBorder(BorderFactory.createTitledBorder("Advanced Criteria Builder"));

        JPanel pnlBuilderTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        JLabel lblType = new JLabel("Type:");
        cbAdvancedType = new JComboBox<>();
        populateAdvancedTypeCombo();
        cbAdvancedType.addActionListener(e -> updateArgumentField());

        JLabel lblArg = new JLabel("Value:");
        tfArgument = new JTextField(15);

        btnAdd = new JButton("Add Criteria");
        btnAdd.addActionListener(e -> addAdvancedCriteria());

        pnlBuilderTop.add(lblType);
        pnlBuilderTop.add(cbAdvancedType);
        pnlBuilderTop.add(lblArg);
        pnlBuilderTop.add(tfArgument);
        pnlBuilderTop.add(btnAdd);

        pnlBuilder.add(pnlBuilderTop, BorderLayout.NORTH);

        // ── Criteria display panel ────────────────────────────────────────────
        pnlCriteria = new JPanel();
        pnlCriteria.setLayout(new FlowLayout(FlowLayout.LEFT, 3, 3));
        JScrollPane spCriteria = new JScrollPane(pnlCriteria);
        spCriteria.setBorder(BorderFactory.createTitledBorder("Selected Criteria"));
        spCriteria.setPreferredSize(new Dimension(0, 50));
        pnlBuilder.add(spCriteria, BorderLayout.CENTER);

        add(pnlPresets, BorderLayout.NORTH);
        add(pnlBuilder, BorderLayout.CENTER);
    }

    private void populateAdvancedTypeCombo() {
        cbAdvancedType.addItem("-- Select Type --");
        cbAdvancedType.addItem("── Text Search ──");
        for (TextCriteria tc : TextCriteria.values()) {
            cbAdvancedType.addItem(tc);
        }
        cbAdvancedType.addItem("── Date Search ──");
        for (DateCriteria dc : DateCriteria.values()) {
            cbAdvancedType.addItem(dc);
        }
        cbAdvancedType.addItem("── Size Search ──");
        for (SizeCriteria sc : SizeCriteria.values()) {
            cbAdvancedType.addItem(sc);
        }
        cbAdvancedType.addItem("── Advanced ──");
        for (AdvancedCriteria ac : AdvancedCriteria.values()) {
            cbAdvancedType.addItem(ac);
        }
    }

    private void updateArgumentField() {
        Object selected = cbAdvancedType.getSelectedItem();
        if (selected instanceof TextCriteria) {
            TextCriteria tc = (TextCriteria) selected;
            tfArgument.setToolTipText("Enter text value for " + tc.getDescription());
        } else if (selected instanceof DateCriteria) {
            DateCriteria dc = (DateCriteria) selected;
            tfArgument.setToolTipText("Enter date (DD-MMM-YYYY format) for " + dc.getDescription());
        } else if (selected instanceof SizeCriteria) {
            SizeCriteria sc = (SizeCriteria) selected;
            tfArgument.setToolTipText("Enter size in bytes for " + sc.getDescription());
        } else if (selected instanceof AdvancedCriteria) {
            AdvancedCriteria ac = (AdvancedCriteria) selected;
            tfArgument.setToolTipText("Enter value for " + ac.getDescription());
        }
    }

    private void addSimpleCriteria(SimpleCriteria criteria) {
        addCriteriaTag(criteria.getCriterion(), criteria.getDescription());
    }

    private void addAdvancedCriteria() {
        Object selected = cbAdvancedType.getSelectedItem();
        String value = tfArgument.getText().trim();

        if (selected instanceof TextCriteria) {
            if (value.isEmpty()) { showError("Please enter a text value"); return; }
            TextCriteria tc = (TextCriteria) selected;
            addCriteriaTag(tc.getCriterion() + " \"" + escapeQuotes(value) + "\"", tc.getDescription() + ": " + value);
        } else if (selected instanceof DateCriteria) {
            if (value.isEmpty()) { showError("Please enter a date value"); return; }
            DateCriteria dc = (DateCriteria) selected;
            addCriteriaTag(dc.getCriterion() + " " + value, dc.getDescription() + ": " + value);
        } else if (selected instanceof SizeCriteria) {
            if (value.isEmpty()) { showError("Please enter a size value"); return; }
            SizeCriteria sc = (SizeCriteria) selected;
            addCriteriaTag(sc.getCriterion() + " " + value, sc.getDescription() + ": " + value);
        } else if (selected instanceof AdvancedCriteria) {
            if (value.isEmpty()) { showError("Please enter a value"); return; }
            AdvancedCriteria ac = (AdvancedCriteria) selected;
            addCriteriaTag(ac.getCriterion() + " " + value, ac.getDescription() + ": " + value);
        } else {
            showError("Please select a criterion type");
            return;
        }

        tfArgument.setText("");
        cbAdvancedType.setSelectedIndex(0);
    }

    private void addCriteriaTag(String criterion, String display) {
        // "Selected Criteria" is the single source of truth for what's actually
        // applied. Previously, adding via Quick Presets never notified listeners
        // (only the Advanced Builder path did) and nothing stopped the same
        // criterion being added twice (e.g. picking the same preset again),
        // which let the two panels drift out of sync. Both are fixed here:
        // every successful add notifies, and duplicates are silently ignored.
        boolean alreadyPresent = selectedCriteria.stream().anyMatch(t -> t.criterion.equals(criterion));
        if (alreadyPresent) {
            return;
        }
        final CriterionTag[] tagHolder = new CriterionTag[1];
        tagHolder[0] = new CriterionTag(criterion, display, () -> {
            CriterionTag tag = tagHolder[0];
            pnlCriteria.remove(tag);
            selectedCriteria.removeIf(t -> t.criterion.equals(criterion));
            pnlCriteria.revalidate();
            pnlCriteria.repaint();
            notifyCriteriaChanged();
        });
        CriterionTag tag = tagHolder[0];
        selectedCriteria.add(tag);
        pnlCriteria.add(tag);
        pnlCriteria.revalidate();
        pnlCriteria.repaint();
        notifyCriteriaChanged();
    }

    public String getCriteria() {
        if (selectedCriteria.isEmpty()) {
            return "ALL";
        }
        StringBuilder sb = new StringBuilder();
        for (CriterionTag tag : selectedCriteria) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(tag.criterion);
        }
        return sb.toString();
    }

    /**
     * Reconstructs tags from a previously-saved criteria string. Handles the
     * common single-token case (e.g. "UNSEEN") directly, and best-effort
     * parses compound strings (e.g. {@code FROM "x" SINCE 01-Jan-2024}) built
     * by earlier saves or the Advanced Criteria Builder. Any leftover text
     * that doesn't match a known criterion is dropped — there's no raw text
     * box anymore to fall back to, so unrecognized fragments simply won't be
     * re-displayed (use the Advanced Criteria Builder above to re-add them).
     */
    public void setCriteria(String criteria) {
        clearCriteria();
        if (criteria == null || criteria.trim().isEmpty()) return;
        // "ALL" is the sentinel getCriteria() returns when nothing is
        // selected (see above) — it's a save-time placeholder for "no
        // criteria", not a real user-picked tag. Re-materializing it as a
        // visible "All messages" tag on every load was why it showed up
        // unprompted and reappeared even after being removed: removing it
        // just re-emptied the selection, which re-serializes to "ALL" on
        // the next save, which re-adds the tag on the next load. Treating a
        // bare "ALL" as empty breaks that loop.
        if (criteria.trim().equalsIgnoreCase("ALL")) return;

        String remaining = " " + criteria.trim() + " ";

        // FROM/TO/CC/BCC/SUBJECT/BODY/TEXT "value"
        Matcher tm = Pattern.compile("\\b(FROM|TO|CC|BCC|SUBJECT|BODY|TEXT)\\s+\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE).matcher(remaining);
        while (tm.find()) {
            for (TextCriteria tc : TextCriteria.values()) {
                if (tc.getCriterion().equalsIgnoreCase(tm.group(1))) {
                    addCriteriaTag(tc.getCriterion() + " \"" + tm.group(2) + "\"",
                            tc.getDescription() + ": " + tm.group(2));
                }
            }
            remaining = remaining.replace(tm.group(), " ");
        }

        // BEFORE/SINCE/ON/SENTBEFORE/SENTSINCE/SENTON <date>
        Matcher dm = Pattern.compile("\\b(BEFORE|SINCE|ON|SENTBEFORE|SENTSINCE|SENTON)\\s+(\\S+)",
                Pattern.CASE_INSENSITIVE).matcher(remaining);
        while (dm.find()) {
            for (DateCriteria dc : DateCriteria.values()) {
                if (dc.getCriterion().equalsIgnoreCase(dm.group(1))) {
                    addCriteriaTag(dc.getCriterion() + " " + dm.group(2),
                            dc.getDescription() + ": " + dm.group(2));
                }
            }
            remaining = remaining.replace(dm.group(), " ");
        }

        // LARGER/SMALLER <bytes>
        Matcher sm = Pattern.compile("\\b(LARGER|SMALLER)\\s+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(remaining);
        while (sm.find()) {
            for (SizeCriteria sc : SizeCriteria.values()) {
                if (sc.getCriterion().equalsIgnoreCase(sm.group(1))) {
                    addCriteriaTag(sc.getCriterion() + " " + sm.group(2),
                            sc.getDescription() + ": " + sm.group(2));
                }
            }
            remaining = remaining.replace(sm.group(), " ");
        }

        // Whatever single-word flags are left (UNSEEN, SEEN, ALL, FLAGGED, ...)
        for (String token : remaining.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            for (SimpleCriteria sc : SimpleCriteria.values()) {
                if (token.equalsIgnoreCase(sc.getCriterion())) {
                    addSimpleCriteria(sc);
                }
            }
        }
    }

    public void clearCriteria() {
        selectedCriteria.clear();
        pnlCriteria.removeAll();
        cbSimpleCriteria.setSelectedIndex(0);
        cbAdvancedType.setSelectedIndex(0);
        tfArgument.setText("");
        pnlCriteria.revalidate();
        pnlCriteria.repaint();
    }

    public void setOnCriteriaChanged(ActionListener listener) {
        this.onCriteriaChanged = listener;
    }

    private void notifyCriteriaChanged() {
        if (onCriteriaChanged != null) {
            onCriteriaChanged.actionPerformed(null);
        }
    }

    private String escapeQuotes(String value) {
        return value != null ? value.replace("\"", "\\\"") : "";
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Invalid Input", JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Visual tag component for displaying selected criteria
     */
    private static class CriterionTag extends JPanel {
        String criterion;

        CriterionTag(String criterion, String display, Runnable onRemove) {
            this.criterion = criterion;
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setBorder(BorderFactory.createLineBorder(new Color(0x1976D2), 1));
            setBackground(new Color(0xE3F2FD));
            setOpaque(true);

            JLabel lbl = new JLabel(display);
            lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 10f));
            lbl.setBorder(new EmptyBorder(2, 5, 2, 3));

            JButton btnRemove = new JButton("✕");
            btnRemove.setMargin(new Insets(0, 3, 0, 3));
            btnRemove.setFont(btnRemove.getFont().deriveFont(Font.PLAIN, 9f));
            btnRemove.setFocusPainted(false);
            btnRemove.addActionListener(e -> onRemove.run());

            add(lbl);
            add(btnRemove);
        }
    }
}
