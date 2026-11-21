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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntSupplier;
import java.util.function.ObjIntConsumer;

/**
 * Die Herstellung eines Podcasts, gleich wofür (Update-Zusammenfassung oder Seite): Grobübersicht, je Thema die
 * Abschnitte und Texte parallel ({@code ai.parallel}), dann das Audio in Sendereihenfolge. Jede Stufe liegt sofort in
 * der {@link PodcastStore Ablage}; ein neuer Lauf macht dort weiter, wo der letzte aufhörte, und jede abgelegte Stufe
 * meldet {@link #subscribe} (ADR 0027, kein Polling).
 */
final class PodcastProduction {

    private static final System.Logger LOG = System.getLogger(PodcastProduction.class.getName());

    private final StepModel model;
    private final PodcastSpeech speech;
    private final PodcastStore store;
    private final PodcastVoices voices;
    private final IntSupplier parallel;
    /** Wartet vor einem weiteren Versuch; in Tests ohne Pause. */
    private final ObjIntConsumer<JobContext> waits;
    private final Listeners<Runnable> listeners = new Listeners<>("Podcast");

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
        String key = origin.key();
        context.phase("Podcast", "Sprachmodell");
        List<String> models = voices.ranked(speech.models());
        if (models.isEmpty()) {
            throw new JobFailedException("NO_SPEECH_MODEL", "Der KI-Dienst bietet kein Sprachmodell (TTS) an");
        }
        PodcastSpeaker speaker = new PodcastSpeaker(speech, models, voices);
        PodcastVoices.Pair pair = store.meta(key).filter(meta -> speaker.model().equals(meta.model()))
                .map(meta -> new PodcastVoices.Pair(meta.host(), meta.cohost())).orElse(speaker.pair());
        store.saveMeta(origin.voiced(speaker.model(), pair.host(), pair.cohost()));
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
                    List<Segment> segments = segments(writer, key, material, planned, topic, names, repairs);
                    List<Future<List<Turn>>> texts = new ArrayList<>();
                    for (int s = 0; s < segments.size(); s++) {
                        final int segment = s;
                        Segment before = segment > 0 ? segments.get(segment - 1) : previousLast(key, topic);
                        boolean last = topic == topics - 1 && segment == segments.size() - 1;
                        texts.add(CompletableFuture.completedFuture(script(writer, key, material, planned, topic,
                                segments, segment, before, last, names, repairs)));
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

    private List<Segment> segments(PodcastWriter writer, String key, PodcastWriter.Material material,
                                   PodcastPlan plan, int topic, PodcastWriter.Voices names, List<String> repairs) {
        Optional<List<Segment>> stored = store.segments(key, topic);
        if (stored.isPresent()) {
            return stored.get();
        }
        List<Segment> segments = writer.segments(material, plan, topic, names, repairs);
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

    private List<Turn> script(PodcastWriter writer, String key, PodcastWriter.Material material, PodcastPlan plan,
                              int topic, List<Segment> segments, int segment, Segment before, boolean last,
                              PodcastWriter.Voices names, List<String> repairs) {
        Optional<List<Turn>> stored = store.script(key, topic, segment);
        if (stored.isPresent()) {
            return stored.get();
        }
        List<Turn> turns = writer.script(material, plan, topic, segments, segment, before, last, names, repairs);
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
