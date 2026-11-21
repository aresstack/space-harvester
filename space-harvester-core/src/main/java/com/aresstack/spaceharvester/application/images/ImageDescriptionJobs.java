package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.access.AccessContext;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescriptionException;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.background.services.AnalysisService;
import com.aresstack.spaceharvester.application.configuration.ImageSettings;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * „Bilder beschreiben“ über das Auftragssystem (Fahrplan B1/D2, ADR 0025 Nachtrag „Hintergrundaufträge“). Ein Lauf ist
 * eine Hintergrundgruppe der Art {@value #KIND} ({@link BackgroundJobs}) mit einem Unterauftrag je Space; jeder
 * Unterauftrag hat sein eigenes Kontingent von {@code ai.parallel} gleichzeitigen Beschreibungen ({@link Assets#inLane})
 * und beschreibt auch die Bilder einer Seite gleichzeitig. Die Arbeit eines Unterauftrags baut sich allein aus seinen
 * Angaben (Quelle, Space, Startseite, Teilbaum, Anlass), darum setzt {@link BackgroundJobs#resumeInterrupted()} ihn
 * nach einem Neustart im selben Eintrag fort. Was schon eine aktuelle Beschreibung des eingestellten Modells hat, fragt
 * kein Lauf erneut; nur Seiten mit KI-Freigabe (ADR 0028) gehen an das Modell.
 *
 * <p>Drei Anlässe: die Automatik ({@link #startAutomatic}, technisch, ohne Benutzer), ein Lauf über einen Space oder
 * eine Seite samt Unterseiten ({@link #start}, mit den Rechten des Benutzers) und der Knopf je Seite im Reiter „Bilder“
 * ({@link #describePage}), der als gewöhnlicher Auftrag mit Vorrang wie heute läuft, nicht nachrangig.
 *
 * <p>Nachrangig: Solange der Benutzer selbst Bilder beschreiben lässt, tritt ein Hintergrundlauf vor jedem Bild zurück
 * ({@link ModelPriority}). Die Obergrenze {@code images.max-per-run} gilt je Gruppe über alle Spaces zusammen
 * ({@link #GROUP_BUDGET}).
 */
public final class ImageDescriptionJobs {

    /** Kennung der Hintergrundart. */
    public static final String KIND = "images";
    /** Angabe: Startseite des Laufs, sonst der ganze Space. */
    public static final String ROOT = "root";
    /** Angabe: {@code true}, wenn die Unterseiten der Startseite dazugehören. */
    public static final String SUBTREE = "subtree";
    /** Angabe: der Anlass, {@value #AUTOMATIC} oder {@value #USER}. */
    public static final String TRIGGER = "trigger";
    /** Anlass: die Automatik nach Abgleich und Freigabe; läuft technisch und bleibt bei fehlender Freigabe still. */
    public static final String AUTOMATIC = "automatic";
    /** Anlass: der Benutzer; läuft mit seinen Rechten und meldet nicht freigegebene Seiten gelb. */
    public static final String USER = "user";
    /** Angaben: der Benutzer, mit dessen Rechten ein von ihm gestarteter Lauf auch nach einem Neustart weiterläuft. */
    public static final String PRINCIPAL = "principal";
    public static final String PRINCIPAL_KIND = "principal.kind";
    public static final String VIA = "via";
    /**
     * Angabe eines Teils des Dienst-Planers (D1): seine Seiten-IDs, durch Zeilenumbruch getrennt
     * ({@link AnalysisService#PAGES}).
     */
    public static final String PAGES = AnalysisService.PAGES;
    /** Ereignis: Seiten ohne KI-Freigabe bewusst ausgelassen (gelb, nur bei Läufen des Benutzers). */
    public static final String NOT_RELEASED = "IMAGES_SKIPPED:NOT_RELEASED";
    /** Ereignis: der Lauf eines Benutzers lässt sich ohne ihn nicht fortsetzen (gelb, angehalten). */
    public static final String RESUME_BY_HAND = "IMAGES_RESUME_BY_HAND";
    /**
     * Die Obergrenze {@code images.max-per-run} gilt je Gruppe, also je Quelle bzw. Auswahl, über alle Unteraufträge
     * zusammen, nicht je Space (Architekt 21.11.2026): sonst ginge bei vielen Spaces ein Vielfaches an das Modell. Das
     * gemeinsame Budget lebt im Speicher; nach einem Neustart beginnt es neu.
     */
    public static final boolean GROUP_BUDGET = true;

    private static final String COMPONENT = AutomaticImageDescriptions.COMPONENT;
    private static final System.Logger LOG = System.getLogger(ImageDescriptionJobs.class.getName());

    /** Löst eine Wurzel mit den Rechten eines Aufrufers zu Seiten auf, etwa {@code Holdings#resolve}. */
    @FunctionalInterface
    public interface Scope {
        PageSet resolve(AccessContext caller, Selection.Root root);
    }

    private final BackgroundJobs background;
    private final Jobs jobs;
    private final String owner;
    private final PageImageAnalysis analysis;
    private final Function<String, Iterator<PageSnapshot>> snapshots;
    private final Function<PageKey, Optional<PageSnapshot>> find;
    private final Scope scope;
    private final Supplier<ImageSettings> settings;
    private final AccessContext technical;
    /** Aufrufer der jüngsten Gruppen; nach einem Neustart gelten die Angaben am Unterauftrag. */
    private final Map<String, AccessContext> callers = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AccessContext> eldest) {
            return size() > 256;
        }
    });
    /**
     * Gemeinsames Budget je Gruppe ({@link #GROUP_BUDGET}); ein Unterauftrag kann enden, bevor der nächste anläuft,
     * darum bleibt es stehen und verdrängt nur die ältesten Gruppen.
     */
    private final Map<String, Budget> budgets = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Budget> eldest) {
            return size() > 256;
        }
    });

    /**
     * @param snapshots die gespeicherten Seiten einer Quelle
     * @param find      eine gespeicherte Seite, für den Knopf je Seite und {@link #missing}
     * @param scope     Teilbäume mit den Rechten des Aufrufers; {@code null} löst sie über die Elternseiten der
     *                  gespeicherten Seiten auf (Dienst, Tests)
     * @param technical Aufrufer der Automatik
     */
    public ImageDescriptionJobs(BackgroundJobs background, Jobs jobs, String owner, PageImageAnalysis analysis,
                                Function<String, Iterator<PageSnapshot>> snapshots,
                                Function<PageKey, Optional<PageSnapshot>> find, Scope scope,
                                Supplier<ImageSettings> settings, AccessContext technical) {
        this.background = Objects.requireNonNull(background, "background");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.owner = owner;
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.find = Objects.requireNonNull(find, "find");
        this.scope = scope;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.technical = Objects.requireNonNull(technical, "technical");
        // Weiter nach einem Neustart, solange ein Modell eingestellt ist; die Arbeit prüft den Rest selbst.
        background.register(new BackgroundKind(KIND, this::work, analysis::available));
    }

    /** Die Hintergrundaufträge, bei denen die Art angemeldet ist. */
    public BackgroundJobs background() {
        return background;
    }

    /**
     * Die Automatik für eine Quelle: ein Unterauftrag je Space mit freigegebenen Seiten, sofern dort nicht schon einer
     * wartet oder läuft. Prüft {@code images.automatic} nicht; das tut {@link AutomaticImageDescriptions}.
     *
     * @return die Gruppe, oder leer, wenn nichts zu tun ist
     */
    public Optional<BackgroundGroup> startAutomatic(String sourceId) {
        Set<String> spaces = new LinkedHashSet<>();
        Iterator<PageSnapshot> all = snapshots.apply(sourceId);
        while (all.hasNext()) {
            PageSnapshot snapshot = all.next();
            if (analysis.released(snapshot.key())) {
                spaces.add(snapshot.spaceKey());
            }
        }
        List<BackgroundPart.Draft> parts = new ArrayList<>();
        for (String space : spaces) {
            // Nur ein wartender oder angehaltener Teil nimmt den neuen Anlass mit; neben einem laufenden, der seine
            // Seiten schon gewählt hat, darf einer warten (wie früher der Folgeauftrag).
            if (open(sourceId, space, null, false, true).isEmpty()) {
                parts.add(new BackgroundPart.Draft(sourceId, space, Map.of(TRIGGER, AUTOMATIC)));
            }
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        String groupId = BackgroundJobs.newGroupId();
        callers.put(groupId, technical);
        return Optional.of(background.start(groupId, KIND, "Bilder beschreiben: " + sourceId, parts));
    }

    /**
     * Ein Lauf des Benutzers über einen Space ({@code pageId == null}), eine Seite oder eine Seite samt Unterseiten,
     * mit dessen Rechten, ein Unterauftrag je Space. Ohne Space alle Spaces der Quelle mit gespeicherten Seiten.
     *
     * @return die Gruppe, oder leer, wenn für jeden Space schon derselbe Lauf wartet oder läuft
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException wenn der Aufrufer die Auswahl nicht
     *                                                                               sehen darf
     */
    public Optional<BackgroundGroup> start(AccessContext caller, String sourceId, String spaceKey, String pageId,
                                           boolean subtree) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(sourceId, "sourceId");
        // Rechte vor dem Start: Teile nur für Spaces, in denen der Aufrufer Seiten der Auswahl sieht. Der Lauf löst
        // dieselbe Auswahl beim Arbeiten erneut mit ihm auf.
        PageSet visible = scope == null ? null : scope.resolve(caller, root(sourceId, spaceKey, pageId, subtree));
        Set<String> spaces = new LinkedHashSet<>();
        if (visible == null && spaceKey != null) {
            spaces.add(spaceKey);
        } else {
            snapshots.apply(sourceId).forEachRemaining(snapshot -> {
                if ((spaceKey == null || spaceKey.equals(snapshot.spaceKey()))
                        && (visible == null || visible.contains(snapshot.key()))
                        && (pageId == null || subtree || pageId.equals(snapshot.key().pageId()))) {
                    spaces.add(snapshot.spaceKey());
                }
            });
        }
        List<BackgroundPart.Draft> parts = new ArrayList<>();
        for (String space : spaces) {
            if (open(sourceId, space, pageId, subtree, false).isPresent()) {
                continue;
            }
            Map<String, String> parameters = new HashMap<>();
            parameters.put(TRIGGER, USER);
            if (pageId != null) {
                parameters.put(ROOT, pageId);
                parameters.put(SUBTREE, Boolean.toString(subtree));
            }
            parameters.put(PRINCIPAL, caller.principal());
            parameters.put(PRINCIPAL_KIND, caller.kind().name());
            if (caller.via() != null) {
                parameters.put(VIA, caller.via());
            }
            parts.add(new BackgroundPart.Draft(sourceId, space, parameters));
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        String title = "Bilder beschreiben: " + (pageId != null
                ? find.apply(new PageKey(sourceId, pageId)).map(snapshot -> snapshot.metadata().title()).orElse(pageId)
                + (subtree ? " mit Unterseiten" : "")
                : spaceKey != null ? spaceKey : sourceId);
        String groupId = BackgroundJobs.newGroupId();
        callers.put(groupId, caller);
        return Optional.of(background.start(groupId, KIND, title, parts));
    }

    /**
     * Setzt einen Unterauftrag mit den Rechten des Benutzers fort, der ▶ drückt; der Weg der App für „Fortsetzen“.
     */
    public JobStatus resume(AccessContext caller, String jobId) {
        JobStatus current = jobs.status(jobId, owner);
        String group = current.parameter(BackgroundJobs.GROUP);
        if (group != null && caller != null && !AUTOMATIC.equals(current.parameter(TRIGGER))) {
            callers.put(group, caller);
        }
        return background.resumeJob(jobId);
    }

    /**
     * Der Knopf je Seite im Reiter „Bilder“ (Architekt 21.11.2026): ein gewöhnlicher Auftrag „Bilder beschreiben“ mit
     * einer Seite und ohne Gruppe, mit Vorrang wie heute und über denselben Lauf wie der Hintergrund. Zählt als
     * interaktive Beschreibung, Hintergrundläufe treten also vor ihm zurück. {@code progress} erfährt jedes Bild,
     * sobald es fertig ist. Kehrt zurück, wenn der Auftrag endet.
     *
     * @throws ImageDescriptionException {@code NOT_CONFIGURED} ohne Modell, {@code NOT_RELEASED} ohne Freigabe
     */
    public PageImages describePage(String caller, PageSnapshot snapshot,
                                   java.util.function.Consumer<ImagePayload.Entry> progress) {
        Objects.requireNonNull(snapshot, "snapshot");
        CompletableFuture<PageImages> done = new CompletableFuture<>();
        // Ein Klick wartet nie hinter Hintergrund (Abnahme #1054, Jobs#submitInteractive aus #1055).
        JobStatus status = jobs.submitInteractive(JobType.ENRICHMENT, snapshot.key().sourceId(),
                snapshot.spaceKey(), null, caller, Map.of(AutomaticImageDescriptions.TASK_PARAMETER,
                        AutomaticImageDescriptions.TASK, ROOT, snapshot.key().pageId()),
                JobTask.blocking(context -> {
                    int parallel = Math.max(1, analysis.parallel());
                    // Der Pool lebt so lange wie die Seite, nicht bis zum letzten gerade laufenden Bild: Ein schnell
                    // übersprungenes erstes Bild darf ihn nicht vor der Abgabe des zweiten schließen.
                    ExecutorService pool = pool(parallel, "bilder-modell");
                    try (ModelPriority.Interactive ignored = analysis.priority().interactive()) {
                        context.phase(COMPONENT, "Bilder beschreiben");
                        context.discovered(1);
                        // Wie der Knopf bisher: Scheitert das Modell an einem Bild, bekommt jedes Bild seinen
                        // Eintrag (FAILED) und die Seite wird gespeichert; kein Halt wie im Hintergrund.
                        PageImages images = analysis.describeInBackground(snapshot, entry -> {
                            context.checkpoint();
                            progress.accept(entry);
                        }, runner(context, true, new java.util.concurrent.atomic.AtomicBoolean(),
                                "images:" + context.jobId(), pool, parallel));
                        context.processed(1);
                        if (images.images().stream().anyMatch(image -> image.status() == ImageStatus.FAILED)) {
                            context.failed(1);
                        } else {
                            context.written(1);
                        }
                        done.complete(images);
                    } catch (ImageDescriptionException e) {
                        done.completeExceptionally(e);
                        if (e.reason() == ImageDescriptionException.Reason.NOT_CONFIGURED) {
                            // Bewusst ausgelassen, gelb statt rot; der Aufrufer erfährt den Grund.
                            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT,
                                    AutomaticImageDescriptions.NO_MODEL,
                                    "Kein Vision-Modell eingestellt; Bilder bewusst nicht beschrieben"));
                        } else if (e.reason() == ImageDescriptionException.Reason.NOT_RELEASED) {
                            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, NOT_RELEASED,
                                    "Seite ohne KI-Freigabe; Bilder bewusst nicht beschrieben"));
                        } else {
                            throw e;
                        }
                    } catch (RuntimeException e) {
                        done.completeExceptionally(e);
                        throw e;
                    } finally {
                        pool.shutdown();
                    }
                }));
        // Abgebrochen, bevor er anlief: die Arbeit läuft nie, der Wartende erfährt es über die Auftragsmeldungen.
        com.aresstack.spaceharvester.application.observe.Subscription watching = jobs.subscribe(caller,
                new com.aresstack.spaceharvester.application.jobs.JobListener() {
                    @Override
                    public void changed(JobStatus changed) {
                        if (changed.id().equals(status.id()) && changed.state().terminal()) {
                            done.completeExceptionally(new JobCancelledException(status.id()));
                        }
                    }
                });
        try {
            JobStatus now = jobs.status(status.id(), caller);
            if (now.state().terminal()) {
                done.completeExceptionally(new JobCancelledException(status.id()));
            }
            return done.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        } finally {
            watching.close();
        }
    }

    /**
     * Freigegebene Seiten der Menge, die ein Lauf an das Modell gäbe, für den Planer (D1): Bilder ohne aktuelle
     * Beschreibung des eingestellten Modells, gescheiterte Ergebnisse und Seiten ohne gespeicherte Lese-Ansicht. Ohne
     * Modell leer. Fragt weder Quelle noch Modell.
     */
    public PageSet missing(AccessContext caller, PageSet pages) {
        return select(pages, true);
    }

    /** Seiten der Menge mit Bildern, die ohne KI-Freigabe nicht beschrieben werden dürfen; ohne Modell leer. */
    public PageSet missingNotReleased(AccessContext caller, PageSet pages) {
        return select(pages, false);
    }

    private PageSet select(PageSet pages, boolean released) {
        if (!analysis.available()) {
            return PageSet.EMPTY;
        }
        List<PageKey> found = new ArrayList<>();
        pages.asMap().forEach((sourceId, pageIds) -> {
            for (String pageId : pageIds) {
                PageKey key = new PageKey(sourceId, pageId);
                Optional<PageSnapshot> snapshot = find.apply(key);
                if (snapshot.isEmpty() || analysis.released(key) != released) {
                    continue;
                }
                try {
                    PageImages images = analysis.read(snapshot.get());
                    // Wie der Lauf: auch gescheiterte Ergebnisse und Seiten ohne gespeicherte Lese-Ansicht.
                    if (released ? work(images, key) != Work.NONE : !images.images().isEmpty()) {
                        found.add(key);
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Bilder einer Seite nicht lesbar ({0})",
                            e.getClass().getName());
                }
            }
        });
        return PageSet.ofKeys(found);
    }

    /**
     * Ein offener Unterauftrag mit derselben Auswahl, gegen doppelte Anläufe; mit {@code waiting} nur ein wartender
     * oder angehaltener, nicht ein laufender.
     */
    private Optional<JobStatus> open(String sourceId, String space, String root, boolean subtree, boolean waiting) {
        return background.groups().stream().flatMap(group -> group.parts().stream())
                .filter(status -> !status.state().terminal()
                        && (!waiting || status.state() != com.aresstack.spaceharvester.application.jobs.JobState.RUNNING)
                        && KIND.equals(status.parameter(BackgroundJobs.KIND))
                        && sourceId.equals(status.sourceId()) && Objects.equals(space, status.partitionKey())
                        && Objects.equals(root, status.parameter(ROOT))
                        && (root == null || Boolean.toString(subtree).equals(status.parameter(SUBTREE))))
                .findFirst();
    }

    private static Selection.Root root(String sourceId, String spaceKey, String pageId, boolean subtree) {
        if (pageId != null) {
            // Auch eine einzelne Seite: Der Teilbaum enthält sie, der Lauf nimmt dann nur sie.
            return Selection.Root.subtree(sourceId, spaceKey, pageId);
        }
        return spaceKey != null ? Selection.Root.space(sourceId, spaceKey) : Selection.Root.source(sourceId);
    }

    /** Die Arbeit eines Unterauftrags, allein aus seinen Angaben. */
    private JobTask work(BackgroundPart part) {
        return JobTask.blocking(context -> run(part, context));
    }

    private void run(BackgroundPart part, JobContext context) {
        boolean automatic = AUTOMATIC.equals(part.parameter(TRIGGER));
        ImageSettings now = settings.get();
        if (automatic && (now == null || !now.automatic())) {
            // Die Automatik wurde inzwischen ausgeschaltet: nichts an das Modell.
            return;
        }
        Budget budget;
        synchronized (budgets) {
            budget = GROUP_BUDGET ? budgets.computeIfAbsent(part.groupId(), id -> new Budget()) : new Budget();
        }
        String listed = part.parameter(PAGES);
        if (listed != null) {
            // Ein Teil des Dienst-Planers (D1): Seine Seiten hat der Planer beim Start mit den Rechten des Aufrufers
            // bestimmt (missing() bzw. sichtbare Seiten); die Liste ist fest und wächst nie, darum braucht der Teil
            // auch nach einem Neustart keinen Aufrufer und läuft nie über mehr als diese Seiten.
            List<PageSnapshot> chosen = new ArrayList<>();
            for (String pageId : listed.split("\n")) {
                if (!pageId.isBlank()) {
                    find.apply(new PageKey(part.sourceId(), pageId.strip()))
                            .filter(snapshot -> part.partitionKey() == null
                                    || part.partitionKey().equals(snapshot.spaceKey()))
                            .ifPresent(chosen::add);
                }
            }
            run(chosen.iterator(), snapshot -> true, now, context, budget, true);
            return;
        }
        AccessContext caller = automatic ? technical : caller(part);
        if (caller == null) {
            // Ohne den Benutzer liefe der Teilbaum mit fremden Rechten (Architekt 21.11.2026).
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, RESUME_BY_HAND,
                    "Lauf eines Benutzers; bitte von Hand fortsetzen"));
            context.pause(PauseReason.USER);
            return;
        }
        Predicate<PageSnapshot> inScope = scope(part, caller, !automatic);
        String root = part.parameter(ROOT);
        Iterator<PageSnapshot> pages = root != null && !"true".equals(part.parameter(SUBTREE))
                ? find.apply(new PageKey(part.sourceId(), root)).stream().iterator()
                : snapshots.apply(part.sourceId());
        run(pages, inScope, now, context, budget, !automatic);
    }

    /** Der Benutzer eines Laufs: im Speicher, sonst aus den Angaben; ohne beides {@code null}. */
    private AccessContext caller(BackgroundPart part) {
        AccessContext known = callers.get(part.groupId());
        if (known != null) {
            return known;
        }
        String principal = part.parameter(PRINCIPAL);
        String kind = part.parameter(PRINCIPAL_KIND);
        if (principal == null || kind == null) {
            return null;
        }
        try {
            // Ohne Anmeldedaten: Der Teilbaum braucht nur die Leserechte des Benutzers, Bilder lädt die Quelle selbst.
            return new AccessContext(principal, AccessContext.PrincipalKind.valueOf(kind), part.parameter(VIA), null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Welche Seiten ein Unterauftrag beschreibt. Ein Lauf des Benutzers prüft jeden Umfang (Space, Seite, Teilbaum)
     * mit dessen Rechten; die Automatik läuft technisch über die freigegebenen Seiten.
     */
    private Predicate<PageSnapshot> scope(BackgroundPart part, AccessContext caller, boolean user) {
        String space = part.partitionKey();
        String root = part.parameter(ROOT);
        boolean subtree = "true".equals(part.parameter(SUBTREE));
        Predicate<PageSnapshot> inSpace = snapshot -> space == null || space.equals(snapshot.spaceKey());
        Predicate<PageSnapshot> range = root == null ? inSpace
                : subtree ? null : snapshot -> snapshot.key().pageId().equals(root);
        if (scope != null && (user || root != null && subtree)) {
            PageSet set = scope.resolve(caller, root(part.sourceId(), space, root, subtree));
            return range == null ? snapshot -> set.contains(snapshot.key())
                    : snapshot -> range.test(snapshot) && set.contains(snapshot.key());
        }
        if (range != null) {
            return range;
        }
        // Ohne Bestand: über die Elternseiten der gespeicherten Seiten.
        Map<String, List<String>> children = new HashMap<>();
        snapshots.apply(part.sourceId()).forEachRemaining(snapshot -> {
            if (inSpace.test(snapshot) && snapshot.metadata().parentPageId() != null) {
                children.computeIfAbsent(snapshot.metadata().parentPageId(), key -> new ArrayList<>())
                        .add(snapshot.key().pageId());
            }
        });
        Set<String> below = new HashSet<>();
        List<String> next = new ArrayList<>(List.of(root));
        while (!next.isEmpty()) {
            String pageId = next.remove(next.size() - 1);
            if (below.add(pageId)) {
                next.addAll(children.getOrDefault(pageId, List.of()));
            }
        }
        return snapshot -> inSpace.test(snapshot) && below.contains(snapshot.key().pageId());
    }

    /** Das gemeinsame Budget einer Gruppe; unter {@code this}. */
    static final class Budget {
        /** Vorgemerkte Bilder; nach jeder Seite um die tatsächlich beschriebenen berichtigt. */
        int reserved;
        boolean oversized;
    }

    /** Ein Lauf über alle gespeicherten Seiten der Quelle, mit eigenem Budget; für Tests und die Automatik ohne Gruppe. */
    AutomaticImageDescriptions.Outcome run(String sourceId, ImageSettings chosen, JobContext context) {
        return run(snapshots.apply(sourceId), snapshot -> true, chosen, context, new Budget(), false);
    }

    /**
     * Der Lauf: bis zu {@code ai.parallel} Seiten zugleich, und die Bilder jeder Seite gleichzeitig, zusammen höchstens
     * {@code ai.parallel} Beschreibungen in der eigenen Spur ({@link Assets#inLane}).
     *
     * @param user ob der Benutzer den Lauf gestartet hat: dann zählen nicht freigegebene Seiten gelb
     */
    AutomaticImageDescriptions.Outcome run(Iterator<PageSnapshot> all, Predicate<PageSnapshot> inScope,
                                           ImageSettings chosen, JobContext context, Budget shared, boolean user) {
        context.phase(COMPONENT, "Bilder beschreiben");
        ImageSettings limits = chosen == null ? ImageSettings.DEFAULT : chosen;
        if (!analysis.available()) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT,
                    AutomaticImageDescriptions.NO_MODEL, "Kein Vision-Modell eingestellt; Bilder bewusst nicht "
                            + "beschrieben"));
            return new AutomaticImageDescriptions.Outcome(0, 0, 0, 0);
        }
        int budget = limits.unlimited() ? Integer.MAX_VALUE : limits.maxPerRun();
        int parallel = Math.max(1, analysis.parallel());
        String lane = "images:" + context.jobId();
        Tally tally = new Tally();
        Semaphore slots = new Semaphore(parallel);
        ExecutorService pool = pool(parallel, "bilder-seiten");
        ExecutorService images = pool(parallel, "bilder-modell");
        PageImageAnalysis.ImageRunner runner = runner(context, false, tally.halt, lane, images, parallel);
        List<Future<?>> running = new ArrayList<>();
        RuntimeException stopped = null;
        try {
            while (all.hasNext() && tally.stop.get() == null) {
                context.checkpoint();
                PageSnapshot snapshot = all.next();
                if (!inScope.test(snapshot)) {
                    continue;
                }
                if (!analysis.released(snapshot.key())) {
                    if (user && hasImages(snapshot)) {
                        // Wie missingNotReleased: nur Seiten, an denen tatsächlich Bilder liegen.
                        tally.notReleased.incrementAndGet();
                    }
                    continue;
                }
                PageImages before;
                try {
                    before = analysis.read(snapshot);
                } catch (RuntimeException e) {
                    tally.failed.incrementAndGet();
                    continue;
                }
                Work work = work(before, snapshot.key());
                if (work == Work.NONE) {
                    continue;
                }
                int pending = work.pending;
                // Erst den Platz, dann die Entscheidung über die Grenze: So rechnet sie mit den berichtigten Zahlen
                // aller Seiten, die vor dem freien Platz fertig wurden. Kein Takt: wartet am Semaphor.
                slots.acquireUninterruptibly();
                try {
                    context.checkpoint();
                } catch (RuntimeException e) {
                    slots.release();
                    throw e;
                }
                if (tally.stop.get() != null) {
                    slots.release();
                    break;
                }
                boolean fits;
                synchronized (shared) {
                    // Eine Seite größer als die ganze Grenze darf als erste der Gruppe trotzdem (überzieht einmal).
                    fits = pending <= budget - shared.reserved || shared.reserved == 0 && !shared.oversized;
                    if (fits) {
                        if (pending > budget - shared.reserved) {
                            shared.oversized = true;
                        }
                        shared.reserved += pending;
                    }
                }
                if (!fits) {
                    // Passt nicht mehr: bleibt für den nächsten Lauf, kleinere Seiten dürfen noch.
                    tally.open.addAndGet(pending);
                    slots.release();
                    continue;
                }
                context.discovered(1);
                running.add(pool.submit(() -> {
                    try {
                        page(snapshot, before, pending, tally, shared, context, runner);
                    } finally {
                        slots.release();
                    }
                }));
            }
        } catch (RuntimeException e) {
            stopped = e;
        } finally {
            boolean interrupted = false;
            for (Future<?> page : running) {
                while (true) {
                    try {
                        page.get();
                        break;
                    } catch (java.util.concurrent.ExecutionException e) {
                        if (stopped == null && e.getCause() instanceof RuntimeException cause) {
                            stopped = cause;
                        }
                        break;
                    } catch (java.util.concurrent.CancellationException e) {
                        break;
                    } catch (InterruptedException e) {
                        // Ende der Anwendung: keine neuen Bilder; was nie anlief, läuft hier sofort leer durch (wirft
                        // wegen des Halts), damit jede Seite endet und kein Arbeiter den Auftrag überlebt.
                        interrupted = true;
                        tally.stop.compareAndSet(null, "INTERRUPTED");
                        tally.halt.set(true);
                        images.shutdownNow().forEach(Runnable::run);
                        pool.shutdownNow();
                    }
                }
            }
            pool.shutdown();
            images.shutdown();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (stopped != null) {
            throw stopped;
        }
        int open = tally.open.get();
        if (open > 0) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT,
                    AutomaticImageDescriptions.LIMIT_REACHED, "Obergrenze von " + limits.maxPerRun()
                            + " Bildern je Lauf erreicht; " + open + (open == 1 ? " Bild bleibt" : " Bilder bleiben")
                            + " für den nächsten Lauf offen"));
            context.incomplete();
        }
        int notReleased = tally.notReleased.get();
        if (notReleased > 0) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, NOT_RELEASED,
                    notReleased + (notReleased == 1 ? " Seite" : " Seiten") + " ohne KI-Freigabe bewusst ausgelassen"));
        }
        int pages = tally.pages.get();
        int sent = tally.sent.get();
        int failed = tally.failed.get();
        context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                AutomaticImageDescriptions.DONE, pages + (pages == 1 ? " Seite, " : " Seiten, ") + sent
                        + (sent == 1 ? " Bild" : " Bilder") + " beschrieben"
                        + (failed > 0 ? ", " + failed + " Seiten nicht" : ""), null, null, null));
        return new AutomaticImageDescriptions.Outcome(pages, sent, open, failed);
    }

    private boolean hasImages(PageSnapshot snapshot) {
        try {
            return !analysis.read(snapshot).images().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ExecutorService pool(int size, String name) {
        return Executors.newFixedThreadPool(size, work -> {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Jedes Bild als eigene Aufgabe: im Hintergrund erst nach den Beschreibungen des Benutzers ({@link ModelPriority}),
     * dann mit einem der {@code parallel} Plätze der Spur. Ist der Lauf abgebrochen, angehalten oder gestoppt
     * ({@code halt}), beginnt kein Bild mehr; scheitert das Modell im Hintergrund an einem Bild auch nach den
     * Wiederholungen, beginnt ab da keines mehr, wie früher der Reihe nach. Interaktiv (Knopf) bekommt jedes Bild
     * seinen Versuch.
     */
    private PageImageAnalysis.ImageRunner runner(JobContext context, boolean interactive,
                                                 java.util.concurrent.atomic.AtomicBoolean halt, String lane,
                                                 ExecutorService images, int parallel) {
        Semaphore models = new Semaphore(parallel);
        return image -> CompletableFuture.supplyAsync(() -> {
            begin(context, halt);
            if (!interactive && analysis.priority().awaitIdle(context, () -> waitingForModel(context))) {
                context.phase(COMPONENT, "Bilder beschreiben");
            }
            models.acquireUninterruptibly();
            ImagePayload.Entry entry;
            try {
                begin(context, halt);
                entry = Assets.inLane(lane, image);
            } finally {
                models.release();
            }
            if (!interactive && entry != null && entry.status() == ImageStatus.FAILED
                    && PageImageAnalysis.modelFailure(entry.reason())) {
                halt.set(true);
            }
            return entry;
        }, images);
    }

    private static void begin(JobContext context, java.util.concurrent.atomic.AtomicBoolean halt) {
        context.checkpoint();
        if (halt.get()) {
            throw new ModelDown(null);
        }
    }

    /** Zähler eines Laufs, von mehreren Arbeitern zugleich geschrieben. */
    private static final class Tally {
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger pages = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger notReleased = new AtomicInteger();
        /** Warum der Lauf keine weiteren Seiten oder Bilder beginnt; der erste Grund gilt. */
        final AtomicReference<String> stop = new AtomicReference<>();
        /** Kein weiteres Bild beginnen; gesetzt mit {@link #stop} oder beim ersten endgültig gescheiterten Bild. */
        final java.util.concurrent.atomic.AtomicBoolean halt = new java.util.concurrent.atomic.AtomicBoolean();
    }

    /** Eine Seite in einem Arbeiter; ihre Bilder laufen gleichzeitig über {@code runner}. */
    private void page(PageSnapshot snapshot, PageImages before, int pending, Tally tally, Budget shared,
                      JobContext context, PageImageAnalysis.ImageRunner runner) {
        if (tally.stop.get() != null) {
            release(shared, pending);
            return;
        }
        boolean settled = false;
        try {
            context.checkpoint();
            PageImages after = analysis.describeInBackground(snapshot, entry -> {
                context.checkpoint();
                if (entry.status() == ImageStatus.FAILED && PageImageAnalysis.modelFailure(entry.reason())) {
                    throw new ModelDown(entry.reason());
                }
                if (tally.stop.get() != null) {
                    throw new ModelDown(null);
                }
            }, runner);
            int described = AutomaticImageDescriptions.newlyDescribed(before, after);
            tally.sent.addAndGet(described);
            synchronized (shared) {
                shared.reserved += described - pending;
            }
            settled = true;
            tally.pages.incrementAndGet();
            List<String> failures = after.images().stream().filter(image -> image.status() == ImageStatus.FAILED)
                    .map(ImagePayload.Entry::reason).toList();
            if (failures.isEmpty()) {
                context.written(1);
            } else {
                tally.failed.incrementAndGet();
                context.failed(1);
                failures.stream().filter(PageImageAnalysis::modelFailure).findFirst().ifPresent(reason ->
                        stop(tally, AutomaticImageDescriptions.MODEL_FAILED, () -> context.record(modelFailed(reason))));
            }
        } catch (ModelDown e) {
            if (e.reason != null) {
                tally.failed.incrementAndGet();
                context.failed(1);
                stop(tally, AutomaticImageDescriptions.MODEL_FAILED, () -> context.record(modelFailed(e.reason)));
            }
        } catch (SignInRequiredException e) {
            stop(tally, AutomaticImageDescriptions.SIGN_IN, () -> context.record(JobEvent.Draft.warning(
                    JobEvent.Category.SOURCE, COMPONENT, AutomaticImageDescriptions.SIGN_IN,
                    "Die Quelle verlangt einen Einmalcode; Bilder dieses Laufs bewusst ausgelassen")));
        } catch (ImageDescriptionException e) {
            if (e.reason() == ImageDescriptionException.Reason.NOT_CONFIGURED) {
                stop(tally, AutomaticImageDescriptions.NO_MODEL, () -> context.record(JobEvent.Draft.warning(
                        JobEvent.Category.JOB, COMPONENT, AutomaticImageDescriptions.NO_MODEL,
                        "Kein Vision-Modell eingestellt; Bilder bewusst nicht beschrieben")));
            } else if (e.reason() != ImageDescriptionException.Reason.NOT_RELEASED) {
                // NOT_RELEASED: die Freigabe fiel eben weg, die Seite bleibt draußen.
                tally.failed.incrementAndGet();
                context.failed(1);
            }
        } catch (JobCancelledException e) {
            // Abbrechen oder Anhalten: keine weitere Seite beginnen.
            tally.stop.compareAndSet(null, "CANCELLED");
            tally.halt.set(true);
            throw e;
        } catch (RuntimeException e) {
            // Eine Seite, deren Lese-Ansicht oder Bilder sich nicht laden lassen, hält die übrigen nicht auf.
            tally.failed.incrementAndGet();
            context.failed(1);
            LOG.log(System.Logger.Level.WARNING, "Bilder einer Seite in {0} nicht beschrieben ({1})",
                    snapshot.key().sourceId(), e.getClass().getName());
        } finally {
            if (!settled) {
                // Pause, Abbruch, Anmeldung, Fehler: Die Vormerkung geht zurück, sonst beschriebe ein fortgesetzter
                // Teil derselben Gruppe nichts mehr. Was schon beschrieben ist, zählt der nächste Lauf nicht erneut.
                release(shared, pending);
            }
        }
        context.processed(1);
    }

    private static void release(Budget shared, int pending) {
        synchronized (shared) {
            shared.reserved = Math.max(0, shared.reserved - pending);
        }
    }

    /** Hält den Lauf an; nur der erste Grund meldet sich. */
    private static void stop(Tally tally, String code, Runnable report) {
        tally.halt.set(true);
        if (tally.stop.compareAndSet(null, code)) {
            report.run();
        }
    }

    private static void waitingForModel(JobContext context) {
        context.phase(COMPONENT, AutomaticImageDescriptions.WAITING_PHASE);
    }

    private static JobEvent.Draft modelFailed(String reason) {
        return new JobEvent.Draft(JobEvent.Severity.ERROR, JobEvent.Category.JOB, COMPONENT,
                AutomaticImageDescriptions.MODEL_FAILED, "Das Vision-Modell antwortet nicht (" + reason
                + "); Lauf beendet, offene Bilder folgen beim nächsten Lauf", null, null, null);
    }

    /** Das Modell scheiterte an einem Bild auch nach den Wiederholungen; die übrigen Bilder warten. */
    private static final class ModelDown extends RuntimeException {
        final String reason;

        ModelDown(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    /** Was an einer Seite zu tun ist: nichts, oder beschreiben mit (geschätzt) so vielen offenen Bildern. */
    private record Work(int pending) {
        static final Work NONE = new Work(-1);
    }

    /**
     * Offene Bilder einer Seite: noch nicht beschrieben, veraltet oder mit gescheitertem Modellaufruf. Ohne gespeicherte
     * Lese-Ansicht ist die Zahl unbekannt; die Seite zählt dann als ein offenes Bild, bis die Quelle sie liefert.
     */
    private Work work(PageImages images, PageKey key) {
        return switch (images.state()) {
            case MISSING -> {
                if (PageImages.NO_TEXT.equals(images.reason())) {
                    yield Work.NONE;
                }
                long open = images.images().stream().filter(ImageDescriptionJobs::open).count();
                // Ohne gespeicherte Lese-Ansicht ist die Liste leer; die Quelle sagt erst beim Beschreiben, was dort ist.
                // Mit gespeicherter Lese-Ansicht ohne Bilder ist nichts zu tun.
                if (images.images().isEmpty()) {
                    yield analysis.readingViewStored(key) ? Work.NONE : new Work(1);
                }
                yield open == 0 ? Work.NONE : new Work((int) open);
            }
            case STALE -> new Work(Math.max(1, (int) images.images().stream()
                    .filter(image -> image.status() != ImageStatus.SKIPPED).count()));
            case FAILED -> new Work(1);
            case CURRENT -> {
                long open = images.images().stream().filter(image -> image.status() == ImageStatus.PENDING
                        && (image.reason() == null || "model-busy".equals(image.reason()))
                        || image.status() == ImageStatus.FAILED && (PageImageAnalysis.modelFailure(image.reason())
                        || AutomaticImageDescriptions.sourceFailure(image.reason())))
                        .count();
                yield open == 0 ? Work.NONE : new Work((int) open);
            }
        };
    }

    private static boolean open(ImagePayload.Entry image) {
        return image.status() != ImageStatus.SKIPPED && image.status() != ImageStatus.DESCRIBED;
    }
}
