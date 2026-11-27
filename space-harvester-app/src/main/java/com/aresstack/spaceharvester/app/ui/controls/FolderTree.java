package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellEditor;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Generischer Ordnerbaum mit Drag and Drop (ADR 0034, Paket 2c), Verhalten nach dem Favoritenbaum des Enterprise AI
 * Client: Ordner beliebig verschachtelt, Einträge und Ordner per Ziehen in einen Ordner, zwischen zwei Zeilen oder auf
 * die freie Fläche (oberste Ebene, ans Ende); ein Ordner nie in sich selbst. Eine Mehrfachauswahl (Strg/Umschalt) zieht
 * als Block in Baumreihenfolge. Ohne Maus: Kontextmenü „Verschieben nach …“. F2 benennt einen Ordner direkt im Baum um,
 * Entf entfernt, ein Ordner mit Inhalt nur nach „wirklich“-Rückfrage. Ein neuer Ordner heißt zuerst „Neuer Ordner“ und
 * steht sofort im Umbenennen.
 *
 * <p>Das Steuerelement kennt keine Ablage: den Baum liefert {@link #setRoots}, jede Änderung geht als Wunsch an
 * {@link Actions}; die Ablage meldet sich danach mit dem neuen Baum zurück. Auswahl und zugeklappte Ordner überstehen
 * das Neuaufbauen über die IDs. Darstellung über einen eigenen {@link TreeCellRenderer}, der als Wert den Eintrag
 * selbst bekommt ({@link #entryOf}).
 *
 * @param <T> Eintrag (Ordner oder Blatt), beschrieben durch {@link Shape}
 */
public final class FolderTree<T> {

    /** Wie das Steuerelement einen Eintrag liest. */
    public interface Shape<T> {
        String id(T entry);

        boolean isFolder(T entry);

        /** Kinder eines Ordners in Reihenfolge; für Blätter leer. */
        List<T> children(T entry);

        /** Anzeigename, auch Vorgabe beim Umbenennen. */
        String name(T entry);
    }

    /** Wünsche an die Ablage; Aufrufe auf dem EDT. */
    public interface Actions {
        /** Neuer Ordner in {@code parentId} ({@code null} = oberste Ebene); die ID des neuen Ordners oder null. */
        String createFolder(String parentId, String name);

        void renameFolder(String id, String name);

        /** Eintrag oder Ordner samt Inhalt entfernen; die Rückfrage ist schon erfolgt. */
        void remove(String id);

        /**
         * {@code ids} als Block in Baumreihenfolge nach {@code targetFolderId} ({@code null} = oberste Ebene) an
         * Position {@code index}, gezählt vor dem Herausnehmen wie die Einfügemarke ({@code -1} = ans Ende);
         * {@code false} = abgelehnt.
         */
        boolean move(List<String> ids, String targetFolderId, int index);
    }

    /** Wohin ein Ablegen führt: Zielordner ({@code null} = oberste Ebene) und Position ({@code -1} = ans Ende). */
    public record Target(String folderId, int index) {
    }

    public static final String NEW_FOLDER = "Neuer Ordner";
    public static final String NEW_SUBFOLDER = "Neuer Unterordner";
    public static final String RENAME = "Umbenennen";
    public static final String REMOVE = "Entfernen";
    public static final String MOVE_TO = "Verschieben nach";
    public static final String TOP_LEVEL = "Oberste Ebene";

    private static final DataFlavor FLAVOR = new DataFlavor(List.class, "Ordnerbaum");

    private final Shape<T> shape;
    private final Actions actions;
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(null);
    private final Model model = new Model(rootNode);
    private final Tree tree = new Tree(model);
    private final Set<String> collapsed = new HashSet<>();
    private List<T> roots = List.of();
    private Predicate<T> filter;
    private boolean rebuilding;
    private List<T> dragged;
    /** Fragt „Ordner … wirklich entfernen?“ mit Ja/Nein; austauschbar für Tests ohne Bildschirm. */
    private BiPredicate<JComponent, String> confirm = (parent, question) ->
            com.aresstack.spaceharvester.app.ui.Dialogs.confirm(parent, REMOVE, question);

    public FolderTree(Shape<T> shape, Actions actions) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.actions = Objects.requireNonNull(actions, "actions");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);
        tree.setOpaque(false);
        tree.setFont(Comic.regular(13f));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setToggleClickCount(2);
        tree.setEditable(true);
        tree.setInvokesStopCellEditing(true);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public java.awt.Component getTreeCellRendererComponent(JTree owner, Object value, boolean selected,
                                                                   boolean expanded, boolean leaf, int row,
                                                                   boolean focus) {
                T entry = entryOf(value);
                return super.getTreeCellRendererComponent(owner, entry == null ? "" : shape.name(entry), selected,
                        expanded, entry != null && !shape.isFolder(entry), row, focus);
            }
        });
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new Transfer());
        setDragEnabled(true);
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                remember(event.getPath(), false);
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                remember(event.getPath(), true);
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                int row = tree.getRowForLocation(event.getX(), event.getY());
                if (row < 0) {
                    tree.clearSelection(); // freie Fläche: Ziel ist wieder die oberste Ebene
                } else if (SwingUtilities.isRightMouseButton(event) && !tree.isRowSelected(row)) {
                    tree.setSelectionRow(row);
                }
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event); // Windows meldet das Kontextmenü beim Loslassen
            }
        });
        bind(KeyEvent.VK_DELETE, "folder-tree-remove", this::removeSelected);
        bind(KeyEvent.VK_F2, "folder-tree-rename", () -> {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                tree.startEditingAtPath(path);
            }
        });
    }

    /** Der Baum selbst; ein Rollbereich gehört zur Ansicht. */
    public JTree component() {
        return tree;
    }

    public void setCellRenderer(TreeCellRenderer renderer) {
        tree.setCellRenderer(renderer);
    }

    /** Für Tests ohne Bildschirm: ersetzt die Ja/Nein-Rückfrage. */
    public void setConfirmation(BiPredicate<JComponent, String> confirmation) {
        this.confirm = Objects.requireNonNull(confirmation, "confirmation");
    }

    /** Der Baum unterhalb der Wurzel, wie die Ablage ihn liefert. */
    public void setRoots(List<T> values) {
        roots = values == null ? List.of() : List.copyOf(values);
        rebuild();
    }

    /**
     * Zeigt nur Einträge, die {@code accepted} annimmt, samt ihren Ordnern; {@code null} hebt den Filter auf. Solange
     * gefiltert wird, ist Ziehen aus, denn die Positionen entsprächen nicht dem Baum.
     */
    public void setFilter(Predicate<T> accepted) {
        filter = accepted;
        setDragEnabled(accepted == null);
        rebuild();
    }

    /** Der Eintrag eines Baumknotens, null für die Wurzel. */
    @SuppressWarnings("unchecked")
    public T entryOf(Object node) {
        return node instanceof DefaultMutableTreeNode tn && tn.getUserObject() != null ? (T) tn.getUserObject() : null;
    }

    /** Die ausgewählten Einträge in Baumreihenfolge. */
    public List<T> selection() {
        int[] rows = tree.getSelectionRows();
        if (rows == null) {
            return List.of();
        }
        int[] sorted = rows.clone();
        java.util.Arrays.sort(sorted);
        List<T> selected = new ArrayList<>();
        for (int row : sorted) {
            T entry = entryOf(tree.getPathForRow(row).getLastPathComponent());
            // Ein Kind eines ebenfalls gewählten Ordners zieht mit dem Ordner, nicht noch einmal einzeln.
            if (entry != null && selected.stream().noneMatch(parent -> contains(parent, shape.id(entry)))) {
                selected.add(entry);
            }
        }
        return selected;
    }

    /**
     * Wohin etwas Neues gehört (etwa ein neuer Stern): der gewählte Ordner, beim gewählten Eintrag dessen Ordner, ohne
     * Auswahl die oberste Ebene ({@code null}).
     */
    public String targetFolderId() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        T entry = entryOf(path.getLastPathComponent());
        if (entry != null && shape.isFolder(entry)) {
            return shape.id(entry);
        }
        T parent = path.getParentPath() == null ? null : entryOf(path.getParentPath().getLastPathComponent());
        return parent == null ? null : shape.id(parent);
    }

    /** Wählt die Einträge mit diesen IDs und klappt ihre Ordner auf. */
    public void select(List<String> ids) {
        List<TreePath> paths = new ArrayList<>();
        for (String id : ids) {
            DefaultMutableTreeNode node = find(rootNode, id);
            if (node != null) {
                paths.add(new TreePath(node.getPath()));
            }
        }
        tree.setSelectionPaths(paths.toArray(TreePath[]::new));
        if (!paths.isEmpty()) {
            tree.scrollPathToVisible(paths.get(0));
        }
    }

    /** Legt in {@code parentId} einen Ordner „Neuer Ordner“ an und startet das Umbenennen (Knopf und Menü). */
    public void createFolder(String parentId) {
        if (parentId != null) {
            collapsed.remove(parentId); // der neue Ordner soll sichtbar sein
        }
        String id = actions.createFolder(parentId, NEW_FOLDER);
        if (id != null) {
            select(List.of(id));
            DefaultMutableTreeNode node = find(rootNode, id);
            if (node != null) {
                tree.startEditingAtPath(new TreePath(node.getPath()));
            }
        }
    }

    /** Neuer Ordner neben der Auswahl: im gewählten Ordner bzw. im Ordner des gewählten Eintrags. */
    public void createFolder() {
        createFolder(targetFolderId());
    }

    /** Entfernt die Auswahl; ist ein Ordner mit Inhalt dabei, erst nach „wirklich“-Rückfrage. */
    public void removeSelected() {
        List<T> selected = selection();
        if (selected.isEmpty()) {
            return;
        }
        List<T> filled = selected.stream().filter(e -> shape.isFolder(e) && !shape.children(e).isEmpty()).toList();
        if (!filled.isEmpty()) {
            String question = filled.size() == 1
                    ? "Ordner „" + shape.name(filled.get(0)) + "“ samt Inhalt wirklich entfernen?"
                    : filled.size() + " Ordner samt Inhalt wirklich entfernen?";
            if (!confirm.test(tree, question)) {
                return;
            }
        }
        selected.forEach(entry -> actions.remove(shape.id(entry)));
    }

    /** Verschiebt die Auswahl ohne Maus an das Ende von {@code folderId} ({@code null} = oberste Ebene). */
    public boolean moveSelectedTo(String folderId) {
        List<T> selected = selection();
        if (selected.isEmpty() || !allowed(selected, folderId)) {
            return false;
        }
        return move(selected, new Target(folderId, -1));
    }

    /** Weitere Einträge des Kontextmenüs eines einzelnen Ordners, die der Nutzer des Steuerelements beiträgt. */
    private java.util.function.BiConsumer<String, JPopupMenu> folderItems = (id, menu) -> { };

    /** Trägt weitere Einträge in das Kontextmenü eines einzelnen Ordners bei (ID des Ordners, Menü). */
    public void folderItems(java.util.function.BiConsumer<String, JPopupMenu> items) {
        this.folderItems = java.util.Objects.requireNonNull(items, "items");
    }

    /** Das Kontextmenü zur aktuellen Auswahl (für Tests ohne Mausereignis). */
    public JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        List<T> selected = selection();
        if (selected.isEmpty()) {
            menu.add(item(NEW_FOLDER, () -> createFolder(null)));
            return menu;
        }
        T first = selected.get(0);
        if (selected.size() == 1 && shape.isFolder(first)) {
            menu.add(item(NEW_SUBFOLDER, () -> createFolder(shape.id(first))));
            menu.add(item(RENAME, () -> tree.startEditingAtPath(tree.getSelectionPath())));
            folderItems.accept(shape.id(first), menu);
        } else {
            menu.add(item(NEW_FOLDER, this::createFolder));
        }
        JMenu moveTo = new JMenu(MOVE_TO + " …");
        moveTo.add(item(TOP_LEVEL, () -> moveSelectedTo(null)));
        addFolders(moveTo, roots, selected, 1);
        menu.add(moveTo);
        menu.addSeparator();
        menu.add(item(REMOVE, this::removeSelected));
        return menu;
    }

    /**
     * Übersetzt eine Ablagestelle: auf einen Ordner = hinein, ans Ende; zwischen Zeilen = an diese Stelle; auf einen
     * Eintrag = hinter ihn in seinen Ordner; auf die freie Fläche = oberste Ebene. {@code null}, wenn ein gezogener
     * Ordner in sich selbst oder seinen Unterbaum fiele.
     */
    Target target(List<T> moved, T onEntry, T parentOfEntry, int indexOfEntry, int childIndex) {
        Target target;
        if (onEntry == null) {
            target = new Target(null, childIndex);
        } else if (shape.isFolder(onEntry)) {
            target = new Target(shape.id(onEntry), childIndex);
        } else {
            target = new Target(parentOfEntry == null ? null : shape.id(parentOfEntry), indexOfEntry + 1);
        }
        return allowed(moved, target.folderId()) ? target : null;
    }

    // ------------------------------------------------------------------ intern

    private boolean allowed(List<T> moved, String folderId) {
        return folderId == null || moved.stream().noneMatch(entry -> contains(entry, folderId));
    }

    /** Ob {@code entry} selbst oder einer seiner Nachfahren die ID trägt. */
    private boolean contains(T entry, String id) {
        if (shape.id(entry).equals(id)) {
            return true;
        }
        return shape.isFolder(entry) && shape.children(entry).stream().anyMatch(child -> contains(child, id));
    }

    private boolean move(List<T> moved, Target target) {
        List<String> ids = moved.stream().map(shape::id).toList();
        if (target.folderId() != null) {
            collapsed.remove(target.folderId()); // hineingezogen = sichtbar
        }
        boolean done = actions.move(ids, target.folderId(), target.index());
        if (done) {
            select(ids);
        }
        return done;
    }

    private void addFolders(JMenu menu, List<T> level, List<T> moved, int depth) {
        for (T entry : level) {
            if (shape.isFolder(entry) && allowed(moved, shape.id(entry))) {
                String id = shape.id(entry);
                menu.add(item("    ".repeat(depth - 1) + shape.name(entry), () -> moveSelectedTo(id)));
                addFolders(menu, shape.children(entry), moved, depth + 1);
            }
        }
    }

    private void rebuild() {
        List<String> selected = selection().stream().map(shape::id).toList();
        rebuilding = true;
        try {
            if (tree.isEditing()) {
                tree.cancelEditing();
            }
            rootNode.removeAllChildren();
            for (T entry : roots) {
                DefaultMutableTreeNode node = node(entry);
                if (node != null) {
                    rootNode.add(node);
                }
            }
            model.reload();
            expand(rootNode);
            select(selected);
        } finally {
            rebuilding = false;
        }
    }

    /** Ein Knoten samt Kindern; null, wenn der Filter weder ihn noch ein Kind annimmt. */
    private DefaultMutableTreeNode node(T entry) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry, shape.isFolder(entry));
        for (T child : shape.isFolder(entry) ? shape.children(entry) : List.<T>of()) {
            DefaultMutableTreeNode built = node(child);
            if (built != null) {
                node.add(built);
            }
        }
        boolean accepted = filter == null || filter.test(entry);
        return accepted || node.getChildCount() > 0 ? node : null;
    }

    private void expand(DefaultMutableTreeNode parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(index);
            T entry = entryOf(child);
            // Beim Filtern alles offen, sonst alle außer den zugeklappten, auch neue.
            if (child.getChildCount() > 0 && (filter != null || !collapsed.contains(shape.id(entry)))) {
                tree.expandPath(new TreePath(child.getPath()));
                expand(child);
            }
        }
    }

    private void remember(TreePath path, boolean collapse) {
        T entry = entryOf(path.getLastPathComponent());
        if (rebuilding || filter != null || entry == null) {
            return;
        }
        if (collapse) {
            collapsed.add(shape.id(entry));
        } else {
            collapsed.remove(shape.id(entry));
        }
    }

    private DefaultMutableTreeNode find(DefaultMutableTreeNode parent, String id) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(index);
            if (shape.id(entryOf(child)).equals(id)) {
                return child;
            }
            DefaultMutableTreeNode deeper = find(child, id);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private void popup(MouseEvent event) {
        if (event.isPopupTrigger()) {
            menu().show(tree, event.getX(), event.getY());
        }
    }

    private void bind(int key, String name, Runnable action) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        tree.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Ziehen gibt es nur mit Bildschirm ({@code JTree} wirft sonst, etwa in Tests ohne Anzeige). */
    private void setDragEnabled(boolean enabled) {
        if (!GraphicsEnvironment.isHeadless()) {
            tree.setDragEnabled(enabled);
        }
    }

    /** Umbenennen im Baum geht an die Ablage, statt den Knoten zu überschreiben; nur Ordner sind umbenennbar. */
    private final class Model extends DefaultTreeModel {
        Model(DefaultMutableTreeNode root) {
            super(root);
        }

        @Override
        public void valueForPathChanged(TreePath path, Object value) {
            T entry = entryOf(path.getLastPathComponent());
            String name = value == null ? "" : value.toString().trim();
            if (entry != null && shape.isFolder(entry) && !name.isEmpty() && !name.equals(shape.name(entry))) {
                actions.renameFolder(shape.id(entry), name);
            }
        }
    }

    /** Der Baum: füllt die Höhe (Ablegen und Rechtsklick auf der freien Fläche) und zeichnet eine deutliche Marke. */
    private final class Tree extends JTree {
        Tree(DefaultTreeModel model) {
            super(model);
            setCellEditor(new DefaultTreeCellEditor(this, new DefaultTreeCellRenderer()) {
                @Override
                public boolean isCellEditable(java.util.EventObject event) {
                    // Nur per F2 oder Menü, nie per Mausklick (der Klick wählt bzw. öffnet).
                    TreePath path = getSelectionPath();
                    T entry = path == null ? null : entryOf(path.getLastPathComponent());
                    return event == null && entry != null && shape.isFolder(entry) && super.isCellEditable(null);
                }
            });
        }

        @Override
        public String convertValueToText(Object value, boolean selected, boolean expanded, boolean leaf, int row,
                                         boolean focus) {
            T entry = entryOf(value);
            return entry == null ? "" : shape.name(entry);
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getPreferredSize().height < getParent().getHeight();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            JTree.DropLocation location = getDropLocation();
            if (location == null || dragged == null) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(Comic.INK);
                Rectangle mark = mark(location);
                if (mark != null) {
                    g.fill(mark);
                }
            } finally {
                g.dispose();
            }
        }

        /** Einfügemarke: dicke Linie zwischen Zeilen; auf einem Ordner ein Rahmen um die Zeile. */
        private Rectangle mark(JTree.DropLocation location) {
            TreePath path = location.getPath();
            if (path == null) {
                Rectangle last = getRowCount() == 0 ? null : getRowBounds(getRowCount() - 1);
                return last == null ? new Rectangle(0, 0, getWidth(), 3)
                        : new Rectangle(0, last.y + last.height - 1, getWidth(), 3);
            }
            if (location.getChildIndex() < 0) {
                Rectangle row = getPathBounds(path);
                if (row == null) {
                    return null;
                }
                // Hinein: gelbe Fläche unter der Zeile wäre verdeckt; ein Unterstrich über die ganze Zeile genügt.
                return new Rectangle(row.x, row.y + row.height - 2, Math.max(row.width, getWidth() - row.x), 2);
            }
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) path.getLastPathComponent();
            int index = location.getChildIndex();
            Rectangle near;
            int y;
            if (index < parent.getChildCount()) {
                near = getPathBounds(path.pathByAddingChild(parent.getChildAt(index)));
                y = near == null ? -1 : near.y - 1;
            } else if (parent.getChildCount() > 0) {
                near = getPathBounds(path.pathByAddingChild(parent.getChildAt(parent.getChildCount() - 1)));
                y = near == null ? -1 : near.y + near.height - 1;
            } else {
                near = getPathBounds(path);
                y = near == null ? -1 : near.y + near.height - 1;
            }
            return near == null ? null : new Rectangle(near.x, y, getWidth() - near.x, 3);
        }
    }

    /** Zieht Ordner und Einträge über ihre IDs. */
    private final class Transfer extends TransferHandler {
        @Override
        public int getSourceActions(JComponent component) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent component) {
            List<T> selection = selection();
            if (selection.isEmpty() || filter != null) {
                return null;
            }
            dragged = selection;
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{FLAVOR};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return FLAVOR.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) {
                        throw new UnsupportedFlavorException(flavor);
                    }
                    return Collections.unmodifiableList(selection);
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            boolean ok = target(support) != null;
            tree.repaint();
            return ok;
        }

        @Override
        public boolean importData(TransferSupport support) {
            Target target = target(support);
            return target != null && move(dragged, target);
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            dragged = null;
            tree.repaint();
        }

        private Target target(TransferSupport support) {
            if (dragged == null || filter != null || !support.isDrop() || !support.isDataFlavorSupported(FLAVOR)) {
                return null;
            }
            JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation();
            TreePath path = location.getPath();
            if (path == null) {
                return new Target(null, -1); // unterhalb der letzten Zeile: oberste Ebene, ans Ende
            }
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            T onEntry = entryOf(node);
            T parent = null;
            int index = -1;
            if (onEntry != null && !shape.isFolder(onEntry)) {
                DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node.getParent();
                parent = entryOf(parentNode);
                index = parentNode.getIndex(node);
            }
            return FolderTree.this.target(dragged, onEntry, parent, index, location.getChildIndex());
        }
    }
}
