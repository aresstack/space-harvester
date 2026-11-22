package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.check.CheckScope;
import com.aresstack.spaceharvester.application.check.CheckUnavailableException;
import com.aresstack.spaceharvester.application.check.StatementChecks;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.Holdings;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.Partition;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Aussagenanalyse für einen ganzen Umfang als ausdrücklich gestarteter Auftrag (#499 Abschnitt E, ADR 0024): eine
 * Seite, eine Seite mit allen Nachfahren, ein Space oder eine Quelle. Der Auftrag ermittelt die Seiten über
 * {@link PageNavigation#children} mit allen Cursorn (nie über den sichtbar geladenen Baum der App), lädt fehlende
 * Inhalte nur in diesem Auftrag über den geschützten Seitenabruf und analysiert jede Seite einzeln mit
 * {@link StatementAnalysis#analyze}. Jede Seite wird für sich atomar ersetzt; ein Abbruch behält die fertigen Seiten,
 * die angefangene schreibt nichts.
 *
 * <p>Aktuelle Seiten (gleicher Text, gleicher Analysator) werden übersprungen, außer der Nutzer verlangt
 * {@link Options#reanalyze()}. Zähler: {@code discovered} = ermittelte lesbare Seiten, {@code processed} = erledigt
 * (analysiert, übersprungen oder gescheitert), {@code written} = neu analysiert, {@code failed} = gescheitert; die
 * Differenz {@code processed - written - failed} sind übersprungene Seiten (aktuell oder ohne Lesetext; das
 * Abschlussereignis nennt beide Zahlen).
 *
 * <p>Rechte: Seiten, deren Inhalt der Auftraggeber nicht lesen darf, werden weder geladen noch gezählt noch in
 * Ereignissen genannt. Ereignisse enthalten keine Titel, Seiten-IDs oder Texte, nur Zahlen und Ausnahmeklassen.
 *
 * <p>Es gibt keinen anderen Auslöser: kein Abgleich, kein Zeitplan und keine Anreicherungskette startet diesen
 * Auftrag. Die Seiten laufen nacheinander; der Analysator lädt sein Modell einmal und teilt es.
 *
 * <p>Fahrplan D3: Eine einzelne Seite ist ein normaler Auftrag ({@link JobType#STATEMENT_ANALYSIS}) mit dem Vorrang
 * wie bisher. Teilbaum, Space und Quelle laufen, sobald {@link #background} angeschlossen ist, als Hintergrundgruppe
 * der Art {@value #KIND} mit einem Unterauftrag je Space; derselbe Code rechnet, die Arbeit entsteht allein aus den
 * Angaben des Unterauftrags (Umfang, Analysator, Vorstufe, Eigentümer). So setzt ein Neustart sie fort, und der
 * Analysator bleibt der beim Start gewählte, auch wenn die Einstellung inzwischen anders ist.
 */
public class StatementAnalysisJobs {

    /** Komponente in Diagnoseereignissen. */
    public static final String COMPONENT = "Aussagenanalyse";
    /** Phase, in der der Auftrag Seiten ermittelt. */
    public static final String PHASE_DISCOVERY = "Seiten ermitteln";
    /** Phase, in der der Auftrag Seiten analysiert. */
    public static final String PHASE_ANALYSIS = "Seiten analysieren";
    /** Fehlercode, wenn der Umfang die bestätigte Sicherheitsgrenze überschreitet. */
    public static final String SCOPE_TOO_LARGE = "SCOPE_TOO_LARGE";
    /**
     * Ereignis eines Teils mit Grenze: so viele Seiten hat er in seinem Space ermittelt (Zahl vorn). Es bleibt beim
     * Fortsetzen erhalten, die Zähler nicht; so zählen Geschwister ihn auch, wenn er nach Anhalten neu wartet.
     */
    public static final String SCOPE_COUNTED = "SCOPE_COUNTED";
    /** Fehlercode, wenn Teile des Umfangs nicht ermittelt werden konnten; analysierte Seiten bleiben. */
    public static final String INCOMPLETE = "INCOMPLETE";
    /** Fehlercode ohne eingerichteten oder mit fehlendem bzw. defektem Analysator. */
    public static final String ANALYZER_UNAVAILABLE = "ANALYZER_UNAVAILABLE";
    /** Code des Info-Ereignisses „so viele Seiten ohne KI-Freigabe, ohne Vorstufe analysiert“ (ADR 0032, FL3). */
    public static final String STAGE_NOT_RELEASED = "STAGE_NOT_RELEASED";
    /** Code des Info-Ereignisses „so viele Seiten ohne Vorstufe, weil kein Modell eingestellt ist“. */
    public static final String STAGE_NO_MODEL = "STAGE_NO_MODEL";
    /** Code des Info-Ereignisses „so viele Seiten ohne KI-Freigabe nicht vom Sprachmodell analysiert“ (A2). */
    public static final String MODEL_NOT_RELEASED = "MODEL_NOT_RELEASED";
    /** Code des Info-Ereignisses „so viele Seiten nicht vom Sprachmodell analysiert, kein Modell eingestellt“. */
    public static final String MODEL_NO_MODEL = "MODEL_NO_MODEL";
    /** Code des Info-Ereignisses mit den Zahlen des KI-Analysators (Fahrplan Paket A2). */
    public static final String MODEL_COSTS = "MODEL_COSTS";
    /** Phase, in der der Prüfauftrag Seiten prüft (ADR 0032, FL4). */
    public static final String PHASE_CHECK = "Aussagen prüfen";
    /** Fehlercode des Prüfauftrags ohne eingestelltes Chat-Modell. */
    public static final String CHECK_UNAVAILABLE = "CHECK_UNAVAILABLE";
    /** Code des Info-Ereignisses mit den Zahlen des Prüfschritts. */
    public static final String CHECK_COSTS = "CHECK_COSTS";
    /** Code des Info-Ereignisses „so viele Seiten ohne KI-Freigabe nicht geprüft“. */
    public static final String CHECK_NOT_RELEASED = "CHECK_NOT_RELEASED";
    /** Ereignis des Prüfauftrags: so viele Seiten ohne aktuelle Analyse nicht geprüft (Zahl vorn). */
    public static final String CHECK_NOT_CURRENT = "CHECK_NOT_CURRENT";
    /**
     * Anfang des Codes der Angabe mit dem Grund zu {@value #ANALYZER_UNAVAILABLE}, dahinter
     * {@link AnalyzerUnavailableException.Reason#name()}, etwa {@code ANALYZER_REASON_MODEL_MISSING}.
     */
    public static final String ANALYZER_REASON = "ANALYZER_REASON_";
    /** Kennung der Hintergrundart (Fahrplan D3). */
    public static final String KIND = "statements";
    /** Code der gelben Angabe: nach dem Neustart nicht von selbst fortgesetzt, weil ein anderer Eigentümer startete. */
    public static final String RESUME_BY_HAND = "RESUME_BY_HAND";
    /** Angabe: Umfang des Unterauftrags, {@value #SCOPE_PAGE}, {@value #SCOPE_SUBTREE} oder {@value #SCOPE_SPACE}. */
    public static final String SCOPE = "scope";
    /** Angabe: Ausgangsseite eines Teilbaums. */
    public static final String ROOT = "root";
    /** Angabe: der Analysator ({@link AnalyzerChoice#value()}), beim Start festgelegt. */
    public static final String ANALYZER = "analyzer";
    /** Angabe: erneut analysieren ({@code true}/{@code false}). */
    public static final String REANALYZE = "reanalyze";
    /** Angabe: Sicherheitsgrenze je Unterauftrag, {@code 0} ohne. */
    public static final String MAX_PAGES = "max-pages";
    /** Angabe: Betriebsart der Vorstufe ({@link com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode#value()}), fehlt ohne Vorstufe. */
    public static final String STAGE_MODE = "stage-mode";
    /** Angabe: Obergrenze zugleich der Vorstufe. */
    public static final String STAGE_PARALLEL = "stage-parallel";
    /** Angabe: Startzeit; beim Fortsetzen mit „erneut analysieren“ gilt eine Seite ab hier als erledigt. */
    public static final String SINCE = "since";
    /** Angabe: Eigentümer ({@link AccessContext#jobOwner()}) des Aufrufers, der startete. */
    public static final String OWNER = "owner";
    /**
     * Angabe eines Teils aus dem Dienst-Planer (D1): Kennung des Start-Aufrufers in diesem Prozess, damit der erste Lauf
     * mit seinen Rechten rechnet; nach einem Neustart gilt die Regel für {@value #OWNER}.
     */
    public static final String CALLER = "caller";
    static final String SCOPE_PAGE = "page";
    static final String SCOPE_SUBTREE = "subtree";
    static final String SCOPE_SPACE = "space";

    private final Jobs jobs;
    private final Discovery discovery;
    private final PageFetch fetch;
    private final StatementAnalysis analysis;
    private final AccessGuard access;
    private final String owner;
    private volatile BackgroundJobs background;
    private volatile AccessContext restored;
    private volatile LocalPages local;
    /** Aufrufer der jüngsten Gruppen, älteste zuerst verdrängt; danach und nach einem Neustart gilt {@link #restored}. */
    private final Map<String, AccessContext> callers = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AccessContext> eldest) {
                    return size() > 256;
                }
            });

    /**
     * @param owner Auftraggeber, unter dem die Aufträge in {@link Jobs} stehen
     */
    public StatementAnalysisJobs(Jobs jobs, Discovery discovery, PageFetch fetch, StatementAnalysis analysis,
                                 AccessGuard access, String owner) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.fetch = Objects.requireNonNull(fetch, "fetch");
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.access = Objects.requireNonNull(access, "access");
        this.owner = owner;
    }

    /**
     * Schließt die Hintergrundaufträge an (Fahrplan D3): Teilbaum, Space und Quelle laufen dann als Gruppe der Art
     * {@value #KIND}. Ein Unterauftrag, den {@link BackgroundJobs#resumeInterrupted()} nach einem Neustart fortsetzt,
     * läuft nur weiter, wenn {@code restored} derselbe Eigentümer ist, der ihn startete; sonst bleibt er angehalten mit
     * der gelben Angabe {@value #RESUME_BY_HAND}.
     *
     * @param restored der Aufrufer nach einem Neustart (in der Desktop-App der App-Benutzer {@code Harvester.OWNER})
     */
    public void background(BackgroundJobs jobs, AccessContext restored) {
        this.restored = Objects.requireNonNull(restored, "restored");
        Objects.requireNonNull(jobs, "jobs").register(new BackgroundKind(KIND, this::work));
        this.background = jobs;
    }

    /** Schließt den Bestand an, aus dem {@link #missing} liest. */
    public void local(LocalPages pages) {
        this.local = Objects.requireNonNull(pages, "pages");
    }

    /** Ob der Auftrag eine Aussagenanalyse ist: der normale Auftrag oder ein Unterauftrag der Art {@value #KIND}. */
    public static boolean isAnalysis(JobStatus status) {
        return status != null && (status.type() == JobType.STATEMENT_ANALYSIS
                || status.type() == JobType.BACKGROUND && KIND.equals(status.parameter(BackgroundJobs.KIND)));
    }

    /**
     * Wie viele Seiten der KI-Analysator laut den Ereignissen eines Auftrags bewusst ausgelassen hat (ohne KI-Freigabe,
     * ohne Chat-Modell; {@value #MODEL_NOT_RELEASED}, {@value #MODEL_NO_MODEL}), für die gelbe Zahl im Reiter.
     */
    public static long omitted(List<JobEvent> events) {
        long omitted = 0;
        for (JobEvent event : events) {
            if (MODEL_NOT_RELEASED.equals(event.code()) || MODEL_NO_MODEL.equals(event.code())) {
                omitted += leadingNumber(event.message());
            }
        }
        return omitted;
    }

    /** Die Zahl am Anfang der Meldung („12 Seiten …“), wie {@code runModel} sie schreibt; sonst 0. */
    private static long leadingNumber(String message) {
        if (message == null) {
            return 0;
        }
        int end = 0;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        try {
            return end == 0 ? 0 : Long.parseLong(message.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Die Gruppe eines Unterauftrags ({@value BackgroundJobs#GROUP}), sonst leer. */
    public static Optional<String> groupOf(JobStatus status) {
        return status == null || status.type() != JobType.BACKGROUND ? Optional.empty()
                : Optional.ofNullable(status.parameter(BackgroundJobs.GROUP));
    }

    /**
     * Setzt eine Gruppe von Hand fort, mit den Rechten des Fortsetzenden; auch nach {@value #RESUME_BY_HAND}.
     *
     * @return die Gruppe, leer ohne Hintergrund oder unbekannte Gruppe
     */
    public Optional<BackgroundGroup> resume(AccessContext caller, String groupId) {
        Objects.requireNonNull(caller, "caller");
        BackgroundJobs group = background;
        if (group == null) {
            return Optional.empty();
        }
        callers.put(Objects.requireNonNull(groupId, "groupId"), caller);
        return group.resume(groupId);
    }

    /** Ob überhaupt analysiert werden kann (Analysator eingerichtet); lädt kein Modell. */
    public boolean available() {
        return analysis.available();
    }

    /**
     * Ermittelt den Umfang für den Bestätigungsdialog, höchstens {@code limit + 1} Seiten weit. Lädt keine Inhalte
     * und analysiert nichts.
     *
     * @param limit Sicherheitsgrenze; darüber endet die Zählung mit {@link Estimate#exceeded()}
     */
    public Estimate estimate(AccessContext caller, Scope scope, int limit) {
        Objects.requireNonNull(scope, "scope");
        if (limit < 1) {
            throw new IllegalArgumentException("limit muss mindestens 1 sein");
        }
        Walk walk = new Walk(caller, scope, bound(limit), () -> {
        });
        walk.run();
        return new Estimate(scope, Math.min(walk.pages.size(), limit), walk.pages.size() > limit, walk.incomplete);
    }

    /**
     * Startet den Auftrag; der Aufrufer wartet nicht. Der Umfang wird im Auftrag vollständig ermittelt, bevor die
     * erste Seite analysiert wird. Ohne {@link Options#analyzer()} gilt die Einstellung {@code statements.analyzer}
     * ({@link StatementAnalysis#selected()}); der Analysator wird beim Start festgelegt.
     *
     * <p>Eine einzelne Seite ist ein normaler Auftrag; Teilbaum, Space und Quelle sind mit angeschlossenem Hintergrund
     * eine Gruppe der Art {@value #KIND}, je Space ein Unterauftrag (Fahrplan D3).
     *
     * @return der Auftrag, bei einer Gruppe ihr erster Unterauftrag ({@link #groupOf} nennt die Gruppe)
     * @throws AnalyzerUnavailableException wenn kein Analysator eingerichtet ist
     */
    public JobStatus start(AccessContext caller, Scope scope, Options options) {
        return start(caller, scope, options, fetch);
    }

    /**
     * Wie {@link #start} für genau die gezeigte Seite (Reiter „Aussagen“, „Analysieren“): derselbe Auftrag
     * {@code STATEMENT_ANALYSIS}, er analysiert aber die gezeigte Fassung, statt die Seite erneut abzurufen; so fragt
     * ein Klick die Quelle nicht und rechnet denselben Text, den der Benutzer sieht.
     */
    public JobStatus startShown(AccessContext caller, PageSnapshot shown, Options options) {
        Objects.requireNonNull(shown, "shown");
        PageFetch pages = (who, sourceId, pageId) -> shown.key().sourceId().equals(sourceId)
                && shown.key().pageId().equals(pageId) ? shown : fetch.page(who, sourceId, pageId);
        return start(caller, new Scope.SinglePage(shown.key().sourceId(), shown.spaceKey(), shown.key().pageId()),
                options, pages);
    }

    private JobStatus start(AccessContext caller, Scope scope, Options options, PageFetch pages) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(options, "options");
        AnalyzerChoice analyzer = options.analyzer() != null ? options.analyzer() : analysis.selected();
        Options fixed = options.withAnalyzer(analyzer);
        if (!analysis.available(analyzer)) {
            throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NOT_CONFIGURED,
                    analyzer == AnalyzerChoice.MODEL ? "In dieser Laufzeit ist kein KI-Analysator eingerichtet"
                            : "In dieser Laufzeit ist kein Satzanalysator eingerichtet (nur in der Desktop-App)", null);
        }
        BackgroundJobs group = background;
        if (group != null && !(scope instanceof Scope.SinglePage)) {
            List<BackgroundPart.Draft> parts = parts(caller, scope, fixed);
            if (!parts.isEmpty()) {
                // Den Aufrufer vor dem Start ablegen: ein Unterauftrag kann schon während start() laufen.
                String groupId = BackgroundJobs.newGroupId();
                callers.put(groupId, caller);
                return group.start(groupId, KIND, title(analyzer), parts).parts().get(0);
            }
        }
        JobTask task = JobTask.blocking(context -> run(caller, scope, fixed, null, () -> 0, null, pages, context));
        if (scope instanceof Scope.SinglePage) {
            // Ein Klick auf eine Seite wartet nie hinter Hintergrund (AT, #1055).
            return jobs.submitInteractive(JobType.STATEMENT_ANALYSIS, scope.sourceId(), scope.spaceKey(),
                    scope.rootPageId(), owner, Map.of(), task);
        }
        return jobs.submit(JobType.STATEMENT_ANALYSIS, scope.sourceId(), scope.spaceKey(), scope.rootPageId(), owner,
                task);
    }

    /** Titel der Gruppe in der Auftragsliste. */
    static String title(AnalyzerChoice analyzer) {
        return analyzer == AnalyzerChoice.MODEL ? "Aussagen analysieren (KI)" : "Aussagen analysieren (MaltParser)";
    }

    /**
     * Die Unteraufträge, je Space einer: eine Quelle in alle Spaces mit {@code metadata}-Freigabe, ein Teilbaum in den
     * Space seiner Seite (unbekannt: der Unterauftrag ermittelt ihn wie bisher selbst).
     */
    private List<BackgroundPart.Draft> parts(AccessContext caller, Scope scope, Options options) {
        Map<String, String> common = new LinkedHashMap<>();
        common.put(ANALYZER, options.analyzer().value());
        common.put(REANALYZE, Boolean.toString(options.reanalyze()));
        common.put(MAX_PAGES, Integer.toString(options.maxPages()));
        if (options.simplification() != null) {
            common.put(STAGE_MODE, options.simplification().mode().value());
            common.put(STAGE_PARALLEL, Integer.toString(options.simplification().parallel()));
        }
        common.put(SINCE, analysis.now().toString());
        common.put(OWNER, caller.jobOwner());
        List<BackgroundPart.Draft> parts = new ArrayList<>();
        switch (scope) {
            case Scope.SinglePage page -> {
                Map<String, String> own = new LinkedHashMap<>(common);
                own.put(SCOPE, SCOPE_PAGE);
                own.put(ROOT, page.pageId());
                parts.add(new BackgroundPart.Draft(page.sourceId(), page.spaceKey(), own));
            }
            case Scope.Subtree subtree -> {
                Map<String, String> own = new LinkedHashMap<>(common);
                own.put(SCOPE, SCOPE_SUBTREE);
                own.put(ROOT, subtree.pageId());
                parts.add(new BackgroundPart.Draft(subtree.sourceId(), subtree.spaceKey(), own));
            }
            case Scope.Space space -> parts.add(spacePart(space.sourceId(), space.spaceKey(), common));
            case Scope.Source source -> {
                List<Partition> spaces;
                try {
                    spaces = discovery.spaces(caller, source.sourceId());
                } catch (AccessDeniedException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // Quelle gerade nicht erreichbar: ein normaler Auftrag, der wie bisher „unvollständig“ endet und
                    // sich wiederholen lässt, statt dass der Start scheitert.
                    return List.of();
                }
                for (Partition space : spaces) {
                    parts.add(spacePart(source.sourceId(), space.key(), common));
                }
            }
        }
        return parts;
    }

    private static BackgroundPart.Draft spacePart(String sourceId, String spaceKey, Map<String, String> common) {
        Map<String, String> own = new LinkedHashMap<>(common);
        own.put(SCOPE, SCOPE_SPACE);
        return new BackgroundPart.Draft(sourceId, spaceKey, own);
    }

    /**
     * Die Arbeit eines Unterauftrags, allein aus seinen Angaben: Umfang, festgelegter Analysator, Vorstufe. Beim
     * Fortsetzen überspringt der Lauf Seiten, die der Analysator schon aktuell hat (mit „erneut analysieren“: seit
     * {@value #SINCE}). Setzt {@link BackgroundJobs#resumeInterrupted()} ihn fort und der Eigentümer ist ein anderer
     * als der wiederhergestellte Aufrufer, hält er gelb an ({@value #RESUME_BY_HAND}). Ein Teil des Dienst-Planers
     * (D1) analysiert genau seine Seiten ({@link
     * com.aresstack.spaceharvester.application.background.services.AnalysisService#PAGES}), zuerst mit den Rechten
     * des Start-Aufrufers ({@value #CALLER}).
     */
    private JobTask work(BackgroundPart part) {
        String scopeName = part.parameter(SCOPE);
        String root = part.parameter(ROOT);
        List<String> listed = listed(part);
        Scope scope = listed != null ? (part.partitionKey() != null ? new Scope.Space(part.sourceId(),
                part.partitionKey()) : new Scope.Source(part.sourceId()))
                : SCOPE_PAGE.equals(scopeName) && root != null
                ? new Scope.SinglePage(part.sourceId(), part.partitionKey(), root)
                : SCOPE_SUBTREE.equals(scopeName) && root != null
                ? new Scope.Subtree(part.sourceId(), part.partitionKey(), root)
                : new Scope.Space(part.sourceId(), part.partitionKey());
        Options options = options(part);
        Instant since = part.resumed() ? instant(part.parameter(SINCE)) : null;
        AccessContext known = callers.get(part.groupId());
        if (known == null && part.parameter(CALLER) != null) {
            known = callers.get(part.parameter(CALLER));
        }
        AccessContext fallback = restored;
        AccessContext starter = known;
        return JobTask.blocking(context -> {
            // Nie mit fremden Rechten: ohne bekannten Aufrufer nur, wenn der wiederhergestellte der Eigentümer ist.
            // Von Hand mit eigenen Rechten fortsetzen geht über resume(caller, groupId).
            if (starter == null && (fallback == null || !fallback.jobOwner().equals(part.parameter(OWNER)))) {
                context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, RESUME_BY_HAND,
                        part.automatic() ? "Nach dem Neustart nicht von selbst fortgesetzt, weil ein anderer Aufrufer "
                                + "ihn gestartet hat; bitte von Hand fortsetzen"
                                : "Ein anderer Aufrufer hat ihn gestartet; bitte mit den eigenen Rechten fortsetzen"));
                context.pause(PauseReason.RESUME_BY_HAND);
                return;
            }
            AccessContext caller = starter != null ? starter : fallback;
            if (caller == null) {
                throw new IllegalStateException("Kein Aufrufer für den Hintergrund angeschlossen");
            }
            if (!analysis.available(options.analyzer())) {
                throw new JobFailedException(ANALYZER_UNAVAILABLE, options.analyzer() == AnalyzerChoice.MODEL
                        ? "In dieser Laufzeit ist kein KI-Analysator eingerichtet"
                        : "In dieser Laufzeit ist kein Satzanalysator eingerichtet");
            }
            run(caller, scope, options, since, () -> siblings(part), listed, fetch, context);
        });
    }

    /**
     * Die Seiten eines Teils aus dem Dienst-Planer ({@link
     * com.aresstack.spaceharvester.application.background.services.AnalysisService#PAGES}) oder {@code null} ohne.
     */
    private static List<String> listed(BackgroundPart part) {
        String value = part.parameter(
                com.aresstack.spaceharvester.application.background.services.AnalysisService.PAGES);
        return value == null ? null
                : java.util.Arrays.stream(value.split("\n")).filter(id -> !id.isBlank()).distinct().toList();
    }

    /**
     * Die schon ermittelten Seiten der übrigen Teile der Gruppe, aus dem Auftragsspeicher: Eine Grenze gilt für die
     * Summe aller Spaces wie beim früheren einzelnen Auftrag, ohne dass ein Teil fremde Spaces abgeht.
     */
    private long siblings(BackgroundPart part) {
        BackgroundJobs group = background;
        if (group == null) {
            return 0;
        }
        return group.group(part.groupId()).map(found -> found.parts().stream()
                .filter(status -> !status.id().equals(part.jobId()))
                .mapToLong(status -> Math.max(status.progress().discovered(), counted(status.id()))).sum())
                .orElse(0L);
    }

    /** Die zuletzt gemeldete Zahl {@value #SCOPE_COUNTED} eines Teils; 0 ohne oder wenn er gelöscht ist. */
    private long counted(String jobId) {
        List<JobEvent> events;
        try {
            events = jobs.events(jobId);
        } catch (com.aresstack.spaceharvester.application.jobs.UnknownJobException e) {
            return 0;
        }
        long last = 0;
        for (JobEvent event : events) {
            if (SCOPE_COUNTED.equals(event.code())) {
                last = leadingNumber(event.message());
            }
        }
        return last;
    }

    /** Die Optionen aus den Angaben; ein fehlender Analysator ist der MaltParser, nie die aktuelle Einstellung. */
    static Options options(BackgroundPart part) {
        StatementAnalysis.SimplificationRequest stage = null;
        String mode = part.parameter(STAGE_MODE);
        if (mode != null) {
            stage = new StatementAnalysis.SimplificationRequest(
                    com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode.parse(mode),
                    Math.max(1, number(part.parameter(STAGE_PARALLEL), 1)));
        }
        return new Options(Boolean.parseBoolean(part.parameter(REANALYZE)),
                Math.max(0, number(part.parameter(MAX_PAGES), 0)), stage, AnalyzerChoice.parse(part.parameter(ANALYZER)));
    }

    private static int number(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Die Angaben der Teile, die der Dienst-Planer (D1) für diese Art anlegt ({@link
     * com.aresstack.spaceharvester.application.background.services.AnalysisService#parameters()}): der eingestellte
     * Analysator, festgelegt für den ganzen Lauf, Eigentümer und Startzeit wie bei {@link #start}; ohne „erneut
     * analysieren“ und ohne Grenze, denn die Seiten zählt der Planer. Die Seiten selbst stehen in
     * {@link com.aresstack.spaceharvester.application.background.services.AnalysisService#PAGES}.
     */
    public Map<String, String> plannerParameters(AccessContext caller) {
        Objects.requireNonNull(caller, "caller");
        String token = java.util.UUID.randomUUID().toString();
        callers.put(token, caller);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(ANALYZER, analysis.selected().value());
        parameters.put(REANALYZE, Boolean.toString(false));
        parameters.put(MAX_PAGES, Integer.toString(0));
        parameters.put(SINCE, analysis.now().toString());
        parameters.put(OWNER, caller.jobOwner());
        parameters.put(CALLER, token);
        return parameters;
    }

    /**
     * Seiten einer Menge, die jetzt ohne aktuelle Analyse des eingestellten Analysators ({@code statements.analyzer})
     * sind und die der Dienst gleich analysieren kann (für den Planer, Fahrplan D1): lokal in aktueller Fassung und
     * ohne Analyse, mit geändertem Text oder nur mit einer Analyse des anderen Analysators. Seiten, die erst geladen
     * werden müssen (nur in der Quelle bekannt oder im Bestand veraltet, {@link HoldingState#OUTDATED}), zählt
     * {@link Missing#toLoad()}; sie bleiben ausgelassen, bis eine Ladestufe sie holt. Seiten ohne Leserecht und ohne
     * Lesetext fallen still heraus, mit dem KI-Analysator auch Seiten ohne KI-Freigabe (sie bekäme der Teil ohnehin
     * nicht; W5). Nur aus Bestand und Cache, keine Quellenabrufe. {@link #coverage} zählt nach derselben Regel.
     *
     * <p>Der Bestand kennt nur Abgeglichenes: Ein nicht abgeglichener Space ist hier leer, der Auftrag findet ihn über
     * die Navigation trotzdem vollständig.
     *
     * @throws IllegalStateException ohne angeschlossenen Bestand ({@link #local})
     */
    public Missing missing(AccessContext caller, PageSet pages) {
        Map<String, Set<String>> missing = new LinkedHashMap<>();
        int toLoad = 0;
        for (Map.Entry<PageKey, Standing> entry : classify(caller, pages, analysis.selected()).entrySet()) {
            PageKey key = entry.getKey();
            switch (entry.getValue()) {
                case TO_LOAD -> toLoad++;
                case OPEN -> missing.computeIfAbsent(key.sourceId(), ignored -> new LinkedHashSet<>())
                        .add(key.pageId());
                default -> {
                    // aktuell, ohne Lesetext oder ohne KI-Freigabe: nichts zu tun
                }
            }
        }
        return new Missing(PageSet.of(missing), toLoad);
    }

    /**
     * Die Abdeckung einer Menge durch Analysen eines Analysators (W5, Abschlussteil „Wissensgraph“), nach derselben
     * Regel wie {@link #missing}: aktuell, ohne aktuelle Analyse, erst zu laden, ausgelassen ohne Lesetext oder ohne
     * KI-Freigabe. Gesperrte Seiten und Seiten ohne Leserecht zählen nirgends. Nur aus Bestand und Cache, keine
     * Quellenabrufe; rechnet nie.
     *
     * @param choice der Analysator, dessen Ablage zählt, etwa der beim Start festgelegte
     * @throws IllegalStateException ohne angeschlossenen Bestand ({@link #local})
     */
    public Coverage coverage(AccessContext caller, PageSet pages, AnalyzerChoice choice) {
        int current = 0;
        int open = 0;
        int toLoad = 0;
        int noText = 0;
        int notReleased = 0;
        for (Standing standing : classify(caller, pages, Objects.requireNonNull(choice, "choice")).values()) {
            switch (standing) {
                case CURRENT -> current++;
                case OPEN -> open++;
                case TO_LOAD -> toLoad++;
                case NO_TEXT -> noText++;
                case NOT_RELEASED -> notReleased++;
            }
        }
        return new Coverage(current, open, toLoad, noText, notReleased);
    }

    /** Der eingestellte Analysator ({@code statements.analyzer}), aus dessen Ablage Graph und Stand lesen. */
    public AnalyzerChoice selected() {
        return analysis.selected();
    }

    /** Stand einer Seite für {@link #missing} und {@link #coverage}; gesperrte und unlesbare fehlen ganz. */
    private enum Standing { CURRENT, OPEN, TO_LOAD, NO_TEXT, NOT_RELEASED }

    /**
     * Die eine Regel hinter {@link #missing} und {@link #coverage}, damit Planer, Stufe und Abschlusszählung nicht
     * auseinanderlaufen.
     */
    private Map<PageKey, Standing> classify(AccessContext caller, PageSet pages, AnalyzerChoice choice) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(pages, "pages");
        LocalPages stock = local;
        if (stock == null) {
            throw new IllegalStateException("Kein Bestand angeschlossen");
        }
        Map<PageKey, Standing> standings = new LinkedHashMap<>();
        for (Map.Entry<PageKey, Holdings.Located> entry : stock.locate(caller, pages).entrySet()) {
            PageKey key = entry.getKey();
            HoldingState state = entry.getValue().state();
            if (state == HoldingState.LOCKED || !access.permits(caller,
                    ResourceScope.page(key.sourceId(), entry.getValue().spaceKey(), key.pageId()), AccessKind.CONTENT)) {
                continue; // gesperrt oder ohne Leserecht: still heraus, auch nicht unter „zu laden“
            }
            if (state != HoldingState.LOCAL) {
                standings.put(key, Standing.TO_LOAD);
                continue;
            }
            Optional<PageSnapshot> snapshot = stock.stored(key);
            if (snapshot.isEmpty()) {
                standings.put(key, Standing.TO_LOAD);
                continue;
            }
            PageStatements read;
            try {
                read = analysis.read(caller, snapshot.get(), choice);
            } catch (AccessDeniedException e) {
                continue; // ohne Leserecht: still heraus
            }
            if (read.state() == PageStatements.State.CURRENT) {
                standings.put(key, Standing.CURRENT);
            } else if (read.state() == PageStatements.State.MISSING && PageStatements.NO_TEXT.equals(read.reason())) {
                standings.put(key, Standing.NO_TEXT);
            } else if (choice == AnalyzerChoice.MODEL && analysis.modelUnavailable(key)
                    .filter(reason -> reason == AnalyzerUnavailableException.Reason.NOT_RELEASED).isPresent()) {
                standings.put(key, Standing.NOT_RELEASED); // wie im Lauf: bewusst ausgelassen, kein Fehler
            } else {
                standings.put(key, Standing.OPEN);
            }
        }
        return standings;
    }

    /**
     * Ergebnis von {@link #missing}.
     *
     * @param pages  Seiten ohne aktuelle Analyse, die der Dienst jetzt analysieren kann
     * @param toLoad so viele Seiten müssten erst geladen werden (nur in der Quelle bekannt oder veraltet); ohne Titel
     */
    public record Missing(PageSet pages, int toLoad) {

        public Missing {
            Objects.requireNonNull(pages, "pages");
        }
    }

    /**
     * Ergebnis von {@link #coverage}, nur Zahlen.
     *
     * @param current     Seiten mit aktueller Analyse
     * @param stale       Seiten ohne aktuelle Analyse (veraltet oder noch nie analysiert), die der Dienst rechnen könnte
     * @param toLoad      Seiten, die erst geladen werden müssten
     * @param noText      Seiten ohne Lesetext, bewusst ausgelassen
     * @param notReleased Seiten ohne KI-Freigabe beim KI-Analysator, bewusst ausgelassen
     */
    public record Coverage(int current, int stale, int toLoad, int noText, int notReleased) {

        /** Alle gezählten Seiten. */
        public int pages() {
            return current + stale + toLoad + noText + notReleased;
        }

        /** Bewusst ausgelassene Seiten (ohne Lesetext oder ohne KI-Freigabe). */
        public int skipped() {
            return noText + notReleased;
        }

        /** Ob jede gezählte Seite eine aktuelle Analyse hat. */
        public boolean complete() {
            return current == pages();
        }
    }

    /** Bestand und Cache für {@link #missing}, ohne Quellenabrufe. */
    public interface LocalPages {

        /** Space und Bestand je Seite, mit den Rechten des Aufrufers ({@link Holdings#locate}). */
        Map<PageKey, Holdings.Located> locate(AccessContext caller, PageSet pages);

        /** Der gespeicherte Stand der Seite aus dem Cache. */
        Optional<PageSnapshot> stored(PageKey key);
    }

    /**
     * Ob die Seite übersprungen wird: ohne „erneut analysieren“, wenn der Analysator sie aktuell hat; mit „erneut
     * analysieren“ nur beim Fortsetzen ({@code since} gesetzt), wenn er sie seit dem Start aktuell gerechnet hat.
     */
    private boolean done(AccessContext caller, PageSnapshot snapshot, Options options, Instant since) {
        if (options.reanalyze() && since == null) {
            return false;
        }
        boolean current = options.analyzer() == AnalyzerChoice.MODEL
                ? analysis.currentWith(caller, snapshot, AnalyzerChoice.MODEL)
                : analysis.current(caller, snapshot, options.simplification());
        if (!current || !options.reanalyze()) {
            return current;
        }
        PageStatementAnalysis stored = analysis.read(caller, snapshot, options.analyzer()).analysis();
        return stored != null && stored.analyzedAt() != null && !stored.analyzedAt().isBefore(since);
    }

    /**
     * @param siblings    die schon ermittelten Seiten der übrigen Teile derselben Gruppe (Auftragsspeicher): Eine
     *                    Grenze gilt für die Summe aller Spaces; wer sie mit seinen Seiten überschreitet, scheitert vor
     *                    seiner ersten Analyse, fertige Teile behalten ihre Analysen
     * @param listed      genau diese Seiten des Space (Teil des Dienst-Planers) statt des Umfangs, oder {@code null};
     *                    jede einzeln mit den Rechten des Aufrufers geprüft
     * @param pages       woher die Seiten kommen; für die gezeigte Seite ihre Fassung ({@link #startShown})
     */
    private void run(AccessContext caller, Scope scope, Options options, Instant since,
                     java.util.function.LongSupplier siblings, List<String> listed, PageFetch pages,
                     JobContext context) {
        context.phase(COMPONENT, PHASE_DISCOVERY);
        Walk walk = new Walk(caller, scope, options.maxPages() == 0 ? Integer.MAX_VALUE : bound(options.maxPages()),
                context::checkpoint);
        walk.onPage = () -> context.discovered(1);
        if (listed != null) {
            walk.listed(listed);
        } else {
            walk.run();
        }
        long total = walk.pages.size();
        if (options.maxPages() > 0) {
            context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, SCOPE_COUNTED,
                    total + " Seiten im Umfang ermittelt", null, null, null));
            if (total <= options.maxPages()) {
                total += siblings.getAsLong();
            }
        }
        if (options.maxPages() > 0 && total > options.maxPages()) {
            throw new JobFailedException(SCOPE_TOO_LARGE, "Der Umfang hat mehr als " + options.maxPages()
                    + " Seiten; es wurde nichts analysiert. Bitte ausdrücklich mit höherer Grenze bestätigen.");
        }
        context.phase(COMPONENT, PHASE_ANALYSIS);
        if (options.analyzer() == AnalyzerChoice.MODEL) {
            runModel(caller, walk, options, since, pages, context);
            return;
        }
        long current = 0;
        long withoutText = 0;
        StatementAnalysis.SimplificationRequest vorstufe = options.simplification();
        long[] costs = new long[10];
        for (Target target : walk.pages.values()) {
            context.checkpoint();
            try {
                PageSnapshot snapshot = pages.page(caller, target.sourceId(), target.pageId());
                // Aktuell = gleicher Text, Analysator und (mit Vorstufe) Stand der Vorstufe; auch nach Fortsetzen
                if (done(caller, snapshot, options, since)) {
                    current++;
                    context.processed(1);
                    continue;
                }
                if (vorstufe != null) {
                    // Diese Seite ohne Vorstufe: ohne KI-Freigabe und ohne Modell getrennt gezählt
                    String unavailable = analysis.simplificationUnavailable(snapshot.key()).orElse(null);
                    if ("NOT_RELEASED".equals(unavailable)) {
                        costs[7]++;
                    } else if (unavailable != null) {
                        costs[9]++;
                    }
                }
                if (analysis.analyze(caller, snapshot, context::cancelRequested, vorstufe, run -> {
                    costs[0]++;
                    costs[1] += run.chosen();
                    costs[2] += run.modelCalls();
                    costs[3] += run.fromStore();
                    costs[4] += run.rejected();
                    costs[5] += run.added();
                    costs[6] += run.incomplete();
                    costs[8] += run.lost();
                }).analysis() == null) {
                    withoutText++; // ohne Lesetext: nichts zu analysieren
                } else {
                    context.written(1);
                }
            } catch (CancellationException e) {
                context.checkpoint();
                throw e;
            } catch (AnalyzerUnavailableException e) {
                // Der Grund als eigene Angabe, damit der Reiter etwa „Modell fehlt“ von „nicht eingerichtet“ trennt.
                context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                        ANALYZER_REASON + e.reason().name(), "Analysator nicht bereit", null, null, null));
                throw new JobFailedException(ANALYZER_UNAVAILABLE, e.getMessage(), e);
            } catch (RuntimeException e) {
                if (context.cancelRequested()) {
                    context.checkpoint();
                }
                context.failed(1);
                context.record(JobEvent.Draft.warning(e instanceof AccessDeniedException ? JobEvent.Category.ACCESS
                        : JobEvent.Category.SOURCE, COMPONENT, "PAGE_FAILED",
                        "Eine Seite wurde nicht analysiert (" + e.getClass().getSimpleName() + ")"));
            }
            context.processed(1);
        }
        context.record(JobEvent.Draft.info(COMPONENT, walk.pages.size() + " Seiten ermittelt, " + current
                + " aktuell übersprungen, " + withoutText + " ohne Lesetext"));
        if (vorstufe != null) {
            // ADR 0032, FL3: Kosten und Zähler der Vorstufe, nur Zahlen
            context.record(JobEvent.Draft.info(COMPONENT, "Vorstufe „" + (vorstufe.mode()
                    == com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode.ALWAYS
                    ? "immer" : "nur wo nötig") + "“: " + costs[0] + " Seiten, " + costs[1] + " Sätze gewählt, "
                    + costs[2] + " Abschnitte mit Modellaufruf, " + costs[3] + " Sätze aus der Ablage, " + costs[4]
                    + " Antwortzeilen verworfen, " + costs[5] + " Sätze mit ergänzten Wörtern verworfen, " + costs[6]
                    + " unvollständig, " + costs[8] + " wegen verlorener Verneinung oder Modalverb verworfen; " + costs[7]
                    + " Seiten ohne KI-Freigabe und " + costs[9] + " Seiten ohne Modell ohne Vorstufe"));
            // Dieselben beiden Zahlen einzeln und mit Code, damit die Oberfläche sie am Auftrag zeigen kann
            context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                    STAGE_NOT_RELEASED, costs[7] + " Seiten ohne KI-Freigabe, ohne Vorstufe analysiert", null, null,
                    null));
            if (costs[9] > 0) {
                context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                        STAGE_NO_MODEL, costs[9] + " Seiten ohne Vorstufe analysiert, weil kein Modell eingestellt "
                        + "ist", null, null, null));
            }
        }
        if (walk.incomplete) {
            throw new JobFailedException(INCOMPLETE, "Der Umfang konnte nicht vollständig ermittelt werden; die "
                    + "gefundenen Seiten wurden verarbeitet, weitere fehlen.");
        }
    }

    /**
     * Der Lauf mit dem KI-Analysator (Fahrplan Paket A2): nur Seiten mit KI-Freigabe gehen an den KI-Dienst. Ohne
     * Freigabe oder ohne eingestelltes Modell wird die Seite bewusst ausgelassen und gezählt (gelb), nie als Fehler;
     * ein Fehler des Dienstes zählt die Seite als gescheitert (rot), die übrigen laufen weiter.
     */
    private void runModel(AccessContext caller, Walk walk, Options options, Instant since, PageFetch pages,
                          JobContext context) {
        long current = 0;
        long withoutText = 0;
        long notReleased = 0;
        long noModel = 0;
        // Seiten, Sätze, Modellaufrufe, verworfene Antwortzeilen, verworfene Aussagen, Sätze ohne Aussage
        long[] costs = new long[6];
        for (Target target : walk.pages.values()) {
            context.checkpoint();
            try {
                PageSnapshot snapshot = pages.page(caller, target.sourceId(), target.pageId());
                if (done(caller, snapshot, options, since)) {
                    current++;
                    context.processed(1);
                    continue;
                }
                java.util.Optional<AnalyzerUnavailableException.Reason> unavailable =
                        analysis.modelUnavailable(snapshot.key());
                if (unavailable.isPresent()) {
                    if (unavailable.get() == AnalyzerUnavailableException.Reason.NOT_RELEASED) {
                        notReleased++;
                    } else {
                        noModel++;
                    }
                    context.processed(1);
                    continue;
                }
                if (analysis.analyzeWith(caller, snapshot, context::cancelRequested, AnalyzerChoice.MODEL, run -> {
                    costs[0]++;
                    costs[1] += run.sentences();
                    costs[2] += run.modelCalls();
                    costs[3] += run.rejectedLines();
                    costs[4] += run.rejectedStatements();
                    costs[5] += run.noStatement();
                }).analysis() == null) {
                    withoutText++;
                } else {
                    context.written(1);
                }
            } catch (CancellationException e) {
                context.checkpoint();
                throw e;
            } catch (AnalyzerUnavailableException e) {
                // bewusst ausgelassen: Freigabe während des Laufs entzogen oder Modell inzwischen entfernt
                if (e.reason() == AnalyzerUnavailableException.Reason.NOT_RELEASED) {
                    notReleased++;
                } else {
                    noModel++;
                }
            } catch (RuntimeException e) {
                if (context.cancelRequested()) {
                    context.checkpoint();
                }
                context.failed(1);
                context.record(JobEvent.Draft.warning(e instanceof AccessDeniedException ? JobEvent.Category.ACCESS
                        : JobEvent.Category.SOURCE, COMPONENT, "PAGE_FAILED",
                        "Eine Seite wurde nicht analysiert (" + e.getClass().getSimpleName() + ")"));
            }
            context.processed(1);
        }
        context.record(JobEvent.Draft.info(COMPONENT, walk.pages.size() + " Seiten ermittelt, " + current
                + " aktuell übersprungen, " + withoutText + " ohne Lesetext"));
        context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, MODEL_COSTS,
                "Sprachmodell: " + costs[0] + " Seiten, " + costs[1] + " Sätze, " + costs[2] + " Modellaufrufe, "
                        + costs[3] + " Zeilen verworfen, " + costs[4] + " Aussagen im Wortabgleich verworfen, "
                        + costs[5] + " Sätze ohne eindeutige Aussage", null, null, null));
        if (notReleased > 0) {
            context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                    MODEL_NOT_RELEASED, notReleased + " Seiten ohne KI-Freigabe bewusst ausgelassen", null, null,
                    null));
        }
        if (noModel > 0) {
            context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                    MODEL_NO_MODEL, noModel + " Seiten bewusst ausgelassen, weil kein Chat-Modell eingestellt ist",
                    null, null, null));
        }
        if (walk.incomplete) {
            throw new JobFailedException(INCOMPLETE, "Der Umfang konnte nicht vollständig ermittelt werden; die "
                    + "gefundenen Seiten wurden verarbeitet, weitere fehlen.");
        }
    }

    /** Ob der Prüfschritt laufen kann: eingerichtet und Chat-Modell eingestellt; ruft kein Modell. */
    public boolean checkAvailable() {
        return analysis.checkAvailable();
    }

    /**
     * Startet den Prüfauftrag (ADR 0032, FL4): Das Chat-Modell beurteilt die Aussagen der gespeicherten, aktuellen
     * Analysen im Umfang; der Aufrufer wartet nicht. Nur auf ausdrücklichen Auftrag, nie als Folge eines Abgleichs oder
     * einer Analyse. Seiten ohne KI-Freigabe gehen nicht an das Modell, Seiten ohne aktuelle Analyse werden nicht neu
     * analysiert; beide werden gezählt. Jede Seite wird für sich ersetzt, ein Abbruch behält die fertigen Seiten, und
     * schon gelesene Urteile bleiben in der Ablage, sodass ein neuer Lauf nur fehlende fragt.
     *
     * <p>Der Auftrag steht als eigene Art {@link JobType#STATEMENT_CHECK} in der Liste (Phase {@value #PHASE_CHECK});
     * {@code written} zählt geprüfte Seiten. Ereignisse nennen nur Zahlen.
     *
     * @throws CheckUnavailableException ohne eingestelltes Modell
     */
    public JobStatus startCheck(AccessContext caller, Scope scope, CheckOptions options) {
        return startCheck(caller, scope, options, fetch);
    }

    /**
     * Wie {@link #startCheck} für genau die gezeigte Seite (Reiter „Aussagen“, „Prüfen“): derselbe Auftrag
     * {@code STATEMENT_CHECK} als Klick, er prüft aber die gezeigte Fassung, statt die Seite erneut abzurufen.
     *
     * @throws CheckUnavailableException ohne eingestelltes Modell
     */
    public JobStatus startCheckShown(AccessContext caller, PageSnapshot shown, CheckOptions options) {
        Objects.requireNonNull(shown, "shown");
        PageFetch pages = (who, sourceId, pageId) -> shown.key().sourceId().equals(sourceId)
                && shown.key().pageId().equals(pageId) ? shown : fetch.page(who, sourceId, pageId);
        return startCheck(caller, new Scope.SinglePage(shown.key().sourceId(), shown.spaceKey(),
                shown.key().pageId()), options, pages);
    }

    private JobStatus startCheck(AccessContext caller, Scope scope, CheckOptions options, PageFetch pages) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(options, "options");
        if (!analysis.checkAvailable()) {
            throw new CheckUnavailableException(CheckUnavailableException.Reason.NO_MODEL);
        }
        JobTask task = JobTask.blocking(context -> runCheck(caller, scope, options, pages, context));
        if (scope instanceof Scope.SinglePage) {
            // „Prüfen“ einer Seite ist ein Klick wie „Analysieren“ (AT, #1055).
            return jobs.submitInteractive(JobType.STATEMENT_CHECK, scope.sourceId(), scope.spaceKey(),
                    scope.rootPageId(), owner, Map.of(), task);
        }
        return jobs.submit(JobType.STATEMENT_CHECK, scope.sourceId(), scope.spaceKey(), scope.rootPageId(), owner,
                task);
    }

    private void runCheck(AccessContext caller, Scope scope, CheckOptions options, PageFetch pages,
                          JobContext context) {
        context.phase(COMPONENT, PHASE_DISCOVERY);
        Walk walk = new Walk(caller, scope, options.maxPages() == 0 ? Integer.MAX_VALUE : bound(options.maxPages()),
                context::checkpoint);
        walk.onPage = () -> context.discovered(1);
        walk.run();
        if (options.maxPages() > 0 && walk.pages.size() > options.maxPages()) {
            throw new JobFailedException(SCOPE_TOO_LARGE, "Der Umfang hat mehr als " + options.maxPages()
                    + " Seiten; es wurde nichts geprüft. Bitte ausdrücklich mit höherer Grenze bestätigen.");
        }
        context.phase(COMPONENT, PHASE_CHECK);
        StatementAnalysis.CheckRequest request = new StatementAnalysis.CheckRequest(options.scope(),
                options.parallel());
        long notReleased = 0;
        long notCurrent = 0;
        // Aussagen, Modellaufrufe, aus der Ablage, verworfene Zeilen, widersprüchlich, Korrekturen im Wortabgleich
        // verworfen, STIMMT, UNVOLLSTAENDIG, FALSCH, ohne Urteil, übernommene Korrekturen, als Aussage verworfene
        // Korrekturen, Vorstufen-Aussagen ohne Abschnitt
        long[] costs = new long[13];
        for (Target target : walk.pages.values()) {
            context.checkpoint();
            try {
                PageSnapshot snapshot = pages.page(caller, target.sourceId(), target.pageId());
                java.util.Optional<CheckUnavailableException.Reason> unavailable =
                        analysis.checkUnavailable(snapshot.key());
                if (unavailable.isPresent()) {
                    if (unavailable.get() == CheckUnavailableException.Reason.NO_MODEL) {
                        throw new JobFailedException(CHECK_UNAVAILABLE, "Für den Prüfschritt ist kein Chat-Modell "
                                + "eingestellt; geprüfte Seiten bleiben geprüft.");
                    }
                    notReleased++;
                    context.processed(1);
                    continue;
                }
                analysis.check(caller, snapshot, context::cancelRequested, request, run -> {
                    StatementChecks.Costs c = run.run();
                    long[] values = {c.statements(), c.modelCalls(), c.fromStore(), c.rejected(), c.conflicting(),
                            c.correctionsRejected(), c.stimmt(), c.unvollstaendig(), c.falsch(), c.unanswered(),
                            run.corrections(), run.rejectedCorrections(), run.withoutSection()};
                    for (int i = 0; i < values.length; i++) {
                        costs[i] += values[i];
                    }
                });
                context.written(1);
            } catch (CancellationException e) {
                context.checkpoint();
                throw e;
            } catch (CheckUnavailableException e) {
                if (e.reason() == CheckUnavailableException.Reason.NO_MODEL) {
                    throw new JobFailedException(CHECK_UNAVAILABLE, "Für den Prüfschritt ist kein Chat-Modell "
                            + "eingestellt; geprüfte Seiten bleiben geprüft.", e);
                }
                if (e.reason() == CheckUnavailableException.Reason.NOT_RELEASED) {
                    notReleased++; // Freigabe während des Laufs entzogen: nichts geschrieben
                } else {
                    notCurrent++;
                }
            } catch (JobFailedException e) {
                throw e;
            } catch (RuntimeException e) {
                if (context.cancelRequested()) {
                    context.checkpoint();
                }
                context.failed(1);
                context.record(JobEvent.Draft.warning(e instanceof AccessDeniedException ? JobEvent.Category.ACCESS
                        : JobEvent.Category.SOURCE, COMPONENT, "PAGE_FAILED",
                        "Eine Seite wurde nicht geprüft (" + e.getClass().getSimpleName() + ")"));
            }
            context.processed(1);
        }
        context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, CHECK_COSTS,
                "Prüfschritt „" + (options.scope() == CheckScope.ALL ? "alle Aussagen" : "unsichere und Vorstufe")
                        + "“: " + walk.pages.size() + " Seiten ermittelt, " + notCurrent + " ohne aktuelle Analyse; "
                        + costs[0] + " Aussagen geprüft, " + costs[1] + " Sätze mit Modellaufruf, " + costs[2]
                        + " Urteile aus der Ablage; " + costs[6] + " STIMMT, " + costs[7] + " UNVOLLSTAENDIG, "
                        + costs[8] + " FALSCH, " + costs[9] + " ohne gültiges Urteil (werden im nächsten Lauf erneut "
                        + "gefragt); " + costs[3] + " Antwortzeilen verworfen, " + costs[4] + " widersprüchlich; "
                        + costs[10] + " Korrekturen übernommen, " + (costs[5] + costs[11])
                        + " Korrekturen verworfen; " + costs[12] + " Aussagen der Vorstufe ohne Abschnitt ungeprüft "
                        + "(Seite neu analysieren)", null, null, null));
        context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                CHECK_NOT_RELEASED, notReleased + " Seiten ohne KI-Freigabe nicht geprüft", null, null, null));
        if (notCurrent > 0) {
            context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                    CHECK_NOT_CURRENT, notCurrent + " Seiten ohne aktuelle Analyse nicht geprüft", null, null, null));
        }
        if (walk.incomplete) {
            throw new JobFailedException(INCOMPLETE, "Der Umfang konnte nicht vollständig ermittelt werden; die "
                    + "gefundenen Seiten wurden geprüft, weitere fehlen.");
        }
    }

    /** Grenze für die Ermittlung: eine Seite über der Sicherheitsgrenze, ohne Überlauf. */
    private static int bound(int limit) {
        return limit >= Integer.MAX_VALUE - 1 ? Integer.MAX_VALUE : limit + 1;
    }

    /** Ermittelt die lesbaren Seiten eines Umfangs in Baumreihenfolge, ohne Doppelte, mit allen Cursorn. */
    private final class Walk {

        private final AccessContext caller;
        private final Scope scope;
        private final int bound;
        private final Runnable checkpoint;
        private final Map<PageKey, Target> pages = new LinkedHashMap<>();
        private final Set<PageKey> seen = new HashSet<>();
        private Runnable onPage = () -> {
        };
        private boolean incomplete;

        Walk(AccessContext caller, Scope scope, int bound, Runnable checkpoint) {
            this.caller = caller;
            this.scope = scope;
            this.bound = bound;
            this.checkpoint = checkpoint;
        }

        void run() {
            switch (scope) {
                case Scope.SinglePage page -> root(page.sourceId(), page.spaceKey(), page.pageId());
                case Scope.Subtree subtree -> {
                    String spaceKey = root(subtree.sourceId(), subtree.spaceKey(), subtree.pageId());
                    below(subtree.sourceId(), spaceKey, subtree.pageId());
                }
                case Scope.Space space -> below(space.sourceId(), space.spaceKey(), null);
                case Scope.Source source -> {
                    List<Partition> spaces;
                    try {
                        spaces = discovery.spaces(caller, source.sourceId());
                    } catch (AccessDeniedException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        incomplete = true;
                        return;
                    }
                    for (Partition space : spaces) {
                        if (full()) {
                            return;
                        }
                        below(source.sourceId(), space.key(), null);
                    }
                }
            }
        }

        /**
         * Merkt die Ausgangsseite. Ohne bekannten Space ermittelt ihn zuerst die geschützte Navigation
         * ({@code metadata}), damit eine Freigabe für genau diesen Space greift; die Inhaltsprüfung bleibt danach
         * dieselbe. Ist die Seite gesperrt, bleibt sie ungenannt; scheitert nur die Ermittlung und schließt die strenge
         * Prüfung die Seite deshalb aus, gilt der Auftrag als unvollständig statt still gekürzt.
         *
         * @return der Space der Seite, sofern bekannt
         */
        private String root(String sourceId, String spaceKey, String pageId) {
            boolean lookupFailed = false;
            if (spaceKey == null) {
                checkpoint.run();
                try {
                    spaceKey = discovery.spaceKey(caller, sourceId, pageId);
                } catch (AccessDeniedException e) {
                    spaceKey = null; // ohne Freigabe: weder zählen noch melden
                } catch (RuntimeException e) {
                    lookupFailed = true;
                }
            }
            add(sourceId, spaceKey, pageId);
            if (lookupFailed && !pages.containsKey(new PageKey(sourceId, pageId))) {
                incomplete = true;
            }
            return spaceKey;
        }

        /** Alle Nachfahren von {@code parent} (oder alle Seiten des Space ab den Wurzelseiten), Breite zuerst. */
        private void below(String sourceId, String spaceKey, String parent) {
            Deque<String> open = new ArrayDeque<>();
            if (!children(sourceId, spaceKey, parent, open) && parent == null) {
                return;
            }
            while (!open.isEmpty() && !full()) {
                children(sourceId, spaceKey, open.removeFirst(), open);
            }
        }

        /**
         * Alle Stapel der Kinder von {@code parentId} ({@code null} = Wurzelseiten des Space); neue Seiten mit
         * möglichen Kindern kommen nach {@code open}.
         *
         * @return {@code false}, wenn der Knoten nicht freigegeben ist
         */
        private boolean children(String sourceId, String spaceKey, String parentId, Deque<String> open) {
            String cursor = null;
            do {
                checkpoint.run();
                PageNavigation.Children batch;
                try {
                    batch = discovery.children(caller, sourceId, spaceKey, parentId, cursor);
                } catch (AccessDeniedException e) {
                    return false; // ohne Freigabe: weder zählen noch melden
                } catch (RuntimeException e) {
                    incomplete = true;
                    return true;
                }
                for (PageNode node : batch.pages()) {
                    if (full()) {
                        return true;
                    }
                    if (add(sourceId, batch.spaceKey(), node.pageId())
                            && !Boolean.FALSE.equals(node.hasChildren())) {
                        open.addLast(node.pageId());
                    }
                }
                cursor = batch.nextCursor();
            } while (cursor != null && !full());
            return true;
        }

        /**
         * Merkt eine Seite; zählt sie nur, wenn der Aufrufer ihren Inhalt lesen darf.
         *
         * @return {@code true}, wenn die Seite neu ist (dann werden ihre Kinder ermittelt)
         */
        private boolean add(String sourceId, String spaceKey, String pageId) {
            PageKey key = new PageKey(sourceId, pageId);
            if (!seen.add(key)) {
                return false; // Zyklus oder doppelte Seite
            }
            // Metadaten sichtbar, Inhalt nicht: Nachfahren können lesbar sein, die Seite selbst zählt nicht. Ohne
            // bekannten Space prüft die Richtlinie streng (alle Spaces freigegeben oder Prüfung an der Seite selbst).
            if (access.permits(caller, ResourceScope.page(sourceId, spaceKey, pageId), AccessKind.CONTENT)) {
                pages.put(key, new Target(sourceId, pageId));
                onPage.run();
            }
            return true;
        }

        private boolean full() {
            return pages.size() >= bound;
        }

        /** Genau diese Seiten im Space des Umfangs, ohne Navigation; jede nur mit Leserecht des Aufrufers. */
        void listed(List<String> pageIds) {
            for (String pageId : pageIds) {
                if (full()) {
                    return;
                }
                checkpoint.run();
                add(scope.sourceId(), scope.spaceKey(), pageId);
            }
        }
    }

    private record Target(String sourceId, String pageId) {
    }

    /** Umfang eines Auftrags. */
    public sealed interface Scope {

        String sourceId();

        /** Space oder {@code null}, wenn der Umfang eine ganze Quelle ist bzw. der Space der Seite noch offen ist. */
        String spaceKey();

        /** Startseite oder {@code null}. */
        default String rootPageId() {
            return null;
        }

        /** Nur diese Seite. */
        record SinglePage(String sourceId, String spaceKey, String pageId) implements Scope {
            public SinglePage {
                Objects.requireNonNull(sourceId, "sourceId");
                Objects.requireNonNull(pageId, "pageId");
            }

            @Override
            public String rootPageId() {
                return pageId;
            }
        }

        /** Die Seite und alle Nachfahren, rekursiv, auch nicht aufgeklappte. */
        record Subtree(String sourceId, String spaceKey, String pageId) implements Scope {
            public Subtree {
                Objects.requireNonNull(sourceId, "sourceId");
                Objects.requireNonNull(pageId, "pageId");
            }

            @Override
            public String rootPageId() {
                return pageId;
            }
        }

        /** Alle erreichbaren Seiten eines Space. */
        record Space(String sourceId, String spaceKey) implements Scope {
            public Space {
                Objects.requireNonNull(sourceId, "sourceId");
                Objects.requireNonNull(spaceKey, "spaceKey");
            }
        }

        /** Alle erreichbaren Spaces und Seiten einer Quelle. */
        record Source(String sourceId) implements Scope {
            public Source {
                Objects.requireNonNull(sourceId, "sourceId");
            }

            @Override
            public String spaceKey() {
                return null;
            }
        }
    }

    /**
     * @param reanalyze {@code true} analysiert auch aktuelle Seiten neu („Neu analysieren“)
     * @param maxPages  ausdrücklich bestätigte Sicherheitsgrenze; {@code 0} ohne Grenze. Wird sie überschritten,
     *                  scheitert der Auftrag vor der ersten Analyse mit {@link #SCOPE_TOO_LARGE} – nie still gekürzt.
     * @param simplification Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3) oder {@code null} ohne sie; nur Seiten mit
     *                  KI-Freigabe gehen an das Modell, die übrigen werden ohne Vorstufe analysiert; nur mit dem MaltParser
     * @param analyzer  welcher Analysator rechnet (Fahrplan Paket A2), {@code null} = Einstellung (D3). Mit
     *                  {@link AnalyzerChoice#MODEL} gehen nur Seiten mit KI-Freigabe an den KI-Dienst; Seiten ohne
     *                  Freigabe und ohne eingestelltes Modell werden bewusst ausgelassen und gezählt, nie als Fehler
     */
    public record Options(boolean reanalyze, int maxPages, StatementAnalysis.SimplificationRequest simplification,
                          AnalyzerChoice analyzer) {

        public static final Options DEFAULT = new Options(false, 0);

        /** Ohne Vorstufe, mit dem eingestellten Analysator. */
        public Options(boolean reanalyze, int maxPages) {
            this(reanalyze, maxPages, null);
        }

        /** Mit dem eingestellten Analysator ({@code statements.analyzer}). */
        public Options(boolean reanalyze, int maxPages, StatementAnalysis.SimplificationRequest simplification) {
            this(reanalyze, maxPages, simplification, null);
        }

        public Options {
            if (maxPages < 0) {
                throw new IllegalArgumentException("maxPages darf nicht negativ sein");
            }
        }

        /** Dieselben Optionen mit festgelegtem Analysator. */
        public Options withAnalyzer(AnalyzerChoice choice) {
            return new Options(reanalyze, maxPages, simplification, Objects.requireNonNull(choice, "choice"));
        }
    }

    /**
     * Optionen des Prüfauftrags (ADR 0032, FL4); {@link #of} übernimmt Auswahl und Obergrenze aus den Einstellungen.
     *
     * @param maxPages wie bei {@link Options#maxPages()}
     * @param scope    welche Aussagen das Modell sieht
     * @param parallel so viele Sätze einer Seite höchstens zugleich, mindestens 1
     */
    public record CheckOptions(int maxPages, CheckScope scope, int parallel) {

        public CheckOptions {
            if (maxPages < 0) {
                throw new IllegalArgumentException("maxPages darf nicht negativ sein");
            }
            Objects.requireNonNull(scope, "scope");
            if (parallel < 1) {
                throw new IllegalArgumentException("parallel muss mindestens 1 sein");
            }
        }

        /** Auswahl und Obergrenze aus den Einstellungen. */
        public static CheckOptions of(int maxPages,
                                      com.aresstack.spaceharvester.application.configuration.CheckSettings settings) {
            return new CheckOptions(maxPages, settings.scope(), settings.parallel());
        }
    }

    /**
     * Umfang für den Bestätigungsdialog.
     *
     * @param pages      lesbare Seiten, höchstens die Grenze
     * @param exceeded   {@code true}, wenn es mehr als die Grenze sind (Zahl dann „mehr als“)
     * @param incomplete {@code true}, wenn Teile nicht ermittelt werden konnten (Zahl ist eine Schätzung)
     */
    public record Estimate(Scope scope, int pages, boolean exceeded, boolean incomplete) {

        /** Ob die Zahl exakt ist. */
        public boolean exact() {
            return !exceeded && !incomplete;
        }
    }

    /** Seitenermittlung: Kindseiten mit Cursor und die Spaces einer Quelle, jeweils rechtegeprüft. */
    public interface Discovery {

        PageNavigation.Children children(AccessContext caller, String sourceId, String spaceKey, String parentPageId,
                                         String cursor);

        List<Partition> spaces(AccessContext caller, String sourceId);

        /**
         * Space einer Seite über die geschützte Navigation ({@code metadata} auf Quelle und Seite).
         *
         * @throws AccessDeniedException ohne Freigabe der Seite
         */
        String spaceKey(AccessContext caller, String sourceId, String pageId);
    }

    /** Geschützter Seitenabruf (Cache, sonst die Quelle) – nur innerhalb des Auftrags. */
    @FunctionalInterface
    public interface PageFetch {

        PageSnapshot page(AccessContext caller, String sourceId, String pageId);
    }
}
