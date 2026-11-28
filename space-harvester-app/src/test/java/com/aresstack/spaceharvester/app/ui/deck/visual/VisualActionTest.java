package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Knopf „Visuell“ im Editor: nur bei Großrechner-Quelltext und Mermaid, schaltet Text ↔ Diagramm, Diagrammtypen,
 * Aufrufziele neben der Datei werden für Mindmap und Doppelklick gefunden.
 */
class VisualActionTest {

    private static final String JCL = """
            //BUILD    JOB (ACCT),'BAU',CLASS=A
            //STEP1    EXEC PGM=IEFBR14
            //DD1      DD   DSN=KKR.DATA,DISP=SHR
            //STEP2    EXEC PGM=PROG1
            """;

    private static final String NATURAL_MAIN = """
            DEFINE DATA LOCAL
            1 #A (A10)
            END-DEFINE
            CALLNAT 'SUBPROG' #A
            END
            """;

    private static final String NATURAL_SUB = """
            DEFINE DATA PARAMETER
            1 #A (A10)
            END-DEFINE
            CALLNAT 'DEEPPROG' #A
            END
            """;

    @TempDir
    Path folder;

    @Test
    void offeredForJclAndMermaidOnly() throws Exception {
        assertThat(button(write("build.jcl", JCL))).isTrue();
        assertThat(button(write("ablauf.mmd", "graph TD\n A-->B\n"))).isTrue();
        assertThat(button(write("notiz.txt", "nur Text"))).isFalse();
    }

    @Test
    void togglesBetweenTextAndStructureDiagram() throws Exception {
        Path jcl = write("build.jcl", JCL);
        Fixture fixture = open(jcl);

        onEdt(() -> {
            fixture.action.toggle();
            return null;
        });
        DiagramView view = onEdt(fixture.action::view);
        await(() -> onEdtQuiet(() -> view.diagram().hasDiagram()), "Strukturdiagramm gezeichnet");

        assertThat(onEdt(fixture.action::active)).isTrue();
        assertThat(onEdt(() -> view.diagram().getMermaidSource())).contains("STEP1").contains("STEP2");
        onEdt(() -> {
            fixture.editor.search("STEP1");
            return null;
        });
        // Die Suchzeile sucht im Diagramm nach Knoten, nicht im Text
        assertThat(onEdt(fixture.editor::searchCount)).matches("1 / \\d+");

        onEdt(() -> {
            fixture.action.toggle();
            return null;
        });
        assertThat(onEdt(fixture.action::active)).isFalse();
    }

