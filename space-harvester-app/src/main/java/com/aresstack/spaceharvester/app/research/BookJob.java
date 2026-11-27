package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.basket.SourceBasket;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.writing4j.book.BookRun;
import com.aresstack.writing4j.cornerstone.CornerstoneSurvey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * „Buch schreiben“ (Buchfunktion Phase 4, Arbeitszweig) als zwei Aufträge, jeder auf einen Klick: Vorbereiten
 * (Quellensteckbriefe, Begriffe ohne Modell, Sieb, Themenfelder) und Schreiben (Gliederung aus den gewählten
 * Begriffen, Fragen der Mission, Belegprüfung, Abschnitte, Missionsbericht). Dazwischen wählt der Leser die Begriffe;
 * ist die unsichere Mitte leer, fragt die Ansicht nichts und das Schreiben folgt mit den sicheren Begriffen. Die
 * Vorbereitung liegt nur im Speicher ({@link Session}); nach einem Neustart beginnt ein neuer Klick von vorn.
 * Seiten gehen erst an das Modell, wenn sie freigegeben sind ({@link PendingReleases}); Abbrechen wirkt am nächsten
 * Modellaufruf. Buch und Missionsbericht kommen in den Ausgabeordner, nach dem Ordner benannt.
 */
public final class BookJob {

    private static final System.Logger LOG = System.getLogger(BookJob.class.getName());
    static final String TITLE = "Buch schreiben";

    private BookJob() {
    }

    /** Ein Korb-Ordner mit seinem Lauf: Modell, Leser der Seiten, Korb mit Weidezaun und die Vorbereitung. */
    public static final class Session {
        private final String folderId;
        private final String folderName;
        private final MainModelChat chat;
        private final StopSwitch stop;
        private final BookRun run;
        private final SourceBasket basket;
        private final PageBasketReader reader;
        /** Ein Auftrag je Sitzung: Abbrechen und Modell gehören der Sitzung, zwei Läufe würden sie teilen. */
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile BookRun.Preparation prepared;

        Session(String folderId, String folderName, MainModelChat chat, StopSwitch stop, BookRun run,
                SourceBasket basket, PageBasketReader reader) {
            this.folderId = folderId;
            this.folderName = folderName;
            this.chat = chat;
            this.stop = stop;
            this.run = run;
            this.basket = basket;
            this.reader = reader;
        }

        public String folderId() {
            return folderId;
        }

        public String folderName() {
            return folderName;
        }

        public Fence fence() {
            return basket.fence();
        }

        /** Die Vorbereitung; {@code null}, solange sie nicht fertig ist. */
        public BookRun.Preparation prepared() {
            return prepared;
        }

        /** Ob gerade ein Auftrag dieser Sitzung läuft (Vorbereiten oder Schreiben). */
        public boolean busy() {
            return busy.get();
        }

        private void claim() {
            if (!busy.compareAndSet(false, true)) {
                throw new IllegalStateException("Für „" + folderName + "“ läuft schon ein Buch-Auftrag.");
            }
        }

        private void release() {
            busy.set(false);
        }

        /** Die Begriffe des Siebs; {@code null} vor der Vorbereitung. */
        public CornerstoneSurvey.Survey survey() {
            return prepared == null ? null : prepared.getSurvey();
        }
    }

    /**
     * Ein Lauf für den Korb-Ordner: der Korb sind seine Seiten-Favoriten mit dem Weidezaun aus Auftrag und Rand.
     *
     * @param chat     das Chat-Modell des KI-Dienstes für alle Schritte (Kapazität in {@code settings})
     * @param questions die Fragen des Lesers an das Buch (leer: keine)
     * @param settings  Sprache, Länge, Kapazität und die Einstellungen der Schritte
     */
    public static Session session(MainModelChat chat, PageStock stock, FavoritesBasket basket, String folderId,
                                  String folderName, Fence fence, List<String> questions,
                                  BookRun.Settings settings) {
        Objects.requireNonNull(fence, "fence");
        PendingReleases releases = new PendingReleases(stock);
        StopSwitch stop = new StopSwitch();
        MainModelChat model = stop.guard(releases.releasingBefore(chat));
        PageBasketReader reader = new PageBasketReader(stock, releases, PageBasketReader.defaultSegmentation());
        // Ohne Embedding-Modell: gemeinsame Wörter wählen die Passagen einer Pflichtfrage (Writing4j).
        BookRun run = new BookRun(model, model, model, null, settings, Clock.systemUTC(),
                line -> LOG.log(System.Logger.Level.DEBUG, line));
        // Die Fragen des Lesers gehen als Pflichtfragen neben dem Weidezaun mit (Writing4j: immer verfolgt, ohne Beleg
        // eine Lücke im Missionsbericht).
        return new Session(folderId, folderName, chat, stop, run,
                basket.basket().withFence(fence).withQuestions(questions), reader);
    }

