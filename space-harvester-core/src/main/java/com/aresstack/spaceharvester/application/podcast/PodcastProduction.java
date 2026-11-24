package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Segment;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Turn;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.IntSupplier;
import java.util.function.ObjIntConsumer;

/**
 * Die Herstellung eines Podcasts, gleich wofür (Update-Zusammenfassung oder Seite): Grobübersicht, dann je Thema
 * Abschnitte, Texte, Audio und Bild, alles parallel ({@code ai.parallel}) in einem {@link RankedPool}, der immer zuerst
 * nimmt, was die Wiedergabe als Nächstes braucht: Der erste Abschnitt spricht so früh wie möglich, die übrigen Fäden
 * rechnen die folgenden Themen voraus. Auswege und fehlende Abschnitte stehen in der Diagnose des Auftrags. Jede
 * Stufe liegt sofort in der {@link PodcastStore Ablage}; ein neuer Lauf macht dort weiter, wo der letzte aufhörte, und
 * jede abgelegte Stufe meldet {@link #subscribe} (ADR 0027, kein Polling).
 */
final class PodcastProduction {

    private static final System.Logger LOG = System.getLogger(PodcastProduction.class.getName());
    /** Komponente in der Diagnose. */
    static final String COMPONENT = "Podcast";
    /** Höchstens so viele Arten von Auswegen je Lauf in der Diagnose. */
    static final int MAX_REPORTED_REPAIRS = 20;

    private final StepModel model;
    private final PodcastSpeech speech;
    private final PodcastStore store;
    private final PodcastVoices voices;
    private final IntSupplier parallel;
    /**
     * Eigene Grenzen (Angelo 24.11.2026): wie viele Texte und Audios zugleich entstehen und, in einem eigenen Pool, wie
     * viele Modellaufrufe der Bilder; {@code null} = ai.parallel für die Texte, {@link #DEFAULT_PICTURES} für Bilder.
     */
    private volatile IntSupplier texts;
    private volatile IntSupplier pictureCalls;
    /** Standard des Bilder-Pools: sonst kommen die Bilder nach dem ersten meist zu spät (Angelo 24.11.2026). */
    static final int DEFAULT_PICTURES = 8;
    /** Wartet vor einem weiteren Versuch; in Tests ohne Pause. */
    private final ObjIntConsumer<JobContext> waits;
    private final Listeners<Runnable> listeners = new Listeners<>("Podcast");
    /** Zurückgezogene Podcasts mit laufendem Auftrag: sie legen nichts mehr ab und löschen am Ende ihre Ablage. */
    private final java.util.Set<String> revoked = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Woher die Bilder je Thema kommen; {@code null} = keine Bilder. */
    private volatile Pictures pictures;

    /**
     * Die Bilder je Thema (Angelo 21.11.2026): wer Schaubilder zeichnet und woher die Seitenbilder kommen.
     *
     * @param drawer planen und zeichnen die Schaubilder (Bildgenerierung – Plan und – Einzelteile)
     */
    record Pictures(DiagramComposer.Models drawer, PodcastPictures.PageImageSource images) {
        Pictures {
            Objects.requireNonNull(drawer, "drawer");
            Objects.requireNonNull(images, "images");
        }
    }

    PodcastProduction(StepModel model, PodcastSpeech speech, PodcastStore store, PodcastVoices voices,
                      IntSupplier parallel, ObjIntConsumer<JobContext> waits) {
        this.model = Objects.requireNonNull(model, "model");
        this.speech = Objects.requireNonNull(speech, "speech");
        this.store = Objects.requireNonNull(store, "store");
        this.voices = Objects.requireNonNull(voices, "voices");
        this.parallel = Objects.requireNonNull(parallel, "parallel");
        this.waits = Objects.requireNonNull(waits, "waits");
    }

    PodcastStore store() {
        return store;
    }

