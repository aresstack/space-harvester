package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.NavigationTreeIcons;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import com.aresstack.spaceharvester.mainframe.dependency.CallNode;
import com.aresstack.spaceharvester.mainframe.dependency.Relation;
import com.aresstack.spaceharvester.mainframe.dependency.SourceRelations;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Die Beziehungen-Leiste links neben dem Editor wie der {@code LeftDrawer} von MainframeMate: oben „Ressourcen“
 * (Daten, Data Areas, Datasets), darunter die Aufrufhierarchie mit „Callees“ (was dieser Quelltext aufruft) und
 * „Caller“ (wer ihn aufruft). Je Reiter: nach Art gruppieren, Arten filtern („Alle anzeigen“, „Auswahl umkehren“,
 * Häkchen je Art) und ein Namens-Ausdruck. Einfachklick springt zur Zeile (bzw. öffnet ein Ziel ohne Zeile),
 * Doppelklick öffnet das Ziel; Rechtsklick bietet „Im Browser anzeigen“.
 */
final class RelationsPanel extends JPanel {

    static final String CALLEES = "Callees";
    static final String CALLERS = "Caller";
    static final String GROUP_CALLEES_KEY = "deck.relations.group.callees";
    static final String GROUP_CALLERS_KEY = "deck.relations.group.callers";

    /** Was ein Klick auslöst; die Oberfläche des Editors entscheidet, wie geöffnet wird. */
    interface Actions {
        /** Zeile in einer Quelle; {@code sourceFile == null} = das offene Dokument. */
        void line(String sourceFile, int line);

        /** Ziel öffnen ({@code ndv://}, {@code nat-jcl://}, {@code sysfunc://}, http(s)). */
        void open(String target);
    }

    private final Actions actions;
    private final DefaultMutableTreeNode resourcesRoot = new DefaultMutableTreeNode("Ressourcen");
    private final DefaultTreeModel resourcesModel = new DefaultTreeModel(resourcesRoot);
    private final JTree resourcesTree = new JTree(resourcesModel);
    private final JLabel resourcesStatus = Comic.muted(" ");
    private final SegmentedToggle sides = new SegmentedToggle().segment(CALLEES).segment(CALLERS);
    private final Side callees = new Side(CALLEES, GROUP_CALLEES_KEY, "(keine ausgehenden Aufrufe)");
    private final Side callers = new Side(CALLERS, GROUP_CALLERS_KEY, "(keine eingehenden Aufrufe)");
    private final CardLayout sideCards = new CardLayout();
    private final JPanel sideHolder = new JPanel(sideCards);
    private final JSplitPane split;
    private final JPanel resourcesView;
    private final JPanel callsView;

