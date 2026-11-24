package com.aresstack.spaceharvester.app.ui.deck.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.editor.local.LocalVersionHistory;
import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import com.aresstack.spaceharvester.mainframe.syntax.MainframeSyntax;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der gemeinsame Editor mit den Funktionen aus MainframeMate: Sprache, Gliederung, Suche, Vergleich. */
class DocumentEditorTest {

    private static final Executor NOW = Runnable::run;
    static final String JCL = """
            //BEISPJOB JOB (ACCT),'TESTLAUF',CLASS=A
            //* erfundener Beispieljob
            //SCHRITT1 EXEC PGM=IEFBR14
            //EINGABE  DD DSN=TEST.DATEN.EINGABE,DISP=SHR
            //SCHRITT2 EXEC PGM=SORT
            //SYSIN    DD *
              SORT FIELDS=(1,10,CH,A)
            /*
            """;

    @TempDir
    Path root;

    private EditorSessions sessions;

    @BeforeEach
    void setUp() {
        AppState.shared().put(DocumentEditor.OUTLINE_KEY, true);
        AppState.shared().put(DocumentEditor.FILTER_KEY, true);
        AppState.shared().put(DocumentEditor.WRAP_KEY, false);
        sessions = new EditorSessions(NOW, new LocalVersionHistory(root.resolve("verlauf")));
    }

    @Test
    void recognisesJclHighlightsAndBuildsTheOutline() throws Exception {
        DocumentEditor editor = open("BEISPJOB.jcl", JCL);

        assertThat(on(editor::syntaxStyle)).isEqualTo(MainframeSyntax.JCL);
        assertThat(editor.language()).contains(SourceLanguage.JCL);
        assertThat(waitFor(() -> editor.outlinePanel().statusText(), s -> s.startsWith("1 Jobs, 2 Steps")))
                .startsWith("1 Jobs, 2 Steps");
        assertThat(editor.outline()).isPresent();
        assertThat(on(editor::outlineShown)).isTrue();
    }

    @Test
    void languageChoiceSteersHighlightingAndParser() throws Exception {
        DocumentEditor editor = open("ohne-endung", "Nur Text\n");
        assertThat(on(editor::syntaxStyle)).isEqualTo("text/plain");
        assertThat(editor.language()).isEmpty();

        SwingUtilities.invokeAndWait(() -> editor.language(EditorLanguage.of(SourceLanguage.NATURAL)));

        assertThat(on(editor::syntaxStyle)).isEqualTo(MainframeSyntax.NATURAL);
        assertThat(editor.language()).contains(SourceLanguage.NATURAL);
        SwingUtilities.invokeAndWait(() -> editor.language(EditorLanguage.AUTO));
        assertThat(editor.language()).isEmpty();
    }

    @Test
    void searchFiltersLinesOrStepsThroughMatches() throws Exception {
        DocumentEditor editor = open("BEISPJOB.jcl", JCL);

        SwingUtilities.invokeAndWait(() -> editor.search("exec"));
        assertThat(on(editor::searchCount)).isEqualTo("2 Treffer in 2 Zeilen");
        assertThat(on(editor::hiddenLines)).isPositive();

        SwingUtilities.invokeAndWait(() -> editor.lineFilter(false));
        assertThat(on(editor::hiddenLines)).isZero();
        SwingUtilities.invokeAndWait(() -> editor.searchStep(1));
        assertThat(on(editor::searchCount)).isEqualTo("1 / 2");
        assertThat(on(editor::caretLine)).isEqualTo(3);
        SwingUtilities.invokeAndWait(() -> editor.searchStep(1));
        assertThat(on(editor::caretLine)).isEqualTo(5);

        SwingUtilities.invokeAndWait(() -> editor.search("("));
        assertThat(on(editor::searchCount)).isEqualTo("ungültig");
    }

