package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;

/**
 * Knopf „Visuell“ (Auge) in der Werkzeugleiste jedes Editors im Command Deck, wie „👁 Visuell“ in MainframeMate:
 * nur bei Großrechner-Quelltext (JCL, COBOL, Natural, DDM) und Mermaid sichtbar; ein Klick tauscht den Text gegen die
 * Diagramm-Ansicht, ein zweiter holt den Text zurück.
 */
public final class VisualAction {

    static final String SHOW = "Visuell: interaktive Diagramm-Ansicht";
    static final String BACK = "Zurück zur Text-Ansicht";

    private final DocumentEditor editor;
    private final EditorSessions editors;
    private final IconButton button;
    private DiagramView view;
    private boolean active;

    private VisualAction(DocumentEditor editor, EditorSessions editors) {
        this.editor = editor;
        this.editors = editors;
        this.button = editor.addAction(SHOW, VisualIcons.eye(), e -> toggle());
        button.getAccessibleContext().setAccessibleName("Visuell");
        refresh();
        editor.onLanguageChanged(this::refresh);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile EditorSessions installed;

    /**
     * Hängt den Knopf an jeden künftig gebauten Editor (beim Start des Command Deck). Der Haken wird nur einmal
     * angemeldet; ein späterer Aufruf ersetzt nur die offenen Dokumente, in denen Aufrufziele gesucht werden.
     */
    public static void install(EditorSessions editors) {
        installed = editors;
        if (INSTALLED.compareAndSet(false, true)) {
            DocumentEditor.onCreated(editor -> attach(editor, installed));
        }
    }

    /** Hängt den Knopf an einen Editor (auch für Tests). */
    public static VisualAction attach(DocumentEditor editor, EditorSessions editors) {
        return new VisualAction(editor, editors);
    }

    private void refresh() {
        button.setVisible(active || DiagramView.applies(editor));
    }

    void toggle() {
        if (active) {
            active = false;
            button.marked(false);
            button.setToolTipText(SHOW);
            editor.showText();
            refresh();
            return;
        }
        active = true;
        button.marked(true);
        button.setToolTipText(BACK);
        // Bei jedem Öffnen neu: Text und Sprache können sich seit dem letzten Mal geändert haben
        view = new DiagramView(editor, editors);
        editor.showInCenter(view);
        view.generate();
    }

    // ---- für Tests -------------------------------------------------------------------------------------------------

    boolean active() {
        return active;
    }

    DiagramView view() {
        return view;
    }

    IconButton button() {
        return button;
    }
}
