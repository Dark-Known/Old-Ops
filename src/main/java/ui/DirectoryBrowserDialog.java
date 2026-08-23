package ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Small "browse for a folder" popup used by every path field in the Task
 * dialog (source path, target folder, backup source/destination). Works
 * against either the local filesystem or a remote SFTP session through the
 * {@link Provider} abstraction, and skins itself differently depending on
 * the OS of whatever it's browsing:
 *
 * <ul>
 *   <li><b>WINDOWS</b> — light "Explorer" look: white panels, blue accent,
 *       folder icons, a "This PC ▸ C: ▸ Users ▸ ..." breadcrumb.</li>
 *   <li><b>LINUX</b> — dark "terminal" look: monospace font, green/cyan
 *       accents, a {@code user@host:/path$} style prompt bar.</li>
 * </ul>
 */
public final class DirectoryBrowserDialog {

    /** One entry (always a directory — this browser is folder-only) shown in the list. */
    public static final class Item {
        public final String name;
        public Item(String name) { this.name = name; }
    }

    /** Abstracts local-filesystem vs remote-SFTP directory listing behind one navigation API. */
    public interface Provider {
        List<Item> list(String path) throws Exception;
        String initialPath();
        String parentOf(String path);
        String join(String dir, String name);
        /** Shortcut paths shown as quick-access chips (drives, home dir, root, etc). */
        List<String> quickAccess();
        /** Released when the dialog closes (e.g. disconnects the SFTP session). No-op for local. */
        void close();
    }

    private DirectoryBrowserDialog() {}