    /**
     * Die eigenen Grenzen der Parallelität; gelten ab dem nächsten Lauf. Der Plan entsteht immer zuerst und allein,
     * danach laufen Texte und Audio im einen, die Bilder im anderen Pool.
     *
     * @param texts    wie viele Abschnitte, Texte und Audios zugleich entstehen
     * @param pictures wie viele Modellaufrufe der Bilder (Auswahl, Plan, Symbole) zugleich laufen
     */
    void parallel(IntSupplier texts, IntSupplier pictures) {
        this.texts = Objects.requireNonNull(texts, "texts");
        this.pictureCalls = Objects.requireNonNull(pictures, "pictures");
    }

    /** Die Größe des Pools für Texte und Audio. */
    int textThreads() {
        IntSupplier own = texts;
        return Math.max(1, (own == null ? parallel : own).getAsInt());
    }

    /** Die Größe des Pools für die Bilder. */
    int pictureThreads() {
        IntSupplier own = pictureCalls;
        return Math.max(1, own == null ? DEFAULT_PICTURES : own.getAsInt());
    }

    /** Schaltet die Bilder je Thema ein. */
    void pictures(Pictures pictures) {
        this.pictures = Objects.requireNonNull(pictures, "pictures");
    }

    /** Ob Chat-Modell und ein Dienst mit Sprachausgabe eingerichtet sind. */
    boolean available() {
        return model.available() && speech.available();
    }

    /** Der Stand eines Podcasts; leer, solange nichts erzeugt ist und kein Auftrag läuft. */
    Optional<Podcast> podcast(String key, boolean running) {
        Optional<PodcastPlan> plan = store.plan(key);
        if (plan.isEmpty()) {
            return store.meta(key).isPresent() || running ? Optional.of(new Podcast(key, null, List.of(), running))
                    : Optional.empty();
        }
        List<Podcast.Part> parts = new ArrayList<>();
        boolean pending = false;
        for (int t = 0; t < plan.get().topics().size(); t++) {
            String topicTitle = plan.get().topics().get(t).title();
            Optional<PodcastPictures.Picture> decided = PodcastPictures.stored(store, key, t);
            pending |= decided.isEmpty();
            Podcast.Picture picture = decided
                    .filter(found -> found.file() != null)
                    .map(found -> new Podcast.Picture(found.file(), found.mediaType(), found.caption()))
                    .orElse(null);
            Optional<List<Segment>> segments = store.segments(key, t);
            if (segments.isEmpty()) {
                parts.add(new Podcast.Part(t, -1, topicTitle, null, null, picture));
                continue;
            }
            for (int s = 0; s < segments.get().size(); s++) {
                List<Turn> script = store.script(key, t, s).orElse(List.of());
                // Gemessen beim Vertonen (für die Untertitel); passt die Messung nicht zum Text, gilt sie nicht.
                List<Long> starts = store.starts(key, t, s);
                boolean measured = starts.size() == script.size();
                List<Podcast.Line> lines = new ArrayList<>();
                for (int i = 0; i < script.size(); i++) {
                    Turn turn = script.get(i);
                    if (!turn.text().isBlank()) {
                        lines.add(new Podcast.Line(turn.speaker() == PodcastPlan.Speaker.HOST, turn.text(),
                                measured ? starts.get(i) : -1));
                    }
                }
                parts.add(new Podcast.Part(t, s, topicTitle, segments.get().get(s).title(),
                        store.audio(key, t, s).orElse(null), picture, lines));
            }
        }
        // Fehlen noch Bilder, ist der Podcast nicht fertig: Ein neuer Lauf zeichnet nur sie (Audio bleibt).
        Pictures configured = pictures;
        boolean picturesPending = pending && configured != null && configured.drawer().available();
        return Optional.of(new Podcast(key, plan.get().title(), parts, running, picturesPending));
    }

