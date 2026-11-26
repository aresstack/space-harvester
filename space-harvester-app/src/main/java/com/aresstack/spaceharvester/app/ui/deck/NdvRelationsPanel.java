package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import com.aresstack.spaceharvester.deck.ndv.analysis.NaturalDependencyGraph;
import com.aresstack.spaceharvester.mainframe.dependency.NaturalDependencyService;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAnalysis;
import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * Beziehungen des geöffneten Natural-Objekts wie im linken Drawer von MainframeMate: „Aufrufe“ (was es aufruft,
 * rekursiv bis Tiefe 5), „Aufrufer“ (wer es aufruft, ebenso) aus dem Abhängigkeitsgraphen der Bibliothek, und
 * „Daten“ (Data Areas, Views/DDMs, Copycodes, Maps, DB-Zugriffe) aus dem Quelltext selbst. Ein Klick springt zur
 * Fundstelle (Zeile im aufrufenden Quelltext), ein Doppelklick oder Eingabe öffnet das Ziel.
 */
final class NdvRelationsPanel extends JPanel {

    static final String CALLEES = "Aufrufe";
    static final String CALLERS = "Aufrufer";
    static final String DATA = "Daten";

    /** Ein Knoten: Anzeigetext, Ziel (Objektname), Fundstelle (Objekt, Zeile ab 1). */
    record Entry(String text, String target, String siteObject, int line) {

        @Override
        public String toString() {
            return text;
        }
    }

    /** Was ein Klick bewirkt: zur Fundstelle springen oder das Ziel öffnen. */
    interface Navigator {

        void site(String object, int line);

        void target(String object);
    }

    private final SegmentedToggle view = new SegmentedToggle();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JLabel note = Comic.muted(" ");
    private String object;
    private Optional<NaturalDependencyGraph> graph = Optional.empty();
    private NaturalDependencyService.DependencyResult data;

    NdvRelationsPanel(Navigator navigator) {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        getAccessibleContext().setAccessibleName("Beziehungen");
        view.segment(CALLEES).segment(CALLERS).segment(DATA);
        view.select(CALLEES);
        view.onSelect(name -> fill());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Comic.regular(12.5f));
        tree.putClientProperty("html.disable", Boolean.TRUE);
        tree.getAccessibleContext().setAccessibleName("Beziehungen");
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                Entry entry = entry(path);
                if (entry == null) {
                    return;
                }
                if (event.getClickCount() == 2) {
                    if (!entry.target().isEmpty()) {
                        navigator.target(entry.target());
                    }
                } else if (entry.line() > 0) {
                    navigator.site(entry.siteObject(), entry.line());
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ndv-relation-open");
        tree.getActionMap().put("ndv-relation-open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Entry entry = entry(tree.getSelectionPath());
                if (entry != null && !entry.target().isEmpty()) {
                    navigator.target(entry.target());
                }
            }
        });
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(view, BorderLayout.NORTH);
        head.add(note, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);
        add(Comic.scroll(tree), BorderLayout.CENTER);
        fill();
    }

    private static Entry entry(TreePath path) {
        if (path == null) {
            return null;
        }
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof Entry entry ? entry : null;
    }

    /** Zeigt die Beziehungen eines Objekts; ohne Graph sagt der Hinweis, wie er entsteht. */
    void show(String objectName, Optional<NaturalDependencyGraph> libraryGraph,
              NaturalDependencyService.DependencyResult dependencies) {
        object = objectName;
        graph = libraryGraph;
        data = dependencies;
        fill();
    }

    void select(String name) {
        view.select(name);
    }

    private String query = "";

    /** Suche der Seitenleiste: nur passende Einträge (mit ihren Kindern) bleiben stehen. */
    void query(String text) {
        query = text == null ? "" : text;
        fill();
    }

    private void fill() {
        root.removeAllChildren();
        String mode = view.selected();
        if (object == null) {
            note.setText("Objekt öffnen, um seine Beziehungen zu sehen");
        } else if (DATA.equals(mode)) {
            fillData();
        } else if (graph.isEmpty()) {
            note.setText("Abhängigkeiten entstehen, sobald die Bibliothek zwischengespeichert ist");
        } else {
            boolean callees = CALLEES.equals(mode);
            NaturalDependencyGraph.CallHierarchyNode start = graph.get().getCallHierarchy(object, callees,
                    NdvAnalysis.HIERARCHY_DEPTH);
            start.getChildren().forEach(child -> root.add(node(child, start.getObjectName(), callees)));
            note.setText(start.getChildren().isEmpty() ? (callees ? "Ruft nichts auf" : "Wird von nichts aufgerufen")
                    : (start.totalNodes() - 1) + " Einträge, Tiefe bis " + NdvAnalysis.HIERARCHY_DEPTH);
        }
        com.aresstack.spaceharvester.app.ui.deck.editor.TreeQuery.prune(root, query, String::valueOf);
        model.reload();
        for (int row = 0; row < tree.getRowCount() && row < 200; row++) {
            if (tree.getPathForRow(row).getPathCount() <= 2) {
                tree.expandRow(row);
            }
        }
    }

    /**
     * Wie {@code convertHierarchyNode}: bei Aufrufen liegt die Fundstelle im Quelltext des übergeordneten Objekts,
     * bei Aufrufern im Quelltext des Aufrufers selbst.
     */
    private static DefaultMutableTreeNode node(NaturalDependencyGraph.CallHierarchyNode node, String parent,
                                               boolean callees) {
        String site = callees ? parent : node.getObjectName();
        DefaultMutableTreeNode tree = new DefaultMutableTreeNode(new Entry(node.getDisplayText(), node.getObjectName(),
                site, node.getLineNumber()));
        node.getChildren().forEach(child -> tree.add(node(child, node.getObjectName(), callees)));
        return tree;
    }

    /** Wie {@code showFullDependenciesInLeftDrawer}: nur Datenbeziehungen, Aufrufe stehen unter „Aufrufe“. */
    private void fillData() {
        int total = 0;
        if (data != null) {
            for (Map.Entry<NaturalDependencyService.DependencyKind, List<NaturalDependencyService.Dependency>> group
                    : data.getGrouped().entrySet()) {
                if (NdvAnalysis.callKind(group.getKey())) {
                    continue;
                }
                DefaultMutableTreeNode section = new DefaultMutableTreeNode(group.getKey().getDisplayLabel() + " ("
                        + group.getValue().size() + ")");
                for (NaturalDependencyService.Dependency dep : group.getValue()) {
                    section.add(new DefaultMutableTreeNode(new Entry(dep.getDisplayText(),
                            dep.getTargetName().toUpperCase(java.util.Locale.ROOT), object, dep.getLineNumber()),
                            false));
                    total++;
                }
                root.add(section);
            }
        }
        note.setText(total == 0 ? "Keine Daten / Data Areas." : total + " Datenbeziehungen");
    }

    // Für Tests
    List<String> rows() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            rows.add(String.valueOf(((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent())
                    .getUserObject()));
        }
        return rows;
    }

    String noteText() {
        return note.getText();
    }

    JTree tree() {
        return tree;
    }

    Entry entryAt(int row) {
        return entry(tree.getPathForRow(row));
    }
}
