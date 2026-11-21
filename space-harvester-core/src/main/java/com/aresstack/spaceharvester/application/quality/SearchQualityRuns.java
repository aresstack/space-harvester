package com.aresstack.spaceharvester.application.quality;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearchListener;
import com.aresstack.spaceharvester.application.query.progressive.SearchOrchestrator;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * „Suchqualität messen“ als Auftrag (Q1b, {@link JobType#SEARCH_QUALITY}): liest den Testbestand, stellt jede Frage
 * über genau den Suchweg des Chats (progressive Suche mit dem eingestellten Profil, im Bereich als Seitenmenge wie
 * {@code ChatTurn}, gleiche Frist) und schreibt nach jeder Frage die lokale Ergebnisdatei, am Ende den Bericht, beides
 * in den Unterordner {@value #FOLDER} des Ausgabeordners (Angelo #979: alle Ausgaben in den Ausgabeordner). Ein
 * Abbruch schreibt den Bericht der gemessenen Fragen; Fortsetzen misst nur, was in der Ergebnisdatei fehlt, auch nach
 * einem Neustart der App. Auftrag, Zähler und Meldungen tragen nur den Namen des Testbestands und Zahlen, nie eine
 * Frage, einen Titel oder eine Seiten-ID. An der Suche ändert sich nichts.
 */
public final class SearchQualityRuns {

    /** Angabe des Auftrags: Pfad des Testbestands. */
    public static final String SUITE_PARAMETER = "testbestand";
    /** Angabe des Auftrags: Pfad der Ergebnisdatei ({@code .tsv}); der Bericht liegt daneben ({@code .md}). */
    public static final String RESULT_PARAMETER = "ergebnis";
    /** Angabe des Auftrags: früheres Ergebnis zum Vergleich, fehlt ohne. */
    public static final String PREVIOUS_PARAMETER = "vergleich";
    /** Angabe des Auftrags: Treffer im Chat-Kontext. */
    public static final String CONTEXT_PARAMETER = "kontext";
    /** Angabe des Auftrags: Frist je Frage in Sekunden. */
    public static final String TIMEOUT_PARAMETER = "frist";
    /** Angabe des Auftrags: App-Version für den Kopf des Ergebnisses. */
    public static final String VERSION_PARAMETER = "version";
    /** Ob mit der Suchmethode „Nachbarseiten“ (R2) gemessen wird; fehlt er, ohne. */
    public static final String NEIGHBORS_PARAMETER = "nachbarseiten";
    /** Zusatz im Namen von Ergebnis und Bericht eines Laufs mit „Nachbarseiten“; ohne bleibt der Name wie bisher. */
    public static final String NEIGHBORS_SUFFIX = " – mit Nachbarseiten";

    static final String COMPONENT = "Suchqualität";
    /** Unterordner des Ausgabeordners für Ergebnisse und Berichte. */
    public static final String FOLDER = "Suchqualität";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm");

    /**
     * Ein Start.
     *
     * @param suite       Testbestand ({@code fragen.tsv})
     * @param previous    früheres Ergebnis ({@code .tsv}) zum Vergleich oder {@code null}
     * @param output      Ausgabeordner der App; Ergebnis und Bericht landen in dessen Unterordner {@value #FOLDER}
     * @param contextSize Treffer, die der Chat ins Modell gibt ({@code ChatKnowledge.MAX_SOURCES})
     * @param timeout     Frist je Frage wie im Chat ({@code ChatTurn.SEARCH_SECONDS})
     * @param version     App-Version
     * @param neighbors   mit der Suchmethode „Nachbarseiten“ (R2), damit ihr Nutzen neben einem Lauf ohne messbar ist
     */
    public record Request(Path suite, Path previous, Path output, int contextSize, Duration timeout, String version,
                          boolean neighbors) {

        /** Ohne „Nachbarseiten“. */
        public Request(Path suite, Path previous, Path output, int contextSize, Duration timeout, String version) {
            this(suite, previous, output, contextSize, timeout, version, false);
        }

        public Request {
            Objects.requireNonNull(suite, "suite");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout muss positiv sein");
            }
        }
    }

    private final Supplier<List<HarvestSource>> sources;
    private final AccessGuard access;
    private final Function<PageKey, Optional<String>> spaceOf;
    private final BiFunction<AccessContext, Selection, PageSet> selections;
    private final BiPredicate<AccessContext, PageKey> stored;
    private final Supplier<SearchOrchestrator> search;
    private final Supplier<SearchProfile> profile;
    private final Jobs jobs;
    private final Clock clock;
    private final Searches searches;

    /** Die Suche je Frage mit dem Profil des Laufs. */
    @FunctionalInterface
    interface Searches {
        QualitySearch of(AccessContext caller, Duration timeout, SearchProfile settings);
    }

    /**
     * @param sources    eingetragene Quellen
     * @param spaceOf    Space einer Seite aus den Kopfdaten im Cache
     * @param selections löst eine Auswahl zu Seiten auf ({@code Holdings.resolve})
     * @param stored     ob eine Seite lokal gespeichert und für den Aufrufer lesbar ist ({@code PageQueries.cached})
     * @param search     die progressive Suche des Core
     * @param profile    das eingestellte Suchprofil
     */
    public SearchQualityRuns(Supplier<List<HarvestSource>> sources, AccessGuard access,
                             Function<PageKey, Optional<String>> spaceOf,
                             BiFunction<AccessContext, Selection, PageSet> selections,
                             BiPredicate<AccessContext, PageKey> stored, Supplier<SearchOrchestrator> search,
                             Supplier<SearchProfile> profile, Jobs jobs, Clock clock) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.access = Objects.requireNonNull(access, "access");
        this.spaceOf = Objects.requireNonNull(spaceOf, "spaceOf");
        this.selections = Objects.requireNonNull(selections, "selections");
        this.stored = Objects.requireNonNull(stored, "stored");
        this.search = Objects.requireNonNull(search, "search");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.searches = this::search;
    }

    /** Für Tests: eine andere Suche je Frage statt der progressiven Suche. */
    SearchQualityRuns(Supplier<List<HarvestSource>> sources, AccessGuard access,
                      BiFunction<AccessContext, Duration, QualitySearch> searches,
                      BiPredicate<AccessContext, PageKey> stored, Supplier<SearchProfile> profile, Jobs jobs,
                      Clock clock) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.access = Objects.requireNonNull(access, "access");
        this.spaceOf = key -> Optional.empty();
        this.selections = (who, selection) -> PageSet.EMPTY;
        this.stored = Objects.requireNonNull(stored, "stored");
        this.search = () -> {
            throw new IllegalStateException("keine progressive Suche");
        };
        this.profile = Objects.requireNonNull(profile, "profile");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(searches, "searches");
        this.searches = (who, timeout, settings) -> searches.apply(who, timeout);
    }

    /** Liest einen Testbestand zur Vorschau im Startdialog (Zahl der Fragen, Zeilenfehler). */
    public QualitySuite read(AccessContext caller, Path suite) throws IOException {
        return QualitySuite.read(Files.readAllBytes(suite), resolver(caller));
    }

    /** Nimmt den Auftrag an; er läuft im Hintergrund. */
    public JobStatus submit(AccessContext caller, Request request) {
        Objects.requireNonNull(caller, "caller");
        Path suite = request.suite().toAbsolutePath();
        Path output = request.output().toAbsolutePath().resolve(FOLDER);
        Path result = reserve(output, baseName(suite) + " – "
                + STAMP.format(clock.instant().atZone(ZoneId.systemDefault()))
                + (request.neighbors() ? NEIGHBORS_SUFFIX : ""));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(SUITE_PARAMETER, suite.toString());
        parameters.put(RESULT_PARAMETER, result.toString());
        if (request.previous() != null) {
            parameters.put(PREVIOUS_PARAMETER, request.previous().toAbsolutePath().toString());
        }
        parameters.put(CONTEXT_PARAMETER, Integer.toString(request.contextSize()));
        parameters.put(TIMEOUT_PARAMETER, Long.toString(request.timeout().toSeconds()));
        parameters.put(VERSION_PARAMETER, request.version() == null ? "" : request.version());
        if (request.neighbors()) {
            parameters.put(NEIGHBORS_PARAMETER, "true");
        }
        return jobs.submit(JobType.SEARCH_QUALITY, Jobs.ALL_SOURCES, null, null, caller.jobOwner(), parameters,
                task(caller, parameters));
    }

    /**
     * Legt für einen neuen Lauf eine eigene, leere Ergebnisdatei an. Zwei Starts in derselben Minute bekommen so nie
     * dieselbe Datei; nur Fortsetzen arbeitet in einer schon gefüllten weiter.
     */
    private static Path reserve(Path folder, String base) {
        try {
            Files.createDirectories(folder);
            for (int n = 1; ; n++) {
                Path candidate = folder.resolve(n == 1 ? base + ".tsv" : base + " (" + n + ").tsv");
                try {
                    return Files.createFile(candidate);
                } catch (FileAlreadyExistsException taken) {
                    // nächste Nummer
                }
            }
        } catch (IOException failed) {
            throw new JobFailedException("RESULT_UNWRITABLE", "Ergebnisordner nicht schreibbar ("
                    + failed.getClass().getSimpleName() + ")", failed);
        }
    }

    /**
     * Setzt einen Auftrag im selben Eintrag fort; misst nur die Fragen, die in seiner Ergebnisdatei fehlen. Nach einem
     * Neustart aus seinen Angaben neu gebaut.
     */
    public JobStatus resume(String jobId, String owner) {
        if (jobs.resumable(jobId, owner)) {
            return jobs.resume(jobId, owner);
        }
        JobStatus status = jobs.status(jobId, owner);
        AccessContext caller = caller(status.owner()).orElseThrow(() -> new JobNotResumableException(jobId,
                "die Anmeldung des Auftraggebers ist nach dem Neustart nicht mehr da; neu starten, es misst nur, was "
                        + "fehlt"));
        if (status.parameter(SUITE_PARAMETER) == null || status.parameter(RESULT_PARAMETER) == null) {
            throw new JobNotResumableException(jobId, "der Auftrag kennt seinen Testbestand nicht mehr");
        }
        return jobs.rerun(jobId, owner, task(caller, status.parameters()));
    }

    /** Name des Testbestands eines Auftrags (für Liste und Statuszeile), ohne Ordner; leer, wenn unbekannt. */
    public static String suiteName(JobStatus status) {
        String suite = status.parameter(SUITE_PARAMETER);
        if (suite == null || suite.isBlank()) {
            return "";
        }
        try {
            Path name = Path.of(suite).getFileName();
            return name == null ? "" : name.toString();
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    // --- Arbeit des Auftrags ----------------------------------------------------------------------------------------

    private JobTask task(AccessContext caller, Map<String, String> parameters) {
        Path suite = Path.of(parameters.get(SUITE_PARAMETER));
        Path result = Path.of(parameters.get(RESULT_PARAMETER));
        String previous = parameters.get(PREVIOUS_PARAMETER);
        int contextSize = Integer.parseInt(parameters.getOrDefault(CONTEXT_PARAMETER, "8"));
        Duration timeout = Duration.ofSeconds(Long.parseLong(parameters.getOrDefault(TIMEOUT_PARAMETER, "90")));
        String version = parameters.getOrDefault(VERSION_PARAMETER, "");
        boolean neighbors = Boolean.parseBoolean(parameters.get(NEIGHBORS_PARAMETER));
        return JobTask.blocking(context -> run(caller, suite, result, previous == null ? null : Path.of(previous),
                contextSize, timeout, version, neighbors, context));
    }

    private void run(AccessContext caller, Path suitePath, Path resultPath, Path previousPath, int contextSize,
                     Duration timeout, String version, boolean neighbors, JobContext context) throws IOException {
        context.phase(COMPONENT, "Testbestand lesen");
        QualitySuite suite;
        try {
            suite = read(caller, suitePath);
        } catch (IOException unreadable) {
            throw new JobFailedException("SUITE_UNREADABLE", "Testbestand nicht lesbar ("
                    + unreadable.getClass().getSimpleName() + ")");
        }
        if (suite.cases().isEmpty()) {
            throw new JobFailedException("SUITE_EMPTY", "Testbestand enthält keine gültige Frage ("
                    + suite.errors().size() + " Zeilenfehler)");
        }
        Map<String, CaseResult> done = new HashMap<>();
        Instant started = clock.instant();
        SearchProfile settings = profile.get().withNeighbors(neighbors);
        // Eine leere Datei ist die beim Start reservierte: noch nichts gemessen.
        if (Files.exists(resultPath) && Files.size(resultPath) > 0) {
            QualityResults.Stored earlier = QualityResults.read(Files.readString(resultPath, StandardCharsets.UTF_8));
            // Ein Ergebnis misst genau einen Stand: geändertes Profil oder anderer Chat-Kontext mischt sonst zwei.
            if (!earlier.info().profile().equals(describe(settings)) || earlier.info().contextSize() != contextSize) {
                throw new JobFailedException("PROFILE_CHANGED", "Das Suchprofil hat sich seit dem Abbruch geändert; "
                        + "Fortsetzen würde zwei Stände mischen. Bitte neu starten.");
            }
            started = earlier.info().started();
            Set<String> ids = new LinkedHashSet<>();
            suite.cases().forEach(question -> ids.add(question.id()));
            for (CaseResult measured : earlier.results()) {
                if (ids.contains(measured.id())) {
                    done.put(measured.id(), measured);
                }
            }
        }
        QualityRun.Settings runSettings = new QualityRun.Settings(contextSize, settings.minScore());
        QualityRun run = new QualityRun(searches.of(caller, timeout, settings), page -> stored(caller, page),
                runSettings);
        context.discovered(suite.cases().size());
        context.processed(done.size());
        // Dieselbe Leitzahl wie der Bericht: erwartete Fundstelle im Chat-Kontext.
        context.written(done.values().stream().filter(r -> !r.noAnswer() && r.inTop(contextSize)).count());
        context.failed(done.values().stream().filter(CaseResult::failed).count());
        context.phase(COMPONENT, "Fragen stellen");
        Instant start = started;
        List<CaseResult> measured = new ArrayList<>(done.values());
        QualityRun.Result outcome = run.run(suite.cases(), done, context::cancelRequested, (n, total, r) -> {
            context.processed(1);
            if (!r.noAnswer() && r.inTop(contextSize)) {
                context.written(1);
            }
            if (r.failed()) {
                context.failed(1);
            }
            measured.add(r);
            write(resultPath, QualityResults.write(info(version, settings, start, suitePath, runSettings,
                    suite.cases().size(), true), ordered(suite, measured)));
        });
        context.phase(COMPONENT, "Bericht schreiben");
        RunInfo info = info(version, settings, start, suitePath, runSettings, suite.cases().size(),
                outcome.cancelled());
        write(resultPath, QualityResults.write(info, outcome.results()));
        QualityComparison comparison = null;
        if (previousPath != null && Files.isReadable(previousPath)) {
            QualityResults.Stored previous = QualityResults.read(Files.readString(previousPath,
                    StandardCharsets.UTF_8));
            comparison = QualityComparison.of(previous, outcome.results(), contextSize);
        }
        write(report(resultPath), QualityReport.markdown(info, outcome.results(), suite.errors(), comparison));
        // Auch ein Abbruch oder eine Pause während der letzten Frage gilt; Ergebnis und Bericht stehen schon.
        context.checkpoint();
        if (!suite.errors().isEmpty() || outcome.results().stream().anyMatch(CaseResult::failed)) {
            context.incomplete();
        }
    }

    private static List<CaseResult> ordered(QualitySuite suite, List<CaseResult> measured) {
        Map<String, CaseResult> byId = new HashMap<>();
        measured.forEach(r -> byId.put(r.id(), r));
        List<CaseResult> ordered = new ArrayList<>();
        for (QualityCase question : suite.cases()) {
            CaseResult r = byId.get(question.id());
            if (r != null) {
                ordered.add(r);
            }
        }
        return ordered;
    }

    private static RunInfo info(String version, SearchProfile profile, Instant started, Path suite,
                                QualityRun.Settings settings, int total, boolean cancelled) {
        return new RunInfo(version, describe(profile), started, String.valueOf(suite.getFileName()),
                settings.contextSize(), settings.relevantScore(), total, cancelled);
    }

    /** Suchprofil in Kurzform für den Kopf des Ergebnisses. */
    static String describe(SearchProfile profile) {
        return "Varianten " + (profile.expansion() == SearchProfile.Expansion.AI ? "KI" : "Regeln")
                + " (höchstens " + profile.maxVariants() + ")"
                + ", Ranking " + (profile.ranking() == SearchProfile.Ranking.SEMANTIC ? "semantisch" : "Schlagwort")
                + ", Modus " + profile.mode()
                + ", Ziel " + profile.minRelevantHits() + " Treffer ab " + profile.minScore()
                + ", Budget " + profile.timeBudget().toSeconds() + " s / " + profile.searchBudget() + " Anfragen"
                // Nur wenn an: frühere Ergebnisse ohne die Methode behalten ihre Beschreibung (Fortsetzen).
                + (profile.neighbors() ? ", Nachbarseiten an" : "");
    }

    static Path report(Path result) {
        String name = result.getFileName().toString();
        String base = name.endsWith(".tsv") ? name.substring(0, name.length() - 4) : name;
        return result.resolveSibling(base + ".md");
    }

    private static void write(Path target, String content) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            throw new JobFailedException("RESULT_UNWRITABLE", "Ergebnis nicht schreibbar ("
                    + failed.getClass().getSimpleName() + ")", failed);
        }
    }

    static String baseName(Path file) {
        String name = String.valueOf(file.getFileName());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private boolean stored(AccessContext caller, PageRef page) {
        try {
            return stored.test(caller, new PageKey(page.sourceId(), page.pageId()));
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    // --- Suchweg des Chats ------------------------------------------------------------------------------------------

    /**
     * Die Suche einer Frage wie im Chat: {@code KeywordSearch.Query(...).within(Bereich)} an die progressive Suche mit
     * dem eingestellten Profil; nach der Frist gilt die letzte vorläufige Rangliste.
     */
    QualitySearch search(AccessContext caller, Duration timeout, SearchProfile settings) {
        return (question, interim) -> {
            KeywordSearch.Query query = new KeywordSearch.Query(question.question(), null, Set.of(), Set.of());
            PageSet scope = scope(caller, question.scope());
            if (scope != null) {
                if (scope.isEmpty()) {
                    throw new EmptyScopeException();
                }
                query = query.within(scope);
            }
            AtomicReference<List<ProgressiveResult.RankedHit>> latest = new AtomicReference<>(List.of());
            ProgressiveSearch running = search.get().searchAsync(caller, query, settings,
                    new ProgressiveSearchListener() {
                        @Override
                        public void ranked(List<ProgressiveResult.RankedHit> hits) {
                            latest.set(hits);
                            interim.accept(pages(hits));
                        }
                    });
            ProgressiveResult result;
            try {
                result = running.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException slow) {
                running.cancel();
                return new QualitySearch.Answer(pages(latest.get()), "TIMEOUT", 0, 0);
            } catch (InterruptedException interrupted) {
                running.cancel();
                throw interrupted;
            } catch (ExecutionException | CancellationException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                throw cause instanceof Exception exception ? exception : failed;
            }
            int queries = 0;
            int failedQueries = 0;
            for (ProgressiveResult.Variant variant : result.variants()) {
                if (variant.status() == ProgressiveResult.Status.DONE) {
                    queries++;
                } else if (variant.status() == ProgressiveResult.Status.FAILED) {
                    queries++;
                    failedQueries++;
                }
            }
            if (result.reason() == ProgressiveResult.Completion.FAILED) {
                // Alle Suchanfragen gescheitert: eine gescheiterte Messung, kein „zu Recht leer“.
                throw new SearchFailedException();
            }
            return new QualitySearch.Answer(pages(result.hits()), result.reason().name(), queries, failedQueries);
        };
    }

    private static List<QualitySearch.RankedPage> pages(List<ProgressiveResult.RankedHit> hits) {
        List<QualitySearch.RankedPage> pages = new ArrayList<>();
        for (ProgressiveResult.RankedHit ranked : hits) {
            KeywordSearch.Hit hit = ranked.hit();
            pages.add(new QualitySearch.RankedPage(new PageRef(hit.sourceId(), hit.pageId()), ranked.score(),
                    hit.title()));
        }
        return pages;
    }

    /** Der Bereich als Seitenmenge wie im Chat; {@code null} = alles (globale Suche). */
    PageSet scope(AccessContext caller, List<ScopeSpec> specs) {
        List<Selection.Root> roots = new ArrayList<>();
        Map<String, Set<String>> single = new LinkedHashMap<>();
        for (ScopeSpec spec : specs) {
            switch (spec.kind()) {
                case ALL -> {
                    return null;
                }
                case SOURCE -> roots.add(Selection.Root.source(spec.sourceId()));
                case SPACE -> roots.add(Selection.Root.space(spec.sourceId(), spec.spaceKey()));
                case PAGE -> {
                    if (spec.withChildren()) {
                        String space = spaceOf.apply(new PageKey(spec.sourceId(), spec.pageId()))
                                .orElseThrow(EmptyScopeException::new);
                        roots.add(Selection.Root.subtree(spec.sourceId(), space, spec.pageId()));
                    } else {
                        single.computeIfAbsent(spec.sourceId(), id -> new LinkedHashSet<>()).add(spec.pageId());
                    }
                }
            }
        }
        if (!roots.isEmpty()) {
            PageSet resolved = selections.apply(caller, new Selection(roots));
            resolved.asMap().forEach((source, ids) -> single.computeIfAbsent(source, id -> new LinkedHashSet<>())
                    .addAll(ids));
        }
        return PageSet.of(single);
    }

    /**
     * Löst Seitenadressen über die eingetragenen Quellen auf, nur über deren eigene Adressmuster
     * ({@link HarvestSource#resolveAddress}); lädt dabei keine Seite in den Cache, damit „lokal gespeichert“ vor der
     * Frage stimmt. Passen mehrere Quellen, ist die Adresse nicht eindeutig und bleibt ein Zeilenfehler.
     */
    PageResolver resolver(AccessContext caller) {
        return url -> {
            List<PageRef> found = new ArrayList<>();
            for (HarvestSource source : sources.get()) {
                if (!source.capabilities().contains(SourceCapability.ADDRESSES)
                        || !access.permits(caller, ResourceScope.source(source.id()), AccessKind.CONTENT)) {
                    continue;
                }
                try {
                    if (source.addressable(url)) {
                        source.resolveAddress(url).ifPresent(pageId -> found.add(new PageRef(source.id(), pageId)));
                    }
                } catch (RuntimeException unreachable) {
                    // Quelle nicht erreichbar oder Adresse unbekannt: keine Zuordnung über diese Quelle.
                }
            }
            return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
        };
    }

    /** Auftraggeber aus {@link JobStatus#owner()}, wie bei „In PPTX umwandeln“ nach einem Neustart. */
    static Optional<AccessContext> caller(String owner) {
        String prefix = AccessContext.PrincipalKind.TECHNICAL_CLIENT.name() + ":";
        if (owner == null || !owner.startsWith(prefix) || owner.length() == prefix.length()) {
            return Optional.empty();
        }
        AccessContext caller = AccessContext.technicalClient(owner.substring(prefix.length()));
        return caller.jobOwner().equals(owner) ? Optional.of(caller) : Optional.empty();
    }

    /** Der Bereich einer Frage enthält keine sichtbare Seite. */
    /** Die Suche ist mit „gescheitert“ abgeschlossen (keine Suchanfrage lief durch). */
    public static final class SearchFailedException extends RuntimeException {

        SearchFailedException() {
            super("alle Suchanfragen gescheitert", null, false, false);
        }
    }

    public static final class EmptyScopeException extends RuntimeException {

        EmptyScopeException() {
            super("Bereich ohne sichtbare Seite", null, false, false);
        }
    }
}
