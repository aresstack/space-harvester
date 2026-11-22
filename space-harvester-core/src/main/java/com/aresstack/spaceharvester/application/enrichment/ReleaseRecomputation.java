package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.configuration.ConfigurationChange;
import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rechnet nach einem Wechsel der Freigabe {@code ai.external-processing-sources} (ADR 0015) die Seiten einer Quelle im
 * Cache für die Prozessoren neu, deren Ergebnis an der Freigabe hängt (im Kern {@code embedding}, {@code passages},
 * {@code navigation}, eingeschaltet {@code statements}; {@code images} rechnet nur auf Abruf und prüft die Freigabe
 * dann selbst). Die Version bleibt dieselbe, deshalb erzwungen über {@link Enrichments#recomputeInBackground},
 * gedrosselt im vorhandenen Hintergrund und ohne neuen Abruf von der Quelle:
 *
 * <ul>
 *     <li>neu freigegeben: mit Modell;</li>
 *     <li>entzogen: ohne Modell auf den Rückfall (Absätze und Navigation nach Struktur, keine Embeddings). Ob eine
 *     Berechnung das Modell fragen darf, entscheidet ihre Ausführung, nicht das Einreihen; ab dem Aktivieren fragt
 *     also auch keine schon eingereihte Berechnung mehr das Modell. Was außerhalb der Ergebnisse gespeichert ist
 *     (Bildbeschreibungen, ADR 0028), nimmt die {@link Withdrawal} zurück, auch wenn nur ein Vision-Modell
 *     eingestellt ist.</li>
 * </ul>
 *
 * Was die Freigabe verpasst, weil sie wechselte, während der Dienst nicht lief, oder weil ein Lauf abbrach, holt
 * {@link #reconcileOnStart} beim Start einmal nach, allein aus den gespeicherten Ergebnissen.
 *
 * <p>Läufe gehen nacheinander; wechselt die Freigabe während eines Laufs erneut, endet er, und der nächste übernimmt.
 * Das Log nennt nur Quelle und Anzahl, nie Seiten.
 *
 * <p>Als {@link ConfigurationListener} muss er nach dem Beobachter angemeldet sein, der das Embedding-Modell tauscht:
 * {@code released} fragt die Freigabe so, wie das Modell sie sieht.
 *
 * <p>Mit Hintergrundaufträgen ({@link #background}) laufen alle Läufe sichtbar im Reiter „Hintergrund“, anhaltbar und
 * abbrechbar (ADR 0025 Nachtrag, Punkt 9): je Quelle ein Teil der Art {@value #BACKGROUND_KIND}, nach einer geänderten
 * Liste freigegebener Seiten je Quelle ein Teil der Art {@value #PAGES_KIND}. Ohne sie (Dienst) in einem eigenen
 * Thread.
 */
public final class ReleaseRecomputation implements ConfigurationListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ReleaseRecomputation.class.getName());
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Kennung des Prozessors {@code EmbeddingProcessor}, dessen veraltete Ergebnisse der Start nachholt. */
    private static final String EMBEDDING = "embedding";

    private final Enrichments enrichments;
    private final CachedPages pages;
    private final Predicate<String> released;
    private final List<String> processorIds;
    private final ExecutorService runs = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("enrichment-release-", 0).factory());
    private volatile boolean closed;
    private volatile Withdrawal withdrawal = pages -> {
    };
    /** Ob eine Seite auf der Liste freigegebener Seiten steht (ADR 0028); ohne Liste keine. */
    private volatile Predicate<PageKey> pageReleases = key -> false;
    /** So viele Seiten gibt ein Lauf höchstens auf einmal an die {@link Withdrawal}. */
    static final int WITHDRAWAL_BATCH = 100;
    /** Art der Läufe je Quelle als Hintergrundauftrag. */
    public static final String BACKGROUND_KIND = "release";
    /** Titel der Gruppe für den Abgleich beim Start. */
    public static final String CHECK_TITLE = "KI-Freigabe prüfen";
    /** Titel der Gruppe nach einem Wechsel der Freigabe einer Quelle. */
    public static final String CHANGE_TITLE = "KI-Freigabe nachziehen";
    /** Art der Läufe nach einer geänderten Liste freigegebener Seiten. */
    public static final String PAGES_KIND = "page-release";
    /** Titel der Gruppe nach einer geänderten Liste freigegebener Seiten. */
    public static final String PAGES_TITLE = "Seitenfreigabe nachziehen";
    /** Angabe am Teil der Art {@value #BACKGROUND_KIND}: was er tut. */
    static final String MODE = "mode";
    /** Abgleich beim Start: nur Seiten, deren Ergebnisse nicht zur Freigabe passen. */
    static final String CHECK = "check";
    /** Neu freigegeben: alle Seiten mit Modell. */
    static final String GRANT = "grant";
    /** Entzogen: alle Seiten ohne Modell, samt {@link Withdrawal}. */
    static final String WITHDRAW = "withdraw";
    /** Entzogen ohne Embedding- und Chat-Modell: nur die {@link Withdrawal}. */
    static final String IMAGES = "images";
    /** Angabe am Teil der Art {@value #PAGES_KIND}: alle geänderten Seiten-IDs der Quelle, je Zeile eine. */
    static final String PAGES = "pages";
    /** Angabe am Teil der Art {@value #PAGES_KIND}: die entfernten Seiten-IDs der Quelle, je Zeile eine. */
    static final String REMOVED = "removed";
    private static final String SEPARATOR = "\n";
    /** Hintergrundaufträge; ohne sie im eigenen Thread (Dienst). */
    private volatile BackgroundJobs background;
    /** Der gespeicherte Snapshot einer Seite, für Teile der Art {@value #PAGES_KIND}. */
    private volatile Function<PageKey, Optional<PageSnapshot>> lookup = key -> Optional.empty();
    /** Ob ein Modell eingestellt ist; ohne tut der Abgleich beim Start nichts. */
    private volatile BooleanSupplier model = () -> false;
    /** Der Grund im Ergebnis, das ohne Freigabe entstand. */
    private volatile String notApproved;
    /** Anzahl der bekannten Seiten einer Quelle, für den Fortschritt eines Teils. */
    private volatile ToLongFunction<String> pageCount = sourceId -> 0;

    /**
     * @param pages        die Seiten einer Quelle im Cache
     * @param released     ob Inhalte der Quelle jetzt an den KI-Dienst gehen, z. B.
     *                     {@code EmbeddingProcessor::covers}
     * @param processorIds die Prozessoren, deren Ergebnis an der Freigabe hängt
     */
    public ReleaseRecomputation(Enrichments enrichments, CachedPages pages, Predicate<String> released,
                                List<String> processorIds) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.released = Objects.requireNonNull(released, "released");
        this.processorIds = List.copyOf(processorIds);
    }

    /** Die Seiten einer Quelle mit gespeichertem Snapshot, nach und nach gelesen. */
    @FunctionalInterface
    public interface CachedPages {

        Iterator<PageSnapshot> of(String sourceId);
    }

    /**
     * Nimmt zurück, was zu Seiten außerhalb der Prozessorergebnisse gespeichert ist, wenn sie ihre Freigabe verlieren,
     * etwa ihre Bildbeschreibungen (ADR 0028, Abschnitt 4). Läuft im Hintergrund der Neuberechnung; Seiten, die bei der
     * Ausführung doch freigegeben sind, lässt sie stehen.
     */
    @FunctionalInterface
    public interface Withdrawal {

        void withdrawn(List<PageSnapshot> pages);
    }

    /**
     * Schließt die Liste freigegebener Seiten an (ADR 0028, Abschnitt 5): Der Abgleich beim Start behandelt eine Seite
     * darauf wie eine Seite einer freigegebenen Quelle. Gefragt wird je Seite bei der Ausführung.
     */
    public void pageReleases(Predicate<PageKey> released) {
        this.pageReleases = Objects.requireNonNull(released, "released");
    }

    /** Schließt die Rücknahme an; ohne sie bleibt außerhalb der Ergebnisse alles stehen. */
    public void withdrawal(Withdrawal withdrawal) {
        this.withdrawal = Objects.requireNonNull(withdrawal, "withdrawal");
    }

    /**
     * Läuft als Hintergrundauftrag (ADR 0025 Nachtrag, Punkt 9): je Quelle ein Teil der Art {@value #BACKGROUND_KIND},
     * nach einer geänderten Liste freigegebener Seiten je Quelle einer der Art {@value #PAGES_KIND}. Ein Teil baut
     * seine Arbeit allein aus Quelle und Angaben und fragt die Freigabe wie bisher bei der Ausführung, je Seite. Ein
     * fortgesetzter Teil der Art {@value #BACKGROUND_KIND} (nach Anhalten oder Neustart) rechnet nur noch die Seiten,
     * deren Ergebnisse nicht zur Freigabe passen, wie der Abgleich beim Start; nach einem Entzug nimmt er die
     * {@link Withdrawal} erneut vor. Modell und Grund gelten auch für {@link #reconcileOnStart}.
     *
     * @param lookup      der gespeicherte Snapshot einer Seite
     * @param model       ob ein Modell eingestellt ist, wie bei {@link #reconcileOnStart}
     * @param notApproved der Grund im Ergebnis, das ohne Freigabe entstand, wie bei {@link #reconcileOnStart}
     * @param pageCount   Anzahl der bekannten Seiten einer Quelle, für den Fortschritt
     */
    public void background(BackgroundJobs background, Function<PageKey, Optional<PageSnapshot>> lookup,
                           BooleanSupplier model, String notApproved, ToLongFunction<String> pageCount) {
        Objects.requireNonNull(background, "background");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.model = Objects.requireNonNull(model, "model");
        this.notApproved = Objects.requireNonNull(notApproved, "notApproved");
        this.pageCount = Objects.requireNonNull(pageCount, "pageCount");
        background.register(new BackgroundKind(BACKGROUND_KIND,
                part -> JobTask.blocking(context -> sourcePart(part, context))));
        background.register(new BackgroundKind(PAGES_KIND,
                part -> JobTask.blocking(context -> pagesPart(part, context))));
        this.background = background;
    }

    @Override
    public Preparation prepare(ConfigurationChange change) {
        Map<String, Boolean> changed = changes(change.previous().ai(), change.next().ai());
        List<String> imagesOnly = withdrawnImages(change.previous().ai(), change.next().ai(), changed);
        if (changed.isEmpty() && imagesOnly.isEmpty()) {
            return Preparation.NOTHING;
        }
        return () -> {
            BackgroundJobs hosted = background;
            if (hosted != null) {
                // Ein Wechsel, eine Gruppe: je Quelle ein Teil.
                List<BackgroundPart.Draft> parts = new ArrayList<>();
                changed.forEach((sourceId, release) -> parts.add(new BackgroundPart.Draft(sourceId, null,
                        Map.of(MODE, release ? GRANT : WITHDRAW))));
                imagesOnly.forEach(sourceId -> parts.add(new BackgroundPart.Draft(sourceId, null,
                        Map.of(MODE, IMAGES))));
                start(hosted, BACKGROUND_KIND, CHANGE_TITLE, parts);
                return;
            }
            changed.forEach(this::recompute);
            imagesOnly.forEach(this::withdraw);
        };
    }

    /** Startet eine Gruppe; was nicht angenommen wird, holt der Abgleich beim nächsten Start nach. */
    private static void start(BackgroundJobs hosted, String kind, String title, List<BackgroundPart.Draft> parts) {
        try {
            hosted.start(kind, title, parts);
        } catch (JobCapacityExceededException | IllegalStateException e) {
            LOG.log(System.Logger.Level.WARNING, "{0} für {1} Quellen nicht gestartet ({2})", title, parts.size(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Entzogene Quellen, für die nur die Rücknahme gespeicherter Bildbeschreibungen läuft: vorher war ein
     * Vision-Modell eingestellt, und {@link #changes} rechnet sie nicht schon neu (kein Embedding- und kein
     * Chat-Modell).
     */
    static List<String> withdrawnImages(AiServiceSettings before, AiServiceSettings after,
                                        Map<String, Boolean> changed) {
        if (!before.vision()) {
            return List.of();
        }
        return before.externalProcessingSources().stream()
                .filter(sourceId -> !after.mayProcessExternally(sourceId) && !changed.containsKey(sourceId))
                .sorted().toList();
    }

    /**
     * Quellen, deren Freigabe wechselt: {@code true} neu freigegeben, {@code false} entzogen; leer, wenn weder vorher
     * noch nachher ein Embedding- oder Chat-Modell (Satzanalyse {@code statements}, #108) eingestellt ist.
     */
    static Map<String, Boolean> changes(AiServiceSettings before, AiServiceSettings after) {
        if (!usesService(before) && !usesService(after)
                || before.externalProcessingSources().equals(after.externalProcessingSources())) {
            return Map.of();
        }
        Map<String, Boolean> changed = new TreeMap<>();
        after.externalProcessingSources().stream().filter(sourceId -> !before.mayProcessExternally(sourceId))
                .forEach(sourceId -> changed.put(sourceId, true));
        before.externalProcessingSources().stream().filter(sourceId -> !after.mayProcessExternally(sourceId))
                .forEach(sourceId -> changed.put(sourceId, false));
        return changed;
    }

    private static boolean usesService(AiServiceSettings settings) {
        return settings.embeddings() || settings.chat();
    }

    /** Reiht einen Lauf für die Quelle ein; schließt der Dienst schon, gilt die Freigabe beim nächsten Abruf. */
    void recompute(String sourceId, boolean release) {
        try {
            runs.execute(() -> run(sourceId, release, null, !release, null));
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.INFO, "Neuberechnung nach Wechsel der Freigabe, Quelle {0}, nicht gestartet",
                    sourceId);
        }
    }

    /** Reiht für eine entzogene Quelle nur die {@link Withdrawal} ihrer Seiten ein, ohne neu zu rechnen. */
    void withdraw(String sourceId) {
        try {
            runs.execute(() -> withdrawAll(sourceId, null));
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.INFO, "Rücknahme der Bildbeschreibungen, Quelle {0}, nicht gestartet",
                    sourceId);
        }
    }

    /** Die {@link Withdrawal} aller Seiten einer entzogenen Quelle; mit {@code context} als Teil. */
    private void withdrawAll(String sourceId, JobContext context) {
        int seen = 0;
        try {
            Iterator<PageSnapshot> snapshots = pages.of(sourceId);
            long total = discover(sourceId, context);
            List<PageSnapshot> batch = new ArrayList<>();
            while (!closed && !released.test(sourceId) && snapshots.hasNext()) {
                checkpoint(context);
                batch.add(snapshots.next());
                seen++;
                if (batch.size() == WITHDRAWAL_BATCH) {
                    withdrawal.withdrawn(List.copyOf(batch));
                    processed(context, batch.size());
                    batch.clear();
                }
            }
            if (!batch.isEmpty() && !closed) {
                withdrawal.withdrawn(List.copyOf(batch));
                processed(context, batch.size());
            }
            finish(context, total, seen);
            LOG.log(System.Logger.Level.INFO, "Freigabe für den KI-Dienst entzogen, Quelle {0}: "
                    + "Bildbeschreibungen von {1} Seiten zurückgenommen", sourceId, seen);
        } catch (JobCancelledException | JobFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Rücknahme der Bildbeschreibungen, Quelle {0}, nach {1} "
                    + "Seiten abgebrochen ({2})", sourceId, seen, e.getClass().getName());
            if (context != null) {
                throw e;
            }
        }
    }

    /**
     * Reiht die Seiten ein, deren Freigabe sich über die Liste freigegebener Seiten änderte (ADR 0028), im selben
     * Hintergrund wie die Läufe nach einem Wechsel je Quelle: Neu eingetragene rechnen mit Modell, entfernte ohne;
     * Ergebnisse der Prozessoren, die die Seite danach nicht mehr bearbeiten (Embeddings, Satzanalyse), entfallen, und
     * die {@link Withdrawal} nimmt für die entfernten zurück, was außerhalb der Ergebnisse liegt. Ob eine Berechnung
     * das Modell fragen darf, entscheidet wie immer ihre Ausführung. Seiten ohne Snapshot im Cache haben nichts zu
     * rechnen. Das Log nennt nur die Anzahl.
     *
     * <p>Mit Hintergrundaufträgen eine Gruppe „{@value #PAGES_TITLE}“ mit einem Teil je Quelle, der die Seiten-IDs
     * als Angabe trägt; gelesen wird dann über den {@code lookup} aus {@link #background}.
     *
     * @param added   neu eingetragene Seiten; nur solche, deren Quelle nicht als Ganzes freigegeben ist, sonst ändert
     *                sich nichts
     * @param removed entfernte Seiten, ebenso
     * @param lookup  der gespeicherte Snapshot einer Seite
     */
    public void recomputePages(Collection<PageKey> added, Collection<PageKey> removed,
                               Function<PageKey, Optional<PageSnapshot>> lookup) {
        List<PageKey> pending = new ArrayList<>(added);
        pending.addAll(removed);
        Set<PageKey> withdrawn = Set.copyOf(removed);
        Objects.requireNonNull(lookup, "lookup");
        if (pending.isEmpty()) {
            return;
        }
        BackgroundJobs hosted = background;
        if (hosted != null) {
            Map<String, List<String>> changedIds = new TreeMap<>();
            Map<String, List<String>> removedIds = new TreeMap<>();
            pending.forEach(key -> changedIds.computeIfAbsent(key.sourceId(), id -> new ArrayList<>())
                    .add(key.pageId()));
            withdrawn.forEach(key -> removedIds.computeIfAbsent(key.sourceId(), id -> new ArrayList<>())
                    .add(key.pageId()));
            List<BackgroundPart.Draft> parts = new ArrayList<>();
            changedIds.forEach((sourceId, pageIds) -> {
                Map<String, String> parameters = new java.util.HashMap<>();
                parameters.put(PAGES, String.join(SEPARATOR, pageIds));
                // Ohne entfernte Seiten keine leere Angabe.
                List<String> removedHere = removedIds.getOrDefault(sourceId, List.of());
                if (!removedHere.isEmpty()) {
                    parameters.put(REMOVED, String.join(SEPARATOR, removedHere));
                }
                parts.add(new BackgroundPart.Draft(sourceId, null, parameters));
            });
            start(hosted, PAGES_KIND, PAGES_TITLE, parts);
            return;
        }
        try {
            runs.execute(() -> recomputeListed(pending, withdrawn, lookup, null));
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.INFO, "Neuberechnung nach geänderter Liste freigegebener Seiten nicht "
                    + "gestartet");
        }
    }

    /** Ein Teil der Art {@value #PAGES_KIND}: die Seiten aus seinen Angaben, wie {@link #recomputePages}. */
    private void pagesPart(BackgroundPart part, JobContext context) {
        Set<PageKey> withdrawn = Set.copyOf(keys(part.sourceId(), part.parameter(REMOVED)));
        recomputeListed(keys(part.sourceId(), part.parameter(PAGES)), withdrawn, lookup, context);
    }

    private static List<PageKey> keys(String sourceId, String pageIds) {
        if (pageIds == null || pageIds.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(pageIds.split(SEPARATOR)).filter(pageId -> !pageId.isEmpty())
                .map(pageId -> new PageKey(sourceId, pageId)).toList();
    }

    /** Rechnet die Seiten neu und nimmt für die entfernten zurück; mit {@code context} als Teil. */
    private void recomputeListed(List<PageKey> pending, Set<PageKey> withdrawn,
                                 Function<PageKey, Optional<PageSnapshot>> lookup, JobContext context) {
        int count = 0;
        try {
            if (context != null) {
                context.discovered(pending.size());
            }
            List<PageSnapshot> batch = new ArrayList<>();
            for (PageKey key : pending) {
                if (closed) {
                    interrupted(context);
                    return;
                }
                checkpoint(context);
                Optional<PageSnapshot> snapshot = lookup.apply(key);
                boolean recomputed = snapshot.isPresent() && enrichments.recomputeInBackground(snapshot.get(),
                        processorIds) > 0;
                if (recomputed) {
                    count++;
                }
                if (snapshot.isPresent() && withdrawn.contains(key)) {
                    batch.add(snapshot.get());
                }
                processed(context, 1);
                if (recomputed && context != null) {
                    context.written(1);
                }
            }
            enrichments.flush();
            if (!batch.isEmpty()) {
                withdrawal.withdrawn(List.copyOf(batch));
            }
            LOG.log(System.Logger.Level.INFO, "Liste freigegebener Seiten geändert: {0} von {1} Seiten zur "
                    + "Neuberechnung", count, pending.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled(context);
        } catch (JobCancelledException | JobFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Neuberechnung nach geänderter Liste freigegebener Seiten "
                    + "nach {0} Seiten abgebrochen ({1})", count, e.getClass().getName());
            if (context != null) {
                throw e;
            }
        }
    }

    /**
     * Gleicht beim Start einmal ab, ohne gespeicherten Freigabestand: Je Seite gilt die Freigabe, wie
     * {@code released} sie jetzt für ihre Quelle sieht oder die Liste freigegebener Seiten für sie selbst
     * ({@link #pageReleases}, ADR 0028), und nur Seiten, deren gespeicherte Ergebnisse nicht dazu passen, rechnen
     * erzwungen neu, und nur die Prozessoren, deren Ergebnis nicht passt, im selben Hintergrund wie die Läufe nach
     * einem Wechsel:
     *
     * <ul>
     *     <li>nicht freigegeben: Seiten mit einem Ergebnis der Prozessoren, das nicht den Grund {@code notApproved}
     *     trägt (also auch jedem gespeicherten Embedding);</li>
     *     <li>freigegeben: Seiten, denen ein Ergebnis eines Prozessors fehlt, der die Seite bearbeitet, oder deren
     *     Ergebnis den Grund {@code notApproved} trägt, oder deren Embedding eine ältere Prozessorversion trägt (etwa nach
     *     einer geänderten Zerlegung).</li>
     * </ul>
     *
     * Ohne Modell beim Lauf tut er nichts. Wechselt die Freigabe währenddessen, endet er für die Quelle, und der Lauf
     * des Wechsels übernimmt. Mit Hintergrundaufträgen ({@link #background}) eine Gruppe „{@value #CHECK_TITLE}“ mit
     * einem Teil je Quelle, für die nicht schon ein Teil wartet oder läuft, etwa nach einem Neustart fortgesetzt; ohne
     * Modell keine.
     *
     * @param sourceIds   die Quellen; ohne Seiten im Cache geschieht für eine Quelle nichts
     * @param model       ob ein Embedding-Modell eingestellt ist, z. B. {@code EmbeddingProcessor::available}
     * @param notApproved der Grund ({@code reason}) im Ergebnis, das ohne Freigabe entstand, z. B.
     *                    {@code PassagePayload.REASON_NOT_APPROVED}
     */
    public void reconcileOnStart(Collection<String> sourceIds, BooleanSupplier model, String notApproved) {
        List<String> ids = List.copyOf(sourceIds);
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(notApproved, "notApproved");
        BackgroundJobs hosted = background;
        if (hosted != null) {
            if (!model.getAsBoolean()) {
                return;
            }
            try {
                hosted.startUnlessRunning(BACKGROUND_KIND, CHECK_TITLE, ids.stream()
                        .map(sourceId -> new BackgroundPart.Draft(sourceId, null, Map.of(MODE, CHECK))).toList());
            } catch (JobCapacityExceededException | IllegalStateException e) {
                LOG.log(System.Logger.Level.INFO, "Abgleich der Freigabe beim Start nicht gestartet ({0})",
                        e.getClass().getSimpleName());
            }
            return;
        }
        try {
            runs.execute(() -> {
                if (!model.getAsBoolean()) {
                    return;
                }
                for (String sourceId : ids) {
                    if (closed) {
                        return;
                    }
                    run(sourceId, released.test(sourceId), notApproved, false, null);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.INFO, "Abgleich der Freigabe beim Start nicht gestartet");
        }
    }

    /**
     * Ein Teil der Art {@value #BACKGROUND_KIND}. Zum ersten Mal wie bisher; fortgesetzt nur noch, was nicht zur
     * Freigabe passt (wie der Abgleich beim Start), nach einem Entzug samt erneuter {@link Withdrawal}.
     */
    private void sourcePart(BackgroundPart part, JobContext context) {
        String sourceId = part.sourceId();
        String mode = Objects.requireNonNullElse(part.parameter(MODE), CHECK);
        switch (mode) {
            case IMAGES -> withdrawAll(sourceId, context);
            case WITHDRAW -> run(sourceId, false, part.resumed() ? notApproved : null, true, context);
            case GRANT -> {
                if (!part.resumed()) {
                    run(sourceId, true, null, false, context);
                } else if (model.getAsBoolean()) {
                    run(sourceId, true, notApproved, false, context);
                }
            }
            default -> {
                if (model.getAsBoolean()) {
                    run(sourceId, released.test(sourceId), notApproved, false, context);
                }
            }
        }
    }

    /**
     * Ein Lauf für eine Quelle; mit {@code notApproved} der Abgleich beim Start, der nur nicht passende Seiten neu
     * rechnet, sonst alle Seiten. Nur ein Entzug ({@code withdrawing}) nimmt Bildbeschreibungen zurück, nicht der
     * Start: „Nur diese Seite einmalig beschreiben“ in einer nie freigegebenen Quelle bleibt so über Neustarts
     * erhalten. Mit {@code context} als Teil eines Hintergrundauftrags.
     */
    private void run(String sourceId, boolean release, String notApproved, boolean withdrawing, JobContext context) {
        boolean onStart = notApproved != null;
        List<PageSnapshot> batch = new ArrayList<>();
        int seen = 0;
        int count = 0;
        try {
            Iterator<PageSnapshot> snapshots = pages.of(sourceId);
            long total = discover(sourceId, context);
            // Wechselt die Freigabe währenddessen wieder, übernimmt der nächste Lauf.
            while (!closed && released.test(sourceId) == release && snapshots.hasNext()) {
                checkpoint(context);
                PageSnapshot snapshot = snapshots.next();
                seen++;
                if (withdrawing) {
                    batch.add(snapshot);
                    if (batch.size() == WITHDRAWAL_BATCH) {
                        withdrawal.withdrawn(List.copyOf(batch));
                        batch.clear();
                    }
                }
                List<String> due = onStart ? outdated(snapshot.key(), release
                        || pageReleases.test(snapshot.key()), notApproved) : processorIds;
                boolean recomputed = !due.isEmpty() && enrichments.recomputeInBackground(snapshot, due) > 0;
                if (recomputed) {
                    count++;
                }
                processed(context, 1);
                if (recomputed && context != null) {
                    context.written(1);
                }
            }
            enrichments.flush();
            if (!batch.isEmpty() && !closed && released.test(sourceId) == release) {
                withdrawal.withdrawn(List.copyOf(batch));
            }
            finish(context, total, seen);
            if (onStart) {
                if (seen > 0) {
                    LOG.log(System.Logger.Level.INFO, release
                            ? "Abgleich der Freigabe beim Start, Quelle {0} freigegeben: {1} von {2} Seiten zur "
                                    + "Neuberechnung mit Modell"
                            : "Abgleich der Freigabe beim Start, Quelle {0} nicht freigegeben: {1} von {2} Seiten "
                                    + "zur Neuberechnung ohne Modell", sourceId, count, seen);
                }
                return;
            }
            LOG.log(System.Logger.Level.INFO, release
                    ? "Freigabe für den KI-Dienst erteilt, Quelle {0}: {1} Seiten zur Neuberechnung mit Modell"
                    : "Freigabe für den KI-Dienst entzogen, Quelle {0}: {1} Seiten zur Neuberechnung ohne Modell",
                    sourceId, count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled(context);
        } catch (JobCancelledException | JobFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, (onStart ? "Abgleich der Freigabe beim Start"
                    : "Neuberechnung nach Wechsel der Freigabe") + ", Quelle {0}, nach {1} Seiten abgebrochen ({2})",
                    sourceId, count, e.getClass().getName());
            if (context != null) {
                throw e;
            }
        }
    }

    // --- Fortschritt eines Teils; ohne Teil (Dienst) geschieht nichts -----------------------------------------------

    /** Meldet die Anzahl der bekannten Seiten der Quelle als gefunden. */
    private long discover(String sourceId, JobContext context) {
        if (context == null) {
            return 0;
        }
        long total = pageCount.applyAsLong(sourceId);
        if (total > 0) {
            context.discovered(total);
        }
        return total;
    }

    private static void checkpoint(JobContext context) {
        if (context != null) {
            context.checkpoint();
        }
    }

    private static void processed(JobContext context, long pages) {
        if (context != null) {
            context.processed(pages);
        }
    }

    /**
     * Am Ende eines Teils: Beendet der Dienst ihn, gilt er nicht als fertig, sondern als unterbrochen und setzt nach
     * dem Neustart fort; Seiten nur mit Kopfdaten liefert der Cache nicht, sie haben nichts zu rechnen.
     */
    private void finish(JobContext context, long total, int seen) {
        if (context == null) {
            return;
        }
        if (closed) {
            interrupted(context);
        }
        if (total > seen) {
            context.processed(total - seen);
        }
    }

    /** Beendet den Teil ohne Fehler; beim Beenden des Dienstes gilt er damit als unterbrochen ({@code Jobs}). */
    private static void interrupted(JobContext context) {
        if (context != null) {
            throw new JobCancelledException(context.jobId());
        }
    }

    private static void cancelled(JobContext context) {
        if (context != null) {
            throw new JobCancelledException(context.jobId());
        }
    }

    /**
     * Die Prozessoren, deren gespeichertes Ergebnis nicht zur Freigabe passt; liest nur die Ablage. Nur sie rechnen neu,
     * damit etwa ein Bestand ohne Navigation nicht alle Seiten neu einbettet.
     */
    private List<String> outdated(PageKey key, boolean release, String notApproved) {
        List<Enrichment> stored = enrichments.store().find(key);
        List<String> due = new ArrayList<>();
        for (String processorId : processorIds) {
            Enrichment result = stored.stream().filter(enrichment -> enrichment.processorId().equals(processorId))
                    .findFirst().orElse(null);
            // Ein fehlendes Ergebnis zählt nur, wenn der Prozessor die Seite bearbeitet; sonst rechnete jeder Start sie
            // erneut (etwa ohne Chat-Modell oder für einen nicht eingerichteten Prozessor).
            boolean mismatch = release
                    ? result == null ? enrichments.appliesTo(processorId, key)
                            : notApproved(result, notApproved) || staleEmbedding(result, key)
                    : result != null && !notApproved(result, notApproved);
            if (mismatch) {
                due.add(processorId);
            }
        }
        return due;
    }

    /**
     * Ein Embedding einer älteren Prozessorversion, etwa nach einer geänderten Zerlegung: Der Start bettet es einmal im
     * gedrosselten Hintergrund neu ein, statt erst beim nächsten Abgleich oder Öffnen der Seite. Andere Prozessoren
     * rechnen wie bisher nach Bedarf neu.
     */
    private boolean staleEmbedding(Enrichment result, PageKey key) {
        // Nur, wenn embedding die Seite jetzt bearbeitet: Ohne Embedding-Modell (der Start läuft dann etwa für die
        // Satzanalyse) hieße „veraltet“ sonst Entfernen.
        return EMBEDDING.equals(result.processorId()) && enrichments.appliesTo(EMBEDDING, key)
                && !enrichments.version(EMBEDDING).map(result.processorVersion()::equals).orElse(true);
    }

    /**
     * Ob das Ergebnis den Grund {@code notApproved} trägt ({@code reason}). Geparst wird nur, wenn der Grund im Text
     * vorkommt: Embeddings mit ihren Vektoren lesen sich so nie als JSON.
     */
    private static boolean notApproved(Enrichment result, String notApproved) {
        String payload = result.payloadJson();
        if (payload == null || !payload.contains(notApproved)) {
            return false;
        }
        try {
            JsonNode reason = JSON.readTree(payload).path("reason");
            return reason.isString() && notApproved.equals(reason.asString());
        } catch (JacksonException e) {
            return false;
        }
    }

    /**
     * Beendet den laufenden Lauf nach der aktuellen Seite, ohne zu unterbrechen (ein Interrupt mitten im Schreiben
     * schlösse die Dateikanäle des Lucene-Index); erst nach der Frist wird unterbrochen. Vor dem Hintergrund der
     * Anreicherungen schließen.
     */
    @Override
    public void close() throws InterruptedException {
        closed = true;
        runs.shutdown();
        if (!runs.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            runs.shutdownNow();
        }
    }
}