    /** Vorbereiten als Auftrag; {@code prepared} bekommt die Sitzung, wenn die Vorbereitung fertig ist. */
    public static JobTask prepare(Session session, Consumer<Session> prepared) {
        return JobTask.blocking(context -> {
            context.rootTitle(session.folderName);
            context.phase(TITLE, "Vorbereiten");
            session.claim();
            BookRun.Preparation result;
            try {
                session.stop.bind(() -> stopped(context));
                try (AutoCloseable cancel = context.whenStopRequested(session.chat::cancelInFlight)) {
                    result = session.run.prepare(session.basket, session.reader, progress(context));
                }
                context.checkpoint();
            } finally {
                session.release();
            }
            session.prepared = result;
            context.failed(session.reader.unreadable());
            // Nur Zahlen, nie Titel oder Text (Protokolle tragen keine Inhalte).
            LOG.log(System.Logger.Level.INFO, "Buch vorbereitet: {0} Passagen, {1} außerhalb des Weidezauns, {2} "
                            + "Seiten nicht lesbar; {3}", result.getPassageCount(), result.getSetAside().size(),
                    session.reader.unreadable(), result.getSurvey().numbers());
            prepared.accept(session);
        });
    }

    /**
     * Schreiben als Auftrag mit der Wahl des Lesers; Buch und Missionsbericht kommen in {@code output}.
     *
     * @param written bekommt die Datei des Buchs
     */
    public static JobTask write(Session session, CornerstoneSurvey.Chosen chosen, Path output, String fileName,
                                Consumer<Path> written) {
        Objects.requireNonNull(session.prepared, "prepared");
        return JobTask.blocking(context -> {
            context.rootTitle(session.folderName);
            context.phase(TITLE, "Gliederung");
            session.claim();
            BookRun.Result result;
            try {
                session.stop.bind(() -> stopped(context));
                try (AutoCloseable cancel = context.whenStopRequested(session.chat::cancelInFlight)) {
                    BookRun.Progress progress = progress(context);
                    BookRun.Opening opening = session.run.open(session.prepared, chosen, progress);
                    context.checkpoint();
                    result = session.run.write(opening, progress);
                }
                context.checkpoint();
            } finally {
                session.release();
            }
            context.written(result.getSections().size());
            Path book = save(output, fileName, ".md", result.book());
            save(output, fileName + " – Missionsbericht", ".txt", result.report(ZoneId.systemDefault()));
            long open = result.getQuestionLines().stream()
                    .filter(line -> line.getState() != BookRun.QuestionLine.State.BEANTWORTET).count();
            LOG.log(System.Logger.Level.INFO, "Buch geschrieben: {0} Abschnitte, {1} Fragen, {2} nicht beantwortet, "
                            + "{3} Modellwechsel", result.getSections().size(), result.getQuestionLines().size(), open,
                    result.getModelSwitches());
            written.accept(book);
        });
    }

    /**
     * Schreibt die Datei, ohne eine vorhandene zu überschreiben ({@code Name (2).md}). Der Name wird beim Anlegen
     * reserviert ({@code CREATE_NEW}), so überschreiben auch zwei gleichzeitige Läufe einander nicht.
     */
    static Path save(Path directory, String name, String extension, String text) throws IOException {
        Files.createDirectories(directory);
        for (int number = 1; ; number++) {
            Path target = directory.resolve(number == 1 ? name + extension : name + " (" + number + ")" + extension);
            try {
                Files.writeString(target, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return target;
            } catch (FileAlreadyExistsException taken) {
                // nächste Nummer
            }
        }
    }

    static BookRun.Progress progress(JobContext context) {
        StepProgress steps = new StepProgress(context, TITLE, BookJob::phaseOf);
        return steps::step;
    }

    static String phaseOf(String step) {
        return switch (step) {
            case "quellensteckbriefe" -> "Quellen beschreiben";
            case "sieb" -> "Begriffe sieben";
            case "sieb-zaun" -> "Begriffe der Themenfelder prüfen";
            case "fragen" -> "Gliederung und Fragen";
            case "fragenpruefung" -> "Fragen am Weidezaun prüfen";
            case "belegpruefung" -> "Belege prüfen";
            case "schreiben" -> "Abschnitte schreiben";
            default -> step;
        };
    }

    private static boolean stopped(JobContext context) {
        try {
            context.checkpoint();
            return false;
        } catch (JobCancelledException cancelled) {
            return true;
        }
    }

    /**
     * Abbrechen am nächsten Modellaufruf: Writing4j kennt keinen Abbruch, also endet jeder weitere Aufruf nach dem
     * Abbrechen sofort als Fehler, und der Auftrag endet am nächsten Prüfpunkt.
     */
    static final class StopSwitch {
        private volatile BooleanSupplier stopped = () -> false;

        void bind(BooleanSupplier value) {
            stopped = Objects.requireNonNull(value, "value");
        }

        MainModelChat guard(MainModelChat chat) {
            return new MainModelChat() {
                @Override
                public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                                    int maxOutputTokens) {
                    return stopped.getAsBoolean() ? cancelled() : chat.complete(messages, temperature,
                            maxOutputTokens);
                }

                @Override
                public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                                        int maxOutputTokens, String schemaJson) {
                    return stopped.getAsBoolean() ? cancelled()
                            : chat.completeJson(messages, temperature, maxOutputTokens, schemaJson);
                }

                @Override
                public int contextTokens() {
                    return chat.contextTokens();
                }

                @Override
                public String modelName() {
                    return chat.modelName();
                }

                @Override
                public void cancelInFlight() {
                    chat.cancelInFlight();
                }
            };
        }

        private static MainModelChatResult cancelled() {
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "abgebrochen");
        }
    }
}
