package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import com.aresstack.spaceharvester.deck.ndv.NdvTypeGroups;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * Die Objekte nach Art gruppiert, wie der Natural Navigator von NaturalONE und {@code populateGroupedTree} in
 * MainframeMate: Gruppen in fester Reihenfolge mit Anzahl, leere Gruppen fehlen, aufgeklappte Gruppen bleiben gemerkt.
 */
final class NdvObjectTree extends JTree {

    /** Knoten einer Gruppe. */
    record Group(int typeId, String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    /** Aufgeklappte Gruppen; {@code null} = alle. */
    private Set<Integer> expanded;
    private boolean rebuilding;
    private boolean details;

    NdvObjectTree(Set<Integer> expanded, Consumer<Set<Integer>> save) {
        this.expanded = expanded == null ? null : new HashSet<>(expanded);
        setModel(model);
        setRootVisible(false);
        setShowsRootHandles(true);
        setFont(Comic.regular(13f));
        getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        getAccessibleContext().setAccessibleName("Objekte nach Art");
        setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                          boolean open, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(tree, value, selected, open, leaf, row, focus);
                setIcon(null);
                Object user = ((DefaultMutableTreeNode) value).getUserObject();
                if (user instanceof Group group) {
                    setText(group.text());
                    setFont(Comic.semiBold(13f));
                } else if (user instanceof NdvObject object) {
                    setText(label(object, details));
                    setFont(Comic.regular(13f));
                }
                setBackgroundSelectionColor(Comic.YELLOW);
                setTextSelectionColor(Comic.INK);
                return this;
            }
        });
        addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                if (!rebuilding && groupOf(event.getPath()) instanceof Group group) {
                    if (NdvObjectTree.this.expanded != null) {
                        NdvObjectTree.this.expanded.add(group.typeId());
                    }
                    save.accept(NdvObjectTree.this.expanded);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                if (!rebuilding && groupOf(event.getPath()) instanceof Group group) {
                    if (NdvObjectTree.this.expanded == null) {
                        NdvObjectTree.this.expanded = new HashSet<>(NdvTypeGroups.ORDER);
                    }
                    NdvObjectTree.this.expanded.remove(group.typeId());
                    save.accept(NdvObjectTree.this.expanded);
                }
            }
        });
    }

    /** Wie die Zeile in MainframeMate: Name, mit Details auch Modus, Benutzer, Datum, Größe, DBID, FNR. */
    static String label(NdvObject object, boolean details) {
        if (!details) {
            return object.displayName();
        }
        StringBuilder sb = new StringBuilder(object.displayName());
        if (!object.details().mode().isEmpty()) {
            sb.append("  [").append(object.details().mode()).append("]");
        }
        if (!object.user().isEmpty()) {
            sb.append("  (").append(object.user()).append(")");
        }
        if (!object.changed().isEmpty()) {
            sb.append("  ").append(object.changed());
        }
        if (object.sourceSize() > 0) {
            sb.append("  ").append(object.sourceSize()).append("B");
        }
        if (object.location().databaseId() > 0) {
            sb.append("  DBID:").append(object.location().databaseId());
        }
        if (object.location().fileNumber() > 0) {
            sb.append("  FNR:").append(object.location().fileNumber());
        }
        return sb.toString();
    }

    void details(boolean value) {
        details = value;
        repaint();
    }

    /** Baut den Baum aus den gefilterten, sortierten Objekten neu. */
    void show(List<NdvObject> objects) {
        root.removeAllChildren();
        Map<Integer, List<NdvObject>> groups = new LinkedHashMap<>();
        NdvTypeGroups.ORDER.forEach(id -> groups.put(id, new ArrayList<>()));
        for (NdvObject object : objects) {
            groups.computeIfAbsent(object.type().id(), id -> new ArrayList<>()).add(object);
        }
        groups.forEach((typeId, members) -> {
            if (members.isEmpty()) {
                return;
            }
            DefaultMutableTreeNode group = new DefaultMutableTreeNode(
                    new Group(typeId, NdvTypeGroups.groupName(typeId) + " (" + members.size() + ")"));
            members.forEach(member -> group.add(new DefaultMutableTreeNode(member, false)));
            root.add(group);
        });
        model.reload();
        restoreExpansion();
    }

    private void restoreExpansion() {
        rebuilding = true;
        try {
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode group = (DefaultMutableTreeNode) root.getChildAt(i);
                TreePath path = new TreePath(new Object[] {root, group});
                if (expanded == null || expanded.contains(((Group) group.getUserObject()).typeId())) {
                    expandPath(path);
                } else {
                    collapsePath(path);
                }
            }
        } finally {
            rebuilding = false;
        }
    }

    void expandAll() {
        for (int i = 0; i < getRowCount(); i++) {
            expandRow(i);
        }
    }

    void collapseAll() {
        for (int i = getRowCount() - 1; i >= 0; i--) {
            collapseRow(i);
        }
    }

    /** Die gewählten Objekte; eine gewählte Gruppe zählt mit allen ihren Objekten. */
    List<NdvObject> selectedObjects() {
        List<NdvObject> result = new ArrayList<>();
        TreePath[] paths = getSelectionPaths();
        if (paths == null) {
            return result;
        }
        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof NdvObject object) {
                if (!result.contains(object)) {
                    result.add(object);
                }
            } else if (node.getUserObject() instanceof Group) {
                for (int i = 0; i < node.getChildCount(); i++) {
                    NdvObject child = (NdvObject) ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject();
                    if (!result.contains(child)) {
                        result.add(child);
                    }
                }
            }
        }
        return result;
    }

    /** Das Objekt der zuletzt gewählten Zeile, sonst {@code null}. */
    NdvObject leadObject() {
        TreePath path = getLeadSelectionPath();
        return path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()
                instanceof NdvObject object ? object : null;
    }

    void select(NdvObject wanted) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < group.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) group.getChildAt(j);
                if (leaf.getUserObject().equals(wanted)) {
                    TreePath path = new TreePath(leaf.getPath());
                    setSelectionPath(path);
                    scrollPathToVisible(path);
                    return;
                }
            }
        }
    }

    /** Gruppenzeilen wie gezeigt (für Tests). */
    List<String> groups() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            result.add(((DefaultMutableTreeNode) root.getChildAt(i)).getUserObject().toString());
        }
        return result;
    }

    boolean expanded(int typeId) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode group = (DefaultMutableTreeNode) root.getChildAt(i);
            if (((Group) group.getUserObject()).typeId() == typeId) {
                return isExpanded(new TreePath(new Object[] {root, group}));
            }
        }
        return false;
    }

    private static Object groupOf(TreePath path) {
        return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    static Comparator<NdvObject> byName() {
        return Comparator.comparing(NdvObject::displayName, String.CASE_INSENSITIVE_ORDER);
    }
}
