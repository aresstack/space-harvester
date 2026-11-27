package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.spaceharvester.app.research.AiChatModel;
import com.aresstack.spaceharvester.app.research.BookJob;
import com.aresstack.spaceharvester.app.research.BookNotes;
import com.aresstack.spaceharvester.app.research.FavoritesBasket;
import com.aresstack.spaceharvester.app.research.WorkspacePageStock;
import com.aresstack.spaceharvester.app.ui.content.PageDetailPanel;
import com.aresstack.writing4j.book.BookRun;
import com.aresstack.writing4j.book.StockQuestions;
import com.aresstack.writing4j.cornerstone.CornerstoneSurvey;
import com.aresstack.writing4j.evidence.EvidenceBundleJudge;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die Bedienung der Buchfunktion am Korb-Ordner (Arbeitszweig): „Korb füllen …“ und „Buch schreiben …“ im
 * Rechtsklick-Menü eines Favoritenordners. „Buch schreiben“ fragt Auftrag und Weidezaun (vorab aus den Notizen des
 * Ordners), startet das Vorbereiten als Auftrag und zeigt danach „Begriffe wählen“; ist die unsichere Mitte leer, fragt
 * es nichts und schreibt mit den sicheren Begriffen (und der gespeicherten Wahl des Lesers). Nichts läuft ohne Klick.
 * Die Vorbereitung bleibt je Ordner im Speicher, „Begriffe wählen …“ öffnet sie wieder.
 */
final class BookFlow {

    private static final System.Logger LOG = System.getLogger(BookFlow.class.getName());

    private final AppContext context;
    private final Component parent;
    private final Map<String, BookJob.Session> sessions = new ConcurrentHashMap<>();

    BookFlow(AppContext context, Component parent) {
        this.context = Objects.requireNonNull(context, "context");
        this.parent = Objects.requireNonNull(parent, "parent");
    }

    /**
     * Das Chat-Modell des KI-Dienstes für die Bibliotheken, Nachdenken aus (kein reasoning_effort) wie die übrigen
     * Analyseschritte; leer ohne Dienst oder Chat-Modell (dann steht ein Hinweis im Fenster {@code title}).
     */
    static Optional<AiChatModel> chatModel(AppContext context, Component parent, String title) {
        var runtime = context.runtime();
        var ai = context.settings() == null ? null : context.settings().ai();
        String model = ai == null ? null : ai.chatModel();
        var chat = runtime == null ? null : runtime.workspace().aiChat();
        if (chat == null || model == null || model.isBlank()) {
            Dialogs.view(parent, title, Comic.note("Dafür braucht es einen KI-Dienst mit Chat-Modell (Einstellungen › "
                    + "KI-Dienst)."));
            return Optional.empty();
        }
        return Optional.of(new AiChatModel(chat, model, null, ai.contextTokens() == null ? 0 : ai.contextTokens()));
    }

    /** Kapazität des Chat-Modells laut KI-Einstellungen (Parallelität); bei einer GPU 1, beim Clouddienst mehr. */
    static int capacity(AppContext context) {
        var ai = context.settings() == null ? null : context.settings().ai();
        return ai == null || ai.parallel() == null ? 1 : Math.max(1, ai.parallel());
    }

    /** Die Notizen des Ordners (Weidezaun, Länge, Wahl des Lesers); leer ohne Datenordner oder Notizen. */
    Optional<BookNotes> notes(String folderId) {
        Path data = dataDirectory(context);
        return data == null ? Optional.empty() : BookNotes.load(data, folderId);
    }

    /** Merkt den Weidezaun am Ordner; die Wahl des Lesers bleibt. Scheitert das, läuft der Auftrag trotzdem. */
    static void rememberFence(AppContext context, String folderId, Fence fence, int targetWords) {
        Path data = dataDirectory(context);
        if (data == null) {
            return;
        }
        BookNotes notes = BookNotes.load(data, folderId).map(old -> old.withFence(fence, targetWords))
                .orElseGet(() -> BookNotes.of(fence, targetWords));
        save(data, folderId, notes);
    }

    /** Ob „Begriffe wählen …“ für den Ordner eine fertige Vorbereitung hat. */
    boolean prepared(String folderId) {
        BookJob.Session session = sessions.get(folderId);
        return session != null && session.prepared() != null;
    }