    RelationsPanel(Actions actions) {
        super(new BorderLayout());
        this.actions = actions;
        setOpaque(true);
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Comic.LINE));

        resourcesTree.setRootVisible(false);
        resourcesTree.setShowsRootHandles(true);
        resourcesTree.setFont(Comic.regular(13f));
        resourcesTree.setCellRenderer(new Renderer());
        resourcesTree.getAccessibleContext().setAccessibleName("Ressourcen");
        ToolTipManager.sharedInstance().registerComponent(resourcesTree);
        resourcesTree.addMouseListener(clicks(resourcesTree));
        JPanel top = resourcesView = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        top.add(Comic.caption("Ressourcen"), BorderLayout.NORTH);
        top.add(noHorizontal(Comic.scroll(resourcesTree)), BorderLayout.CENTER);
        top.add(resourcesStatus, BorderLayout.SOUTH);

        sideHolder.setOpaque(false);
        sideHolder.add(callees.panel, CALLEES);
        sideHolder.add(callers.panel, CALLERS);
        sides.onSelect(name -> sideCards.show(sideHolder, name));
        JPanel bottom = callsView = new JPanel(new BorderLayout(0, 6));
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        JPanel sideRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sideRow.setOpaque(false);
        sideRow.add(sides);
        bottom.add(sideRow, BorderLayout.NORTH);
        bottom.add(sideHolder, BorderLayout.CENTER);

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.6);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(6);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);
        loading();
    }

    private boolean hasData;

    /**
     * Löst Ressourcen und Aufrufhierarchie heraus, damit ein Arbeitsbereich sie in seine Seitenleiste legt (Angelo 24.11.:
     * nichts neben dem Quelltext, eine Seitenleiste mit Reitern in der Fußleiste). Die Namensfelder entfallen dann,
     * gefiltert wird über die Suche oben in der Seitenleiste ({@link #names(String)}).
     */
    JComponent[] detach() {
        remove(split);
        split.removeAll();
        callees.nameFrame.setVisible(false);
        callers.nameFrame.setVisible(false);
        return new JComponent[] {resourcesView, callsView};
    }

    /** Namens-Ausdruck für beide Seiten (Callees und Caller), aus der Suche der Seitenleiste. */
    void names(String regex) {
        callees.nameField.setText(regex);
        callers.nameField.setText(regex);
    }

    boolean hasData() {
        return hasData;
    }

    /** Hinweis in der Zählzeile der Ressourcen (z. B. „geht nur mit NDV-Verbindung“). */
    void notice(String text) {
        resourcesStatus.setText(text);
    }

    /** Während die Auswertung läuft. */
    void loading() {
        placeholder(resourcesRoot, resourcesModel, "⏳ Lade Beziehungen…");
        resourcesStatus.setText(" ");
        callees.loading();
        callers.loading();
    }

    private SourceRelations.Analysis shownAnalysis;
    private String resourcesQuery = "";

    /** Suche der Seitenleiste über den Ressourcen. */
    void resources(String query) {
        resourcesQuery = query == null ? "" : query;
        if (shownAnalysis != null) {
            show(shownAnalysis);
        }
    }

    /** Zeigt eine fertige Auswertung. */
    void show(SourceRelations.Analysis analysis) {
        shownAnalysis = analysis;
        hasData = analysis != SourceRelations.Analysis.NONE;
        SourceRelations.Resources resources = analysis.resources();
        resourcesRoot.removeAllChildren();
        if (resources.sections().isEmpty()) {
            resourcesRoot.add(new DefaultMutableTreeNode(resources.placeholder() == null ? "(keine Daten)"
                    : resources.placeholder()));
            resourcesStatus.setText(" ");
        } else {
            for (Map.Entry<String, List<Relation>> section : resources.sections().entrySet()) {
                DefaultMutableTreeNode group = new DefaultMutableTreeNode(
                        section.getKey() + " (" + section.getValue().size() + ")");
                section.getValue().forEach(relation -> group.add(new DefaultMutableTreeNode(relation)));
                resourcesRoot.add(group);
            }
            resourcesStatus.setText(resources.total() + " Einträge in " + resources.sections().size() + " Gruppen");
            TreeQuery.prune(resourcesRoot, resourcesQuery, String::valueOf);
        }
        resourcesModel.reload();
        expand(resourcesTree, Integer.MAX_VALUE);
        callees.show(analysis.callees());
        callers.show(analysis.callers());
    }

    // ---- für Tests --------------------------------------------------------------------------------------------------

    JTree resourceTree() {
        return resourcesTree;
    }

    JTree callTree(String side) {
        return CALLERS.equals(side) ? callers.tree : callees.tree;
    }

    List<String> resourceLabels() {
        return labels(resourcesRoot);
    }

    String resourcesStatus() {
        return resourcesStatus.getText();
    }

    List<String> labels(String side) {
        return labels(side(side).root);
    }

    String sideTitle(String side) {
        return side(side).title();
    }

    void hideKind(String side, String kind, boolean hidden) {
        Side s = side(side);
        if (hidden) {
            s.hidden.add(kind);
        } else {
            s.hidden.remove(kind);
        }
        s.apply();
    }

    void group(String side, boolean on) {
        side(side).group(on);
    }

    void nameFilter(String side, String regex) {
        side(side).nameField.setText(regex);
    }

    void select(String side) {
        sides.select(side);
    }

    void clickFirst(String side, int clicks) {
        Side s = side(side);
        s.tree.setSelectionRow(0);
        Object value = ((DefaultMutableTreeNode) s.tree.getPathForRow(0).getLastPathComponent()).getUserObject();
        if (value instanceof Item item) {
            activate(item.line, item.sourceFile, item.target, clicks);
        }
    }

    /** Klickt den ersten Eintrag, dessen Text mit {@code prefix} beginnt (Tests). */
    void clickLabel(String side, String prefix, int clicks) {
        java.util.Enumeration<javax.swing.tree.TreeNode> all = side(side).root.preorderEnumeration();
        while (all.hasMoreElements()) {
            Object value = ((DefaultMutableTreeNode) all.nextElement()).getUserObject();
            if (value instanceof Item item && item.label.startsWith(prefix)) {
                activate(item.line, item.sourceFile, item.target, clicks);
                return;
            }
        }
    }

    private Side side(String name) {
        return CALLERS.equals(name) ? callers : callees;
    }

    // ---- Klicks ----------------------------------------------------------------------------------------------------

    private MouseAdapter clicks(JTree tree) {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null || event.isPopupTrigger() || !javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (value instanceof Relation relation) {
                    activate(relation.line(), null, relation.target(), event.getClickCount());
                } else if (value instanceof Item item) {
                    activate(item.line, item.sourceFile, item.target, event.getClickCount());
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                menu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                menu(event);
            }

            private void menu(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) {
                    return;
                }
                tree.setSelectionPath(path);
                Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                String target = value instanceof Relation relation ? relation.target()
                        : value instanceof Item item ? item.target : null;
                JPopupMenu menu = new JPopupMenu();
                JMenuItem browser = new JMenuItem("Im Browser anzeigen");
                boolean web = target != null && (target.startsWith("http://") || target.startsWith("https://"));
                browser.setEnabled(web);
                browser.addActionListener(e -> actions.open(target));
                menu.add(browser);
                if (target != null && !web) {
                    JMenuItem open = new JMenuItem(target.startsWith("sysfunc://") ? "Wikipedia-Artikel öffnen"
                            : "Öffnen");
                    open.addActionListener(e -> actions.open(target));
                    menu.add(open);
                }
                menu.show(tree, event.getX(), event.getY());
            }
        };
    }

    /** Wie {@code handleHierarchyClick}: einfach = Zeile (ohne Zeile: Ziel öffnen), doppelt = Ziel öffnen. */
    private void activate(int line, String sourceFile, String target, int clicks) {
        if (clicks == 1) {
            if (line > 0) {
                actions.line(sourceFile, line);
            } else if (target != null && !target.isEmpty()) {
                actions.open(target);
            }
        } else if (clicks == 2 && target != null && !target.isEmpty()) {
            actions.open(target);
        }
    }

    // ---- ein Hierarchie-Reiter ---------------------------------------------------------------------------------------

    /** Ein Eintrag im Hierarchiebaum. */
    private record Item(String label, String target, boolean recursive, int line, String sourceFile, String kind) {

        @Override
        public String toString() {
            return label;
        }
    }

    private final class Side {

        final String name;
        final String groupKey;
        final String emptyText;
        final Set<String> hidden = new HashSet<>();
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        final DefaultTreeModel model = new DefaultTreeModel(root);
        final JTree tree = new JTree(model);
        final JLabel status = Comic.muted(" ");
        final IconButton groupToggle = new IconButton(EditorIcons.group(), "Nach Referenz-Typ gruppieren (CALLNAT, PERFORM, …)");
        final IconButton kindFilter = new IconButton(EditorIcons.filterLines(),
                "Referenz-Typen filtern (CALLNAT, PERFORM, INCLUDE, …)");
        final JTextField nameField = Comic.field(10);
        final JPanel panel = new JPanel(new BorderLayout(0, 6));
        final InputFrame nameFrame;
        SourceRelations.Hierarchy data;
        String title;

        Side(String name, String groupKey, String emptyText) {
            this.name = name;
            this.groupKey = groupKey;
            this.emptyText = emptyText;
            this.title = name;
            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.setFont(Comic.regular(13f));
            tree.setCellRenderer(new Renderer(CALLERS.equals(name)));
            tree.getAccessibleContext().setAccessibleName(name);
            ToolTipManager.sharedInstance().registerComponent(tree);
            tree.addMouseListener(clicks(tree));
            groupToggle.marked(AppState.shared().getBoolean(groupKey, false));
            groupToggle.addActionListener(e -> group(!groupToggle.marked()));
            kindFilter.addActionListener(e -> kindMenu().show(kindFilter, 0, kindFilter.getHeight()));
            nameField.setToolTipText("Dateinamen-Ausdruck — filtert die Treffer nach Quelldatei");
            nameField.getAccessibleContext().setAccessibleName("Namens-Ausdruck " + name);
            nameField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    apply();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    apply();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    apply();
                }
            });
            nameFrame = InputFrame.search(nameField);
            JPanel tools = new JPanel(new BorderLayout(4, 0));
            tools.setOpaque(false);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
            buttons.setOpaque(false);
            buttons.add(groupToggle);
            buttons.add(kindFilter);
            tools.add(buttons, BorderLayout.WEST);
            tools.add(nameFrame, BorderLayout.CENTER);
            panel.setOpaque(false);
            panel.add(tools, BorderLayout.NORTH);
            panel.add(noHorizontal(Comic.scroll(tree)), BorderLayout.CENTER);
            panel.add(status, BorderLayout.SOUTH);
        }

        String title() {
            return title;
        }

        void loading() {
            data = null;
            placeholder(root, model, "⏳ Lade Hierarchie…");
            status.setText(" ");
            title(name);
        }

        void show(SourceRelations.Hierarchy hierarchy) {
            data = hierarchy;
            apply();
        }

        void group(boolean on) {
            groupToggle.marked(on);
            AppState.shared().put(groupKey, on);
            apply();
        }

        /** Wie {@code applyCalleesFilter}/{@code applyCallersFilter}. */
        void apply() {
            root.removeAllChildren();
            if (data == null) {
                return;
            }
            if (data.root().isEmpty()) {
                placeholder(root, model, data.placeholder() == null ? emptyText : data.placeholder());
                status.setText(" ");
                title(name);
                kindFilter.marked(!hidden.isEmpty());
                return;
            }
            Pattern names = namePattern();
            List<CallNode> children = data.root().get().children();
            int[] counts = groupToggle.marked() ? addGrouped(root, children, names) : addFiltered(root, children, names);
            boolean filterActive = !hidden.isEmpty() || names != null;
            if (root.getChildCount() == 0) {
                root.add(new DefaultMutableTreeNode(!children.isEmpty() && filterActive
                        ? "(keine Treffer für Filter)" : emptyText));
            }
            model.reload();
            expand(tree, 20);
            int total = counts[0];
            int visible = counts[1];
            title(filterActive ? name + " (" + visible + "/" + total + ")" : name + " (" + total + ")");
            String object = data.objectName();
            status.setText(object == null ? " " : filterActive ? object + " — " + visible + "/" + total + " Knoten"
                    : object + " — " + total + " Knoten");
            kindFilter.marked(!hidden.isEmpty());
            nameField.setBackground(names == null && !nameField.getText().isBlank() ? Comic.CHANGE_REMOVED
                    : AppColors.of("app.RelationsPanel.apply"));
        }

        private void title(String text) {
            title = text;
            sides.count(name, data == null || data.root().isEmpty() ? 0 : data.root().get().descendants(),
                    Comic.PETROL, text, text);
        }

        private Pattern namePattern() {
            String text = nameField.getText().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Pattern.compile(text, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                return null;
            }
        }

        private int[] addFiltered(DefaultMutableTreeNode parent, List<CallNode> children, Pattern names) {
            int total = 0;
            int visible = 0;
            for (CallNode child : children) {
                total += 1 + child.descendants();
                boolean kindHidden = child.kind() != null && hidden.contains(child.kind());
                if (kindHidden) {
                    continue;
                }
                DefaultMutableTreeNode node = filtered(child, names);
                if (node != null) {
                    visible += countNodes(node);
                    parent.add(node);
                }
            }
            return new int[] {total, visible};
        }

        /**
         * Knoten mit gefilterten Kindern, oder {@code null}, wenn weder er noch ein Nachfahre zum Namen passt. Passt
         * nur ein Nachfahre, bleiben die Vorfahren als Pfad dorthin stehen.
         */
        private DefaultMutableTreeNode filtered(CallNode child, Pattern names) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(item(child));
            addFiltered(node, child.children(), names);
            return nameMatches(child, names) || node.getChildCount() > 0 ? node : null;
        }

        private static int countNodes(DefaultMutableTreeNode node) {
            int count = 1;
            for (int i = 0; i < node.getChildCount(); i++) {
                count += countNodes((DefaultMutableTreeNode) node.getChildAt(i));
            }
            return count;
        }

        private int[] addGrouped(DefaultMutableTreeNode parent, List<CallNode> children, Pattern names) {
            Map<String, List<CallNode>> byKind = new LinkedHashMap<>();
            int total = 0;
            for (CallNode child : children) {
                total += 1 + child.descendants();
                byKind.computeIfAbsent(child.kind() == null ? "—" : child.kind(), k -> new ArrayList<>()).add(child);
            }
            int visible = 0;
            for (Map.Entry<String, List<CallNode>> entry : byKind.entrySet()) {
                if (hidden.contains(entry.getKey())) {
                    continue;
                }
                DefaultMutableTreeNode group = new DefaultMutableTreeNode();
                int groupVisible = 0;
                for (CallNode child : entry.getValue()) {
                    DefaultMutableTreeNode node = filtered(child, names);
                    if (node == null) {
                        continue;
                    }
                    visible += countNodes(node);
                    groupVisible++;
                    group.add(node);
                }
                if (groupVisible == 0) {
                    continue;
                }
                group.setUserObject(entry.getKey() + " (" + groupVisible + ")");
                parent.add(group);
            }
            return new int[] {total, visible};
        }

        private JPopupMenu kindMenu() {
            List<String> kinds = new ArrayList<>(kinds());
            JPopupMenu menu = new JPopupMenu();
            JMenuItem all = new JMenuItem("Alle anzeigen");
            all.addActionListener(e -> {
                hidden.clear();
                apply();
            });
            menu.add(all);
            JMenuItem invert = new JMenuItem("Auswahl umkehren");
            invert.addActionListener(e -> {
                Set<String> next = new HashSet<>();
                for (String kind : kinds) {
                    if (!hidden.contains(kind)) {
                        next.add(kind);
                    }
                }
                hidden.clear();
                hidden.addAll(next);
                apply();
            });
            menu.add(invert);
            menu.addSeparator();
            if (kinds.isEmpty()) {
                JMenuItem none = new JMenuItem("(keine Typen verfügbar)");
                none.setEnabled(false);
                menu.add(none);
            }
            for (String kind : kinds) {
                JCheckBoxMenuItem item = new JCheckBoxMenuItem(kind, !hidden.contains(kind));
                item.setIcon(NavigationTreeIcons.relation(kind));
                item.addActionListener(e -> {
                    if (item.isSelected()) {
                        hidden.remove(kind);
                    } else {
                        hidden.add(kind);
                    }
                    apply();
                });
                menu.add(item);
            }
            return menu;
        }

        private Set<String> kinds() {
            Set<String> out = new LinkedHashSet<>();
            if (data != null && data.root().isPresent()) {
                collectKinds(data.root().get().children(), out);
            }
            return out;
        }
    }

    private static void collectKinds(List<CallNode> children, Set<String> out) {
        for (CallNode child : children) {
            if (child.kind() != null && !child.kind().isEmpty()) {
                out.add(child.kind());
            }
            collectKinds(child.children(), out);
        }
    }

    private static Item item(CallNode node) {
        return new Item(node.label(), node.target(), node.recursive(), node.line(), node.sourceFile(), node.kind());
    }

    /** Wie {@code nameMatches}: Quelldatei, sonst Ziel, sonst Text gegen den Ausdruck. */
    private static boolean nameMatches(CallNode node, Pattern names) {
        if (names == null) {
            return true;
        }
        String candidate = lastSegment(node.sourceFile());
        if (candidate == null) {
            candidate = lastSegment(node.target());
        }
        if (candidate == null) {
            candidate = node.label();
        }
        return names.matcher(candidate).find();
    }

    private static String lastSegment(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String tail = sep >= 0 ? path.substring(sep + 1) : path;
        int q = tail.indexOf('?');
        if (q >= 0) {
            tail = tail.substring(0, q);
        }
        int dot = tail.lastIndexOf('.');
        if (dot > 0) {
            tail = tail.substring(0, dot);
        }
        return tail.isEmpty() ? null : tail;
    }

    /** Kein waagerechter Balken: lange Einträge zeigen den vollen Text im Tooltip. */
    private static javax.swing.JScrollPane noHorizontal(javax.swing.JScrollPane scroll) {
        scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private static void placeholder(DefaultMutableTreeNode root, DefaultTreeModel model, String text) {
        root.removeAllChildren();
        root.add(new DefaultMutableTreeNode(text));
        model.reload();
    }

    private static void expand(JTree tree, int max) {
        for (int i = 0; i < Math.min(tree.getRowCount(), max); i++) {
            tree.expandRow(i);
        }
    }

    private static List<String> labels(DefaultMutableTreeNode root) {
        List<String> out = new ArrayList<>();
        java.util.Enumeration<javax.swing.tree.TreeNode> all = root.preorderEnumeration();
        all.nextElement();
        while (all.hasMoreElements()) {
            out.add(String.valueOf(((DefaultMutableTreeNode) all.nextElement()).getUserObject()));
        }
        return out;
    }

    /**
     * Wie {@code RelationTreeCellRenderer}: keine Ordner-Symbole; Natural-Programme fett grün, Systemfunktionen fett
     * blau, sonst das Symbol der Art vor dem Text.
     */
    private static final class Renderer extends DefaultTreeCellRenderer {

        private static final Color NATURAL = AppColors.of("app.RelationsPanel.natural");
        private static final Color SYSTEM_FUNCTION = AppColors.of("app.RelationsPanel.systemFunction");

        private final boolean incoming;

        Renderer() {
            this(false);
        }

        Renderer(boolean incoming) {
            this.incoming = incoming;
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            setIcon(leaf ? null : NavigationTreeIcons.FOLDER);
            setFont(tree.getFont());
            setToolTipText(null);
            Object item = ((DefaultMutableTreeNode) value).getUserObject();
            String label;
            String target;
            String kind;
            String type;
            if (item instanceof Relation relation) {
                label = relation.label();
                target = relation.target();
                kind = null;
                type = relation.type();
            } else if (item instanceof Item node) {
                label = node.label();
                target = node.target();
                kind = node.kind();
                type = node.recursive() ? "CALL_RECURSIVE" : "CALL_HIERARCHY";
            } else {
                String text = String.valueOf(item);
                setText(NavigationTreeIcons.plainLabel(text));
                setIcon(NavigationTreeIcons.legacyIcon(text, leaf ? null : NavigationTreeIcons.FOLDER));
                return this;
            }
            boolean natural = (type != null && type.startsWith("JCL_NAT_"))
                    || (target != null && target.startsWith("nat-jcl://"));
            boolean systemFunction = target != null && target.startsWith("sysfunc://");
            if (natural) {
                setFont(tree.getFont().deriveFont(Font.BOLD));
                if (!selected) {
                    setForeground(NATURAL);
                }
                setText(NavigationTreeIcons.plainLabel(label));
                setIcon(NavigationTreeIcons.PAGE);
                setToolTipText("Natural-Programm — Doppelklick zum Öffnen über NDV");
            } else if (systemFunction) {
                setFont(tree.getFont().deriveFont(Font.BOLD));
                if (!selected) {
                    setForeground(SYSTEM_FUNCTION);
                }
                setText(NavigationTreeIcons.plainLabel(label));
                setIcon(NavigationTreeIcons.PAGE);
                setToolTipText("Systemfunktion — Doppelklick öffnet den Wikipedia-Artikel");
            } else {
                setText(NavigationTreeIcons.plainLabel(label));
                setIcon(NavigationTreeIcons.legacyIcon(label,
                        incoming ? NavigationTreeIcons.INCOMING : NavigationTreeIcons.relation(kind == null ? type : kind)));
                if (type != null && type.startsWith("DEPENDENCY_")) {
                    setToolTipText(target);
                } else if (kind != null) {
                    setToolTipText(kind + (target != null ? "  —  " + target : ""));
                }
            }
            if (getToolTipText() == null) {
                setToolTipText(NavigationTreeIcons.plainLabel(label));
            }
            if (type != null && type.equals("CALL_RECURSIVE") && !selected) {
                setForeground(Comic.MUTED);
            }
            return this;
        }
    }

    /** Zum Verdrahten von außen (Tests). */
    static Actions actions(Consumer<String> lines, Consumer<String> opens) {
        return new Actions() {
            @Override
            public void line(String sourceFile, int line) {
                lines.accept((sourceFile == null ? "" : sourceFile + ":") + line);
            }

            @Override
            public void open(String target) {
                opens.accept(target);
            }
        };
    }
}