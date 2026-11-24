package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.SideHead;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.editor.local.LocalVersionHistory;
import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die gemeinsame Seitenleiste des Code-Viewers (NDV und FTP, Angelo 24.11.2026): Ressourcen, Callees/Caller und
 * Gliederung des gezeigten Editors als Reiter; in der Diagramm-Ansicht deren Werkzeuge als Reiter „Diagramm“, nichts
 * rechts daneben; zurück im Text wieder der vorige Reiter.
 */
class EditorSideBarTest {

    private static final String JCL = """
            //BEISPJOB JOB (ACCT),'TESTLAUF',CLASS=A
            //SCHRITT1 EXEC PGM=IEFBR14
            //EINGABE  DD DSN=TEST.DATEN.EINGABE,DISP=SHR
            """;

    @TempDir
    Path root;

    @Test
    void theEditorsSidePanelsAndTheDiagramToolsAreTabsOfTheSideBar() throws Exception {
        Path file = Files.writeString(root.resolve("BEISPJOB.jcl"), JCL);
        EditorSessions sessions = new EditorSessions(Runnable::run, new LocalVersionHistory(root.resolve("verlauf")));
        EditorSession session = sessions.open(new LocalDocumentStore(), file.toString(), "BEISPJOB.jcl");
        List<String> remembered = new ArrayList<>();
        AtomicReference<EditorSideBar> bar = new AtomicReference<>();
        AtomicReference<DocumentEditor> editor = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JLabel datasets = new JLabel("Datasets");
            bar.set(new EditorSideBar(new EditorSideBar.Home("Datasets", null, "Datasets",
                    new SideHead("Datasets", new PlaceholderField(8, "Filtern")), datasets, new JPanel()),
                    List.of(), "gibt es nicht", remembered::add));
            editor.set(new DocumentEditor(session));
            bar.get().attach(editor.get());
            bar.get().show(editor.get());
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            EditorSideBar side = bar.get();
            DocumentEditor shown = editor.get();
            assertThat(side.tab()).isEqualTo("Datasets");
            DocumentEditor.SidePanels panels = shown.detachSidePanels();
            for (JComponent panel : List.of(panels.resources(), panels.calls(), panels.outline())) {
                assertThat(SwingUtilities.isDescendingFrom(panel, side)).isTrue();
                assertThat(SwingUtilities.isDescendingFrom(panel, shown)).isFalse();
            }
            side.select(EditorSideBar.OUTLINE);
            assertThat(remembered).last().isEqualTo(EditorSideBar.OUTLINE);

            JPanel tools = new JPanel();
            shown.showInCenter(new Diagram(tools));
            assertThat(side.tab()).isEqualTo(EditorSideBar.DIAGRAM);
            assertThat(SwingUtilities.isDescendingFrom(tools, side)).as("Werkzeuge in der Seitenleiste").isTrue();
            assertThat(remembered).last().as("Diagramm wird nicht gemerkt").isEqualTo(EditorSideBar.OUTLINE);

            shown.showText();
            assertThat(side.tab()).isEqualTo(EditorSideBar.OUTLINE);
            assertThat(SwingUtilities.isDescendingFrom(tools, side)).isFalse();
        });
    }

    /** Eine Mitte mit Werkzeugen wie die Diagramm-Ansicht. */
    private static final class Diagram extends JPanel implements DocumentEditor.CenterTools {
        private final JComponent side;

        Diagram(JComponent side) {
            this.side = side;
        }

        @Override
        public JComponent[] barTools() {
            return new JComponent[0];
        }

        @Override
        public JComponent sideTools() {
            return side;
        }
    }
}
