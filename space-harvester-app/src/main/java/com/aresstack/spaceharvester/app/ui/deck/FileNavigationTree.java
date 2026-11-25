package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.deck.files.FileEntry;
import com.aresstack.spaceharvester.deck.files.FileSource;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/** Present the existing browser results as a navigable tree without performing filesystem I/O. */
final class FileNavigationTree extends JTree {
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
    private final Set<String> loaded = new HashSet<>();
    private final Consumer<FileEntry> open;
    private boolean updating;

    FileNavigationTree(Consumer<FileEntry> open) {
        this.open = open;
        setModel(model);
        setRootVisible(false);
        setShowsRootHandles(true);
        setToggleClickCount(0);
        setFont(Comic.regular(13f));
        getAccessibleContext().setAccessibleName("Dateiexplorer");
        setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
                Object item = ((DefaultMutableTreeNode) value).getUserObject();
                if (item instanceof FileEntry entry) {
                    setText(entry.name());
                    setIcon(entry.directory() ? FilesIcons.folder() : FilesIcons.forName(entry.name()));
                    setToolTipText(entry.location());
                } else {
                    setIcon(null);
                    setToolTipText(null);
                }
                putClientProperty("html.disable", Boolean.TRUE);
                return this;
            }
        });
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                FileEntry entry = entry(event.getPath());
                if (!updating && entry != null && entry.directory() && !loaded.contains(entry.location())) {
                    open.accept(entry);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    open(entry(getPathForLocation(event.getX(), event.getY())));
                }
            }
        });
        getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "open-file");
        getActionMap().put("open-file", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                open(entry(getSelectionPath()));
            }
        });
    }

    private void open(FileEntry entry) {
        if (entry != null) {
            open.accept(entry);
        }
    }

    TreePath pathFor(String location) {
        var node = nodes.get(location);
        return node != null && node.getRoot() == root ? new TreePath(node.getPath()) : null;
    }

    private static FileEntry entry(TreePath path) {
        if (path == null) {
            return null;
        }
        Object item = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return item instanceof FileEntry file ? file : null;
    }

    void showFolder(FileSource source, String folder, List<FileEntry> entries) {
        updating = true;
        try {
            var parent = ensureFolder(source, folder);
            var expanded = getExpandedDescendants(new TreePath(root.getPath()));
            List<TreePath> previous = expanded == null ? List.of() : java.util.Collections.list(expanded);
            parent.removeAllChildren();
            for (FileEntry entry : entries) {
                var child = nodes.computeIfAbsent(entry.location(), ignored -> node(entry));
                child.setUserObject(entry);
                parent.add(child);
            }
            loaded.add(folder);
            model.nodeStructureChanged(parent);
            expandPath(new TreePath(root.getPath()));
            for (TreePath path : previous) {
                var last = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (last.getRoot() == root) {
                    expandPath(new TreePath(last.getPath()));
                }
            }
            var path = new TreePath(parent.getPath());
            makeVisible(path);
            expandPath(path);
        } finally {
            updating = false;
        }
    }

    private DefaultMutableTreeNode ensureFolder(FileSource source, String folder) {
        ArrayDeque<String> ancestors = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        String current = folder;
        while (current != null && seen.add(current)) {
            var existing = nodes.get(current);
            if (existing != null && existing.getRoot() == root) {
                break;
            }
            ancestors.push(current);
            current = source.parent(current).orElse(null);
        }
        var parent = current == null ? root : nodes.getOrDefault(current, root);
        while (!ancestors.isEmpty()) {
            String next = ancestors.pop();
            var child = nodes.computeIfAbsent(next, key -> node(new FileEntry(
                    FilesWorkspace.folderName(key), key, true, 0, null)));
            if (parent.getChildCount() == 1 && ((DefaultMutableTreeNode) parent.getFirstChild()).getUserObject() instanceof String) {
                parent.removeAllChildren();
            }
            parent.add(child);
            model.nodeStructureChanged(parent);
            parent = child;
        }
        return parent;
    }

    private static DefaultMutableTreeNode node(FileEntry entry) {
        var node = new DefaultMutableTreeNode(entry, entry.directory());
        if (entry.directory()) {
            node.add(new DefaultMutableTreeNode("Aufklappen zum Laden", false));
        }
        return node;
    }
}