    /**
     * Der gesprochene Text des Podcasts, soweit er schon geschrieben ist (Angelo 22.11.2026: zum Kopieren und
     * Speichern, um ihn zu prüfen): der Titel, je Thema eine Überschrift und darunter die Beiträge in Sendereihenfolge,
     * jeder mit seinem Sprecher. Leer, solange kein Abschnitt geschrieben ist.
     */
    Optional<String> transcript(String key) {
        Optional<PodcastPlan> plan = store.plan(key);
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        boolean any = false;
        for (int t = 0; t < plan.get().topics().size(); t++) {
            int segments = store.segments(key, t).map(List::size).orElse(0);
            StringBuilder topic = new StringBuilder();
            for (int s = 0; s < segments; s++) {
                for (Turn turn : store.script(key, t, s).orElse(List.of())) {
                    if (!turn.text().isBlank()) {
                        topic.append('\n').append(turn.speaker() == PodcastPlan.Speaker.HOST ? HOST_LABEL
                                : COHOST_LABEL).append(": ").append(turn.text()).append('\n');
                    }
                }
            }
            if (!topic.isEmpty()) {
                any = true;
                String title = plan.get().topics().get(t).title();
                text.append("\n## ").append(title == null || title.isBlank() ? "Thema " + (t + 1) : title.strip())
                        .append('\n').append(topic);
            }
        }
        if (!any) {
            return Optional.empty();
        }
        String title = plan.get().title();
        return Optional.of(((title == null || title.isBlank() ? "" : "# " + title.strip() + "\n") + text).strip()
                + "\n");
    }

    /** Wie die beiden Stimmen im Transkript heißen. */
    static final String HOST_LABEL = "Gastgeber";
    static final String COHOST_LABEL = "Co-Host";

    /**
     * Zieht einen Podcast zurück, dessen Auftrag noch läuft (eine beteiligte Seite verlor die KI-Freigabe, ADR 0028):
     * der Lauf legt nichts mehr ab, bricht ab und löscht beim Ende alles, was er schon hatte. Ohne laufenden Auftrag
     * löscht der Aufrufer die Ablage selbst.
     */
    void revoke(String key) {
        revoked.add(Objects.requireNonNull(key, "key"));
    }

    /** Wirft, sobald der Podcast zurückgezogen ist; vor jedem Ablegen. */
    private void guard(String key) {
        if (revoked.contains(key)) {
            throw new IllegalStateException("Podcast zurückgezogen: KI-Freigabe entzogen");
        }
    }

    /** Meldet jede abgelegte Stufe dieses Podcasts. */
    Subscription subscribe(String key, Runnable listener) {
        Objects.requireNonNull(key, "key");
        return listeners.add(listener, key::equals);
    }

    /**
     * Erzeugt, was fehlt.
     *
     * @param origin wofür der Podcast entsteht; Modell und Stimmen setzt der Lauf
     */
    void run(JobContext context, PodcastStore.Meta origin, PodcastWriter.Material material) throws Exception {
        try {
            produce(context, origin, material);
        } finally {
            if (revoked.remove(origin.key())) {
                store.delete(meta -> meta.key().equals(origin.key()));
                notify(origin.key());
            }
        }
    }

