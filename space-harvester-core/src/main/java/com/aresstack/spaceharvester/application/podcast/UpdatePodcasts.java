package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Segment;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Turn;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Die Update-Zusammenfassung als Podcast (Angelo 21.11.2026): ein lockeres Gespräch zweier Stimmen über das, was sich
 * getan hat, im Stil von NotebookLM. Der Podcast entsteht als Auftrag {@link JobType#UPDATE_PODCAST} im Hintergrund und
 * Schritt für Schritt, damit niemand auf sein Update warten muss:
 * <ol>
 *     <li>die Grobübersicht der Themen ({@link PodcastWriter#outline}),</li>
 *     <li>je Thema die Abschnitte mit ihren Sprecherwechseln, alle Themen parallel ({@code ai.parallel}),</li>
 *     <li>je Abschnitt der gesprochene Text, ebenfalls parallel,</li>
 *     <li>das Audio Abschnitt für Abschnitt in Sendereihenfolge, damit das Abspielen beginnt, sobald der erste fertig
 *     ist.</li>
 * </ol>
 * Jede Stufe liegt sofort auf der Platte ({@link PodcastStore}); ein neuer Lauf, auch nach Abbruch oder Neustart, macht
 * dort weiter, wo der letzte aufhörte. Jede abgelegte Stufe meldet {@link #subscribe} (ADR 0027, kein Polling).
 *
 * <p>Selbstheilend: Planer und Sprecher haben ihre eigenen Auswege ({@link PodcastWriter}, {@link PodcastSpeaker}).
 * Scheitert ein Abschnitt trotzdem, geht der Auftrag zum nächsten weiter und versucht die gescheiterten am Ende noch
 * einmal; der Podcast bleibt hörbar, die Wiedergabe überspringt die Lücke.
 *
 * <p>Der Schlüssel eines Podcasts folgt aus der Zusammenfassung, die der Aufrufer sehen durfte; eine neue Zusammenfassung
 * ergibt einen neuen Podcast, und der alte derselben Ebene fällt weg. An das Modell geht nur der Text der Zusammenfassung,
 * die selbst nur Seiten mit KI-Freigabe enthält (ADR 0028).
 */
public final class UpdatePodcasts {

    /** Parameter des Auftrags: der Schlüssel des Podcasts. */
    public static final String PODCAST_PARAMETER = "podcast";
    /** Parameter des Auftrags: das Update. */
    public static final String UPDATE_PARAMETER = "update";
    /** Kennung des Verfahrens; Teil des Schlüssels, damit eine neue Fassung neu plant. */
    public static final String VERSION = "update-podcast-v1";

    private static final System.Logger LOG = System.getLogger(UpdatePodcasts.class.getName());

    private final Jobs jobs;
    private final StepModel model;
    private final PodcastSpeech speech;
    private final PodcastStore store;
    private final PodcastVoices voices;
    private final IntSupplier parallel;
    private final Listeners<Runnable> listeners = new Listeners<>("Podcast");
    private final Object starting = new Object();
    /** Wartet vor einem weiteren Versuch; in Tests ohne Pause. */
    private final java.util.function.ObjIntConsumer<JobContext> waits;

    /**
     * @param model    das Chat-Modell der Planung und der Texte
     * @param parallel so viele Anfragen schickt der Auftrag höchstens zugleich an das Chat-Modell ({@code ai.parallel})
     */
    public UpdatePodcasts(Jobs jobs, StepModel model, PodcastSpeech speech, PodcastStore store, PodcastVoices voices,
                          IntSupplier parallel) {
        this(jobs, model, speech, store, voices, parallel, UpdatePodcasts::pauseBefore);
    }

    UpdatePodcasts(Jobs jobs, StepModel model, PodcastSpeech speech, PodcastStore store, PodcastVoices voices,
                   IntSupplier parallel,
                   java.util.function.ObjIntConsumer<JobContext> waits) {
        this.waits = Objects.requireNonNull(waits, "waits");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.model = Objects.requireNonNull(model, "model");
        this.speech = Objects.requireNonNull(speech, "speech");
        this.store = Objects.requireNonNull(store, "store");
        this.voices = Objects.requireNonNull(voices, "voices");
        this.parallel = Objects.requireNonNull(parallel, "parallel");
    }

    /** Der Schlüssel des Podcasts einer Zusammenfassung. */
    public static String key(UpdateSummary summary) {
        return key(summary.sourceId(), summary.updateId(), summary.spaceKey(), summary.text(),
                String.valueOf(summary.createdAt()));
    }

    /** Der Schlüssel aus den Teilen einer Zusammenfassung, auch einer abgelegten. */
    public static String key(String sourceId, String updateId, String spaceKey, String text, String createdAt) {
        String material = VERSION + '\n' + sourceId + '\n' + updateId + '\n' + (spaceKey == null ? "" : spaceKey)
                + '\n' + createdAt + '\n' + text;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ob Chat-Modell und ein Dienst mit Sprachausgabe eingerichtet sind. */
    public boolean available() {
        return model.available() && speech.available();
    }

    /** Der Stand des Podcasts einer Zusammenfassung; leer, solange nichts erzeugt ist. */
    public Optional<Podcast> podcast(UpdateSummary summary) {
        Objects.requireNonNull(summary, "summary");
        String key = key(summary);
        Optional<PodcastPlan> plan = store.plan(key);
        boolean running = open(key) != null;
        if (plan.isEmpty()) {
            return store.meta(key).isPresent() || running ? Optional.of(new Podcast(key, null, List.of(), running))
                    : Optional.empty();
        }
        List<Podcast.Part> parts = new ArrayList<>();
        for (int t = 0; t < plan.get().topics().size(); t++) {
            String topicTitle = plan.get().topics().get(t).title();
            Optional<List<Segment>> segments = store.segments(key, t);
            if (segments.isEmpty()) {
                parts.add(new Podcast.Part(t, -1, topicTitle, null, null));
                continue;
            }
            for (int s = 0; s < segments.get().size(); s++) {
                parts.add(new Podcast.Part(t, s, topicTitle, segments.get().get(s).title(),
                        store.audio(key, t, s).orElse(null)));
            }
        }
        return Optional.of(new Podcast(key, plan.get().title(), parts, running));
    }

    /**
     * Startet die Erzeugung oder setzt sie fort; wartet oder läuft für diesen Podcast schon ein Auftrag, ist er die
     * Antwort. Podcasts älterer Zusammenfassungen derselben Ebene fallen weg.
     *
     * @throws IllegalStateException ohne Chat-Modell oder Sprachausgabe
     */
    public JobStatus start(AccessContext caller, UpdateSummary summary) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(summary, "summary");
        if (!available()) {
            throw new IllegalStateException("Kein Chat-Modell oder keine Sprachausgabe eingerichtet");
        }
        String key = key(summary);
        PodcastWriter.Material material = material(summary);
        synchronized (starting) {
            JobStatus running = open(key);
            if (running != null) {
                return running;
            }
            store.delete(meta -> !meta.key().equals(key) && meta.sourceId().equals(summary.sourceId())
                    && meta.updateId().equals(summary.updateId())
                    && Objects.equals(meta.spaceKey(), summary.spaceKey()));
            Map<String, String> parameters = Map.of(PODCAST_PARAMETER, key, UPDATE_PARAMETER, summary.updateId());
            return jobs.submit(JobType.UPDATE_PODCAST, summary.sourceId(), summary.spaceKey(), null,
                    caller.jobOwner(), parameters, JobTask.blocking(context -> run(context, key, summary,
                            material)));
        }
    }

    /** Meldet jede abgelegte Stufe dieses Podcasts. */
    public Subscription subscribe(String key, Runnable listener) {
        Objects.requireNonNull(key, "key");
        return listeners.add(listener, key::equals);
    }

    /**
     * Löscht die Podcasts, deren Zusammenfassung nicht mehr gilt (etwa weil eine Seite die KI-Freigabe verlor und die
     * Zusammenfassung wegfiel, ADR 0028).
     *
     * @param valid ob die Zusammenfassung zu diesem Podcast noch abgelegt ist
     */
    public int prune(Predicate<PodcastStore.Meta> valid) {
        return store.delete(meta -> open(meta.key()) == null && !valid.test(meta));
    }

    private JobStatus open(String key) {
        for (JobStatus status : jobs.listAll(JobType.UPDATE_PODCAST)) {
            if (!status.state().terminal() && !status.cancelRequested()
                    && key.equals(status.parameters().get(PODCAST_PARAMETER))) {
                return status;
            }
        }
        return null;
    }

    static PodcastWriter.Material material(UpdateSummary summary) {
        List<String> statements = new ArrayList<>();
        for (SummaryStatement statement : summary.statements()) {
            if (statement.text() != null && !statement.text().isBlank()) {
                statements.add(statement.text().strip());
            }
        }
        return new PodcastWriter.Material(summary.text(), statements,
                summary.spaceKey() == null ? null : "Space " + summary.spaceKey());
    }

    // --- Auftrag ------------------------------------------------------------------------------------------------

    private void run(JobContext context, String key, UpdateSummary summary, PodcastWriter.Material material)
            throws Exception {
        context.phase("Podcast", "Sprachmodell");
        List<String> models = voices.ranked(speech.models());
        if (models.isEmpty()) {
            throw new JobFailedException("NO_SPEECH_MODEL", "Der KI-Dienst bietet kein Sprachmodell (TTS) an");
        }
        PodcastSpeaker speaker = new PodcastSpeaker(speech, models, voices);
        PodcastVoices.Pair pair = store.meta(key).filter(meta -> speaker.model().equals(meta.model()))
                .map(meta -> new PodcastVoices.Pair(meta.host(), meta.cohost())).orElse(speaker.pair());
        store.saveMeta(new PodcastStore.Meta(key, summary.sourceId(), summary.updateId(), summary.spaceKey(),
                speaker.model(), pair.host(), pair.cohost()));
        notify(key);
        PodcastWriter writer = new PodcastWriter(model, attempt -> waits.accept(context, attempt));
        PodcastWriter.Voices names = new PodcastWriter.Voices(pair.host(), pair.cohost());
        List<String> repairs = Collections.synchronizedList(new ArrayList<>());

        context.phase("Podcast", "Grobübersicht");
        PodcastPlan plan = store.plan(key).orElse(null);
        if (plan == null) {
            plan = writer.outline(material);
            store.savePlan(key, plan);
            repairs.addAll(plan.repairs());
            notify(key);
        }
        context.checkpoint();
        int topics = plan.topics().size();
        context.discovered(topics);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel.getAsInt()), runnable -> {
            Thread thread = new Thread(runnable, "space-harvester-podcast-" + key.substring(0, 6));
            thread.setDaemon(true);
            return thread;
        });
        try {
            // Stufe 2 und 3 parallel; je Thema eine Kette, damit Thema 1 nicht auf Thema 8 wartet.
            final PodcastPlan planned = plan;
            List<Future<List<Future<List<Turn>>>>> scripts = new ArrayList<>();
            for (int t = 0; t < topics; t++) {
                final int topic = t;
                scripts.add(pool.submit(() -> {
                    List<Segment> segments = segments(writer, key, planned, topic, names, repairs);
                    List<Future<List<Turn>>> texts = new ArrayList<>();
                    for (int s = 0; s < segments.size(); s++) {
                        final int segment = s;
                        Segment before = segment > 0 ? segments.get(segment - 1) : previousLast(key, topic);
                        boolean last = topic == topics - 1 && segment == segments.size() - 1;
                        texts.add(CompletableFuture.completedFuture(script(writer, key, planned, topic, segment,
                                segments.get(segment), before, last, names, repairs)));
                    }
                    return texts;
                }));
            }
            // Stufe 4 in Sendereihenfolge: das Abspielen kann beginnen, sobald der erste Abschnitt da ist.
            context.phase("Podcast", "Sprachausgabe");
            List<int[]> failed = new ArrayList<>();
            for (int t = 0; t < topics; t++) {
                List<Future<List<Turn>>> texts = await(scripts.get(t), context);
                for (int s = 0; s < texts.size(); s++) {
                    context.checkpoint();
                    if (!speak(speaker, key, t, s, await(texts.get(s), context), repairs)) {
                        failed.add(new int[]{t, s});
                    }
                }
                context.processed(1);
            }
            // Gescheiterte Abschnitte ein zweites Mal, nachdem der Dienst Zeit hatte, sich zu erholen.
            List<int[]> missing = new ArrayList<>();
            for (int[] part : failed) {
                context.checkpoint();
                List<Turn> turns = store.script(key, part[0], part[1]).orElse(List.of());
                if (!speak(speaker, key, part[0], part[1], turns, repairs)) {
                    missing.add(part);
                }
            }
            for (String repair : repairs) {
                LOG.log(System.Logger.Level.INFO, "Podcast {0}: {1}", key, repair);
            }
            if (!missing.isEmpty()) {
                context.failed(missing.size());
                context.incomplete();
            }
            context.written(topics);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Segment> segments(PodcastWriter writer, String key, PodcastPlan plan, int topic,
                                   PodcastWriter.Voices names, List<String> repairs) {
        Optional<List<Segment>> stored = store.segments(key, topic);
        if (stored.isPresent()) {
            return stored.get();
        }
        List<Segment> segments = writer.segments(plan, topic, names, repairs);
        store.saveSegments(key, topic, segments);
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

    private List<Turn> script(PodcastWriter writer, String key, PodcastPlan plan, int topic, int segment,
                              Segment planned, Segment before, boolean last, PodcastWriter.Voices names,
                              List<String> repairs) {
        Optional<List<Turn>> stored = store.script(key, topic, segment);
        if (stored.isPresent()) {
            return stored.get();
        }
        List<Turn> turns = writer.script(plan, topic, planned, before, last, names, repairs);
        store.saveScript(key, topic, segment, turns);
        return turns;
    }

    /** Spricht einen Abschnitt, falls sein Audio fehlt; {@code false}, wenn das nicht gelang. */
    private boolean speak(PodcastSpeaker speaker, String key, int topic, int segment, List<Turn> turns,
                          List<String> repairs) {
        if (store.audio(key, topic, segment).isPresent()) {
            return true;
        }
        if (turns.isEmpty()) {
            return false;
        }
        try {
            store.saveAudio(key, topic, segment, speaker.speak(turns, repairs));
            notify(key);
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            repairs.add("Abschnitt " + (topic + 1) + "." + (segment + 1) + " ohne Audio (" + e.getMessage() + ")");
            return false;
        }
    }

    private void notify(String key) {
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
    private static void pauseBefore(JobContext context, int attempt) {
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