    /**
     * Angelo 24.11.2026: eine Suchleiste. Der Editor hat keine eigene Suchzeile mehr; die Lupe des Deck sucht in ihm,
     * mit dem Falt-Umschalter (Filter-Symbol) und ▲▼ im Feld, und die Sprachauswahl steht rechts in ihrer Leiste.
     */
    @Test
    void theDeckSearchBarSearchesAndFoldsInTheEditorWhichHasNoSearchRowOfItsOwn() throws Exception {
        DocumentEditor editor = open("BEISPJOB.jcl", JCL);
        javax.swing.JPanel area = new javax.swing.JPanel(new java.awt.BorderLayout());
        AtomicReference<com.aresstack.spaceharvester.app.ui.FindBar> holder = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            area.add(editor);
            holder.set(new com.aresstack.spaceharvester.app.ui.FindBar(() -> area));
        });
        com.aresstack.spaceharvester.app.ui.FindBar bar = holder.get();

        assertThat(on(() -> components(editor).stream().map(DocumentEditorTest::name)
                .anyMatch(n -> n.equals("Suchen") || n.equals("Sprache"))))
                .as("keine Suchzeile, keine Sprachauswahl im Editor").isFalse();

        SwingUtilities.invokeAndWait(() -> bar.search("exec"));
        assertThat(on(bar::counterText)).isEqualTo("2 Treffer in 2 Zeilen");
        assertThat(on(editor::hiddenLines)).isPositive();
        assertThat(on(() -> bar.foldToggle().isVisible() && bar.foldToggle().marked())).isTrue();
        assertThat(on(() -> components(bar.cornerArea()).stream().map(DocumentEditorTest::name).anyMatch("Sprache"::equals)))
                .as("Sprachauswahl rechts in der Leiste").isTrue();

        SwingUtilities.invokeAndWait(() -> bar.foldToggle().doClick());
        assertThat(on(editor::hiddenLines)).isZero();
        SwingUtilities.invokeAndWait(bar::next);
        assertThat(on(bar::counterText)).isEqualTo("1 / 2");
        assertThat(on(editor::caretLine)).isEqualTo(3);
    }

    private static String name(java.awt.Component component) {
        return component.getAccessibleContext() == null || component.getAccessibleContext().getAccessibleName() == null
                ? "" : component.getAccessibleContext().getAccessibleName();
    }

    private static java.util.List<java.awt.Component> components(java.awt.Container root) {
        java.util.List<java.awt.Component> all = new java.util.ArrayList<>();
        for (java.awt.Component child : root.getComponents()) {
            all.add(child);
            if (child instanceof java.awt.Container inner) {
                all.addAll(components(inner));
            }
        }
        return all;
    }

    @Test
    void editingRefreshesTheSearchMatches() throws Exception {
        DocumentEditor editor = open("BEISPJOB.jcl", JCL);
        SwingUtilities.invokeAndWait(() -> {
            editor.lineFilter(false);
            editor.search("exec");
        });
        assertThat(on(editor::searchCount)).isEqualTo("– / 2");

        SwingUtilities.invokeAndWait(() -> {
            editor.goToLine(1);
            editor.type("//SCHRITT0 EXEC PGM=IEFBR14\n");
            editor.searchStep(1);
        });

        assertThat(on(editor::searchCount)).isEqualTo("1 / 3");
    }

    @Test
    void showsJclRelationsAndOpensNaturalProgramsOverNdv() throws Exception {
        AppState.shared().put(DocumentEditor.RELATIONS_KEY, true);
        java.util.List<String> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
        DocumentEditors.naturalFromJcl((lib, program) -> opened.add(lib + "/" + program));
        try {
            DocumentEditor editor = open("NATJOB.jcl", """
                    //NATJOB   JOB (ACCT),'TESTLAUF',CLASS=A
                    //         JCLLIB ORDER=(TEST.PROCLIB)
                    //SCHRITT1 EXEC PGM=IEFBR14
                    //SCHRITT2 EXEC PGM=NATBATCH,PARM='STACK=(LOGON TESTLIB;UMSATZ)'
                    //EINGABE  DD DSN=TEST.DATEN.EINGABE,DISP=SHR
                    """);

            assertThat(on(editor::relationsShown)).isTrue();
            RelationsPanel panel = editor.relationsPanel();
            assertThat(waitFor(() -> String.join("|", panel.resourceLabels()), s -> s.contains("JCLLIB")))
                    .contains("📚 JCLLIB (1)", "📄 Datasets (DSN) (1)");
            assertThat(on(() -> panel.sideTitle(RelationsPanel.CALLEES))).startsWith("Callees (");
            assertThat(on(() -> panel.labels(RelationsPanel.CALLEES))).anyMatch(l -> l.startsWith("📋 NATJOB"))
                    .anyMatch(l -> l.startsWith("🌿 UMSATZ (Lib: TESTLIB)"));

            // Natural-Knoten finden und doppelt klicken: geht über den Öffner des NDV-Bereichs.
            SwingUtilities.invokeAndWait(() -> panel.clickLabel(RelationsPanel.CALLEES, "🌿 UMSATZ", 2));
            assertThat(opened).containsExactly("TESTLIB/UMSATZ");

            // Einfachklick springt zur Zeile.
            SwingUtilities.invokeAndWait(() -> panel.clickLabel(RelationsPanel.CALLEES, "🌿 UMSATZ", 1));
            assertThat(on(editor::caretLine)).isEqualTo(4);
        } finally {
            DocumentEditors.naturalFromJcl(null);
        }
    }

    @Test
    void relationsAreHiddenForPlainText() throws Exception {
        AppState.shared().put(DocumentEditor.RELATIONS_KEY, true);
        DocumentEditor editor = open("notiz.txt", "Nur Text\n");

        assertThat(on(editor::relationsShown)).isFalse();
    }

    @Test
    void comparesWithTheVersionKeptWhenOpening() throws Exception {
        DocumentEditor editor = open("BEISPJOB.jcl", JCL);

        SwingUtilities.invokeAndWait(() -> {
            editor.goToLine(1);
            editor.type("//* neu\n");
            editor.compare(true);
        });

        assertThat(on(editor::versionCount)).isEqualTo(1);
        assertThat(on(editor::compareInfo)).isEqualTo("1 Unterschied");
    }

    /** Codex-Befund #1180/#1185: höchstens einmal angemeldet, sonst hielte sich ein geschlossener Editor fest. */
    @Test
    void theBlockColourObserverIsRegisteredOnlyOnce() throws Exception {
        int before = NaturalBlockHighlighter.observers();
        DocumentEditor editor = open("notiz.txt", "erfundener Text");
        assertThat(NaturalBlockHighlighter.observers()).isEqualTo(before + 1);
        // „Sichtbar geworden“ meldet erneut an, das darf nicht doppeln.
        SwingUtilities.invokeAndWait(editor::watch);
        assertThat(NaturalBlockHighlighter.observers()).isEqualTo(before + 1);
    }

    private DocumentEditor open(String name, String text) throws Exception {
        Path file = Files.writeString(root.resolve(name), text);
        EditorSession session = sessions.open(new LocalDocumentStore(), file.toString(), name);
        AtomicReference<DocumentEditor> editor = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> editor.set(new DocumentEditor(session)));
        flush();
        return editor.get();
    }

    private static <T> T on(Supplier<T> read) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> value.set(read.get()));
        return value.get();
    }

    private static String waitFor(Supplier<String> read, java.util.function.Predicate<String> done)
            throws Exception {
        String value = on(read);
        for (int i = 0; i < 100 && !done.test(value); i++) {
            Thread.sleep(50);
            value = on(read);
        }
        return value;
    }

    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }
}