    private void produce(JobContext context, PodcastStore.Meta origin, PodcastWriter.Material material)
            throws Exception {
        String key = origin.key();
        guard(key);
        context.phase("Podcast", "Sprachmodell");
        // Eigene Einstellung „Podcast – Sprache“ (Angelo 24.11.2026); leer: das bevorzugte TTS-Modell des Dienstes.
        com.aresstack.spaceharvester.ai.AiServiceSettings.Speech chosen = speech.choice();
        String speechModel = chosen.podcastModel();
        if (speechModel == null) {
            List<String> models = voices.ranked(speech.models());
            if (models.isEmpty()) {
                throw new JobFailedException("NO_SPEECH_MODEL", "Der KI-Dienst bietet kein Sprachmodell (TTS) an");
            }
            speechModel = models.get(0);
        }
        // Ein Modell und zwei feste Stimmen für den ganzen Podcast, auch über Neustarts hinweg (Angelo 22.11.2026);
        // eingestellte Stimmen gelten immer, sonst die gemerkten, sonst die Standardstimmen der Familie.
        String ttsModel = speechModel;
        PodcastVoices.Pair automatic = store.meta(key).filter(meta -> ttsModel.equals(meta.model()))
                .map(meta -> new PodcastVoices.Pair(meta.host(), meta.cohost())).orElse(voices.pair(ttsModel));
        PodcastVoices.Pair pair = new PodcastVoices.Pair(
                chosen.podcastHost() != null ? chosen.podcastHost() : automatic.host(),
                chosen.podcastCohost() != null ? chosen.podcastCohost() : automatic.cohost());
        PodcastSpeaker speaker = new PodcastSpeaker(speech, speechModel, pair);
        store.saveMeta(origin.voiced(speaker.model(), pair.host(), pair.cohost()));
        notify(key);
        PodcastWriter writer = new PodcastWriter(model, attempt -> waits.accept(context, attempt));
        PodcastWriter.Voices names = new PodcastWriter.Voices(pair.host(), pair.cohost());
        List<String> repairs = Collections.synchronizedList(new ArrayList<>());

        context.phase("Podcast", "Grobübersicht");
        PodcastPlan plan = store.plan(key).orElse(null);
        if (plan == null) {
            plan = writer.outline(material);
            guard(key);
            store.savePlan(key, plan);
            repairs.addAll(plan.repairs());
            notify(key);
        }
        context.checkpoint();
        int topics = plan.topics().size();
        context.discovered(topics);
        int threads = textThreads();
        RankedPool pool = new RankedPool(threads, "space-harvester-podcast-" + key.substring(0, 6));
        RankedPool picturePool = null;
        Future<?> drawing = null;
        java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        // Jedes Ablegen geht durch diesen Zaun: Nach dem Ende des Laufs (oder dem Zurückziehen) legt kein Faden aus dem
        // Pool mehr etwas ab, auch wenn er seinen Modellaufruf erst später beendet; run() darf dann löschen.
        PodcastPictures.Fence fence = write -> {
            synchronized (ended) {
                if (ended.get() || revoked.contains(key)) {
                    throw new PodcastPictures.Closed("Podcast-Lauf beendet oder zurückgezogen");
                }
                write.run();
            }
        };
        // Ein speak()-Aufruf stellt selbst bis zu speech.workers() Anfragen: zusammen höchstens ai.parallel.
        java.util.concurrent.Semaphore voices = new java.util.concurrent.Semaphore(
                Math.max(1, threads / Math.max(1, speech.workers())));
        try {
            final PodcastPlan planned = plan;
            // Alles auf einmal einreichen, der Rang ordnet (Angelo 22.11.2026: Start dauert, stockt nach Kapitel 1):
            // je Thema Abschnitte, dann alle Texte des Themas parallel, jedes Audio sofort nach seinem Text, auch
            // parallel. Thema 1 hat immer Vorrang, damit die Wiedergabe schnell beginnt; die übrigen Fäden rechnen die
            // folgenden Themen voraus.
            List<CompletableFuture<List<CompletableFuture<Boolean>>>> chapters = new ArrayList<>();
            for (int t = 0; t < topics; t++) {
                final int topic = t;
                chapters.add(pool.supply(rank(topic, Stage.SEGMENTS, 0),
                        () -> segments(writer, key, material, planned, topic, names, repairs, fence))
                        .thenApply(segments -> {
                    List<CompletableFuture<Boolean>> audio = new ArrayList<>();
                    for (int s = 0; s < segments.size(); s++) {
                        final int segment = s;
                        audio.add(pool.supply(rank(topic, Stage.SCRIPT, segment), () -> {
                            Segment before = segment > 0 ? segments.get(segment - 1) : previousLast(key, topic);
                            boolean last = topic == topics - 1 && segment == segments.size() - 1;
                            return script(writer, key, material, planned, topic, segments, segment, before, last,
                                    names, repairs, fence);
                        }).thenCompose(turns -> pool.supply(rank(topic, Stage.SPEECH, segment),
                                () -> speak(speaker, voices, key, topic, segment, turns, repairs, fence))));
                    }
                    return audio;
                }));
            }
            // Bilder je Thema erst, wenn der erste Abschnitt spricht, und hinter Text und Audio ihres Themas. Die
            // Wiedergabe wartet nie darauf.
            Pictures configured = pictures;
            if (configured != null && topics > 0) {
                CompletableFuture<?> firstAudio = chapters.get(0).thenCompose(audio -> audio.isEmpty()
                        ? CompletableFuture.completedFuture(Boolean.FALSE) : audio.get(0));
                // Eigener Pool für die Bilder, damit sie nicht hinter allen Texten warten (Angelo 24.11.2026).
                picturePool = new RankedPool(pictureThreads(), "space-harvester-podcast-bild-" + key.substring(0, 6));
                drawing = picturesThread(key, origin.sourceId(), material, planned, configured, picturePool,
                        firstAudio, context, repairs, fence);
            }
            context.phase("Podcast", "Texte und Sprachausgabe");
            List<int[]> failed = new ArrayList<>();
            for (int t = 0; t < topics; t++) {
                List<CompletableFuture<Boolean>> audio = await(chapters.get(t), context);
                for (int s = 0; s < audio.size(); s++) {
                    if (!await(audio.get(s), context)) {
                        failed.add(new int[]{t, s});
                    }
                }
                context.processed(1);
            }
            // Gescheiterte Abschnitte ein zweites Mal, nachdem der Dienst Zeit hatte, sich zu erholen.
            List<int[]> missing = new ArrayList<>();
            List<CompletableFuture<Boolean>> retries = new ArrayList<>();
            for (int[] part : failed) {
                List<Turn> turns = store.script(key, part[0], part[1]).orElse(List.of());
                retries.add(pool.supply(rank(part[0], Stage.SPEECH, part[1]),
                        () -> speak(speaker, voices, key, part[0], part[1], turns, repairs, fence)));
            }
            for (int i = 0; i < failed.size(); i++) {
                if (!await(retries.get(i), context)) {
                    missing.add(failed.get(i));
                }
            }
            if (drawing != null) {
                context.phase("Podcast", "Bilder");
                await(drawing, context);
            }
            report(context, key, repairs, missing);
            if (!missing.isEmpty()) {
                context.failed(missing.size());
                context.incomplete();
            }
            context.written(topics);
        } finally {
            // Danach legt der Bilderfaden nichts mehr ab, auch kein Symbol im Pool: run() darf löschen.
            synchronized (ended) {
                ended.set(true);
            }
            if (drawing != null) {
                drawing.cancel(true);
            }
            pool.close();
            if (picturePool != null) {
                picturePool.close();
            }
        }
    }

