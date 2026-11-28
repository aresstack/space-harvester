package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.SideFoot;
import com.aresstack.spaceharvester.app.ui.controls.SideHead;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.app.ui.deck.editor.EditorIcons;
import com.aresstack.spaceharvester.app.ui.deck.visual.VisualIcons;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Die Seitenleiste eines Bereichs mit Code-Viewer (NDV, FTP; Angelo 24.11.2026): oben die eine Suche des Reiters
 * ({@link SideHead}), darunter der Reiter, unten in der gemeinsamen Fußleiste ({@link SideFoot}) die Reiter als
 * Symbolknöpfe. Erster Reiter ist der des Bereichs (Bibliotheken, Datasets); dann Ressourcen, Callees/Caller und
 * Gliederung des gezeigten Editors ({@link DocumentEditor#detachSidePanels()}), dazwischen eigene Reiter des Bereichs.
 * In der Diagramm-Ansicht („Visuell“) kommt der Reiter „Diagramm“ mit deren Werkzeugen dazu; nichts steht rechts
 * neben dem Quelltext oder dem Diagramm.
 */
final class EditorSideBar extends JPanel {

    static final String RESOURCES = "Ressourcen";
    static final String CALLS = "Callees/Caller";
    static final String OUTLINE = "Gliederung";
    /** Nur in der Diagramm-Ansicht: deren Werkzeuge; danach zurück zum vorigen Reiter. */
    static final String DIAGRAM = "Diagramm";

    /** Der Reiter des Bereichs: fertiger Kopf (Überschrift, Filter, Knöpfe), Inhalt, Knöpfe links in der Fußleiste. */
    record Home(String name, Icon icon, String tooltip, JComponent head, JComponent content, JComponent footWest) {
    }

    /** Ein weiterer Reiter des Bereichs zwischen Callees/Caller und Gliederung, mit eigenem Filter. */
    record Extra(String name, Icon icon, String tooltip, String placeholder, JComponent content,
                 Consumer<String> filter) {
    }

    private final String home;
    private final JComponent footWest;
    private final CardLayout cards = new CardLayout();
    private final JPanel stage = new JPanel(cards);
    private final CardLayout headCards = new CardLayout();
    private final JPanel heads = new JPanel(headCards);
    private final Map<String, IconButton> tabs = new LinkedHashMap<>();
    private final Map<String, PlaceholderField> filters = new LinkedHashMap<>();
    private final Map<String, Consumer<String>> filterActions = new LinkedHashMap<>();
    private final JPanel resourcesHolder = holder();
    private final JPanel callsHolder = holder();
    private final JPanel outlineHolder = holder();
    private final JPanel diagramHolder = holder();
    private final Map<DocumentEditor, JComponent> diagramOf = new java.util.WeakHashMap<>();
    private final Consumer<String> remember;
    private DocumentEditor shown;
    private String tab;
    private String beforeDiagram;

    /**
     * @param remembered der zuletzt gewählte Reiter (Diagramm zählt nicht); unbekannt: der Reiter des Bereichs
     * @param remember   merkt den gewählten Reiter
     */
    EditorSideBar(Home home, List<Extra> extras, String remembered, Consumer<String> remember) {
        this(home, true, extras, remembered, remember);
    }

    /**
     * @param callsTab ob der Reiter Callees/Caller des Editors erscheint; NDV zeigt Aufrufe und Aufrufer in einem
     *                 eigenen Reiter aus dem Graphen der Bibliothek und braucht ihn nicht doppelt (Angelo 28.11.2026)
     */
    EditorSideBar(Home home, boolean callsTab, List<Extra> extras, String remembered, Consumer<String> remember) {
        super(new BorderLayout(0, 6));
        this.home = home.name();
        this.footWest = home.footWest();
        this.remember = remember;
        this.beforeDiagram = home.name();
        setOpaque(false);
        heads.setOpaque(false);
        stage.setOpaque(false);

        heads.add(home.head(), home.name());
        stage.add(home.content(), home.name());
        tabs.put(home.name(), new IconButton(home.icon(), home.tooltip()));

        editorTab(RESOURCES, Glyph.TABLE, "Ressourcen: Daten, Data Areas, Datasets des geöffneten Quelltexts",
                "Ressourcen filtern", resourcesHolder, text -> withShown(editor -> editor.filterResources(text)));
        if (callsTab) {
            editorTab(CALLS, EditorIcons.relations(), "Callees/Caller: was der Quelltext aufruft und wer ihn aufruft",
                    "Callees/Caller filtern (Ausdruck)", callsHolder,
                    text -> withShown(editor -> editor.filterCalls(text)));
        }
        for (Extra extra : extras) {
            editorTab(extra.name(), extra.icon(), extra.tooltip(), extra.placeholder(), extra.content(),
                    extra.filter());
        }
        editorTab(OUTLINE, EditorIcons.outline(), "Gliederung des geöffneten Quelltexts", "Gliederung filtern",
                outlineHolder, text -> withShown(editor -> editor.filterOutline(text)));
        heads.add(new SideHead(DIAGRAM, null), DIAGRAM);
        stage.add(diagramHolder, DIAGRAM);
        tabs.put(DIAGRAM, new IconButton(VisualIcons.eye(),
                "Diagramm: Bearbeiten, Übersicht, Diagrammart, Mindmap-Tiefe, gewähltes Element"));
        tabs.get(DIAGRAM).setVisible(false);
        tabs.forEach((name, button) -> {
            button.getAccessibleContext().setAccessibleName(name);
            button.addActionListener(e -> select(name));
        });

        // Gemeinsame Fußleiste der Seitenleisten (feste Höhe, auf einer Linie mit der Lupenleiste), Reiter mittig.
        JComponent footer = new SideFoot(footWest,
                SideFoot.centered(tabs.values().toArray(Component[]::new)), null);
        add(heads, BorderLayout.NORTH);
        add(stage, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(150, 0));
        select(remembered != null && tabs.containsKey(remembered) && !DIAGRAM.equals(remembered)
                ? remembered : home.name());
    }

    private void editorTab(String name, Icon icon, String tooltip, String placeholder, JComponent content,
                           Consumer<String> action) {
        PlaceholderField field = new PlaceholderField(10, placeholder);
        Comic.style(field);
        field.getAccessibleContext().setAccessibleName(name + " filtern");
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter(name);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter(name);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter(name);
            }
        });
        filters.put(name, field);
        filterActions.put(name, action);
        heads.add(new SideHead(name, field), name);
        stage.add(content, name);
        tabs.put(name, new IconButton(icon, tooltip));
    }

    /** Zeigt einen Reiter samt seiner Suche. */
    void select(String name) {
        if (!tabs.containsKey(name)) {
            return;
        }
        tab = name;
        if (!DIAGRAM.equals(name)) {
            remember.accept(name);
        }
        cards.show(stage, name);
        headCards.show(heads, name);
        tabs.forEach((key, button) -> button.marked(key.equals(name)));
        if (footWest != null) {
            // Die Knöpfe links in der Fußleiste gehören zum Reiter des Bereichs (etwa alles auf-/zuklappen).
            footWest.setVisible(home.equals(name));
        }
    }

    String tab() {
        return tab;
    }

    PlaceholderField filter(String name) {
        return filters.get(name);
    }

    /** Legt Ressourcen, Callees/Caller (falls als Reiter da) und Gliederung von {@code editor} in die Seitenleiste. */
    void show(DocumentEditor editor) {
        shown = editor;
        DocumentEditor.SidePanels panels = editor.detachSidePanels();
        place(resourcesHolder, panels.resources());
        place(callsHolder, panels.calls());
        place(outlineHolder, panels.outline());
        applyFilter(RESOURCES);
        applyFilter(CALLS);
        applyFilter(OUTLINE);
        showDiagram(diagramOf.get(editor), false);
    }

    /**
     * Verdrahtet einen neuen Editor: seine Seitenteile kommen hierher, seine Diagramm-Werkzeuge als Reiter
     * „Diagramm“, solange er die Diagramm-Ansicht zeigt.
     */
    void attach(DocumentEditor editor) {
        editor.detachSidePanels();
        editor.onCenterSide(side -> diagramSide(editor, side));
    }

    private void diagramSide(DocumentEditor editor, JComponent side) {
        if (side == null) {
            diagramOf.remove(editor);
        } else {
            diagramOf.put(editor, side);
        }
        if (editor == shown) {
            showDiagram(side, true);
        }
    }

    private void showDiagram(JComponent side, boolean select) {
        IconButton button = tabs.get(DIAGRAM);
        if (side == null) {
            diagramHolder.removeAll();
            button.setVisible(false);
            if (DIAGRAM.equals(tab)) {
                select(beforeDiagram);
            }
        } else {
            place(diagramHolder, side);
            button.setVisible(true);
            if (select && !DIAGRAM.equals(tab)) {
                beforeDiagram = tab;
                select(DIAGRAM);
            }
        }
        button.getParent().revalidate();
        revalidate();
        repaint();
    }

    private void applyFilter(String name) {
        Consumer<String> action = filterActions.get(name);
        if (action != null) {
            action.accept(filters.get(name).getText());
        }
    }

    private void withShown(Consumer<DocumentEditor> action) {
        if (shown != null) {
            action.accept(shown);
        }
    }

    private static JPanel holder() {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        return holder;
    }

    private static void place(JPanel holder, JComponent panel) {
        if (holder.getComponentCount() == 1 && holder.getComponent(0) == panel) {
            return;
        }
        holder.removeAll();
        holder.add(panel, BorderLayout.CENTER);
        holder.revalidate();
        holder.repaint();
    }
}
