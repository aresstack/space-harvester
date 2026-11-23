package com.aresstack.spaceharvester.app.ui.deck.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Editor meldet sich höchstens einmal für die Blockfarben an, sonst hielte ein geschlossener Editor sich fest. */
class DocumentEditorTest {

    @TempDir
    Path folder;

    @Test
    void theBlockColourObserverIsRegisteredOnlyOnce() throws Exception {
        Path file = Files.writeString(folder.resolve("notiz.txt"), "erfundener Text");
        EditorSession session = new EditorSessions(Runnable::run).open(new LocalDocumentStore(), file.toString(),
                "notiz.txt");
        int before = NaturalBlockHighlighter.observers();
        DocumentEditor[] editor = new DocumentEditor[1];
        SwingUtilities.invokeAndWait(() -> editor[0] = new DocumentEditor(session));
        assertThat(NaturalBlockHighlighter.observers()).isEqualTo(before + 1);
        // „Sichtbar geworden“ meldet erneut an, das darf nicht doppeln.
        SwingUtilities.invokeAndWait(() -> editor[0].watch());
        assertThat(NaturalBlockHighlighter.observers()).isEqualTo(before + 1);
    }
}