    /** „Buch schreiben …“: Auftrag und Weidezaun, dann Vorbereiten als Auftrag. */
    void start(String folderId) {
        Favorites favorites = context.favorites();
        String name = favorites.folder(folderId).map(Favorites.Folder::name).orElse("Ordner");
        Optional<AiChatModel> chat = chatModel(context, parent, BasketResearchForm.BOOK_TITLE);
        if (chat.isEmpty()) {
            return;
        }
        BasketResearchForm form = BasketResearchForm.book();
        Optional<BookNotes> notes = notes(folderId);
        notes.ifPresent(found -> form.prefill(found.fence(), found.targetWords()));
        Optional<Fence> fence = form.ask(parent, name);
        if (fence.isEmpty()) {
            return;
        }
        rememberFence(context, folderId, fence.get(), form.targetWords());
        var workspace = context.runtime().workspace();
        var ai = context.settings().ai();
        int contextTokens = ai.contextTokens() == null ? 0 : ai.contextTokens();
        // Bündel nach der Kontextlänge des Modells laut Einstellung; ohne Angabe die Vorgaben von Writing4j.
        BookRun.Settings settings = new BookRun.Settings("de", form.targetWords(), capacity(context), null,
                contextTokens > 0 ? StockQuestions.Options.defaults().withContextTokens(contextTokens) : null,
                contextTokens > 0 ? EvidenceBundleJudge.Options.defaults().withContextTokens(contextTokens) : null,
                null);
        BookJob.Session session = BookJob.session(chat.get(), new WorkspacePageStock(workspace, null),
                new FavoritesBasket(favorites, folderId, context::sourceTitle), folderId, name, fence.get(), settings);
        sessions.put(folderId, session);
        workspace.startBook(BookJob.prepare(session, done -> SwingUtilities.invokeLater(() -> prepared(done))));
    }

    /** Nach dem Vorbereiten: fragt nur, wenn das Sieb unsicher war oder nichts sicher drin sah. */
    private void prepared(BookJob.Session session) {
        CornerstoneSurvey.Survey survey = session.survey();
        BookTermsView view = new BookTermsView(survey, notes(session.folderId()).orElse(null));
        if (BookTermsView.needsReader(survey)) {
            choose(session, view);
        } else {
            write(session, view);
        }
    }

    /** „Begriffe wählen …“ für eine fertige Vorbereitung. */
    void choose(String folderId) {
        BookJob.Session session = sessions.get(folderId);
        if (session == null || session.prepared() == null) {
            return;
        }
        choose(session, new BookTermsView(session.survey(), notes(folderId).orElse(null)));
    }

    private void choose(BookJob.Session session, BookTermsView view) {
        boolean write = Dialogs.formOverlaySized(parent, BookTermsView.TITLE + " – " + session.folderName(),
                view.body(), BookTermsView.ACTION, "book-terms", 0.8);
        Path data = dataDirectory(context);
        if (data != null) {
            BookNotes notes = BookNotes.load(data, session.folderId()).orElseGet(() -> BookNotes.of(session.fence(),
                    0));
            save(data, session.folderId(), view.remember(notes));
        }
        if (write) {
            write(session, view);
        }
    }

    private void write(BookJob.Session session, BookTermsView view) {
        if (session.busy()) {
            Dialogs.view(parent, BasketResearchForm.BOOK_TITLE, Comic.note("Für „" + session.folderName()
                    + "“ läuft schon ein Buch-Auftrag. Warte, bis er fertig ist, oder brich ihn unter „Aufträge“ ab."));
            return;
        }
        Path output = context.settings() == null ? com.aresstack.spaceharvester.app.settings.AppSettings
                .defaultOutputDirectory() : context.settings().outputDirectory();
        CornerstoneSurvey.Chosen chosen = view.chosen(session.prepared().basketHints());
        context.runtime().workspace().startBook(BookJob.write(session, chosen, output,
                PageDetailPanel.safeName(session.folderName()),
                book -> SwingUtilities.invokeLater(() -> written(session, book))));
    }

    private void written(BookJob.Session session, Path book) {
        Feedback feedback = new Feedback();
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(Comic.note("Das Buch aus „" + session.folderName() + "“ liegt im Ausgabeordner: "
                + book.getFileName() + ", daneben der Missionsbericht mit allen Fragen und ob sie beantwortet sind."));
        var open = Comic.button("Buch öffnen", book.toString());
        open.addActionListener(e -> SettingsView.open(book, feedback));
        body.add(open);
        body.add(feedback);
        Dialogs.view(parent, BasketResearchForm.BOOK_TITLE, body);
    }

    private static Path dataDirectory(AppContext context) {
        return context.settings() == null || context.settings().storage() == null ? null
                : context.settings().storage().dataDirectory();
    }

    private static void save(Path data, String folderId, BookNotes notes) {
        try {
            notes.save(data, folderId);
        } catch (IOException | RuntimeException failed) {
            LOG.log(System.Logger.Level.WARNING, "Notizen zum Buch nicht gespeichert ({0})",
                    failed.getClass().getSimpleName());
        }
    }
}
