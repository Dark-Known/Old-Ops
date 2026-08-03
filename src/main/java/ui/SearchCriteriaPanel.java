package ui;

import util.ImapSearchCriteria;
import util.ImapSearchCriteria.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;

/**
 * UI Panel for building IMAP search criteria with a hybrid approach:
 * - Quick preset dropdowns for common criteria
 * - Additional criteria type selection
 * - Argument input fields
 * - Display of selected criteria as removable tags
 * - Advanced text field for custom criteria
 */
public class SearchCriteriaPanel extends JPanel {

    private JComboBox<SimpleCriteria> cbSimpleCriteria;
    private JComboBox<Object> cbAdvancedType;
    private JTextField tfArgument;
    private JButton btnAdd;
    private JPanel pnlCriteria;
    private JTextArea taCustom;
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

        // ── Custom criteria field ─────────────────────────────────────────────
        JPanel pnlCustom = new JPanel(new BorderLayout(5, 5));
        pnlCustom.setBorder(BorderFactory.createTitledBorder("Custom/Advanced (Raw IMAP)"));
        taCustom = new JTextArea(3, 40);
        taCustom.setLineWrap(true);
        taCustom.setWrapStyleWord(true);
        taCustom.setFont(new Font("Monospaced", Font.PLAIN, 11));
        taCustom.setBorder(new EmptyBorder(3, 3, 3, 3));
        JScrollPane spCustom = new JScrollPane(taCustom);
        pnlCustom.add(new JLabel("<html><i>Enter raw IMAP criteria or leave blank to use selected criteria above</i></html>"), BorderLayout.NORTH);
        pnlCustom.add(spCustom, BorderLayout.CENTER);

        add(pnlPresets, BorderLayout.NORTH);
        
        JPanel center = new JPanel(new BorderLayout(5, 5));
        center.add(pnlBuilder, BorderLayout.NORTH);
        center.add(pnlCustom, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
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
        notifyCriteriaChanged();
    }

    private void addCriteriaTag(String criterion, String display) {
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
    }

    public String getCriteria() {
        String customCriteria = taCustom.getText().trim();
        if (!customCriteria.isEmpty()) {
            return customCriteria;
        }

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

    public void setCriteria(String criteria) {
        if (criteria == null || criteria.trim().isEmpty() || "UNSEEN".equals(criteria)) {
            clearCriteria();
            return;
        }

        // Try to parse standard criteria; if it's complex, put in custom field
        if (isComplexCriteria(criteria)) {
            taCustom.setText(criteria);
        } else {
            // Try to parse as simple criteria
            clearCriteria();
            for (SimpleCriteria sc : SimpleCriteria.values()) {
                if (criteria.equals(sc.getCriterion())) {
                    addSimpleCriteria(sc);
                    return;
                }
            }
            // If not recognized, put in custom field
            taCustom.setText(criteria);
        }
    }

    private boolean isComplexCriteria(String criteria) {
        // Check if criteria contains multiple keywords or special characters
        return criteria.contains(" ") || criteria.contains("\"") || criteria.contains("(");
    }

    public void clearCriteria() {
        selectedCriteria.clear();
        pnlCriteria.removeAll();
        taCustom.setText("");
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
