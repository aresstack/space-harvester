package com.aresstack.spaceharvester.application.presentation;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.images.ImagePayload;
import com.aresstack.spaceharvester.application.images.ImageStatus;
import com.aresstack.spaceharvester.application.images.PageImageAnalysis;
import com.aresstack.spaceharvester.application.images.PageImages;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.marks.VideoMarks;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import com.aresstack.spaceharvester.document.PptxExportWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * „In PPTX umwandeln“ (Angelo 20.11.2026): aus einem Video mit Folienmarken eine PowerPoint-Datei mit je Folie ihrem
 * Standbild und dem Vortragstext als Notiz. Der Vorgang besteht aus fünf eigenen Aufträgen in der Auftragsliste; was
 * nicht aufeinander wartet, läuft parallel:
 *
 * <ol>
 *     <li>{@link JobType#VIDEO_TRANSCRIPT} „Transkript ziehen“: das abgelegte Transkript, von der App lokal
 *     vervollständigt ({@link VideoMedia#transcribe}),</li>
 *     <li>{@link JobType#VIDEO_SLIDES} „Folien beschreiben“, zugleich mit 1: fehlende Standbilder ziehen und jede
 *     Folie mit dem Vision-Modell beschreiben (dieselben Beschreibungen wie unter „Analysen › Bilder“),</li>
 *     <li>{@link JobType#TRANSCRIPT_CORRECTION} „Transkript korrigieren“, nach 1 und 2: das Text-Modell
 *     ({@code ai.chat-model}) korrigiert Spracherkennungsfehler, mit den Folienbeschreibungen als Hilfe,</li>
 *     <li>{@link JobType#SLIDE_NOTES} „Folienkommentare“, nach 3: je Folie Standbild und der Transkriptauszug ihrer
 *     Zeit einzeln an das Vision-Modell, das den Vortragstext schreibt,</li>
 *     <li>{@link JobType#PRESENTATION_EXPORT} „PPTX erzeugen“, nach 4: die Datei im Ausgabeordner.</li>
 * </ol>
 *
 * <p>Die Modellaufrufe je Folie und je Transkriptpaket laufen parallel ({@link Options#parallel()}). Was ein Auftrag
 * fertig hat, bleibt abgelegt; Fortsetzen im selben Eintrag fragt nur, was fehlt (ADR 0025). An den KI-Dienst gehen
 * nur Seiten mit KI-Freigabe (ADR 0028); verliert die Seite sie, fallen die Modelltexte weg ({@link #withdraw}).</p>
 */
public final class VideoPresentations {

    /** Komponente in den Diagnoseereignissen. */
    static final String COMPONENT = "Präsentation";
    /** Angabe der Aufträge: die Seite des Videos. */
    public static final String PAGE_PARAMETER = "page";
    /** Angabe von {@link JobType#PRESENTATION_EXPORT}: die erzeugte Datei. */
    public static final String FILE_PARAMETER = "file";
    /** Angabe aller Aufträge eines Vorgangs: der Auftrag „PPTX erzeugen“, an dem die Datei hängt. */
    public static final String RUN_PARAMETER = "presentation";

    /** Kennung des Text-Schritts „Transkript korrigieren“ für die Modellversion. */
    public static final String CORRECTION_STEP = "transcript-correction";
    /** Verfahren der Folienkommentare; ändern sich Anweisung oder Auswertung, steigt die Version. */
    static final String NOTES_VERSION = "slide-notes-v1";
    /** Verfahren der Korrektur; ändern sich Anweisung oder Auswertung, steigt die Version. */
    static final String CORRECTION_VERSION = "transcript-correction-v1";
    /** So viele Zeichen Transkript gehen höchstens in eine Korrekturanfrage. */
    static final int CORRECTION_CHUNK_CHARS = 3000;
    /** So viele Zeichen Transkript gehen höchstens in die Anfrage zu einer Folie. */
    static final int NOTES_EXCERPT_CHARS = 12_000;

    static final String CORRECTION_INSTRUCTIONS = "Du korrigierst ein automatisch erstelltes Transkript "
            + "(Spracherkennung) eines Vortrags. Korrigiere nur Erkennungsfehler: falsch verstandene Wörter, "
            + "Fachbegriffe, Namen, Abkürzungen und Zeichensetzung. Die Beschreibungen der Folien, die während des "
            + "Vortrags zu sehen waren, helfen dir bei der Schreibweise von Fachbegriffen und Namen. Ändere nicht den "
            + "Inhalt, kürze nicht, fasse nicht zusammen und ergänze nichts. Gib jeden Abschnitt mit genau derselben "
            + "Zeitangabe in eckigen Klammern am Zeilenanfang zurück, in derselben Reihenfolge, je Abschnitt eine "
            + "Zeile, getrennt durch eine Leerzeile. Antworte nur mit dem korrigierten Transkript.";

    static final String NOTES_INSTRUCTIONS = "Dies ist eine Folie aus der Videoaufzeichnung eines Vortrags. Darunter "
            + "steht, was der Vortragende gesagt hat, während die Folie zu sehen war. Schreibe daraus auf Deutsch den "
            + "Vortragstext für die Notizen dieser Folie: was der Vortragende zu dieser Folie erklärt, in ganzen "
            + "Sätzen, sachlich und in der Reihenfolge des Vortrags, mit Bezug auf die Inhalte der Folie. Erfinde "
            + "nichts, was weder auf der Folie steht noch gesagt wurde. Antworte nur mit dem Vortragstext, ohne "
            + "Überschrift.";

    private static final System.Logger LOG = System.getLogger(VideoPresentations.class.getName());

    /** Liest eine Seite für den Aufrufer, mit Rechteprüfung. */
    @FunctionalInterface
    public interface PageFetch {
        PageSnapshot page(AccessContext caller, String sourceId, String pageId);
    }

    /**
     * Einstellungen eines Vorgangs.
     *
     * @param parallel so viele Modellaufrufe laufen je Auftrag zugleich, mindestens 1
     */
    public record Options(int parallel) {

        /** Vier Aufrufe zugleich. */
        public static final Options DEFAULT = new Options(4);

        public Options {
            if (parallel < 1 || parallel > 32) {
                throw new IllegalArgumentException("parallel muss zwischen 1 und 32 liegen");
            }
        }
    }

    /** Die fünf Aufträge eines Vorgangs. */
    public record Run(JobStatus transcript, JobStatus slides, JobStatus correction, JobStatus notes, JobStatus file) {

        /** Alle in der Reihenfolge des Ablaufs. */
        public List<JobStatus> all() {
            return List.of(transcript, slides, correction, notes, file);
        }
    }

    private final Jobs jobs;
    private final PageFetch fetch;
    private final Assets assets;
    private final PageImageAnalysis images;
    private final VideoMarks slides;
    private final StepModel textModel;
    private final PresentationStore store;
    private final Predicate<PageKey> released;
    private final Path outputDirectory;

    /**
     * @param slides          die Folien der Videos ({@link VideoMarks#slides})
     * @param textModel       das Text-Modell für „Transkript korrigieren“
     * @param released        ob eine Seite an den KI-Dienst darf ({@code AiRelease::mayProcess})
     * @param outputDirectory wohin die Dateien kommen
     */
    public VideoPresentations(Jobs jobs, PageFetch fetch, Assets assets, PageImageAnalysis images, VideoMarks slides,
                              StepModel textModel, PresentationStore store, Predicate<PageKey> released,
                              Path outputDirectory) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.fetch = Objects.requireNonNull(fetch, "fetch");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.images = Objects.requireNonNull(images, "images");
        this.slides = Objects.requireNonNull(slides, "slides");
        this.textModel = Objects.requireNonNull(textModel, "textModel");
        this.store = Objects.requireNonNull(store, "store");
        this.released = Objects.requireNonNull(released, "released");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
    }

    /**
     * Was „In PPTX umwandeln“ für diese Seite jetzt fehlt; leer, wenn es starten kann. Fragt weder Quelle noch Modell.
     */
    public Optional<PresentationUnavailableException.Reason> unavailable(AccessContext caller, String sourceId,
                                                                         String pageId) {
        PageSnapshot snapshot = fetch.page(caller, sourceId, pageId);
        if (assets.describerInUse().isEmpty()) {
            return Optional.of(PresentationUnavailableException.Reason.NO_VISION_MODEL);
        }
        if (!textModel.available()) {
            return Optional.of(PresentationUnavailableException.Reason.NO_TEXT_MODEL);
        }
        if (!released.test(snapshot.key())) {
            return Optional.of(PresentationUnavailableException.Reason.NOT_RELEASED);
        }
        if (slides.find(snapshot.key(), snapshot.revision()).isEmpty()) {
            return Optional.of(PresentationUnavailableException.Reason.NO_SLIDES);
        }
        return Optional.empty();
    }

    /**
     * Startet die fünf Aufträge für das Video dieser Seite; der Aufrufer wartet nicht. Die Rechte auf die Seite prüft
     * {@link PageFetch}; Transkript und Standbilder ablegen darf nur, wer die Seite abgleichen darf, das prüft der
     * Aufrufer.
     *
     * @param media was die App mit dem Video kann; {@link VideoMedia#NONE} nimmt nur Abgelegtes
     * @throws PresentationUnavailableException wenn etwas fehlt ({@link #unavailable})
     */
    public Run start(AccessContext caller, String sourceId, String pageId, VideoMedia media, Options options) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(media, "media");
        Objects.requireNonNull(options, "options");
        Optional<PresentationUnavailableException.Reason> missing = unavailable(caller, sourceId, pageId);
        if (missing.isPresent()) {
            throw new PresentationUnavailableException(missing.get());
        }
        PageSnapshot snapshot = fetch.page(caller, sourceId, pageId);
        String space = snapshot.metadata().partitionKey();
        String owner = caller.jobOwner();
        String title = snapshot.metadata().title();
        Path file = outputDirectory.resolve(pageFolder(snapshot.key())).resolve(fileName(title));
        String run = java.util.UUID.randomUUID().toString();
        Map<String, String> parameters = Map.of(PAGE_PARAMETER, pageId, RUN_PARAMETER, run);
        JobStatus transcript = jobs.submit(JobType.VIDEO_TRANSCRIPT, sourceId, space, pageId, owner, parameters,
                JobTask.blocking(context -> transcribe(caller, sourceId, pageId, media, context)));
        JobStatus described = jobs.submit(JobType.VIDEO_SLIDES, sourceId, space, pageId, owner, parameters,
                JobTask.blocking(context -> describeSlides(caller, sourceId, pageId, media, options, context)));
        JobStatus correction = jobs.submitAfter(JobType.TRANSCRIPT_CORRECTION, sourceId, space, pageId, owner,
                parameters, JobTask.blocking(context -> correct(caller, sourceId, pageId, options, context)),
                List.of(transcript.id(), described.id()));
        JobStatus notes = jobs.submitAfter(JobType.SLIDE_NOTES, sourceId, space, pageId, owner, parameters,
                JobTask.blocking(context -> writeNotes(caller, sourceId, pageId, options, context)),
                correction.id());
        Map<String, String> fileParameters = new HashMap<>(parameters);
        fileParameters.put(FILE_PARAMETER, file.toString());
        JobStatus output = jobs.submitAfter(JobType.PRESENTATION_EXPORT, sourceId, space, pageId, owner,
                fileParameters, JobTask.blocking(context -> writeFile(caller, sourceId, pageId, file, context)),
                notes.id());
        return new Run(transcript, described, correction, notes, output);
    }

    /**
     * Die Datei eines fertigen Auftrags „PPTX erzeugen“; leer, solange sie nicht geschrieben ist.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.UnknownJobException wenn der Auftrag nicht diesem
     *                                                                           Auftraggeber gehört
     */
    public Optional<Path> file(String jobId, String owner) {
        JobStatus status = jobs.status(jobId, owner);
        if (status.type() != JobType.PRESENTATION_EXPORT || status.state() != JobState.COMPLETED) {
            return Optional.empty();
        }
        String file = status.parameters().get(FILE_PARAMETER);
        return file == null ? Optional.empty() : Optional.of(Path.of(file)).filter(Files::isRegularFile);
    }

    /**
     * Nimmt die Modelltexte der Seiten zurück, die jetzt nicht mehr an den KI-Dienst dürfen (ADR 0028); die erzeugten
     * Dateien bleiben, sie gehören dem Benutzer.
     */
    public void withdraw(java.util.Collection<PageKey> pages, Predicate<PageKey> stillReleased) {
        for (PageKey page : pages) {
            if (!stillReleased.test(page) && store.has(page)) {
                store.remove(page);
            }
        }
    }

    // --- 1. Transkript ziehen -------------------------------------------------------------------------------------

    private void transcribe(AccessContext caller, String sourceId, String pageId, VideoMedia media,
                            JobContext context) throws Exception {
        context.phase(COMPONENT, "Transkript");
        PageSnapshot snapshot = fetch.page(caller, sourceId, pageId);
        context.rootTitle(snapshot.metadata().title());
        context.discovered(1);
        String version = snapshot.revision();
        String name = transcriptName(snapshot.metadata().title());
        String existing = assets.videoTranscript(sourceId, pageId, version).orElse(null);
        String text = media.transcribe(existing, partial -> {
            if (partial != null && !partial.isBlank()) {
                assets.putVideoTranscript(sourceId, pageId, version, name, partial);
            }
        }, context::cancelRequested);
        context.checkpoint();
        if (text == null || TranscriptSections.parse(text).isEmpty()) {
            throw new JobFailedException("NO_TRANSCRIPT", "Für dieses Video gibt es kein Transkript, und die App konnte "
                    + "keines erzeugen.");
        }
        if (!text.equals(existing)) {
            assets.putVideoTranscript(sourceId, pageId, version, name, text);
        }
        context.processed(1);
        context.written(1);
    }

    // --- 2. Folien beschreiben ------------------------------------------------------------------------------------

    private void describeSlides(AccessContext caller, String sourceId, String pageId, VideoMedia media,
                                Options options, JobContext context) throws Exception {
        context.phase(COMPONENT, "Standbilder");
        PageSnapshot snapshot = fetch.page(caller, sourceId, pageId);
        context.rootTitle(snapshot.metadata().title());
        PageKey key = snapshot.key();
        String version = snapshot.revision();
        List<Long> marks = slides.find(key, version);
        List<Long> missing = assets.missingVideoFrames(sourceId, pageId, version, marks);
        if (!missing.isEmpty()) {
            media.frames(missing, (millis, png) -> {
                if (png != null && png.length > 0) {
                    assets.putVideoFrame(sourceId, pageId, version, new Assets.VideoFrame(millis, png));
                }
            }, context::cancelRequested);
            context.checkpoint();
            // Ein neues Standbild kann eine Marke als Wiederholung derselben Folie entfallen lassen.
            marks = slides.find(key, version);
        }
        int limit = assets.slideThresholds().maxFrames();
        List<Long> slideMarks = marks.size() > limit ? marks.subList(0, limit) : marks;
        context.discovered(slideMarks.size());
        context.phase(COMPONENT, "Folien beschreiben");
        Map<String, ImagePayload.Entry> known = new HashMap<>();
        for (ImagePayload.Entry entry : images.read(snapshot).images()) {
            if (entry.assetId() != null) {
                known.putIfAbsent(entry.assetId(), entry);
            }
        }
        List<Integer> open = new ArrayList<>();
        long withoutFrame = 0;
        for (int index = 0; index < slideMarks.size(); index++) {
            long millis = slideMarks.get(index);
            Optional<Asset> frame = assets.videoFrameAsset(sourceId, pageId, version, millis);
            ImagePayload.Entry entry = known.get(Assets.markAttachmentId(pageId, millis));
            if (frame.isEmpty()) {
                // Ohne Standbild keine Folie in der Datei.
                context.processed(1);
                context.failed(1);
                withoutFrame++;
            } else if (entry != null && entry.status() == ImageStatus.DESCRIBED && entry.description() != null
                    && Objects.equals(entry.sha256(), frame.get().sha256())) {
                context.processed(1);
                context.written(1);
            } else {
                open.add(index);
            }
        }
        AtomicLong failed = new AtomicLong(withoutFrame);
        inParallel(open, options.parallel(), context, index -> {
            long millis = slideMarks.get(index);
            PageImages result = images.describeOne(snapshot, index, false, false);
            boolean described = result.images().stream().anyMatch(entry -> Assets.markAttachmentId(pageId, millis)
                    .equals(entry.assetId()) && entry.status() == ImageStatus.DESCRIBED);
            context.processed(1);
            if (described) {
                context.written(1);
            } else {
                context.failed(1);
                failed.incrementAndGet();
            }
        });
        if (failed.get() > 0) {
            context.incomplete();
        }
    }

    // --- 3. Transkript korrigieren --------------------------------------------------------------------------------

    private void correct(AccessContext caller, String sourceId, String pageId, Options options, JobContext context) {
        context.phase(COMPONENT, "Transkript korrigieren");
        PageSnapshot snapshot = fetch.page(caller, sourceId, pageId);
        context.rootTitle(snapshot.metadata().title());
        PageKey key = snapshot.key();
        String version = snapshot.revision();
        requireReleased(key);
        if (!textModel.available()) {
            throw new JobFailedException("NO_TEXT_MODEL", "Für „Transkript korrigieren“ ist kein Text-Modell "
                    + "(Chat-Modell) eingestellt.");
        }
        String transcript = assets.videoTranscript(sourceId, pageId, version).orElseThrow(() ->
                new JobFailedException("NO_TRANSCRIPT", "Für dieses Video gibt es kein Transkript."));
        List<TranscriptSections.Section> sections = TranscriptSections.parse(transcript);
        Map<Long, String> descriptions = slideDescriptions(snapshot);
        List<Long> marks = new ArrayList<>(descriptions.keySet());
        String model = CORRECTION_VERSION + "|" + textModel.version();
        String input = Assets.sha256((model + "\n" + transcript + "\n" + descriptions)
                .getBytes(StandardCharsets.UTF_8));
        Optional<PresentationStore.Result> stored = store.corrected(key, version);
        List<List<TranscriptSections.Section>> chunks = TranscriptSections.chunks(sections, CORRECTION_CHUNK_CHARS);
        context.discovered(chunks.size());
        if (stored.isPresent() && stored.get().input().equals(input)) {
            context.processed(chunks.size());
            context.written(chunks.size());
            return;
        }
        List<List<TranscriptSections.Section>> corrected = new ArrayList<>(chunks);
        AtomicLong failed = new AtomicLong();
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            indices.add(i);
        }
        inParallel(indices, options.parallel(), context, index -> {
            List<TranscriptSections.Section> chunk = chunks.get(index);
            List<TranscriptSections.Section> result = null;
            for (int attempt = 0; attempt <= retries() && result == null && !context.cancelRequested(); attempt++) {
                try {
                    String answer = textModel.complete(CORRECTION_INSTRUCTIONS,
                            correctionInput(chunk, descriptions, marks));
                    result = acceptCorrection(chunk, answer);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Korrektur eines Transkriptpakets gescheitert ({0})",
                            e.getClass().getName());
                }
            }
            synchronized (corrected) {
                if (result != null) {
                    corrected.set(index, result);
                }
            }
            context.processed(1);
            if (result != null) {
                context.written(1);
            } else {
                context.failed(1);
                failed.incrementAndGet();
            }
        });
        requireReleased(key);
        List<TranscriptSections.Section> all = new ArrayList<>();
        corrected.forEach(all::addAll);
        // Gescheiterte Pakete bleiben unkorrigiert stehen; ein neuer Lauf fragt dann alles erneut.
        store.saveCorrected(key, version, new PresentationStore.Result(TranscriptSections.render(all), model,
                failed.get() == 0 ? input : "incomplete:" + input));
        if (failed.get() > 0) {
            context.incomplete();
        }
    }

    /** Folien, deren Zeit sich mit dem Paket überschneidet, mit ihrer Beschreibung, dann das Paket. */
    static String correctionInput(List<TranscriptSections.Section> chunk, Map<Long, String> descriptions,
                                  List<Long> marks) {
        long from = chunk.get(0).startMillis();
        long to = Math.max(chunk.get(chunk.size() - 1).endMillis(), chunk.get(chunk.size() - 1).startMillis()) + 1;
        StringBuilder input = new StringBuilder("Folien:\n");
        boolean any = false;
        for (int i = 0; i < marks.size(); i++) {
            long start = marks.get(i);
            long end = i + 1 < marks.size() ? marks.get(i + 1) : Long.MAX_VALUE;
            if (start < to && end > from) {
                input.append('[').append(TranscriptSections.clock(start)).append("] ")
                        .append(descriptions.get(start).strip()).append('\n');
                any = true;
            }
        }
        if (!any) {
            input.append("(keine)\n");
        }
        input.append("\nTranskript:\n").append(TranscriptSections.render(chunk));
        return input.toString();
    }

    /**
     * Die Antwort des Modells, wenn sie dieselben Abschnitte mit denselben Anfängen trägt; ein einzelner Abschnitt
     * darf ohne Zeitangabe zurückkommen. Sonst {@code null}: das Paket bleibt, wie es war.
     */
    static List<TranscriptSections.Section> acceptCorrection(List<TranscriptSections.Section> chunk, String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        List<TranscriptSections.Section> parsed = TranscriptSections.parse(answer.strip());
        if (parsed.size() == 1 && chunk.size() == 1 && parsed.get(0).startMillis() == 0
                && !answer.strip().startsWith("[")) {
            TranscriptSections.Section only = chunk.get(0);
            return List.of(new TranscriptSections.Section(only.startMillis(), only.endMillis(),
                    parsed.get(0).text()));
        }
        if (parsed.size() != chunk.size()) {
            return null;
        }
        List<TranscriptSections.Section> result = new ArrayList<>(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            TranscriptSections.Section original = chunk.get(i);
            TranscriptSections.Section answered = parsed.get(i);
            if (answered.startMillis() / 1000 != original.startMillis() / 1000) {
                return null;
            }
            result.add(new TranscriptSections.Section(original.startMillis(), original.endMillis(), answered.text()));
        }
        return result;
    }

    // --- 4. Folienkommentare --------------------------------------------------------------------------------------

    private void writeNotes(AccessContext caller, String sourceId, String pageId, Options options,
                            JobContext context) {
        context.phase(COMPONENT, "Folienkommentare");
        PageSnapshot snapshot = fetch.page(caller, sourceId, pageId);
        context.rootTitle(snapshot.metadata().title());
        PageKey key = snapshot.key();
        String version = snapshot.revision();
        requireReleased(key);
        ImageDescriber vision = assets.describerInUse().orElseThrow(() -> new JobFailedException("NO_VISION_MODEL",
                "Für „Folienkommentare“ ist kein Vision-Modell eingestellt."));
        List<TranscriptSections.Section> sections = TranscriptSections.parse(transcriptFor(key, version));
        List<Long> marks = slideMarks(key, version);
        context.discovered(marks.size());
        Map<Long, PresentationStore.Result> stored = store.notes(key, version);
        String model = NOTES_VERSION + "|" + vision.id() + "|" + vision.modelVersion();
        List<Integer> open = new ArrayList<>();
        Map<Integer, String> inputs = new HashMap<>();
        Map<Integer, Assets.AssetContent> frames = new HashMap<>();
        long missingFrames = 0;
        for (int i = 0; i < marks.size(); i++) {
            long millis = marks.get(i);
            Optional<Assets.AssetContent> frame = assets.videoFrame(sourceId, pageId, version, millis);
            if (frame.isEmpty()) {
                context.processed(1);
                context.failed(1);
                missingFrames++;
                continue;
            }
            String excerpt = excerpt(sections, millis, i + 1 < marks.size() ? marks.get(i + 1) : Long.MAX_VALUE);
            String input = Assets.sha256((model + "\n" + frame.get().asset().sha256() + "\n" + excerpt)
                    .getBytes(StandardCharsets.UTF_8));
            PresentationStore.Result note = stored.get(millis);
            if (note != null && note.input().equals(input)) {
                context.processed(1);
                context.written(1);
                continue;
            }
            open.add(i);
            inputs.put(i, excerpt + "\u0000" + input);
            frames.put(i, frame.get());
        }
        AtomicLong failed = new AtomicLong(missingFrames);
        inParallel(open, options.parallel(), context, index -> {
            long millis = marks.get(index);
            String[] parts = inputs.get(index).split("\u0000", 2);
            String instruction = NOTES_INSTRUCTIONS + "\n\nGesagt:\n"
                    + (parts[0].isBlank() ? "(Während dieser Folie wurde nichts gesagt.)" : parts[0]);
            String text = null;
            for (int attempt = 0; attempt <= vision.retries() && text == null && !context.cancelRequested();
                    attempt++) {
                try {
                    String answer = vision.describe(frames.get(index).content(), "image/png", instruction);
                    text = answer == null || answer.isBlank() ? null : answer.strip();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Folienkommentar gescheitert ({0})", e.getClass().getName());
                }
            }
            if (text != null && released.test(key)) {
                store.saveNote(key, version, millis, new PresentationStore.Result(text, model, parts[1]));
            }
            context.processed(1);
            if (text != null) {
                context.written(1);
            } else {
                context.failed(1);
                failed.incrementAndGet();
            }
        });
        requireReleased(key);
        if (failed.get() > 0) {
            context.incomplete();
        }
    }

    /** Der Transkriptauszug zur Zeit einer Folie, höchstens {@link #NOTES_EXCERPT_CHARS} Zeichen. */
    static String excerpt(List<TranscriptSections.Section> sections, long from, long to) {
        String text = TranscriptSections.render(TranscriptSections.between(sections, from, to)).strip();
        return text.length() <= NOTES_EXCERPT_CHARS ? text : text.substring(0, NOTES_EXCERPT_CHARS) + " …";
    }

    // --- 5. PPTX erzeugen -----------------------------------------------------------------------------------------

    private void writeFile(AccessContext caller, String sourceId, String pageId, Path file, JobContext context)
            throws IOException {
        context.phase(COMPONENT, "PPTX erzeugen");
        PageSnapshot snapshot = fetch.page(caller, sourceId, pageId);
        context.rootTitle(snapshot.metadata().title());
        PageKey key = snapshot.key();
        String version = snapshot.revision();
        List<Long> marks = slideMarks(key, version);
        context.discovered(marks.size());
        Map<Long, PresentationStore.Result> notes = store.notes(key, version);
        List<PptxExportWriter.Slide> content = new ArrayList<>();
        boolean incomplete = false;
        for (long millis : marks) {
            context.checkpoint();
            Optional<Assets.AssetContent> frame = assets.videoFrame(sourceId, pageId, version, millis);
            context.processed(1);
            if (frame.isEmpty()) {
                context.failed(1);
                incomplete = true;
                continue;
            }
            PresentationStore.Result note = notes.get(millis);
            incomplete |= note == null;
            content.add(new PptxExportWriter.Slide(frame.get().content(), Assets.markTitle(millis),
                    note == null ? null : note.text()));
            context.written(1);
        }
        if (content.isEmpty()) {
            throw new JobFailedException("NO_SLIDES", "Keine Folie hat ein Standbild; es wurde keine Datei erzeugt.");
        }
        Files.createDirectories(file.getParent());
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try (OutputStream out = Files.newOutputStream(partial)) {
            new PptxExportWriter().write(content, out);
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (incomplete) {
            context.incomplete();
        }
    }

    // --- gemeinsam ------------------------------------------------------------------------------------------------

    /** Die Folien mit ihrer Position, höchstens so viele, wie ein Video Marken haben darf. */
    private List<Long> slideMarks(PageKey key, String version) {
        List<Long> marks = slides.find(key, version);
        int limit = assets.slideThresholds().maxFrames();
        return marks.size() > limit ? marks.subList(0, limit) : marks;
    }

    /** Das korrigierte Transkript, sonst das abgelegte. */
    private String transcriptFor(PageKey key, String version) {
        return store.corrected(key, version).map(PresentationStore.Result::text)
                .or(() -> assets.videoTranscript(key.sourceId(), key.pageId(), version))
                .orElseThrow(() -> new JobFailedException("NO_TRANSCRIPT", "Für dieses Video gibt es kein Transkript."));
    }

    /** Die Beschreibungen der Folien aus „Folien beschreiben“ je Position, in zeitlicher Reihenfolge. */
    private Map<Long, String> slideDescriptions(PageSnapshot snapshot) {
        Map<Long, String> descriptions = new java.util.TreeMap<>();
        for (ImagePayload.Entry entry : images.read(snapshot).images()) {
            if (entry.status() == ImageStatus.DESCRIBED && entry.description() != null
                    && Assets.videoMark(entry.assetId())) {
                descriptions.put(Assets.markMillis(entry.assetId()), entry.description());
            }
        }
        return new LinkedHashMap<>(descriptions);
    }

    private void requireReleased(PageKey key) {
        if (!released.test(key)) {
            throw new JobFailedException("NOT_RELEASED", "Diese Seite ist nicht für den KI-Dienst freigegeben; was "
                    + "schon fertig ist, bleibt.");
        }
    }

    private int retries() {
        return assets.describerInUse().map(ImageDescriber::retries).orElse(1);
    }

    /**
     * Führt {@code work} je Eintrag mit höchstens {@code parallel} Aufrufen zugleich aus. Nach Abbruch oder Anhalten
     * beginnt kein neuer; laufende enden noch, und was sie liefern, bleibt.
     */
    static <T> void inParallel(List<T> items, int parallel, JobContext context, Consumer<T> work) {
        if (items.isEmpty()) {
            context.checkpoint();
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallel, items.size()),
                Thread.ofPlatform().daemon().name("video-presentation-", 0).factory());
        try {
            List<Future<?>> running = new ArrayList<>(items.size());
            for (T item : items) {
                running.add(pool.submit(() -> {
                    if (!context.cancelRequested()) {
                        work.accept(item);
                    }
                }));
            }
            for (Future<?> future : running) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JobCancelledException(context.jobId());
                }
            }
        } finally {
            pool.shutdown();
        }
        context.checkpoint();
    }

    /** Der Dateiname der Präsentation nach dem Video, etwa {@code Vortrag.pptx}. */
    static String fileName(String title) {
        return baseName(title) + ".pptx";
    }

    /** Der Dateiname des Transkripts nach dem Video, wie in der App, etwa {@code Vortrag – Transkript.txt}. */
    static String transcriptName(String title) {
        return baseName(title) + " – Transkript.txt";
    }

    private static String baseName(String title) {
        String base = title == null || title.isBlank() ? "Video" : title.strip();
        int dot = base.lastIndexOf('.');
        if (dot > 0 && base.length() - dot <= 5) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (base.length() > 100) {
            base = base.substring(0, 100).strip();
        }
        return base.isEmpty() ? "Video" : base;
    }

    /** Ein Ordner je Seite, damit gleichnamige Videos verschiedener Seiten sich nicht überschreiben. */
    private static String pageFolder(PageKey key) {
        return Assets.sha256((key.sourceId() + "\n" + key.pageId()).getBytes(StandardCharsets.UTF_8))
                .substring("sha256:".length(), "sha256:".length() + 12);
    }
}