    /** Arbeitsstufen in der Reihenfolge, in der die Wiedergabe sie braucht; Bilder zuletzt. */
    enum Stage { SEGMENTS, SCRIPT, SPEECH, PICTURE }

    /** Der Rang einer Arbeit: zuerst das Thema, dann die Stufe, dann der Abschnitt; kleiner = früher. */
    static long rank(int topic, Stage stage, int segment) {
        return (long) topic * 1_000_000L + stage.ordinal() * 10_000L + Math.min(segment, 9_999);
    }

    /**
     * Schreibt die Auswege und fehlende Abschnitte in die Diagnose des Auftrags: jede Art Ausweg einmal mit Anzahl,
     * fehlende Abschnitte als Warnung. Nie Text des Podcasts.
     */
    private static void report(JobContext context, String key, List<String> repairs, List<int[]> missing) {
        java.util.Map<String, Integer> counted = new java.util.LinkedHashMap<>();
        synchronized (repairs) {
            for (String repair : repairs) {
                counted.merge(repair, 1, Integer::sum);
            }
        }
        int listed = 0;
        for (java.util.Map.Entry<String, Integer> entry : counted.entrySet()) {
            LOG.log(System.Logger.Level.INFO, "Podcast {0}: {1} ({2}×)", key, entry.getKey(), entry.getValue());
            if (listed++ < MAX_REPORTED_REPAIRS) {
                context.record(com.aresstack.spaceharvester.application.jobs.JobEvent.Draft.info(COMPONENT,
                        entry.getKey() + (entry.getValue() > 1 ? " (" + entry.getValue() + "×)" : "")));
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder parts = new StringBuilder();
            for (int[] part : missing) {
                parts.append(parts.isEmpty() ? "" : ", ").append(part[0] + 1).append('.').append(part[1] + 1);
            }
            context.record(com.aresstack.spaceharvester.application.jobs.JobEvent.Draft.warning(
                    com.aresstack.spaceharvester.application.jobs.JobEvent.Category.JOB, COMPONENT,
                    "PODCAST_AUDIO_MISSING", missing.size() + " Abschnitt(e) ohne Audio nach zwei Versuchen: "
                            + parts + "; der nächste Start holt sie nach"));
        }
    }

    /** Startet die Bilder aller Themen in einem eigenen Faden; jedes fertige Bild meldet {@link #subscribe}. */
    private Future<?> picturesThread(String key, String sourceId, PodcastWriter.Material material, PodcastPlan plan,
                                     Pictures configured, RankedPool pool, CompletableFuture<?> firstAudio,
                                     JobContext context, List<String> repairs, PodcastPictures.Fence fence) {
        // Jeder Modellaufruf eines Bildes läuft im Pool mit dem Rang seines Themas, hinter dessen Text und Audio. Die
        // Themen laufen zugleich (je ein eigener Faden, der nur wartet), damit jedes Bild vor seinem Kapitel fertig
        // ist; wie viele Aufrufe wirklich zum Modell gehen, begrenzt allein der Pool (ai.parallel).
        List<PodcastPictures.Brief> briefs = briefs(material, plan);
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
            try {
                firstAudio.get();
            } catch (ExecutionException firstFailed) {
                // die Bilder kommen trotzdem; der Fehler des Abschnitts zeigt sich im Auftrag
            } catch (InterruptedException stopped) {
                return null;
            }
            java.util.Set<String> used = new java.util.HashSet<>();
            java.util.concurrent.ExecutorService topics = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(briefs.size(), 8)),
                    runnable -> {
                        Thread thread = new Thread(runnable, "space-harvester-podcast-bild-" + key.substring(0, 6));
                        thread.setDaemon(true);
                        return thread;
                    });
            try {
                List<Future<?>> each = new ArrayList<>();
                for (PodcastPictures.Brief brief : briefs) {
                    long topicRank = rank(brief.topic(), Stage.PICTURE, 0);
                    PodcastPictures maker = new PodcastPictures(store, model, configured.drawer(),
                            configured.images(), work -> pool.execute(topicRank, work),
                            attempt -> waits.accept(context, attempt), fence);
                    each.add(topics.submit(() -> {
                        if (Thread.currentThread().isInterrupted() || revoked.contains(key)) {
                            return;
                        }
                        boolean known = maker.picture(key, brief.topic()).isPresent();
                        maker.make(key, sourceId, brief, used, repairs);
                        if (!known) {
                            notify(key);
                        }
                    }));
                }
                for (Future<?> one : each) {
                    try {
                        one.get();
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof PodcastPictures.Closed closed) {
                            throw closed;
                        }
                        repairs.add("Bild gescheitert (" + e.getCause().getClass().getSimpleName() + ")");
                    }
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                topics.shutdownNow();
            }
            return null;
        });
        Thread thread = new Thread(task, "space-harvester-podcast-bilder-" + key.substring(0, 6));
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    /** Wofür je Thema ein Bild entsteht: die Meldungen des Themas mit ihren Seiten, beim Einstieg der Überblick. */
    static List<PodcastPictures.Brief> briefs(PodcastWriter.Material material, PodcastPlan plan) {
        List<PodcastPictures.Brief> briefs = new ArrayList<>();
        for (int t = 0; t < plan.topics().size(); t++) {
            PodcastPlan.Topic topic = plan.topics().get(t);
            StringBuilder text = new StringBuilder(topic.angle());
            java.util.LinkedHashSet<String> pages = new java.util.LinkedHashSet<>();
            List<PodcastWriter.Story> stories = material.stories(topic);
            for (PodcastWriter.Story story : stories) {
                text.append("\n\n").append(story.title()).append(": ").append(story.text());
                pages.addAll(story.pageIds());
            }
            if (stories.isEmpty()) {
                // Einstieg und Abschluss: der Überblick und die Themen der Folge
                text.append("\n\n").append(material.overview());
                for (PodcastPlan.Topic other : plan.topics()) {
                    if (other != topic) {
                        text.append("\n- ").append(other.title());
                    }
                }
            }
            briefs.add(new PodcastPictures.Brief(t, topic.title(), text.toString().strip(), List.copyOf(pages)));
        }
        return briefs;
    }

    private List<Segment> segments(PodcastWriter writer, String key, PodcastWriter.Material material,
                                   PodcastPlan plan, int topic, PodcastWriter.Voices names, List<String> repairs,
                                   PodcastPictures.Fence fence) {
        Optional<List<Segment>> stored = store.segments(key, topic);
        if (stored.isPresent()) {
            return stored.get();
        }
        List<Segment> segments = writer.segments(material, plan, topic, names, repairs);
        fence.write(() -> store.saveSegments(key, topic, segments));
        notify(key);
        return segments;
    }

    private Segment previousLast(String key, int topic) {
        if (topic == 0) {
            return null;
        }
        return store.segments(key, topic - 1).filter(list -> !list.isEmpty()).map(list -> list.get(list.size() - 1))
                .orElse(new Segment("vorheriges Thema", List.of()));
    }

    private List<Turn> script(PodcastWriter writer, String key, PodcastWriter.Material material, PodcastPlan plan,
                              int topic, List<Segment> segments, int segment, Segment before, boolean last,
                              PodcastWriter.Voices names, List<String> repairs, PodcastPictures.Fence fence) {
        Optional<List<Turn>> stored = store.script(key, topic, segment);
        if (stored.isPresent()) {
            return stored.get();
        }
        List<Turn> turns = writer.script(material, plan, topic, segments, segment, before, last, names, repairs);
        fence.write(() -> store.saveScript(key, topic, segment, turns));
        return turns;
    }

    /** Spricht einen Abschnitt, falls sein Audio fehlt; {@code false}, wenn das nicht gelang. */
    private boolean speak(PodcastSpeaker speaker, java.util.concurrent.Semaphore voices, String key, int topic,
                          int segment, List<Turn> turns, List<String> repairs, PodcastPictures.Fence fence)
            throws InterruptedException {
        if (store.audio(key, topic, segment).isPresent()) {
            return true;
        }
        if (turns.isEmpty()) {
            return false;
        }
        guard(key);
        voices.acquire();
        try {
            PodcastSpeaker.Section spoken = speaker.section(turns, repairs);
            fence.write(() -> {
                store.saveStarts(key, topic, segment, spoken.starts());
                store.saveAudio(key, topic, segment, spoken.wav());
            });
            notify(key);
            return true;
        } catch (PodcastPictures.Closed closed) {
            throw closed;
        } catch (java.io.IOException | RuntimeException e) {
            repairs.add("Abschnitt " + (topic + 1) + "." + (segment + 1) + " ohne Audio (" + e.getMessage() + ")");
            return false;
        } finally {
            voices.release();
        }
    }

    void notify(String key) {
        listeners.deliver(key, Runnable::run);
    }

    private static <T> T await(Future<T> future, JobContext context) throws Exception {
        while (true) {
            context.checkpoint();
            try {
                return future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException stillRunning) {
                // weiter warten; der Checkpoint oben bricht bei Pause oder Abbruch ab
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw e;
            }
        }
    }

    /** Wartet vor einem weiteren Versuch 2, dann 5 Sekunden; bricht bei Pause oder Abbruch ab. */
    static void pauseBefore(JobContext context, int attempt) {
        long until = System.nanoTime() + (attempt <= 2 ? 2000L : 5000L) * 1_000_000L;
        while (System.nanoTime() < until) {
            context.checkpoint();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                context.checkpoint();
                return;
            }
        }
    }
}