    @Test
    void withTheWorkspaceSideBarNothingStandsRightOfTheDiagramAndTheToolsAreIconsInTheToolRow() throws Exception {
        Fixture fixture = open(write("MAINPROG.NSP", NATURAL_MAIN));
        java.util.List<javax.swing.JComponent> sides = new java.util.ArrayList<>();
        onEdt(() -> {
            fixture.editor.detachSidePanels();
            fixture.editor.onCenterSide(sides::add);
            fixture.action.toggle();
            return null;
        });
        DiagramView view = onEdt(fixture.action::view);
        onEdt(() -> {
            // Kein Teiler, kein Panel rechts: nur Meldungszeile und Diagramm.
            assertThat(java.util.Arrays.stream(view.getComponents())
                    .noneMatch(c -> c instanceof javax.swing.JSplitPane)).isTrue();
            // Die Werkzeuge gehen als Reiter in die Seitenleiste des Arbeitsbereichs, ohne Emoji-Knöpfe und ohne Lupe.
            assertThat(sides).containsExactly(view.sideTools());
            assertThat(components(view.sideTools(), javax.swing.JToggleButton.class)).isEmpty();
            assertThat(components(view.sideTools(), javax.swing.JSpinner.class)).isEmpty();
            // Zoom, Einpassen, Neu zeichnen, Export, im Chat erklären: Symbolknöpfe in der Werkzeugzeile des Editors.
            for (javax.swing.JComponent tool : view.barTools()) {
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(tool, fixture.editor)).isTrue();
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(tool, view)).isFalse();
            }
            assertThat(java.util.Arrays.stream(view.barTools())
                    .filter(c -> c instanceof com.aresstack.spaceharvester.app.ui.IconButton)).hasSize(6);
            fixture.action.toggle();
            return null;
        });
        assertThat(sides).hasSize(2).last().isNull();
        onEdt(() -> {
            for (javax.swing.JComponent tool : view.barTools()) {
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(tool, fixture.editor)).isFalse();
            }
            return null;
        });
    }

    private static <T> java.util.List<T> components(java.awt.Container root, Class<T> type) {
        java.util.List<T> found = new java.util.ArrayList<>();
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof java.awt.Container inner) {
                found.addAll(components(inner, type));
            }
        }
        return found;
    }

    @Test
    void mindmapFollowsCallsIntoSiblingFiles() throws Exception {
        Path main = write("MAINPROG.NSP", NATURAL_MAIN);
        write("SUBPROG.NSN", NATURAL_SUB);
        Fixture fixture = open(main);
        com.aresstack.spaceharvester.app.ui.AppState.shared().put(DiagramView.DEPTH_KEY, 2);
        com.aresstack.spaceharvester.app.ui.AppState.shared().put(DiagramView.TYPE_KEY, DiagramType.MINDMAP.name());
        try {
            onEdt(() -> {
                fixture.action.toggle();
                return null;
            });
            DiagramView view = onEdt(fixture.action::view);
            await(() -> onEdtQuiet(() -> view.diagram().getMermaidSource() != null
                    && view.diagram().getMermaidSource().startsWith("mindmap")), "Mindmap erzeugt");
            // DEEPPROG steht nur in SUBPROG: die Mindmap ist der Aufrufkette in die Nachbardatei gefolgt
            assertThat(onEdt(() -> view.diagram().getMermaidSource())).contains("SUBPROG").contains("DEEPPROG");

            CallTargets targets = new CallTargets(fixture.editors, fixture.session);
            assertThat(targets.open("CALLNAT SUBPROG")).isTrue();
            assertThat(fixture.editors.all()).extracting(EditorSession::name).contains("SUBPROG.NSN");
        } finally {
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(DiagramView.DEPTH_KEY, 0);
            com.aresstack.spaceharvester.app.ui.AppState.shared().put(DiagramView.TYPE_KEY,
                    DiagramType.STRUCTURE.name());
        }
    }

    // ---- Hilfen ----------------------------------------------------------------------------------------------------

    private record Fixture(EditorSessions editors, EditorSession session, DocumentEditor editor,
                           VisualAction action) {
    }

    private Fixture open(Path file) throws Exception {
        EditorSessions editors = new EditorSessions(Runnable::run);
        LocalDocumentStore store = new LocalDocumentStore();
        EditorSession session = editors.open(store, file.toString(), file.getFileName().toString());
        AtomicReference<Fixture> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            DocumentEditor editor = new DocumentEditor(session);
            editor.setSize(1200, 800);
            ref.set(new Fixture(editors, session, editor, VisualAction.attach(editor, editors)));
        });
        Fixture fixture = ref.get();
        await(() -> onEdtQuiet(() -> fixture.action.button().isVisible() || !fixture.editor.text().isEmpty()),
                "Datei geladen");
        return fixture;
    }

    private boolean button(Path file) throws Exception {
        Fixture fixture = open(file);
        await(() -> onEdtQuiet(() -> !fixture.editor.text().isEmpty()), "Text geladen");
        return onEdt(() -> fixture.action.button().isVisible());
    }

    private Path write(String name, String text) throws Exception {
        Path file = folder.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + 120_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("Zeit abgelaufen: " + what);
            }
            Thread.sleep(100);
        }
    }

    private static boolean onEdtQuiet(Callable<Boolean> call) {
        try {
            return onEdt(call);
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> T onEdt(Callable<T> call) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(call.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return ref.get();
    }
}
