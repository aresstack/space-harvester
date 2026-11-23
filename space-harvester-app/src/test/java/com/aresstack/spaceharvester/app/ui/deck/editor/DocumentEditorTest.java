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