    /**
     * @param label   what's being browsed, shown in the title (e.g. "This Computer" or "prod-01 (10.0.0.5)")
     * @param osType  "WINDOWS" or "LINUX" — purely cosmetic, picks the Explorer vs terminal skin
     * @return the chosen folder path, or {@code null} if cancelled
     */
    public static String show(Component parent, String label, String osType, Provider provider) {
        boolean windows = !"LINUX".equalsIgnoreCase(osType == null ? "" : osType);

        // ── Palette ──────────────────────────────────────────────────────────
        Color bg          = windows ? new Color(0xFFFFFF) : new Color(0x1E1E1E);
        Color panelBg     = windows ? new Color(0xF7F8FA) : new Color(0x252526);
        Color toolbarBg   = windows ? new Color(0xFAFAFA) : new Color(0x2D2D2D);
        Color text        = windows ? new Color(0x1B1B1B) : new Color(0xD4D4D4);
        Color accent      = windows ? new Color(0x0078D4) : new Color(0x4EC9B0);
        Color muted       = windows ? new Color(0x6B6B6B) : new Color(0x808080);
        Color selectBg    = windows ? new Color(0xCCE4F7) : new Color(0x264F4A);
        Color hoverBg     = windows ? new Color(0xE8F1FB) : new Color(0x2A2D2E);
        Color border      = windows ? new Color(0xE0E0E0) : new Color(0x3C3C3C);
        Color errColor    = windows ? new Color(0xC42B1C) : new Color(0xFF6B6B);
        Font baseFont     = windows
                ? new Font("Segoe UI", Font.PLAIN, 13)
                : new Font(Font.MONOSPACED, Font.PLAIN, 13);
        Font crumbFont    = baseFont.deriveFont(windows ? Font.PLAIN : Font.BOLD, 12.5f);
        Icon folderIcon   = new FolderIcon(windows ? new Color(0xFFC83D) : accent, windows);
        Icon homeIcon     = new HomeIcon(windows ? new Color(0x0078D4) : accent);
        String windowTitle = windows
                ? "File Explorer - " + label
                : "Browse - " + label;

        Window ownerWindow = SwingUtilities.getWindowAncestor(parent);
        Frame ownerFrame = ownerWindow instanceof Frame ? (Frame) ownerWindow : null;
        JDialog dlg = new JDialog(ownerFrame, windowTitle, true);
        dlg.setLayout(new BorderLayout());
        dlg.getContentPane().setBackground(bg);

        // ── Toolbar: back / forward / up / refresh / home + address bar ────────
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBackground(toolbarBg);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, border),
                new EmptyBorder(9, 12, 9, 12)));

        JButton btnBack = navButton(new BackArrowIcon(text), windows);
        JButton btnFwd  = navButton(new FwdArrowIcon(text), windows);
        JButton btnUp   = navButton(new UpArrowIcon(text), windows);
        JButton btnRefresh = navButton(new RefreshIcon(text), windows);
        JButton btnHome = navButton(homeIcon, windows);
        btnBack.setToolTipText("Back");
        btnFwd.setToolTipText("Forward");
        btnUp.setToolTipText("Up one level");
        btnRefresh.setToolTipText("Refresh");
        btnHome.setToolTipText("Home");

        JPanel navGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        navGroup.setOpaque(false);
        navGroup.add(btnBack);
        navGroup.add(btnFwd);
        navGroup.add(btnUp);
        navGroup.add(btnRefresh);
        navGroup.add(btnHome);

        // Address area: breadcrumb "card" (default) <-> editable text field "card" (click to type a path)
        JPanel breadcrumbBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        breadcrumbBar.setBackground(windows ? Color.WHITE : new Color(0x141414));
        breadcrumbBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                new EmptyBorder(3, 8, 3, 8)));

        JTextField address = new JTextField();
        address.setFont(baseFont);
        address.setForeground(text);
        address.setBackground(windows ? Color.WHITE : new Color(0x141414));
        address.setCaretColor(text);
        address.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent),
                new EmptyBorder(5, 8, 5, 8)));

        CardLayout addressCards = new CardLayout();
        JPanel addressContainer = new JPanel(addressCards);
        addressContainer.setOpaque(false);
        addressContainer.add(breadcrumbBar, "crumbs");
        addressContainer.add(address, "edit");

        JButton btnGo = navButton(new GoArrowIcon(windows ? Color.WHITE : accent), windows);
        btnGo.setToolTipText("Go");
        if (windows) {
            btnGo.setBackground(accent);
            btnGo.setOpaque(true);
            btnGo.setBorderPainted(false);
        }

        toolbar.add(navGroup, BorderLayout.WEST);
        toolbar.add(addressContainer, BorderLayout.CENTER);
        toolbar.add(btnGo, BorderLayout.EAST);

        // ── Left sidebar: quick access ──────────────────────────────────────
        JLabel sidebarTitle = new JLabel(windows ? "  QUICK ACCESS" : "  bookmarks");
        sidebarTitle.setFont(baseFont.deriveFont(Font.BOLD, 10.5f));
        sidebarTitle.setForeground(muted);
        sidebarTitle.setBorder(new EmptyBorder(10, 4, 6, 4));

        DefaultListModel<String> quickModel = new DefaultListModel<>();
        for (String q : provider.quickAccess()) quickModel.addElement(q);
        JList<String> quickList = new JList<>(quickModel);
        quickList.setFont(baseFont.deriveFont(12f));
        quickList.setBackground(panelBg);
        quickList.setForeground(text);
        quickList.setSelectionBackground(selectBg);
        quickList.setSelectionForeground(text);
        quickList.setFixedCellHeight(28);
        quickList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setText(String.valueOf(value));
                c.setIcon(homeIcon);
                c.setIconTextGap(8);
                c.setOpaque(true);
                c.setFont(baseFont.deriveFont(12f));
                c.setBackground(isSelected ? selectBg : panelBg);
                c.setForeground(text);
                c.setBorder(new EmptyBorder(4, 10, 4, 6));
                return c;
            }
        });
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(panelBg);
        sidebar.add(sidebarTitle, BorderLayout.NORTH);
        sidebar.add(quickList, BorderLayout.CENTER);
        JScrollPane quickScroll = new JScrollPane(sidebar);
        quickScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, border));
        quickScroll.setPreferredSize(new Dimension(168, 0));
        quickScroll.getViewport().setBackground(panelBg);
        quickScroll.getVerticalScrollBar().setUnitIncrement(16);

        // ── Center: folder list, with hover highlight ───────────────────────
        DefaultListModel<String> listModel = new DefaultListModel<>();
        JList<String> folderList = new JList<>(listModel);
        folderList.setFont(baseFont);
        folderList.setBackground(bg);
        folderList.setSelectionBackground(selectBg);
        folderList.setSelectionForeground(text);
        folderList.setFixedCellHeight(windows ? 28 : 24);
        folderList.setVisibleRowCount(-1);
        int[] hoveredIndex = { -1 };
        folderList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setText(String.valueOf(value));
                c.setIcon(folderIcon);
                c.setIconTextGap(10);
                c.setOpaque(true);
                c.setFont(baseFont);
                Color rowBg = isSelected ? selectBg : (index == hoveredIndex[0] ? hoverBg : bg);
                c.setBackground(rowBg);
                c.setForeground(isSelected ? text : (windows ? text : new Color(0x9CDCFE)));
                c.setBorder(new EmptyBorder(2, 12, 2, 12));
                return c;
            }
        });
        folderList.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int idx = folderList.locationToIndex(e.getPoint());
                if (idx != hoveredIndex[0]) { hoveredIndex[0] = idx; folderList.repaint(); }
            }
        });
        folderList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseExited(java.awt.event.MouseEvent e) { hoveredIndex[0] = -1; folderList.repaint(); }
        });
        JScrollPane listScroll = new JScrollPane(folderList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getViewport().setBackground(bg);
        listScroll.getVerticalScrollBar().setUnitIncrement(20);

        JPanel emptyState = new JPanel(new GridBagLayout());
        emptyState.setBackground(bg);
        JLabel emptyLabel = new JLabel(windows ? "This folder is empty" : "(empty directory)");
        emptyLabel.setFont(baseFont.deriveFont(12.5f));
        emptyLabel.setForeground(muted);
        emptyState.add(emptyLabel);

        JPanel centerCards = new JPanel(new CardLayout());
        centerCards.add(listScroll, "list");
        centerCards.add(emptyState, "empty");

        // ── Status bar ───────────────────────────────────────────────────────
        JLabel statusLabel = new JLabel(" ");
        statusLabel.setFont(baseFont.deriveFont(11f));
        statusLabel.setForeground(muted);

        // ── Bottom: OK / Cancel ──────────────────────────────────────────────
        JButton btnOk = new JButton(windows ? "Select Folder" : "select");
        JButton btnCancel = new JButton(windows ? "Cancel" : "cancel");
        btnOk.setFont(baseFont);
        btnCancel.setFont(baseFont);
        btnOk.setFocusPainted(false);
        btnCancel.setFocusPainted(false);
        btnOk.setMargin(new Insets(6, 16, 6, 16));
        btnCancel.setMargin(new Insets(6, 16, 6, 16));
        if (windows) {
            btnOk.setBackground(accent);
            btnOk.setForeground(Color.WHITE);
            btnOk.setOpaque(true);
            btnOk.setBorderPainted(false);
        } else {
            btnOk.setBackground(new Color(0x2D2D2D));
            btnOk.setForeground(accent);
            btnOk.setBorder(BorderFactory.createLineBorder(accent));
        }

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(toolbarBg);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, border),
                new EmptyBorder(8, 12, 8, 12)));
        bottom.add(statusLabel, BorderLayout.WEST);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.add(btnCancel);
        btnRow.add(btnOk);
        bottom.add(btnRow, BorderLayout.EAST);

        dlg.add(toolbar, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout());
        center.add(quickScroll, BorderLayout.WEST);
        center.add(centerCards, BorderLayout.CENTER);
        dlg.add(center, BorderLayout.CENTER);
        dlg.add(bottom, BorderLayout.SOUTH);

        dlg.setSize(windows ? 620 : 660, 460);
        dlg.setMinimumSize(new Dimension(440, 320));
        dlg.setResizable(true);
        dlg.setLocationRelativeTo(parent);

        // ── Navigation state: history for Back/Forward, breadcrumb builder ──
        String[] result = { null };
        List<String> history = new ArrayList<>();
        int[] historyIndex = { -1 };

        @SuppressWarnings("unchecked")
        java.util.function.Consumer<String>[] navigateRef = new java.util.function.Consumer[1];

        java.util.function.Consumer<String> applyPath = path -> {
            try {
                List<Item> items = provider.list(path);
                listModel.clear();
                for (Item it : items) listModel.addElement(it.name);
                ((CardLayout) centerCards.getLayout()).show(centerCards, items.isEmpty() ? "empty" : "list");
                statusLabel.setForeground(muted);
                statusLabel.setText(items.size() + " folder" + (items.size() == 1 ? "" : "s"));
            } catch (Exception ex) {
                listModel.clear();
                ((CardLayout) centerCards.getLayout()).show(centerCards, "list");
                statusLabel.setForeground(errColor);
                statusLabel.setText("Can't open \"" + path + "\": " + ex.getMessage());
            }

            breadcrumbBar.removeAll();
            for (String[] crumb : buildCrumbs(path)) {
                JButton chip = new JButton(crumb[0]);
                chip.setFont(crumbFont);
                chip.setForeground(windows ? new Color(0x2B2B2B) : accent);
                chip.setBorderPainted(false);
                chip.setContentAreaFilled(false);
                chip.setFocusPainted(false);
                chip.setMargin(new Insets(2, 5, 2, 5));
                chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                String target = crumb[1];
                chip.addActionListener(e -> navigateRef[0].accept(target));
                breadcrumbBar.add(chip);
                JLabel sep = new JLabel(windows ? "\u203A" : "/"); // › for windows, / for linux (plain, well-supported glyphs)
                sep.setFont(crumbFont);
                sep.setForeground(muted);
                breadcrumbBar.add(sep);
            }
            breadcrumbBar.revalidate();
            breadcrumbBar.repaint();
            addressCards.show(addressContainer, "crumbs");

            address.setText(path);
        };

        // navigate(): pushes to history + applies. Back/Forward move historyIndex and call applyPath directly.
        java.util.function.Consumer<String> navigate = path -> {
            String cur = historyIndex[0] >= 0 && historyIndex[0] < history.size() ? history.get(historyIndex[0]) : null;
            if (path.equals(cur)) { applyPath.accept(path); return; }
            while (history.size() > historyIndex[0] + 1) history.remove(history.size() - 1);
            history.add(path);
            historyIndex[0] = history.size() - 1;
            btnBack.setEnabled(historyIndex[0] > 0);
            btnFwd.setEnabled(false);
            applyPath.accept(path);
        };
        navigateRef[0] = navigate;

        btnBack.addActionListener(e -> {
            if (historyIndex[0] > 0) {
                historyIndex[0]--;
                applyPath.accept(history.get(historyIndex[0]));
                btnBack.setEnabled(historyIndex[0] > 0);
                btnFwd.setEnabled(true);
            }
        });
        btnFwd.addActionListener(e -> {
            if (historyIndex[0] < history.size() - 1) {
                historyIndex[0]++;
                applyPath.accept(history.get(historyIndex[0]));
                btnFwd.setEnabled(historyIndex[0] < history.size() - 1);
                btnBack.setEnabled(true);
            }
        });
        btnUp.addActionListener(e -> navigate.accept(provider.parentOf(currentPathOf(history, historyIndex))));
        btnRefresh.addActionListener(e -> applyPath.accept(currentPathOf(history, historyIndex)));
        btnHome.addActionListener(e -> navigate.accept(provider.initialPath()));

        btnGo.addActionListener(e -> {
            String typed = address.getText().trim();
            if (!typed.isEmpty()) navigate.accept(typed);
        });
        address.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) btnGo.doClick();
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) addressCards.show(addressContainer, "crumbs");
            }
        });
        breadcrumbBar.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getSource() == breadcrumbBar) { // clicked empty space, not a chip
                    addressCards.show(addressContainer, "edit");
                    address.requestFocusInWindow();
                    address.selectAll();
                }
            }
        });
        address.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                addressCards.show(addressContainer, "crumbs");
            }
        });

        quickList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && quickList.getSelectedValue() != null) {
                navigate.accept(quickList.getSelectedValue());
            }
        });
        folderList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String name = folderList.getSelectedValue();
                    if (name != null) navigate.accept(provider.join(currentPathOf(history, historyIndex), name));
                }
            }
        });
        folderList.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    String name = folderList.getSelectedValue();
                    if (name != null) navigate.accept(provider.join(currentPathOf(history, historyIndex), name));
                } else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    navigate.accept(provider.parentOf(currentPathOf(history, historyIndex)));
                }
            }
        });

        btnOk.addActionListener(e -> {
            result[0] = currentPathOf(history, historyIndex);
            dlg.dispose();
        });
        btnCancel.addActionListener(e -> dlg.dispose());
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { provider.close(); }
        });

        btnBack.setEnabled(false);
        btnFwd.setEnabled(false);
        navigate.accept(provider.initialPath());
        dlg.setVisible(true); // modal — blocks until OK/Cancel/close
        return result[0];
    }

    private static String currentPathOf(List<String> history, int[] historyIndex) {
        return historyIndex[0] >= 0 && historyIndex[0] < history.size() ? history.get(historyIndex[0]) : "";
    }

    private static JButton navButton(Icon icon, boolean windows) {
        JButton b = new JButton(icon);
        b.setFocusPainted(false);
        b.setMargin(new Insets(6, 8, 6, 8));
        if (!windows) {
            b.setBackground(new Color(0x2D2D2D));
            b.setBorder(BorderFactory.createEmptyBorder());
        } else {
            b.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }
        return b;
    }

    /** Splits a path into clickable {label, cumulativePath} breadcrumb segments. */
    private static List<String[]> buildCrumbs(String path) {
        List<String[]> out = new ArrayList<>();
        if (path == null || path.isEmpty()) return out;
        boolean windowsPath = path.matches("^[A-Za-z]:.*") && path.contains("\\");
        if (windowsPath) {
            String[] parts = path.split("\\\\+");
            StringBuilder acc = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (acc.length() == 0) acc.append(part).append("\\");
                else acc.append(part).append("\\");
                out.add(new String[]{part, acc.toString()});
            }
        } else {
            out.add(new String[]{"/", "/"});
            String[] parts = path.split("/+");
            StringBuilder acc = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                acc.append("/").append(part);
                out.add(new String[]{part, acc.toString()});
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Built-in providers
    // ─────────────────────────────────────────────────────────────────────

    /** Browses the local filesystem this app is running on. */
    public static final class LocalProvider implements Provider {
        private final String initial;

        public LocalProvider(String startPath) {
            this.initial = (startPath != null && !startPath.trim().isEmpty() && new File(startPath.trim()).isDirectory())
                    ? startPath.trim() : System.getProperty("user.home");
        }

        @Override public List<Item> list(String path) {
            File dir = new File(path);
            File[] files = dir.listFiles();
            List<Item> out = new ArrayList<>();
            if (files != null) {
                List<String> names = new ArrayList<>();
                for (File f : files) if (f.isDirectory()) names.add(f.getName());
                names.sort(String.CASE_INSENSITIVE_ORDER);
                for (String n : names) out.add(new Item(n));
            }
            return out;
        }

        @Override public String initialPath() { return initial; }

        @Override public String parentOf(String path) {
            File f = new File(path);
            String p = f.getParent();
            return p != null ? p : path;
        }

        @Override public String join(String dir, String name) { return new File(dir, name).getPath(); }

        @Override public List<String> quickAccess() {
            List<String> out = new ArrayList<>();
            String home = System.getProperty("user.home");
            if (home != null) out.add(home);
            for (File root : File.listRoots()) out.add(root.getPath());
            return out;
        }

        @Override public void close() { /* nothing to release */ }
    }

    /** Browses a remote server over an already-open SFTP session. */
    public static final class RemoteProvider implements Provider {
        private final service.SftpBrowseService sftp;
        private final String initial;

        public RemoteProvider(service.SftpBrowseService sftp, String initial) {
            this.sftp = sftp;
            this.initial = (initial != null && !initial.trim().isEmpty()) ? initial.trim() : "/";
        }

        @Override public List<Item> list(String path) throws Exception {
            List<Item> out = new ArrayList<>();
            for (service.SftpBrowseService.Entry e : sftp.list(path)) {
                if (e.directory) out.add(new Item(e.name));
            }
            return out;
        }

        @Override public String initialPath() { return initial; }

        @Override public String parentOf(String path) {
            if (path == null || path.isEmpty() || "/".equals(path)) return "/";
            String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
            int idx = p.lastIndexOf('/');
            return idx <= 0 ? "/" : p.substring(0, idx);
        }

        @Override public String join(String dir, String name) {
            return dir.endsWith("/") ? dir + name : dir + "/" + name;
        }

        @Override public List<String> quickAccess() {
            List<String> out = new ArrayList<>();
            out.add("/");
            try {
                String home = sftp.home();
                if (home != null && !"/".equals(home)) out.add(home);
            } catch (Exception ignored) { /* best effort */ }
            return out;
        }

        @Override public void close() { sftp.close(); }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Hand-drawn icons — deliberately NOT emoji/dingbat characters. Those
    // depend on the JRE/OS having a font with matching glyphs installed
    // (many Linux and some Windows setups don't), which shows up as empty
    // "tofu" boxes. Painting the shapes ourselves renders identically on
    // every platform with zero font dependency.
    // ─────────────────────────────────────────────────────────────────────

    /** A small filled folder shape, colored per theme. */
    private static final class FolderIcon implements Icon {
        private final Color color;
        private final boolean windows;

        FolderIcon(Color color, boolean windows) {
            this.color = color;
            this.windows = windows;
        }

        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 14; }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (windows) {
                g2.setColor(color);
                g2.fillRoundRect(x, y + 3, 6, 3, 1, 1);           // tab
                g2.fillRoundRect(x, y + 5, 16, 9, 2, 2);          // body
                g2.setColor(color.darker());
                g2.drawRoundRect(x, y + 5, 15, 8, 2, 2);
            } else {
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawRoundRect(x + 1, y + 4, 13, 9, 2, 2);      // outline body
                g2.drawLine(x + 1, y + 4, x + 5, y + 4);
                g2.drawLine(x + 5, y + 4, x + 7, y + 2);
                g2.drawLine(x + 7, y + 2, x + 12, y + 2);
                g2.drawLine(x + 12, y + 2, x + 12, y + 4);
            }
            g2.dispose();
        }
    }

    /** A small upward chevron/arrow, used on the "Up" navigation button. */
    private static final class UpArrowIcon implements Icon {
        private final Color color;

        UpArrowIcon(Color color) { this.color = color; }

        @Override public int getIconWidth() { return 10; }
        @Override public int getIconHeight() { return 10; }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int midX = x + getIconWidth() / 2;
            g2.drawLine(midX, y + 1, x + 1, y + 6);
            g2.drawLine(midX, y + 1, x + getIconWidth() - 1, y + 6);
            g2.drawLine(midX, y + 2, midX, y + getIconHeight());
            g2.dispose();
        }
    }

    /** A left-pointing chevron, used on the Back button. */
    private static final class BackArrowIcon implements Icon {
        private final Color color;
        BackArrowIcon(Color color) { this.color = color; }
        @Override public int getIconWidth() { return 10; }
        @Override public int getIconHeight() { return 10; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 7, y + 1, x + 2, y + 5);
            g2.drawLine(x + 2, y + 5, x + 7, y + 9);
            g2.dispose();
        }
    }

    /** A right-pointing chevron, used on the Forward button. */
    private static final class FwdArrowIcon implements Icon {
        private final Color color;
        FwdArrowIcon(Color color) { this.color = color; }
        @Override public int getIconWidth() { return 10; }
        @Override public int getIconHeight() { return 10; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 3, y + 1, x + 8, y + 5);
            g2.drawLine(x + 8, y + 5, x + 3, y + 9);
            g2.dispose();
        }
    }

    /** A circular refresh arrow. */
    private static final class RefreshIcon implements Icon {
        private final Color color;
        RefreshIcon(Color color) { this.color = color; }
        @Override public int getIconWidth() { return 12; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(x + 1, y + 1, 10, 10, 30, 300);
            // arrowhead at the open end of the arc
            g2.fillPolygon(new int[]{x + 9, x + 12, x + 9}, new int[]{y, y + 1, y + 4}, 3);
            g2.dispose();
        }
    }

    /** A simple house shape, used for the Home button and sidebar entries. */
    private static final class HomeIcon implements Icon {
        private final Color color;
        HomeIcon(Color color) { this.color = color; }
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 13; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            int[] roofX = {x, x + 7, x + 14};
            int[] roofY = {y + 6, y, y + 6};
            g2.fillPolygon(roofX, roofY, 3);
            g2.fillRect(x + 3, y + 6, 8, 7);
            g2.setColor(Color.WHITE);
            g2.fillRect(x + 6, y + 9, 2, 4);
            g2.dispose();
        }
    }

    /** A right-pointing "go" arrow (used on the address-bar Go button). */
    private static final class GoArrowIcon implements Icon {
        private final Color color;
        GoArrowIcon(Color color) { this.color = color; }
        @Override public int getIconWidth() { return 12; }
        @Override public int getIconHeight() { return 12; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillPolygon(new int[]{x + 2, x + 10, x + 2}, new int[]{y + 1, y + 6, y + 11}, 3);
            g2.dispose();
        }
    }
}
