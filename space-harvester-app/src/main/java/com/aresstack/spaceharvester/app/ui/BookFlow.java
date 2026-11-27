package com.aresstack.spaceharvester.app.ui;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.app.research.AiChatModel;
import com.aresstack.spaceharvester.app.research.BookJob;
import com.aresstack.spaceharvester.app.research.BookNotes;
import com.aresstack.spaceharvester.app.research.BookProfiles;
import com.aresstack.spaceharvester.app.research.FavoritesBasket;
import com.aresstack.spaceharvester.app.research.WorkspacePageStock;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.jobs.JobArea;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
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
import java.util.ArrayList;
import java.util.List;
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
        // Der Dienst startet meist erst nach den Ansichten und kann neu starten: nach jedem Start neu anmelden.
        registerResumer(context);
        context.onChange(this::registerResumer);
    }

    private void registerResumer(AppContext current) {
        if (current.runtime() != null) {
            current.runtime().workspace().bookResumer(this::resume);
        }
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

    /**
     * Kapazität des Chat-Modells laut KI-Einstellungen (Parallelität); bei einer GPU 1, beim Clouddienst mehr. Ohne
     * Eintrag gilt derselbe Standard wie im Core ({@link AiServiceSettings#DEFAULT_PARALLEL}); vorher lief die
     * Buchfunktion dann mit einem Aufruf zugleich, während der KI-Dienst mehr annahm (Angelo 27.11.: „Quellen
     * beschreiben“ nicht parallel).
     */
    static int capacity(AppContext context) {
        var ai = context.settings() == null ? null : context.settings().ai();
        return ai == null || ai.parallel() == null ? AiServiceSettings.DEFAULT_PARALLEL : Math.max(1, ai.parallel());
    }

    /** Die Notizen des Ordners (Weidezaun, Länge, Wahl des Lesers); leer ohne Datenordner oder Notizen. */
    Optional<BookNotes> notes(String folderId) {
        Path data = dataDirectory(context);
        return data == null ? Optional.empty() : BookNotes.load(data, folderId);
    }

    /**
     * Merkt Weidezaun, Stichpunkte und Fragen des Formulars am Ordner; die Wahl des Lesers bleibt. Scheitert das, läuft
     * der Auftrag trotzdem.
     */
    static void rememberFence(AppContext context, String folderId, Fence fence, int targetWords,
                              BasketResearchForm form) {
        Path data = dataDirectory(context);
        if (data == null) {
            return;
        }
        BookNotes notes = BookNotes.load(data, folderId).map(old -> old.withFence(fence, targetWords))
                .orElseGet(() -> BookNotes.of(fence, targetWords));
        BookNotes updated = notes.withIdeas(form.ideas(), form.isBook() ? form.questions() : notes.questions());
        save(data, folderId, form.isBook() ? updated.withLength(form.length.amount(), form.length.unit().name()) : updated);
    }

    /** Was der KI-Knopf des Formulars braucht; leer ohne KI-Dienst oder Chat-Modell (dann sagt der Knopf das). */
    static BasketResearchForm.AiAccess aiAccess(AppContext context) {
        var runtime = context.runtime();
        var ai = context.settings() == null ? null : context.settings().ai();
        String model = ai == null ? null : ai.chatModel();
        var chat = runtime == null ? null : runtime.workspace().aiChat();
        if (chat == null || model == null || model.isBlank()) {
            return null;
        }
        return new BasketResearchForm.AiAccess(chat, model, capacity(context),
                runtime.workspace().glossary().orElse(null));
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
        BasketResearchForm form = BasketResearchForm.book().withAi(aiAccess(context));
        Optional<BookNotes> notes = notes(folderId);
        notes.ifPresent(form::prefill);
        Optional<Fence> fence = form.ask(parent, name);
        if (fence.isEmpty()) {
            return;
        }
        rememberFence(context, folderId, fence.get(), form.targetWords(), form);
        BookJob.Session session = session(chat.get(), folderId, name, fence.get(), form.targetWords(),
                form.questions());
        context.runtime().workspace().startBook(folderId, name, BookJob.prepare(session,
                done -> SwingUtilities.invokeLater(() -> prepared(done))));
    }

    /**
     * „Neue Auflage erzeugen“ (Architekt 27.11.2026): startet „Buch schreiben“ ohne Fenster mit den am Ordner gemerkten
     * Angaben; fertige Quellensteckbriefe kommen aus dem Gedächtnis am Ordner, die bisherige Auflage bleibt lesbar.
     * Fehlt etwas, sagt ein Hinweis, was.
     */
    void newEdition(String folderId) {
        BookJob.Session session;
        try {
            session = notedSession(folderId, IllegalStateException::new);
        } catch (IllegalStateException missing) {
            Dialogs.view(parent, BasketResearchForm.BOOK_TITLE, Comic.note("Neue Auflage geht nicht: "
                    + missing.getMessage() + "."));
            return;
        }
        context.runtime().workspace().startBook(folderId, session.folderName(), BookJob.prepare(session,
                done -> SwingUtilities.invokeLater(() -> prepared(done))));
    }

    /**
     * Die Sitzung aus den am Ordner gemerkten Angaben (Weidezaun, Länge, Fragen) und dem Chat-Modell der Einstellungen;
     * fehlt etwas, wirft sie, was {@code fail} aus dem Grund macht.
     */
    private BookJob.Session notedSession(String folderId,
                                         java.util.function.Function<String, ? extends RuntimeException> fail) {
        String name = context.favorites().folder(folderId).map(Favorites.Folder::name).orElse("Ordner");
        BookNotes notes = notes(folderId).orElseThrow(() -> fail.apply(
                "zum Ordner sind keine Angaben gemerkt; bitte „Buch schreiben …“ am Ordner neu starten"));
        var ai = context.settings() == null ? null : context.settings().ai();
        var chat = context.runtime() == null ? null : context.runtime().workspace().aiChat();
        if (chat == null || ai == null || ai.chatModel() == null || ai.chatModel().isBlank()) {
            throw fail.apply("dafür braucht es einen KI-Dienst mit Chat-Modell");
        }
        AiChatModel model = new AiChatModel(chat, ai.chatModel(), null,
                ai.contextTokens() == null ? 0 : ai.contextTokens());
        return session(model, folderId, name, notes.fence(), notes.targetWords(), notes.questions());
    }

    /**
     * ▶ an einem abgebrochenen oder unterbrochenen „Buch schreiben“ (Angelo 27.11.2026): bereitet im selben Eintrag
     * mit den Angaben am Ordner neu vor; fertige Quellensteckbriefe kommen aus dem Gedächtnis am Ordner. Danach geht es
     * weiter wie nach jedem Vorbereiten.
     */
    JobStatus resume(JobStatus job) {
        Favorites favorites = context.favorites();
        String folderId = folderOf(job, favorites, this::notedAt);
        if (folderId == null) {
            throw new JobNotResumableException(job.id(), "der Favoritenordner des Buchs ist nicht mehr da");
        }
        BookJob.Session session = notedSession(folderId, reason -> new JobNotResumableException(job.id(), reason));
        return context.runtime().workspace().rerunBook(job.id(), folderId, BookJob.prepare(session,
                done -> SwingUtilities.invokeLater(() -> prepared(done))));
    }

    /**
     * Der Ordner eines Buch-Auftrags: aus seinen Angaben ({@link Workspace#BOOK_FOLDER}); bei älteren Aufträgen über den
     * eindeutigen Ordnernamen in „Bereich“. Aufträge von vor 13:27 Uhr (27.11.2026) tragen weder Ordner-ID noch Bereich
     * (der Titel eines Auftrags ohne Startseite wird nicht abgelegt): dann der Ordner, an dem Angaben für ein Buch gemerkt
     * sind; sind es mehrere, der, dessen Angaben zuletzt vor dem Start des Auftrags gemerkt wurden („Buch schreiben …“
     * merkt sie unmittelbar vor dem Start).
     *
     * @param notedAt wann am Ordner (ID) zuletzt Angaben für ein Buch gemerkt wurden; {@code null} ohne Angaben
     */
    static String folderOf(JobStatus job, Favorites favorites,
                           java.util.function.Function<String, java.time.Instant> notedAt) {
        String id = job.parameter(Workspace.BOOK_FOLDER);
        if (id != null && favorites.folder(id).isPresent()) {
            return id;
        }
        String name = areaName(job.parameter(JobArea.PARAMETER));
        if (name != null) {
            List<String> matches = new ArrayList<>();
            named(favorites.tree(), name, matches);
            return matches.size() == 1 ? matches.get(0) : null;
        }
        List<String> all = new ArrayList<>();
        named(favorites.tree(), null, all);
        java.time.Instant started = job.createdAt();
        String best = null;
        long bestDistance = Long.MAX_VALUE;
        for (String folder : all) {
            java.time.Instant noted = notedAt.apply(folder);
            if (noted == null) {
                continue;
            }
            // Vor dem Start gemerkt zählt voll, danach (spätere Änderung) nur nachrangig.
            long distance = Math.abs(java.time.Duration.between(noted, started).toMillis())
                    + (noted.isAfter(started.plusSeconds(60)) ? Long.MAX_VALUE / 2 : 0);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = folder;
            }
        }
        return best;
    }

    private static String areaName(String area) {
        return area == null || !area.startsWith("Ordner „") || !area.endsWith("“") ? null
                : area.substring("Ordner „".length(), area.length() - 1);
    }

    private static void named(List<Favorites.Entry> entries, String name, List<String> matches) {
        for (Favorites.Entry entry : entries) {
            if (entry instanceof Favorites.Folder folder) {
                if (name == null || folder.name() != null && name.equals(folder.name().strip())) {
                    matches.add(folder.id());
                }
                named(folder.children(), name, matches);
            }
        }
    }

    /** Wann am Ordner zuletzt Angaben für ein Buch gemerkt wurden; {@code null} ohne. */
    private java.time.Instant notedAt(String folderId) {
        Path data = dataDirectory(context);
        return data == null ? null : BookNotes.savedAt(data, folderId);
    }

    /** Eine Sitzung für den Ordner mit Weidezaun, Länge und Fragen; die Quellensteckbriefe bleiben am Ordner. */
    private BookJob.Session session(AiChatModel chat, String folderId, String name, Fence fence, int targetWords,
                                    List<String> questions) {
        Favorites favorites = context.favorites();
        var workspace = context.runtime().workspace();
        var ai = context.settings().ai();
        int contextTokens = ai.contextTokens() == null ? 0 : ai.contextTokens();
        // Bündel nach der Kontextlänge des Modells laut Einstellung; ohne Angabe die Vorgaben von Writing4j.
        BookRun.Settings settings = new BookRun.Settings("de", targetWords, capacity(context), null,
                contextTokens > 0 ? StockQuestions.Options.defaults().withContextTokens(contextTokens) : null,
                contextTokens > 0 ? EvidenceBundleJudge.Options.defaults().withContextTokens(contextTokens) : null,
                null);
        Path data = dataDirectory(context);
        if (data != null) {
            // Fertige Quellensteckbriefe bleiben am Ordner: ein neuer Start fragt nur die übrigen ab (Angelo 27.11.2026).
            settings.withProfileMemory(BookProfiles.of(data, folderId));
        }
        BookJob.Session session = BookJob.session(chat, new WorkspacePageStock(workspace, null),
                new FavoritesBasket(favorites, folderId, context::sourceTitle), folderId, name, fence,
                questions, settings, parent -> workspace.childJobs(parent, settings.getCapacity(), name));
        sessions.put(folderId, session);
        return session;
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
        context.runtime().workspace().startBook(session.folderId(), session.folderName(), BookJob.write(session,
                chosen, dataDirectory(context), output,
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

    static Path dataDirectory(AppContext context) {
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
