package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.controls.NavigationTreeIcons;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.outline.Outline;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * Gliederung neben dem Editor wie das Outline von MainframeMate ({@code JclOutlinePanel}): Überschrift je Sprache,
 * Filter („Alle“ gruppiert, sonst flach), Baum mit Tooltip je Element, Doppelklick oder Enter springt zur Zeile, unten
 * die Zählzeile. Die Gliederung selbst kommt aus {@code mainframe-languages}.
 */
final class OutlinePanel extends JPanel {

    private final JLabel title = Comic.caption("Gliederung");
    private final JComboBox<String> filter = Comic.combo();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Gliederung");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JLabel status = Comic.muted(" ");
    private final IntConsumer navigator;
    /** Für die Diagramm-Ansicht: gewähltes Element (Klick oder Enter); {@code null} im Text. */
    private java.util.function.Consumer<JclElement> chooser;
    private Outline outline;
    private boolean filling;

    OutlinePanel(IntConsumer navigator) {
        this(navigator, null);
    }

    OutlinePanel(IntConsumer navigator, java.util.function.Consumer<JclElement> chooser) {
        super(new BorderLayout(0, 6));
        this.navigator = navigator;
        this.chooser = chooser;
        setOpaque(true);
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 8, 6, 8)));
        filter.getAccessibleContext().setAccessibleName("Filter der Gliederung");
        filter.setToolTipText("Welche Elemente die Gliederung zeigt");
        filter.addActionListener(e -> {
            if (!filling) {
                build();
            }
        });
        JPanel head = new JPanel(new BorderLayout(6, 0));
        head.setOpaque(false);
        head.add(title, BorderLayout.CENTER);
        head.add(filter, BorderLayout.EAST);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Comic.regular(13f));
        tree.setCellRenderer(new Renderer());
        tree.getAccessibleContext().setAccessibleName("Gliederung");
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    jump();
                }
            }
        });
        // Wie MainframeMate: Auswahl in der Gliederung zeigt das Element im Diagramm (nur mit chooser)
        tree.addTreeSelectionListener(event -> chosen());
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "outline-jump");
        tree.getActionMap().put("outline-jump", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                jump();
            }
        });

        add(head, BorderLayout.NORTH);
        add(Comic.scroll(tree), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        show(Optional.empty());
    }

    /** Zeigt eine neue Gliederung; der gewählte Filter bleibt, wenn die Sprache ihn kennt. */
    void show(Optional<Outline> next) {
        String chosen = (String) filter.getSelectedItem();
        outline = next.orElse(null);
        filling = true;
        try {
            filter.removeAllItems();
            List<String> filters = outline == null ? List.of(Outline.ALL) : outline.filters();
            filters.forEach(filter::addItem);
            filter.setSelectedItem(chosen != null && filters.contains(chosen) ? chosen : Outline.ALL);
        } finally {
            filling = false;
        }
        filter.setEnabled(outline != null && !outline.isEmpty());
        title.setText(outline == null ? "Gliederung" : outline.title());
        build();
    }

    private String query = "";

    /** Suche der Seitenleiste: nur passende Einträge (mit ihren Kindern) bleiben stehen. */
    void query(String text) {
        query = text == null ? "" : text;
        build();
    }

    private void build() {
        root.removeAllChildren();
        if (outline == null) {
            root.add(new DefaultMutableTreeNode("Keine Gliederung – unten rechts JCL, COBOL oder Natural wählen"));
            status.setText(" ");
        } else if (outline.isEmpty()) {
            root.add(new DefaultMutableTreeNode("Keine Struktur erkannt"));
            status.setText(outline.status());
        } else {
            for (Outline.Group group : outline.groups((String) filter.getSelectedItem())) {
                DefaultMutableTreeNode parent = root;
                if (!group.label().isEmpty()) {
                    parent = new DefaultMutableTreeNode(group.label());
                    root.add(parent);
                }
                if (outline.language() == com.aresstack.spaceharvester.mainframe.outline.SourceLanguage.COBOL) {
                    addCobolGroup(parent, group.elements());
                } else {
                    for (JclElement element : group.elements()) {
                        DefaultMutableTreeNode node = new DefaultMutableTreeNode(element);
                        for (JclElement child : element.getChildren()) {
                            node.add(new DefaultMutableTreeNode(child));
                        }
                        parent.add(node);
                    }
                }
            }
            status.setText(outline.status());
            TreeQuery.prune(root, query, item -> item instanceof JclElement element ? element.getDisplayText()
                    : String.valueOf(item));
        }
        model.reload();
        if (outline != null && outline.language() == com.aresstack.spaceharvester.mainframe.outline.SourceLanguage.COBOL
                && query.isBlank()) {
            tree.expandPath(new TreePath(root.getPath()));
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
                if (child.getUserObject() instanceof String) {
                    tree.expandPath(new TreePath(child.getPath()));
                }
            }
        } else {
            for (int row = 0; row < tree.getRowCount(); row++) {
                tree.expandRow(row);
            }
        }
    }

    private void addCobolGroup(DefaultMutableTreeNode group, List<JclElement> elements) {
        java.util.Set<JclElement> visible = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (JclElement element : elements) {
            boolean dataItem = switch (element.getType()) {
                case DATA_ITEM, LEVEL_01, LEVEL_77, LEVEL_88 -> true;
                default -> false;
            };
            if (!(dataItem && element.getLineNumber() == 0)
                    && (!"FILLER".equalsIgnoreCase(element.getName()) || element.hasChildren())) {
                visible.add(element);
            }
        }
        for (JclElement element : elements) {
            if (visible.contains(element) && !visible.contains(element.getParent())) {
                group.add(cobolNode(element, visible));
            }
        }
    }

    private DefaultMutableTreeNode cobolNode(JclElement element, java.util.Set<JclElement> visible) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(element);
        for (JclElement child : element.getChildren()) {
            if (visible.contains(child)) {
                node.add(cobolNode(child, visible));
            }
        }
        return node;
    }

    /** Diagramm-Ansicht an/aus: Auswahl zeigt das Element im Diagramm; {@code null} = zur Zeile springen. */
    void chooser(java.util.function.Consumer<JclElement> next) {
        chooser = next;
    }

    private void chosen() {
        TreePath path = tree.getSelectionPath();
        if (chooser != null && path != null
                && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof JclElement element) {
            chooser.accept(element);
        }
    }

    private void jump() {
        if (chooser != null) {
            chosen();
            return;
        }
        TreePath path = tree.getSelectionPath();
        if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
                instanceof JclElement element && element.getLineNumber() > 0) {
            navigator.accept(element.getLineNumber());
        }
    }

    // ---- für Tests -------------------------------------------------------------------------------------------------

    JTree tree() {
        return tree;
    }

    JComboBox<String> filter() {
        return filter;
    }

    String statusText() {
        return status.getText();
    }

    private static final class Renderer extends DefaultTreeCellRenderer {

        private javax.swing.Icon elementIcon(JclElement element) {
            return switch (element.getType()) {
                case DIVISION, SECTION, PROCEDURE_DIVISION, WORKING_STORAGE, LINKAGE_SECTION,
                        FILE_SECTION, SCREEN_SECTION, JOB, PROC, PEND, NAT_DEFINE_DATA,
                        NAT_SUBROUTINE, NAT_INLINE_SUBROUTINE, DDM_GROUP -> NavigationTreeIcons.FOLDER;
                case COPY_STMT, INCLUDE, JCLLIB, NAT_COPYCODE, NAT_INCLUDE -> NavigationTreeIcons.INCOMING;
                case CALL_STMT, DYNAMIC_CALL_STMT, DEBUG_CALL_STMT, EXEC, EXEC_CICS,
                        NAT_CALL, NAT_CALLNAT, NAT_FETCH -> NavigationTreeIcons.OUTGOING;
                case PERFORM_STMT, NAT_PERFORM, NAT_FOR, NAT_REPEAT -> Glyph.REFRESH;
                case DATA_ITEM, LEVEL_01, LEVEL_77, NAT_DATA_VAR, NAT_DATA_VIEW, DDM_FIELD,
                        EXEC_SQL, EXEC_SQLIMS -> Glyph.TABLE;
                case LEVEL_88 -> Glyph.ACCEPT;
                case DDM_DESCRIPTOR, DDM_SUPERDESCRIPTOR -> Glyph.KEY;
                default -> NavigationTreeIcons.PAGE;
            };
        }

        Renderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            Object item = ((DefaultMutableTreeNode) value).getUserObject();
            JLabel label = (JLabel) super.getTreeCellRendererComponent(tree, item instanceof JclElement element
                    ? element.getDisplayText() : String.valueOf(item), selected, expanded, leaf, row, focus);
            label.setIcon(item instanceof JclElement element ? elementIcon(element)
                    : leaf ? null : NavigationTreeIcons.FOLDER);
            label.setToolTipText(item instanceof JclElement element ? element.getTooltipText() : null);
            return label;
        }
    }
}
