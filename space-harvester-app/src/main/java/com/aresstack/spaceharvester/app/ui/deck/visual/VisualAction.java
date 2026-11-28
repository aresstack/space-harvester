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

    private static volatile DiagramLabels labels;

    /**
     * Schließt die Diagramm-Ansicht an die App an (beim Start des Hauptfensters): die KI-Kurzbeschriftung
     * zusammengefasster Blöcke über den streamenden Chat-Port und die einstellbare Liste der Systemfunktionen
     * (Einstellungen › Diagramme), beide wie in MainframeMate.
     */
    public static void connect(java.util.function.Supplier<DiagramLabels.Target> target) {
        DiagramLabels next = new DiagramLabels(target);
        labels = next;
        com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.blockLabelSummarizer(next);
        com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.labelLayout(DiagramLabels::layout);
        SystemFunctionsStore store = SystemFunctionsStore.shared();
        com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.systemFunctions(store::names);
    }

    /** Ob die KI-Beschreibung an Diagrammen mitschreiben kann (Schalter an und Modell eingerichtet). */
    static boolean aiAvailable() {
        DiagramLabels current = labels;
        return current != null && current.enabled();
    }

    /** Grund, warum die KI-Kurzbeschriftung zuletzt ausblieb, einmalig; {@code null} ohne Problem. */
    static String takeLabelProblem() {
        DiagramLabels current = labels;
        return current == null ? null : current.takeProblem();
    }

    /** Frage an den Chat des Bereichs: Frage, zitierter Ausschnitt und woher er stammt; {@code true} = gesendet. */
    public interface ChatQuestion {
        boolean ask(String question, String excerpt, String reference);
    }

    private static volatile ChatQuestion chat;

    /** Schließt den Chat an (beim Start des Hauptfensters). */
    public static void chat(ChatQuestion question) {
        chat = question;
    }

    /** Fragt im Chat des Bereichs; {@code false}, wenn kein Chat da ist oder er gerade antwortet. */
    static boolean askInChat(String question, String excerpt, String reference) {
        ChatQuestion current = chat;
        return current != null && current.ask(question, excerpt, reference);
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
            if (view != null) {
                view.dispose();
            }
            refresh();
            return;
        }
        active = true;
        button.marked(true);
        button.setToolTipText(BACK);
        // Bei jedem Öffnen neu: Text und Sprache können sich seit dem letzten Mal geändert haben
        if (view != null) {
            view.dispose();
        }
        view = new DiagramView(editor, editors);
        view.onBack(() -> {
            if (active) {
                toggle();
            }
        });
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
