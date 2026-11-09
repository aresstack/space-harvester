package com.aresstack.spaceharvester.application.knowledge;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.Partition;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** Fehlercode, wenn Teile des Umfangs nicht ermittelt werden konnten; analysierte Seiten bleiben. */
    public static final String INCOMPLETE = "INCOMPLETE";
    /** Fehlercode ohne eingerichteten oder mit fehlendem bzw. defektem Analysator. */
    public static final String ANALYZER_UNAVAILABLE = "ANALYZER_UNAVAILABLE";

    private final Jobs jobs;
    private final Discovery discovery;
    private final PageFetch fetch;
    private final StatementAnalysis analysis;
    private final AccessGuard access;
    private final String owner;

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
     * erste Seite analysiert wird.
     *
     * @throws AnalyzerUnavailableException wenn kein Analysator eingerichtet ist
     */
    public JobStatus start(AccessContext caller, Scope scope, Options options) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(options, "options");
        if (!analysis.available()) {
            throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NOT_CONFIGURED,
                    "In dieser Laufzeit ist kein Satzanalysator eingerichtet (nur in der Desktop-App)", null);
        }
        return jobs.submit(JobType.STATEMENT_ANALYSIS, scope.sourceId(), scope.spaceKey(), scope.rootPageId(), owner,
                JobTask.blocking(context -> run(caller, scope, options, context)));
    }

    private void run(AccessContext caller, Scope scope, Options options, JobContext context) {
        context.phase(COMPONENT, PHASE_DISCOVERY);
        Walk walk = new Walk(caller, scope, options.maxPages() == 0 ? Integer.MAX_VALUE : bound(options.maxPages()),
                context::checkpoint);
        walk.onPage = () -> context.discovered(1);
        walk.run();
        if (options.maxPages() > 0 && walk.pages.size() > options.maxPages()) {
            throw new JobFailedException(SCOPE_TOO_LARGE, "Der Umfang hat mehr als " + options.maxPages()
                    + " Seiten; es wurde nichts analysiert. Bitte ausdrücklich mit höherer Grenze bestätigen.");
        }
        context.phase(COMPONENT, PHASE_ANALYSIS);
        long current = 0;
        long withoutText = 0;
        for (Target target : walk.pages.values()) {
            context.checkpoint();
            try {
                PageSnapshot snapshot = fetch.page(caller, target.sourceId(), target.pageId());
                if (!options.reanalyze() && analysis.read(caller, snapshot).state() == PageStatements.State.CURRENT) {
                    current++;
                } else if (analysis.analyze(caller, snapshot, context::cancelRequested).analysis() == null) {
                    withoutText++; // ohne Lesetext: nichts zu analysieren
                } else {
                    context.written(1);
                }
            } catch (CancellationException e) {
                context.checkpoint();
                throw e;
            } catch (AnalyzerUnavailableException e) {
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
        if (walk.incomplete) {
            throw new JobFailedException(INCOMPLETE, "Der Umfang konnte nicht vollständig ermittelt werden; die "
                    + "gefundenen Seiten wurden verarbeitet, weitere fehlen.");
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
     */
    public record Options(boolean reanalyze, int maxPages) {

        public static final Options DEFAULT = new Options(false, 0);

        public Options {
            if (maxPages < 0) {
                throw new IllegalArgumentException("maxPages darf nicht negativ sein");
            }
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
