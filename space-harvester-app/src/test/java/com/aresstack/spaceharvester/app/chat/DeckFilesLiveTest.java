package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.deck.DocumentPreviewStore;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.deck.FilesWorkspace;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.files.FileBrowser;
import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import com.aresstack.spaceharvester.deck.files.local.LocalFileSource;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Echttest des Chats im Command Deck › Dateien gegen einen Ollama-Server (Streaming): Ein Ordner mit einem
 * Word-Dokument, einer Textdatei und einem Unterordner (alles erfunden); das Modell soll ihn zusammenfassen. Läuft nur
 * mit {@code -PchatLiveOllama=http://localhost:11434} [{@code -PchatLiveModel=gemma4:12b}]; in der CI übersprungen.
 * Geprüft: Das Modell liest das Word-Dokument über die Dokumentvorschau (Tika) und nennt seinen Inhalt.
 */
@EnabledIfSystemProperty(named = "chat.live.ollama", matches = "https?://.+")
class DeckFilesLiveTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void aRealModelSummarizesTheOpenFolderIncludingAWordDocument() throws Exception {
        String model = System.getProperty("chat.live.model", "gemma4:12b");
        context = TerminalMaskFixture.runtime(home, """
                  ai:
                    provider: ollama
                    base-url: %s
                    chat-model: %s
                """.formatted(System.getProperty("chat.live.ollama"), model));
        Workspace workspace = TerminalMaskFixture.workspace(context);
        Path folder = Files.createDirectories(home.resolve("Wartung"));
        Files.createDirectories(folder.resolve("Archiv"));
        TestDocx.write(folder.resolve("Wartungsplan-Kassiopeia.docx"), "Wartungsplan der Raumstation Kassiopeia",
                "Die Sauerstoffaufbereitung wird jeden Dienstag geprüft. Verantwortlich ist das Team Orion. "
                        + "Der Austausch der Filtermodule erfolgt alle 90 Tage.");
        Files.writeString(folder.resolve("notiz.txt"), "Ersatzteile für Filtermodul F7 bis Freitag bestellen.\n");

        FileBrowser browser = new FileBrowser(new LocalFileSource(FileSystems.getDefault(), folder, List.of()),
                Runnable::run);
        AtomicReference<FilesWorkspace> view = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            view.set(new FilesWorkspace(browser, new EditorSessions(Runnable::run), new LocalDocumentStore()));
            browser.open(folder.toString());
        });
        SwingUtilities.invokeAndWait(() -> { });
        FilesChatTools files = new FilesChatTools(browser.source(), folder.toString(), new LocalDocumentStore(),
                new DocumentPreviewStore(), DocumentPreviewStore::isDocument);
        DeckChatTools tools = new DeckChatTools(view::get, () -> true, files);
        String area = "Command Deck › Dateien (Ordner " + folder + ")";
        AtomicReference<String> system = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> system.set(DeckChatTools.systemMessage(area,
                "Dort sieht er einen Ordner seiner Festplatte.", FilesChatTools.rules(),
                tools.snapshot() + "\n\nInhalt des offenen Ordners:\n" + files.overview())));

        TerminalMaskFixture.Recording port = new TerminalMaskFixture.Recording(workspace.aiChat());
        TerminalMaskFixture.Events events = new TerminalMaskFixture.Events();
        new ChatTurn(workspace, port, model, null, List.of(), "Fasse zusammen, was in diesem Ordner liegt und worum es "
                + "in den Dateien geht.", null, area, events, Runnable::run)
                .withToolset(tools, system.get()).start();
        boolean finished = events.done.await(10, TimeUnit.MINUTES);

        System.out.println("[live] Modell " + model + ", Anfragen " + port.requests.size() + ", Werkzeuge "
                + events.using);
        System.out.println("[live] Antwort: " + events.completed + (events.failed == null ? ""
                : " / Fehler: " + events.failed));
        assertThat(finished).isTrue();
        assertThat(events.failed).isNull();
        assertThat(events.using).contains("Liest eine Datei");
        String answer = events.completed.toLowerCase(Locale.ROOT);
        assertThat(answer).contains("kassiopeia");
        assertThat(answer).containsAnyOf("sauerstoff", "filter", "orion");
    }
}
