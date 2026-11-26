package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.NavigationTreeIcons;
import com.aresstack.spaceharvester.deck.ftp.MvsPath;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/** Maintain the loaded branches and ancestor path of a single FTP session. */
final class FtpNavigationTree extends JTree {
    private static final String UNLOADED = "Aufklappen zum Laden";
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
    private final java.util.Set<String> loaded = new java.util.HashSet<>();
    private final java.util.Set<String> listed = new java.util.HashSet<>();
    private final Map<String, List<RemoteEntry>> branches = new HashMap<>();
    private final Consumer<String> expand;
    private boolean updating;
    private DefaultMutableTreeNode currentFolder;

    FtpNavigationTree(Consumer<String> expand) {
        this.expand = expand;
        setModel(model);
        setRootVisible(false);
        setToggleClickCount(0);
        setShowsRootHandles(true);
        setFont(Comic.regular(13f));
        getAccessibleContext().setAccessibleName("FTP-Navigationsbaum");
        setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
                Object item = ((DefaultMutableTreeNode) value).getUserObject();
                if (item instanceof RemoteEntry entry) {
                    setText(entry.name());
                    setFont(value == currentFolder ? tree.getFont().deriveFont(java.awt.Font.BOLD) : tree.getFont());
                    setIcon(entry.directory() ? NavigationTreeIcons.FOLDER : NavigationTreeIcons.PAGE);
                    setToolTipText(entry.path());
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
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                if (!updating && node.getUserObject() instanceof RemoteEntry entry && !loaded.contains(entry.path())) {
                    FtpNavigationTree.this.expand.accept(entry.path());
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) { }
        });
    }

    void showPath(String path, boolean mvs, List<RemoteEntry> entries, List<RemoteEntry> visible) {
        updating = true;
        try {
            DefaultMutableTreeNode parent = ensurePath(path, mvs);
            boolean changedFolder = currentFolder != parent;
            currentFolder = parent;
            loaded.add(path);
            List<RemoteEntry> previousEntries = branches.put(path, List.copyOf(entries));
            java.util.Set<String> actualPaths = new java.util.HashSet<>();
            for (RemoteEntry entry : entries) {
                actualPaths.add(entry.path());
                listed.add(entry.path());
            }
            if (previousEntries != null) {
                for (RemoteEntry entry : previousEntries) {
                    if (!actualPaths.contains(entry.path())) {
                        DefaultMutableTreeNode removed = nodes.get(entry.path());
                        if (removed != null) {
                            forget(removed);
                        }
                    }
                }
            }
            // Preserve expanded descendant nodes rather than rebuilding the whole tree.
            List<TreePath> expanded = expandedPaths();
            parent.removeAllChildren();
            for (RemoteEntry entry : visible) {
                DefaultMutableTreeNode child = nodes.get(entry.path());
                if (child == null) {
                    child = node(entry);
                } else {
                    child.setUserObject(entry);
                }
                parent.add(child);
                nodes.put(entry.path(), child);
            }
            model.nodeStructureChanged(parent);
            expandPath(new TreePath(root.getPath()));
            for (TreePath previous : expanded) {
                DefaultMutableTreeNode last = (DefaultMutableTreeNode) previous.getLastPathComponent();
                if (last.getRoot() == root) {
                    expandPath(new TreePath(last.getPath()));
                }
            }
            TreePath current = new TreePath(parent.getPath());
            makeVisible(current);
            expandPath(current);
            scrollPathToVisible(current);
            if (changedFolder) {
                setSelectionPath(current);
            }
        } finally {
            updating = false;
        }
    }

    private List<TreePath> expandedPaths() {
        var paths = getExpandedDescendants(new TreePath(root.getPath()));
        return paths == null ? List.of() : java.util.Collections.list(paths);
    }

    private DefaultMutableTreeNode ensurePath(String path, boolean mvs) {
        DefaultMutableTreeNode existing = nodes.get(path);
        if (existing != null && existing.getRoot() == root) {
            return existing;
        }
        ArrayDeque<String> ancestors = new ArrayDeque<>();
        String current = path;
        while (!nodes.containsKey(current) || nodes.get(current).getRoot() != root) {
            ancestors.push(current);
            String parent = parentOf(current, mvs);
            if (parent.equals(current) || mvs && MvsPath.ROOT.equals(parent)) {
                current = null;
                break;
            }
            current = parent;
        }
        DefaultMutableTreeNode parent = current == null ? root : nodes.get(current);
        while (!ancestors.isEmpty()) {
            String next = ancestors.pop();
            DefaultMutableTreeNode child = nodes.get(next);
            if (child == null) {
                child = node(RemoteEntry.directory(label(next, mvs), next));
            }
            if (unloaded(parent)) {
                parent.removeAllChildren();
            }
            parent.add(child);
            nodes.put(next, child);
            model.nodeStructureChanged(parent);
            parent = child;
        }
        return parent;
    }

    private static String parentOf(String path, boolean mvs) {
        if (mvs) {
            return MvsPath.parent(path);
        }
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private static String label(String path, boolean mvs) {
        if (mvs) {
            return MvsPath.unquote(path);
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash == path.length() - 1 ? path : path.substring(slash + 1);
    }

    private static DefaultMutableTreeNode node(RemoteEntry entry) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry, entry.directory());
        if (entry.directory()) {
            node.add(new DefaultMutableTreeNode(UNLOADED, false));
        }
        return node;
    }

    private static boolean unloaded(DefaultMutableTreeNode node) {
        return node.getChildCount() == 1 && UNLOADED.equals(
                ((DefaultMutableTreeNode) node.getFirstChild()).getUserObject());
    }

    private void forget(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof RemoteEntry entry)) {
            return;
        }
        String path = entry.path();
        List<RemoteEntry> cachedChildren = branches.remove(path);
        if (cachedChildren != null) {
            for (RemoteEntry child : cachedChildren) {
                DefaultMutableTreeNode cached = nodes.get(child.path());
                if (cached != null && cached != node) {
                    forget(cached);
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof RemoteEntry remote && nodes.containsKey(remote.path())) {
                forget(child);
            }
        }
        nodes.remove(path);
        loaded.remove(path);
        listed.remove(path);
    }

    TreePath pathFor(String remotePath) {
        DefaultMutableTreeNode node = nodes.get(remotePath);
        return node != null && node.getRoot() == root ? new TreePath(node.getPath()) : null;
    }

    RemoteEntry selectedEntry() {
        TreePath selection = getLeadSelectionPath();
        if (selection == null) {
            return null;
        }
        Object value = ((DefaultMutableTreeNode) selection.getLastPathComponent()).getUserObject();
        return value instanceof RemoteEntry entry ? entry : null;
    }

    void selectCurrentChildren() {
        if (currentFolder == null) {
            return;
        }
        List<TreePath> paths = new ArrayList<>();
        for (int i = 0; i < currentFolder.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) currentFolder.getChildAt(i);
            if (child.getUserObject() instanceof RemoteEntry) {
                paths.add(new TreePath(child.getPath()));
            }
        }
        setSelectionPaths(paths.toArray(TreePath[]::new));
    }

    RemoteEntry selectedListedEntry() {
        RemoteEntry entry = selectedEntry();
        return entry != null && listed.contains(entry.path()) ? entry : null;
    }

    List<RemoteEntry> selectedEntries() {
        TreePath[] paths = getSelectionPaths();
        if (paths == null) {
            return List.of();
        }
        List<RemoteEntry> selected = new ArrayList<>();
        for (TreePath path : paths) {
            Object item = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (item instanceof RemoteEntry entry && listed.contains(entry.path())) {
                selected.add(entry);
            }
        }
        return List.copyOf(selected);
    }

    void removed(List<RemoteEntry> entries) {
        for (RemoteEntry entry : entries) {
            DefaultMutableTreeNode node = nodes.get(entry.path());
            if (node != null) {
                if (node.getParent() != null) {
                    model.removeNodeFromParent(node);
                }
                forget(node);
            }
        }
    }

    void expandLoaded(boolean open) {
        if (open) {
            updating = true;
            try {
                for (int row = 0; row < getRowCount(); row++) {
                    TreePath path = getPathForRow(row);
                    if (path != null && !unloaded((DefaultMutableTreeNode) path.getLastPathComponent())) {
                        expandPath(path);
                    }
                }
            } finally {
                updating = false;
            }
        } else {
            for (int row = getRowCount() - 1; row >= 0; row--) {
                collapseRow(row);
            }
        }
    }
}
