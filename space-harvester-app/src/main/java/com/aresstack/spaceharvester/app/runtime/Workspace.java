package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.PageAssets;
import com.aresstack.spaceharvester.application.marks.VideoMarks;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.images.ImagePayload;
import com.aresstack.spaceharvester.application.images.PageImages;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.export.ExportPackageImport;
import com.aresstack.spaceharvester.application.export.ExportRequest;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLifecycleState;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobStillRunningException;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.passages.PassageId;
import com.aresstack.spaceharvester.application.passages.PassageLookup;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearch;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearchListener;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.SynonymDiscovery;
import com.aresstack.spaceharvester.application.terms.SynonymScope;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.PageListing;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SpaceTree;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.reading.ReadingView;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SinglePageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceDiagnosis;
import com.aresstack.spaceharvester.application.sync.SpaceCatalog;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;

/**
 * Die fachlichen Aktionen der Oberfläche, genau einmal auf die Anwendungsfälle abgebildet. Jede Methode prüft die
 * Rechte so wie der entsprechende REST-Controller des Dienstes (dieselben {@link AccessKind}s, dieselbe verdeckte
 * Prüfung bei Seiten ohne bekannten Space), damit die App keine Rechteprüfung umgeht. Alle Methoden blockieren und
 * laufen nie auf dem Event Dispatch Thread.
 */
public final class Workspace {

    private final Harvester harvester;
    private final AccessGuard access;
    private final AccessContext caller;
    private final GlossaryAccess glossary;
    private final ActivityLog activities;
    /** Decoded program analyses of earlier program searches, reused while unchanged (#1486, P2). */
    private final com.aresstack.spaceharvester.app.runtime.program.ProgramFactMemo programFacts =
            new com.aresstack.spaceharvester.app.runtime.program.ProgramFactMemo();

    public Workspace(Harvester harvester, AccessGuard access, ActivityLog activities) {
        this.harvester = Objects.requireNonNull(harvester, "harvester");
        this.access = access;
        this.caller = harvester.caller();
        this.glossary = new CoreGlossaryAccess(harvester, caller, this::subscribeJobs);
        this.activities = Objects.requireNonNull(activities, "activities");
        // ADR 0032 (Fortschreibung 26.11.2026): Programmaussagen werden beim Lesen gegen den aktuellen Code, die
        // Copybooks und die Programmanalyse geprüft, je mit den Leserechten des Aufrufers
        harvester.statementAnalysis().programSources(reader ->
                new com.aresstack.spaceharvester.app.runtime.program.ProgramCurrentSources(
                        key -> cachedPage(reader, key), programAnalyses()));
    }

    // ------------------------------------------------------------------ Wissensquellen

    /** Die Quellentypen des Core ({@code GET /api/v1/sources/types}, #375) für das Quellenformular. */
    public List<com.aresstack.spaceharvester.application.sources.SourceType> sourceTypes() {
        return harvester.sourceTypes();
    }

    /** Sichtbare Quellen ({@code GET /api/v1/sources}). */
    public List<HarvestSource> sources() {
        return access.filter(caller, harvester.sources().sources(), AccessKind.METADATA,
                source -> ResourceScope.source(source.id()));
    }

    /**
     * Macht eine laufende Verbindungsprüfung als Hintergrundteil sichtbar (Fundstelle 17); die App ruft das erst nach
     * einer Sekunde. Leer bei nur einem Platz (bisheriger Weg).
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.background.ConnectionChecks.Check>
            beginConnectionCheck(String sourceId) {
        return harvester.connectionChecks().begin(sourceId);
    }

    /** Spaces einer Quelle, live ({@code GET /api/v1/sources/{id}/spaces}); zugleich die Verbindungsprüfung. */
    public List<Partition> spaces(String sourceId) {
        com.aresstack.spaceharvester.application.sync.Exclusions exclusions = harvester.exclusions();
        return allSpaces(sourceId).stream()
                .filter(partition -> !exclusions.spaceExcluded(sourceId, partition.key())).toList();
    }

    /** Wie {@link #spaces}, auch die ganz ausgeschlossenen Spaces; für die Übersicht in den Einstellungen. */
    public List<Partition> allSpaces(String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        return access.filter(caller, harvester.sources().partitions(sourceId), AccessKind.METADATA,
                partition -> ResourceScope.space(sourceId, partition.key()));
    }

    // ------------------------------------------------------------------ Inhalte, live

    /**
     * Kindseiten eines Knotens live in der Reihenfolge der Quelle ({@code PageNavigation.children}, #177); ohne
     * {@code parentPageId} die Wurzelseiten des Space. Quellen ohne Seitenbaum werfen {@code UNSUPPORTED}.
     */
    public PageNavigation.Children children(String sourceId, String spaceKey, String parentPageId, String cursor) {
        return harvester.navigation().children(caller, sourceId, spaceKey, parentPageId, cursor);
    }

    /**
     * Kindseiten nur aus dem Cache, ohne die Quelle zu fragen ({@code PageNavigation.cachedChildren}): zuerst zeigen,
     * dann {@link #children} im Hintergrund holen und tauschen (Angelo 17.11.2026). Paginiert, ohne Gesamtgrenze.
     *
     * @param cursor {@code null} oder {@code nextCursor} des vorigen Stapels
     * @param limit  Stapelgröße
     */
    public PageNavigation.Children cachedChildren(String sourceId, String spaceKey, String parentPageId,
                                                  String cursor, int limit) {
        return harvester.navigation().cachedChildren(caller, sourceId, spaceKey, parentPageId, cursor, limit);
    }

    /** Wo eine Seite steht: Space und Vorfahren von oben nach unten ({@code PageNavigation.position}). */
    public PageNavigation.Position position(String sourceId, String pageId) {
        return harvester.navigation().position(caller, sourceId, pageId);
    }

    /**
     * Meldet einen Empfänger für jede Änderung des Verzeichnisses der Verweise an (Paket V1, ADR 0027), gebündelt auf
     * dem Thread des Verzeichnisses, nie über den EDT.
     */
    public Subscription subscribeLinks(
            java.util.function.Consumer<com.aresstack.spaceharvester.application.links.PageLinks.LinksChanged>
                    listener) {
        return harvester.pageLinks().subscribe(listener);
    }

    /**
     * Querverweise einer Seite, ausgehend und eingehend: von der Quelle oder aus dem Verzeichnis der lokal gespeicherten
     * Seiten ({@code PageNavigation.references}).
     */
    public PageNavigation.References references(String sourceId, String pageId) {
        return harvester.navigation().references(caller, sourceId, pageId);
    }

    /**
     * Ein Stapel des Verzeichnisses der Verben in {@code within} ({@code VerbQueries.verbs}, Paket K1): aus den
     * Aussagen des Vorgabe-Analysators, mit den Rechten des Aufrufers. Nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.knowledge.VerbQueries.VerbList verbs(
            com.aresstack.spaceharvester.application.snapshot.PageSet within, String q, int limit, String cursor) {
        return harvester.verbQueries().verbs(caller, within, q, limit, cursor);
    }

    /**
     * Die Verben einer Seite mit ihren Aussagen ({@code VerbQueries.onPage}, Paket K1); {@code null} ohne Analyse,
     * {@code stale} bei veralteter. Nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.knowledge.VerbQueries.PageVerbs pageVerbs(String sourceId,
                                                                                              String pageId) {
        return harvester.verbQueries().onPage(caller,
                new com.aresstack.spaceharvester.application.snapshot.PageKey(sourceId, pageId));
    }

    /**
     * Abgleich der beiden Aussagenanalysatoren einer Seite (K2): je Aussage der eingestellten Ablage, ob die andere
     * übereinstimmt, abweicht (mit beiden Lesarten) oder nicht vergleichbar ist; dahinter Aussagen nur der anderen.
     * Nie auf dem EDT.
     */
    public java.util.Map<String, com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Comparison>
            analyzerComparisons(String sourceId, String pageId) {
        return harvester.analyzerComparison().comparisons(caller,
                new com.aresstack.spaceharvester.application.snapshot.PageKey(sourceId, pageId));
    }

    /** Die Fundstellen eines Verbs in {@code within} ({@code VerbQueries.occurrences}, Paket K1). Nie auf dem EDT. */
    public List<com.aresstack.spaceharvester.application.knowledge.VerbQueries.Occurrence> verbOccurrences(
            com.aresstack.spaceharvester.application.snapshot.PageSet within,
            com.aresstack.spaceharvester.application.knowledge.VerbQueries.Verb verb, int limit) {
        return harvester.verbQueries().occurrences(caller, within, verb.lemma(), verb.fromText(), limit);
    }

    /** Fundstellen eines Begriffs auf sichtbaren Seiten ({@code TermQueries.occurrences}, #119). */
    public TermQueries.OccurrenceList occurrences(TermQueries.OccurrenceSearch search) {
        return harvester.terms().occurrences(caller, search);
    }

    /** Ein Stapel des Begriffsverzeichnisses über alle sichtbaren Seiten ({@code TermQueries.terms}, #119). */
    public TermQueries.TermList terms(TermQueries.TermSearch search) {
        return harvester.terms().terms(caller, search);
    }

    /**
     * Gesamtzahlen der Begriffe je Art zur Suche ({@code TermQueries.totals}, BG1b); {@code limit} und {@code cursor}
     * gelten nicht. Bei großen Seitenmengen bis etwa eine Sekunde: nie auf dem EDT.
     */
    public TermQueries.TermTotals termTotals(TermQueries.TermSearch search) {
        return harvester.terms().totals(caller, search);
    }

    /** Wie {@link #termTotals(TermQueries.TermSearch)}; {@code progress} erfährt die Zwischenstände. */
    public TermQueries.TermTotals termTotals(TermQueries.TermSearch search,
                                             java.util.function.Consumer<TermQueries.TermTotals> progress) {
        return harvester.terms().totals(caller, search, progress);
    }

    /**
     * Die verwalteten Gesamtzahlen der Begriffe einer Quelle samt ihren Spaces von Hand neu aufbauen, {@code null}
     * für alle Quellen, und die gemerkten Verben verwerfen; die nächste Abfrage liest neu. Für den Fehlerfall.
     */
    public void rebuildTermTotals(String sourceId) {
        harvester.terms().rebuildTotals(sourceId);
        harvester.verbQueries().invalidate();
    }

    /**
     * Zahl der Beziehungen je Art ({@code TermRelations.counts}, BG1b), ohne Status ohne die abgelehnten; mit
     * {@code pages} nur Beziehungen mit einem Begriff auf diesen Seiten, {@code null} ohne Grenze. Nie auf dem EDT.
     */
    public java.util.Map<RelationType, Integer> termRelationCounts(
            com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.termRelations().counts(caller, new TermRelations.RelationSearch(null, null, null, null, null,
                1, null, null, pages));
    }

    /**
     * Beziehungen zwischen Begriffen ({@code GET /api/v1/term-relations}, #120); {@code status} {@code null} heißt
     * vorgeschlagene und bestätigte, wie im Dienst.
     */
    public TermRelations.RelationList termRelations(String term, ReviewStatus status, int limit) {
        return termRelations(term, null, status, limit);
    }

    /** Wie {@link #termRelations(String, ReviewStatus, int)}, nur Beziehungen der Art {@code type} ({@code null}: alle). */
    public TermRelations.RelationList termRelations(String term, RelationType type, ReviewStatus status, int limit) {
        return termRelations(term, type, status, null, limit, null);
    }

    /**
     * Wie {@link #termRelations(String, RelationType, ReviewStatus, int)}, zum Blättern: {@code startsWith} grenzt auf
     * den Anfang des Sortierbegriffs ein (Buchstabenleiste), {@code cursor} ist der {@code nextCursor} der vorigen Seite.
     */
    public TermRelations.RelationList termRelations(String term, RelationType type, ReviewStatus status,
                                                    String startsWith, int limit, String cursor) {
        return termRelations(term, type, status, startsWith, limit, cursor, null);
    }

    /**
     * Wie {@link #termRelations(String, RelationType, ReviewStatus, String, int, String)}, mit {@code pages} nur
     * Beziehungen, von denen ein Begriff auf einer dieser Seiten vorkommt (Auswahl links, ADR 0029); {@code null}
     * ohne diese Grenze.
     */
    public TermRelations.RelationList termRelations(String term, RelationType type, ReviewStatus status,
                                                    String startsWith, int limit, String cursor,
                                                    com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.termRelations().relations(caller, new TermRelations.RelationSearch(term, type, status, null,
                null, limit, cursor, startsWith, pages));
    }

    /**
     * Die Dokumentnavigation einer Seite ({@code GET …/pages/{pageId}/navigation}): Überschriften und erkannte
     * Themenabschnitte zum Text des Cache, sonst sofort die Überschriften.
     */
    public com.aresstack.spaceharvester.application.passages.DocumentNavigationView documentNavigation(
            String sourceId, String pageId) {
        return harvester.documentNavigation().page(caller, sourceId, pageId);
    }

    /**
     * Löst eine stabile Passage-ID auf ({@code GET /api/v1/passages?id=}, #452): der Absatz mit je einem Nachbarn oder,
     * wenn es ihn nicht mehr gibt, alle aktuellen Absätze und der Grund ({@link PassageLookup#match()}). Wie im Dienst
     * antworten eine nicht sichtbare Seite und eine unbekannte Quelle gleich ({@link CacheMissException}), damit eine ID
     * nicht verrät, ob es die Seite gibt.
     *
     * @throws InvalidQueryException wenn {@code passageId} keine Passage-ID ist
     */
    public PassageLookup passage(String passageId) {
        if (!PassageId.looksLike(passageId)) {
            throw new InvalidQueryException("Keine Passage-ID");
        }
        PassageId requested = PassageId.parse(passageId);
        try {
            return harvester.passages().resolve(caller, passageId);
        } catch (AccessDeniedException | UnknownSourceException e) {
            throw new CacheMissException(requested.sourceId(), "Seite nicht vorhanden oder nicht sichtbar");
        }
    }

    /** Die nicht abgelehnten Beziehungen, die einen der Begriffe einer Seite berühren. */
    public List<TermRelation> termRelationsFor(java.util.Collection<String> normalized, String sourceId,
                                               String spaceKey) {
        return harvester.termRelations().forTerms(caller, normalized, sourceId, spaceKey);
    }

    // ------------------------------------------------------------------ Synonyme (#336)

    /** Was ein Synonymlauf im Umfang könnte: Chat-Modell, freigegebene und nicht freigegebene Quellen, Budget. */
    public SynonymDiscovery.Availability synonymAvailability(SynonymScope scope) {
        return harvester.synonymDiscovery().availability(scope);
    }

    /**
     * „Synonyme vorschlagen“ ({@code POST …/term-relations/refresh} mit {@code kind=synonyms}): nur auf Klick, nie
     * beim Start oder Öffnen. Der Lauf erscheint wie jeder Auftrag in Aktivitäten und Statuszeile.
     *
     * @param maxJudgedPairs höchstens so viele Paare an das Chat-Modell, {@code null} für den Wert der Konfiguration
     */
    public JobStatus startSynonymDiscovery(SynonymScope scope, Integer maxJudgedPairs) {
        return startSynonymDiscovery(new SynonymDiscovery.Request(scope, maxJudgedPairs));
    }

    /** Wie {@link #startSynonymDiscovery(SynonymScope, Integer)} mit allen Angaben des Formulars (K3). */
    public JobStatus startSynonymDiscovery(SynonymDiscovery.Request request) {
        JobStatus status = harvester.synonymDiscovery().start(caller, request);
        activities.remember(status.id());
        return status;
    }

    /** Stand eines eigenen Synonymlaufs ({@code GET …/refresh-jobs/{id}}); andere Aufträge gelten als unbekannt. */
    public JobStatus synonymJob(String jobId) {
        if (!harvester.synonymDiscovery().started(jobId)) {
            throw new UnknownJobException(jobId);
        }
        // Der Lauf gehört dem Aufrufer der App, nicht dem Core-Eigentümer der Abgleiche.
        return harvester.jobs().status(jobId, caller.jobOwner());
    }

    /**
     * Meldet einen Empfänger für den Synonymlauf {@code jobId} an (ADR 0027): jeder neue Stand, auf dem
     * Zustell-Thread des Core. Die Läufe gehören dem Aufrufer der App, deshalb nicht über {@link #subscribeJobs}.
     * Erst anmelden, dann {@link #synonymJob} lesen.
     */
    public Subscription subscribeSynonymJob(String jobId, java.util.function.Consumer<JobStatus> listener) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(listener, "listener");
        return harvester.jobs().subscribe(caller.jobOwner(), status -> {
            if (jobId.equals(status.id())) {
                listener.accept(status);
            }
        });
    }

    /** Synonymlauf abbrechen; bisherige Vorschläge bleiben. */
    public JobStatus cancelSynonymJob(String jobId) {
        synonymJob(jobId);
        return harvester.jobs().cancel(jobId, caller.jobOwner());
    }

    /**
     * Vorschläge aus den Ausschreibungen der Akronyme einer Quelle ({@code POST …/rules/acronyms?sourceId=}); die App
     * bietet dafür keinen Knopf an, die Vorschläge stehen nur in der Liste.
     */
    public TermRelations.ProposalRun proposeAcronymRelations(String sourceId) {
        return harvester.acronymRelationRule().run(caller, sourceId);
    }

    /**
     * Das Fachglossar (#122 Stufe 2, Reiter „Fachglossar“ unter Analysen) des Core mit den Rechten dieser App
     * ({@code Harvester.glossary()}, {@code glossaryClassification()}); die Rückgabe bleibt {@code Optional}, damit der
     * Reiter ohne Unterbau „noch nicht verfügbar“ zeigen kann.
     */
    public Optional<GlossaryAccess> glossary() {
        return Optional.of(glossary);
    }

    /**
     * Suchmethode „Nur Fachbegriffe“ (K5b, {@code GET /api/v1/glossary/technical-terms}): vorgeschlagene und bestätigte
     * Fachbegriffe ohne Einordnung technisch, deren Begriff oder eines ihrer Synonyme zum Suchtext passt.
     */
    public com.aresstack.spaceharvester.application.glossary.TechnicalTerms.Page technicalTerms(String q,
            com.aresstack.spaceharvester.application.glossary.TechnicalTerms.Scope scope, int limit, String cursor) {
        return harvester.technicalTerms().search(caller, q, scope, limit, cursor);
    }

    /** Die nicht abgelehnten Synonyme eines Fachbegriffs, deren Bereich ihn abdeckt (K5b). */
    public List<com.aresstack.spaceharvester.application.terms.TermRelation> technicalSynonyms(
            com.aresstack.spaceharvester.application.glossary.GlossaryEntry term) {
        return harvester.technicalTerms().synonymsOf(caller, term);
    }

    /** Fügt einem Fachbegriff ein Synonym global hinzu oder bestätigt ein vorhandenes gleiches (K5b). */
    public com.aresstack.spaceharvester.application.terms.TermRelation addTechnicalSynonym(
            com.aresstack.spaceharvester.application.glossary.GlossaryEntry term, String synonym) {
        return harvester.technicalTerms().addSynonym(caller, term, synonym);
    }

    /** Der Prüfstatus eines Glossareintrags, leer, wenn es ihn nicht (mehr) gibt oder er verborgen ist. */
    public Optional<com.aresstack.spaceharvester.application.terms.ReviewStatus> glossaryStatus(String id) {
        try {
            return Optional.of(harvester.glossary().entry(caller, id).status());
        } catch (com.aresstack.spaceharvester.application.glossary.UnknownGlossaryEntryException e) {
            return Optional.empty();
        }
    }

    /**
     * Meldet jede Änderung an den Begriffsbeziehungen (ADR 0027), aus beliebigem Thread; kein Abfragen in Abständen.
     *
     * @return meldet den Beobachter wieder ab
     */
    public Runnable subscribeTermRelations(Runnable onChange) {
        return harvester.termRelations().subscribe(Objects.requireNonNull(onChange, "onChange"));
    }

    /** Bestätigen ({@code POST …/{id}/confirm?revision=}); wirft bei fremdem Stand {@code RelationConflictException}. */
    public TermRelation confirmRelation(String id, long revision) {
        return harvester.termRelations().confirm(caller, id, revision);
    }

    /** Ablehnen ({@code POST …/{id}/reject?revision=}): bleibt als abgelehnt stehen und kommt nicht wieder. */
    public TermRelation rejectRelation(String id, long revision) {
        return harvester.termRelations().reject(caller, id, revision);
    }

    /** Entfernen ({@code DELETE …/{id}?revision=}): eine manuelle löscht der Core, jede andere lehnt er ab. */
    public Optional<TermRelation> removeRelation(String id, long revision) {
        return harvester.termRelations().remove(caller, id, revision);
    }

    /**
     * Legt eine Beziehung manuell an ({@code POST /api/v1/term-relations}): {@code MANUAL} und {@code CONFIRMED};
     * gibt es den Schlüssel schon, bestätigt der Core ihn.
     */
    public TermRelation addRelation(TermRelations.NewRelation relation) {
        return harvester.termRelations().add(caller, relation);
    }

    /** Hebt eine Ablehnung auf ({@code POST …/{id}/restore}): automatisch wieder Vorschlag, manuell bestätigt. */
    public TermRelation restoreRelation(String id) {
        return harvester.termRelations().restore(caller, id, null);
    }

    /** Bestätigen ohne Revisionsprüfung, für das Rückgängigmachen eines eben entfernten Eintrags. */
    public TermRelation confirmRelation(String id) {
        return harvester.termRelations().confirm(caller, id, null);
    }

    /** Ein Stapel Seitenmetadaten eines Space, live ({@code …/spaces/{key}/pages/metadata}). */
    public PageMetadataListing liveMetadata(String sourceId, String spaceKey, String cursor, int limit) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
        PageMetadataListing listing = harvester.sources().pageMetadata(sourceId, spaceKey, cursor, limit);
        List<PageMetadataListing.Entry> visible = access.filter(caller, listing.entries(), AccessKind.METADATA,
                entry -> ResourceScope.page(sourceId, entry.page().partitionKey(), entry.page().pageId()));
        return new PageMetadataListing(listing.sourceId(), listing.partitionKey(), visible, listing.nextCursor(),
                listing.retrievedAt());
    }

    /** Metadaten einer Seite samt Hashes, live ({@code …/pages/{pageId}/metadata}). */
    public SinglePageMetadata liveMetadata(String sourceId, String pageId) {
        if (access.permits(caller, ResourceScope.page(sourceId, null, pageId), AccessKind.METADATA)) {
            return harvester.sources().pageMetadata(sourceId, pageId);
        }
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        SinglePageMetadata metadata = harvester.sources().pageMetadata(sourceId, pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, metadata.entry().page().partitionKey(), pageId),
                AccessKind.METADATA);
        return metadata;
    }

    /** Anhangsliste einer Seite, live ({@code …/pages/{pageId}/attachments}). */
    public PageAttachmentList attachments(String sourceId, String pageId) {
        return visibleAttachments(sourceId, pageId, AccessKind.METADATA);
    }

    // ------------------------------------------------------------------ Inhalte, Cache

    /** Flache Seitenliste aus dem Cache ({@code …/spaces/{key}/pages}). */
    public PageListing cachedPages(String sourceId, String spaceKey, String cursor, int limit) {
        return harvester.pages().pages(caller, sourceId, spaceKey, cursor, limit);
    }

    /** Seitenbaum aus dem Cache ({@code …/spaces/{key}/tree}). */
    public SpaceTree tree(String sourceId, String spaceKey) {
        return harvester.pages().tree(caller, sourceId, spaceKey);
    }

    /**
     * Lesetext einer gelieferten Seite wie in Anreicherung und Index des Core, damit Fundstellen, Absätze und
     * Navigation auf denselben Zeichenpositionen liegen; {@code null} ohne Lesetext oder bei kaputter Payload.
     */
    public PageText pageText(PageSnapshot snapshot) {
        try {
            return harvester.pageText(snapshot).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Seite mit nativer Payload ({@code …/pages/{pageId}}, {@code refresh=true} prüft gegen die Quelle). */
    /** Read one bounded media range after the Core has verified resource access. */
    public byte[] mediaRange(String sourceId, String pageId, long offset, int maximumBytes) {
        return harvester.mediaRange(sourceId, pageId, offset, maximumBytes);
    }

    /** Authorize a single media-viewer session without exposing source credentials. */
    public com.aresstack.spaceharvester.application.sources.MediaByteRange openMediaRange(
            String sourceId, String pageId) {
        return harvester.openMediaRange(sourceId, pageId);
    }

    public DeliveredPage page(String sourceId, String pageId, boolean refresh) {
        return harvester.pages().page(caller, sourceId, pageId, refresh);
    }

    /** Build a read-only query view of current COBOL artifacts in one configured FTP source. */
    public com.aresstack.spaceharvester.app.runtime.program.StoredCobolSearch.Snapshot storedCobolSearch(
            String sourceId, java.util.function.BooleanSupplier cancelled) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        var config = harvester.sourceManagement().sources().stream()
                .filter(source -> source.id().equals(sourceId) && "ftp".equalsIgnoreCase(source.type()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Keine FTP-Wissensquelle"));
        if (!sourceEnabled(sourceId) || sourceDataBeingDeleted(sourceId)) {
            throw new IllegalStateException("Wissensquelle nicht verfügbar");
        }
        var seen = new java.util.HashSet<com.aresstack.spaceharvester.application.snapshot.PageKey>();
        var snapshot = storedCobol().build(sourceId, syslib(config),
                visitor -> visitProgramHeaders(sourceId, seen, visitor, cancelled), cancelled);
        programFacts.retain(sourceId, seen);
        return snapshot;
    }

    private com.aresstack.spaceharvester.app.runtime.program.StoredCobolSearch storedCobol() {
        return new com.aresstack.spaceharvester.app.runtime.program.StoredCobolSearch(
                key -> cachedPage(key.sourceId(), key.pageId()), this::enrichments, programFacts);
    }

    private static List<String> syslib(SourceSettingsConfig config) {
        return com.aresstack.spaceharvester.app.runtime.program.StoredCopybooks.libraries(config.options().get("syslib"));
    }

    /** Visit the stored headers of a program source and note every page seen, so the memo can drop vanished ones. */
    private void visitProgramHeaders(String sourceId,
                                     java.util.Set<com.aresstack.spaceharvester.application.snapshot.PageKey> seen,
                                     java.util.function.Consumer<com.aresstack.spaceharvester.application.snapshot.PageHeader> visitor,
                                     java.util.function.BooleanSupplier cancelled) {
        harvester.pages().visitCachedHeaders(caller, sourceId, header -> {
            seen.add(header.key());
            visitor.accept(header);
        }, cancelled);
    }

    public java.util.Set<String> programChatSources() {
        return programSearchSources().stream().filter(harvester.aiRelease()::sourceReleased)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public List<String> programSearchSources() {
        return sources().stream()
                .filter(source -> "ftp".equalsIgnoreCase(source.type()) || "ndv".equalsIgnoreCase(source.type()))
                .filter(source -> sourceEnabled(source.id()) && !sourceDataBeingDeleted(source.id())
                        && access.permits(caller, ResourceScope.source(source.id()), AccessKind.CONTENT))
                .map(source -> source.id()).sorted().toList();
    }

    /**
     * Whether a search result may still be shown or opened as current (#1486): its source is still a readable program
     * source, the location has the same content, and every origin passes {@link #programAnalyses()}, including the
     * exact analysis the result was built from. Fails closed.
     */
    public boolean programFactCurrent(com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch.Fact fact) {
        var available = new java.util.HashSet<>(programSearchSources());
        var location = fact.location();
        if (!available.contains(location.key().sourceId())
                || fact.evidence().stream().anyMatch(origin -> !available.contains(origin.key().sourceId()))) return false;
        var access = programAnalyses();
        return access.current(new com.aresstack.spaceharvester.app.runtime.program.StoredCobolSearch.Origin(
                location.key(), location.rawSha256())) && access.current(fact.evidence());
    }

    /**
     * The single gate for stored program analyses of this caller: viewer, program search and chat tools read them
     * only through it, so authorization and currency of all evidence documents are checked the same way (#1486).
     */
    public com.aresstack.spaceharvester.app.runtime.program.ProgramAnalysisAccess programAnalyses() {
        return new com.aresstack.spaceharvester.app.runtime.program.ProgramAnalysisAccess(
                key -> cachedPage(key.sourceId(), key.pageId()),
                page -> harvester.enrichments().view(page.snapshot()),
                harvester.enrichments()::evidence,
                (page, business, views) -> cobolBusiness().visible(page, business, views));
    }

    /** Combine authorized, already stored program analyses across the requested sources. */
    public com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch.Snapshot storedProgramSearch(
            java.util.Set<String> sourceIds, java.util.function.BooleanSupplier cancelled) {
        var search = new com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch(
                key -> cachedPage(key.sourceId(), key.pageId()), this::enrichments, programFacts);
        var configured = harvester.sourceManagement().sources();
        programFacts.retainSources(configured.stream().map(source -> source.id()).toList());
        for (String sourceId : sourceIds.stream().sorted().toList()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException();
            }
            access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
            var config = configured.stream().filter(source -> source.id().equals(sourceId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unbekannte Wissensquelle"));
            boolean ndv = "ndv".equalsIgnoreCase(config.type());
            if (!ndv && !"ftp".equalsIgnoreCase(config.type())) throw new IllegalArgumentException("Keine Programmquelle");
            if (!sourceEnabled(sourceId) || sourceDataBeingDeleted(sourceId)) {
                throw new IllegalStateException("Wissensquelle nicht verfügbar");
            }
            // One traversal per source: COBOL and JCL of an FTP source share every page load (#1486, P2).
            var seen = new java.util.HashSet<com.aresstack.spaceharvester.application.snapshot.PageKey>();
            if (ndv) {
                search.addSource(sourceId, true, visitor -> visitProgramHeaders(sourceId, seen, visitor, cancelled), cancelled);
            } else {
                search.addFtp(sourceId, storedCobol(), syslib(config),
                        visitor -> visitProgramHeaders(sourceId, seen, visitor, cancelled), cancelled);
            }
            programFacts.retain(sourceId, seen);
        }
        return search.snapshot();
    }

    /**
     * Die lokal gespeicherte Seite, nur aus dem Cache und ohne Abruf oder Prüfung bei der Quelle; leer, wenn sie
     * nicht lokal liegt oder nicht lesbar ist. Das Wissen des Chats liest so (ADR 0028: nur Lokales geht an die KI).
     */
    public Optional<DeliveredPage> cachedPage(String sourceId, String pageId) {
        return cachedPage(caller, new PageKey(sourceId, pageId));
    }

    private Optional<DeliveredPage> cachedPage(AccessContext reader, PageKey key) {
        try {
            return harvester.pages().cached(reader, key.sourceId(), key.pageId());
        } catch (AccessDeniedException | UnknownSourceException e) {
            return Optional.empty();
        }
    }

    /**
     * Das Original einer Datei über der Größengrenze des Caches zum Anzeigen, direkt aus der Quelle und ohne es
     * abzulegen (Angelo, 20.11.2026: etwa ein Fotoprotokoll als PNG mit 22 MB).
     */
    public byte[] original(String sourceId, PageMetadata metadata) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        return harvester.snapshots().original(sourceId, metadata);
    }

    /**
     * Lädt das Original einer Datei direkt aus der Quelle in die Datei {@code target}, ohne Größengrenze und ohne es
     * abzulegen: „Speichern“ und „Öffnen“ im Viewer für Dateien über der Größengrenze (Angelo, 20.11.2026).
     */
    public void downloadOriginal(String sourceId, PageMetadata metadata, java.nio.file.Path target) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.CONTENT);
        harvester.snapshots().downloadOriginal(sourceId, metadata, target);
    }

    /**
     * Die Dokumentanalyse der Fristen-Fachlogik (ADR 0035): Ergebnisse je Dokumentstand, Analysieren als Auftrag; die
     * Ablage (E-Akte) schließt das Command Deck an.
     */
    public com.aresstack.spaceharvester.application.deadlines.DeadlineAnalysisService documentAnalyses() {
        return harvester.documentAnalyses();
    }

    /**
     * Lese-Ansicht einer Seite ({@code …/pages/{pageId}/reading-view}, #176): gerendertes HTML der Quelle mit den
     * Bildern, die sie zeigt; Rechte und Version prüft der Core.
     */
    public ReadingView readingView(String sourceId, String pageId, boolean refresh) {
        return harvester.readingViews().view(caller, sourceId, pageId, refresh);
    }

    /**
     * Lese-Ansicht nur aus Speicher und Cache, ohne die Quelle zu fragen ({@code ReadingViews.cached}): sofort zeigen,
     * dann {@link #readingView} mit {@code refresh=false} im Hintergrund prüfen und nur bei anderer Version tauschen
     * (Angelo 17.11.2026). Leer, wenn nichts Passendes lokal liegt oder die Seite nicht lesbar ist.
     */
    public Optional<ReadingView> cachedReadingView(String sourceId, String pageId) {
        try {
            return harvester.readingViews().cached(caller, sourceId, pageId);
        } catch (AccessDeniedException | UnknownSourceException e) {
            return Optional.empty();
        }
    }

    /**
     * Schedule direct native resources through the shared acquisition and index pipeline. Stops at the first failure
     * of the source itself (refused login, unreachable host), so one visit never repeats a failing login per member.
     * Records the acquisition reason "navigation" for every scheduled resource (ADR 0037, point 3).
     */
    public void acquireVisitedResources(String sourceId, java.util.Collection<String> paths) {
        HarvestSource source = sources().stream().filter(candidate -> candidate.id().equals(sourceId))
                .findFirst().orElse(null);
        if (source == null) {
            return;
        }
        java.util.Set<PageKey> keys = new java.util.LinkedHashSet<>();
        for (String path : paths) {
            Optional<String> pageId;
            try {
                pageId = source.navigationPageId(path);
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(Workspace.class).warn(
                        "Navigation acquisition stopped for source {}: {}", sourceId, failure.getMessage());
                break;
            }
            if (pageId.isEmpty()) continue;
            try {
                access.require(caller, ResourceScope.page(sourceId, sourceId, pageId.get()), AccessKind.CONTENT);
                keys.add(new PageKey(sourceId, pageId.get()));
            } catch (AccessDeniedException e) {
                // This caller may not read this one page; the source itself is fine.
            }
        }
        if (!keys.isEmpty()) {
            // Erwerbsgrund „Navigation“ merken (ADR 0037, Punkt 3); Bereich ist bei FTP/NDV die Quelle selbst.
            harvester.navigationMarks().mark(sourceId, sourceId, keys.stream().map(PageKey::pageId).toList());
            harvester.pageLoads().join(caller, "Besuchte Quelltexte laden", keys);
        }
    }

    /**
     * Lädt die Lese-Ansichten dieser Seiten im Hintergrund vor, etwa die Kinder eines eben aufgeklappten Knotens
     * ({@code ReadingViews.prefetch}); kehrt sofort zurück.
     */
    public void prefetchReadingViews(String sourceId, java.util.Collection<String> pageIds) {
        harvester.readingViews().prefetch(caller, sourceId, pageIds);
    }

    /**
     * Ein Bild einer Lese-Ansicht aus der Ablage oder einmalig über die Anmeldung der Quelle geladen und abgelegt
     * ({@link Assets#viewImage}). Nur Bilder, die die Ansicht des Aufrufers selbst nennt.
     */
    public Assets.AssetContent viewImage(ReadingView view, HarvestSource.ViewImage image) {
        PageHeader header = view.header();
        if (view.status() != ReadingView.Status.AVAILABLE || !view.images().contains(image)) {
            throw new IllegalArgumentException("Das Bild gehört nicht zu dieser Lese-Ansicht");
        }
        access.require(caller, ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId()), AccessKind.CONTENT);
        return harvester.assets().viewImage(header.key().sourceId(), header.key().pageId(), view.version(), image,
                Harvester.OWNER);
    }

    /** Der Stand der Hintergrundbefüllung (ADR 0016, {@code Harvester.acquisitionStatus}); nicht auf dem EDT. */
    public java.util.Optional<Acquisition> acquisition() {
        return java.util.Optional.of(Acquisition.of(harvester.acquisitionStatus()));
    }

    /**
     * Meldet einen Empfänger für den Stand der Hintergrundbefüllung an (ADR 0027), statt {@link #acquisition()} in
     * Abständen zu lesen: nach jeder Änderung von Warteschlange, Abrufen, Zählern, Schalter oder Pause der jüngste Stand,
     * gebündelt, auf dem Zustell-Thread des Core (nie auf dem EDT). Erst anmelden, dann {@link #acquisition()} lesen.
     */
    public Subscription subscribeAcquisition(java.util.function.Consumer<Acquisition> listener) {
        Objects.requireNonNull(listener, "listener");
        return harvester.subscribeAcquisition(status -> listener.accept(Acquisition.of(status)));
    }

    /**
     * Das draw.io-Diagramm hinter einem Vorschaubild der Lese-Ansicht als PNG des Core ({@link Assets#viewDiagram},
     * #190); leer, wenn das Bild keine solche Vorschau ist oder sich nicht zeichnen lässt, dann gilt das Bild selbst.
     */
    public java.util.Optional<Assets.AssetContent> viewDiagram(ReadingView view, HarvestSource.ViewImage image) {
        PageHeader header = view.header();
        if (view.status() != ReadingView.Status.AVAILABLE || !view.images().contains(image)) {
            throw new IllegalArgumentException("Das Bild gehört nicht zu dieser Lese-Ansicht");
        }
        access.require(caller, ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId()), AccessKind.CONTENT);
        return harvester.assets().viewDiagram(header.key().sourceId(), header.key().pageId(), view.version(), image);
    }

    /**
     * Das draw.io-Diagramm hinter einem Bild der Lese-Ansicht als Vektorzeichnung ({@link Assets#viewDiagramSvg}):
     * Leseansicht und Bildbetrachter zeichnen es bei jedem Maßstab scharf, statt ein Rasterbild zu vergrößern. Leer,
     * wenn das Bild kein draw.io-Diagramm ist oder sich nicht zeichnen lässt; dann gilt {@link #viewDiagram} bzw. das Bild.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.diagrams.VectorDiagram> viewDiagramVector(
            ReadingView view, HarvestSource.ViewImage image) {
        PageHeader header = view.header();
        if (view.status() != ReadingView.Status.AVAILABLE || !view.images().contains(image)) {
            throw new IllegalArgumentException("Das Bild gehört nicht zu dieser Lese-Ansicht");
        }
        access.require(caller, ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId()), AccessKind.CONTENT);
        return harvester.assets().viewDiagramSvg(header.key().sourceId(), header.key().pageId(), view.version(), image)
                .flatMap(svg -> com.aresstack.spaceharvester.application.diagrams.VectorDiagram.of(svg.content()));
    }

    /**
     * Das gezeichnete Mermaid-Diagramm einer Lese-Ansicht ({@link Assets#rendition}): einmal gezeichnet und abgelegt,
     * dieselben Quelldaten werden nie erneut gezeichnet (#190). Leer, wenn es sich nicht zeichnen lässt.
     */
    public java.util.Optional<byte[]> mermaid(ReadingView view, String source) {
        PageHeader header = view.header();
        if (view.status() != ReadingView.Status.AVAILABLE) {
            throw new IllegalArgumentException("Ohne Lese-Ansicht keine Diagramme");
        }
        access.require(caller, ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId()), AccessKind.CONTENT);
        Assets assets = harvester.assets();
        Assets.Rendered rendered = assets.rendition(header.key().sourceId(), header.key().pageId(),
                Assets.MERMAID_RENDITION, source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (rendered.asset() == null) {
            return java.util.Optional.empty();
        }
        return assets.stored(header.key().sourceId(), rendered.asset().attachmentId(), rendered.asset().version(),
                rendered.asset().sha256());
    }

    /**
     * Probleme der Einstellungen einer Quelle aus Sicht des laufenden Core ({@code SourceManagement.validate}, #188),
     * etwa dasselbe Ziel wie eine vorhandene Quelle unter neuer ID; leer, wenn sie sich übernehmen lassen.
     *
     * @param replacing  {@code true} beim Bearbeiten der Quelle mit derselben ID
     * @param configured alle in der Datei eingerichteten Quellen, auch die, die der Core nicht geöffnet hat (warten auf
     *                   ihr Geheimnis, beim Start abgelehnt); ihre IDs und Ziele sind ebenso belegt
     */
    public List<String> sourceProblems(SourceSettingsConfig draft, boolean replacing,
                                       java.util.Collection<SourceSettingsConfig> configured) {
        return harvester.sourceManagement().validate(draft, replacing, configured);
    }

    /**
     * Löst eine Adresse über alle Quellen auf ({@code Harvester.addresses().resolve}, #178): Seiten-URL in jeder Form
     * der Quelle, Lesezeichen {@code space-harvester://…}, Confluence-Bookmark oder Ressourcen-ID. Nur Seiten, die der
     * Bediener lesen darf.
     */
    public PageAddresses.Resolution resolveAddress(String address) {
        return harvester.addresses().resolve(caller, address);
    }

    /**
     * Verbindungsdiagnose einer Quelle in Stufen (Proxy-Route, Namensauflösung, TCP, TLS, Anmeldung, API) vom Core
     * ({@code SourceManagement.diagnose}, #189); leer, wenn der Core die Quelle nicht kennt (abgeschaltet oder
     * wartet auf ihr Geheimnis). Blockiert, also nur im Hintergrund.
     */
    public Optional<SourceDiagnosis> diagnose(String sourceId) {
        return harvester.sourceManagement().diagnose(sourceId);
    }

    /**
     * Zertifikatsexport einer Quelle als ZIP in den Ordner ({@code SourceManagement.exportCertificates}, Strang ZX);
     * leer, wenn der Core die Quelle nicht kennt. Blockiert, also nur im Hintergrund.
     */
    public Optional<com.aresstack.spaceharvester.application.sources.CertificateExport> exportCertificates(
            String sourceId, java.nio.file.Path directory, java.util.function.BooleanSupplier cancelled)
            throws java.io.IOException {
        return harvester.sourceManagement().exportCertificates(sourceId, directory, cancelled);
    }

    /** Nur die QR-Codes der vertrauten Zertifikate als Bilder in ein ZIP (Strang ZX). Blockiert. */
    public Optional<com.aresstack.spaceharvester.application.sources.CertificateExport> exportCertificateCodes(
            String sourceId, java.nio.file.Path directory, java.util.function.BooleanSupplier cancelled)
            throws java.io.IOException {
        return harvester.sourceManagement().exportCertificateCodes(sourceId, directory, cancelled);
    }

    /** Die vertrauten Zertifikate der Quelle als QR-Codes zur Anzeige (Strang ZX). Blockiert. */
    public Optional<java.util.List<com.aresstack.spaceharvester.application.sources.CertificateCode>>
            certificateCodes(String sourceId, java.util.function.BooleanSupplier cancelled) {
        return harvester.sourceManagement().certificateCodes(sourceId, cancelled);
    }

    /** Ob die Quelle im Core eingeschaltet ist ({@code SourceManagement.enabled}); {@code false} auch für unbekannte. */
    public boolean sourceEnabled(String sourceId) {
        return harvester.sourceManagement().enabled(sourceId);
    }

    /**
     * Übernimmt geänderte Einstellungen einer Quelle in den laufenden Core ({@code SourceManagement.change}, #188).
     * Zeigt sie danach auf ein anderes Ziel (Typ oder Adresse), entfernt der Core ihre bisherigen Daten.
     *
     * @return ob der Core die bisherigen Daten der Quelle entfernt hat
     */
    public boolean changeSource(SourceSettingsConfig config) {
        return harvester.sourceManagement().change(config);
    }

    /**
     * Entfernt eine Quelle aus dem laufenden Core samt Seiten-Cache, Suchindex, Anreicherungen und Bildern; offene
     * Aufträge der Quelle bricht er ab, Exporte bleiben ({@code SourceManagement.remove}, #188).
     */
    public boolean removeSource(String sourceId) {
        return harvester.sourceManagement().remove(sourceId);
    }

    /**
     * Der Auftrag, der die Daten einer entfernten Quelle dieser ID noch löscht (Fundstelle 22); solange lässt sich die
     * Kennung nicht neu vergeben.
     */
    public java.util.Optional<JobStatus> sourceRemovalPending(String sourceId) {
        return harvester.sourceManagement().removalPending(sourceId);
    }

    /**
     * Die Quellen, deren Daten nach dem Entfernen noch gelöscht werden, je mit dem Auftrag; aus dem Speicher des Core,
     * den die Auftragsmeldungen pflegen, darf also auf dem EDT laufen.
     */
    public java.util.Map<String, JobStatus> sourceRemovalsPending() {
        return harvester.sourceManagement().removalsPending();
    }

    /**
     * Ob die Daten der Quelle gerade gelöscht werden (entfernt oder umgestellt), auch angehalten oder gescheitert mit
     * ausstehender Wiederholung; aus dem Speicher des Core.
     */
    public boolean sourceDataBeingDeleted(String sourceId) {
        return harvester.sourceManagement().withdrawalOpen(sourceId) != null;
    }

    /** Ob {@code job} ein offenes Löschen der Daten einer Quelle ist; das bleibt, bis es gelingt oder abbricht. */
    public boolean openWithdrawal(JobStatus job) {
        return job.sourceId() != null && job.id().equals(harvester.sourceManagement().withdrawalOpen(job.sourceId()));
    }

    /**
     * „Daten bereinigen“: löscht als Auftrag alle lokal gespeicherten Daten dieser einen Quelle (Seiten, Katalog,
     * Suchindex, Analysen, Anhänge, Begriffsbeziehungen, geprüfte Fakten, Aussagen); die Quelle bleibt eingerichtet,
     * andere Quellen bleiben unberührt ({@code SourceManagement.purge}, {@code DELETE /api/v1/sources/{id}/data}).
     * Der Fortschritt erscheint wie bei jedem Auftrag in Aktivitäten und Statuszeile.
     */
    public JobStatus purgeSourceData(String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.SYNC);
        JobStatus status = harvester.sourceManagement().purge(sourceId, Harvester.OWNER);
        activities.remember(status.id());
        return status;
    }

    /**
     * Was ein Paket „JSON mit Medien“ enthält und ob der Space in dieser Quelle schon Daten hat, vor dem Import
     * ({@code ExportPackageImport.preview}).
     */
    public ExportPackageImport.Preview previewPackage(Path file, String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.SYNC);
        return harvester.packageImport().preview(file, sourceId);
    }

    /**
     * Importiert ein Paket „JSON mit Medien“ als Auftrag in den Bestand dieser Quelle, ohne die Quelle zu fragen
     * ({@code ExportPackageImport.start}, {@code POST /api/v1/sources/{id}/data/imports}); braucht wie das
     * Abgleichen die sync-Freigabe. Der Fortschritt erscheint wie bei jedem Auftrag in Aktivitäten und Statuszeile.
     *
     * @param replace ob vorhandene Daten des Space ersetzt werden dürfen
     */
    public JobStatus importPackage(Path file, String sourceId, boolean replace) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.SYNC);
        JobStatus status = harvester.packageImport().start(file, sourceId, replace, Harvester.OWNER);
        activities.remember(status.id());
        return status;
    }

    /**
     * Importiert mehrere Pakete „JSON mit Medien“ in einem Auftrag nacheinander in den Bestand dieser Quelle
     * ({@code ExportPackageImport.start(List, …)}); ein unpassendes Paket wird übersprungen.
     */
    public JobStatus importPackages(List<Path> files, String sourceId, boolean replace) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.SYNC);
        JobStatus status = harvester.packageImport().start(files, sourceId, replace, Harvester.OWNER);
        activities.remember(status.id());
        return status;
    }

    /** Seite zu einer Quell-URL mit Seiten-ID ({@code …/pages/by-url}). */
    public DeliveredPage pageByUrl(String sourceId, String url, boolean refresh) {
        return harvester.pages().pageByUrl(caller, sourceId, url, refresh);
    }

    /** Read local section analyses only after page content access has been checked. */
    public Optional<com.aresstack.spaceharvester.application.enrichment.LocalReaderAnalyses.Result> localReading(
            DeliveredPage page) {
        return harvester.enrichments().localReading(page.snapshot());
    }

    /** Anreicherungen einer gelieferten Seite ({@code view=extended}); das Lesen der Seite hat CONTENT geprüft. */
    public List<Enrichments.View> enrichments(DeliveredPage page) {
        // Program analyses only through the common gate (#1486); other results unchanged.
        return programAnalyses().visible(page);
    }

    private com.aresstack.spaceharvester.app.runtime.program.CobolBusinessUseCase cobolBusiness() {
        return cobolBusiness(() -> true);
    }

    private com.aresstack.spaceharvester.app.runtime.program.CobolBusinessUseCase cobolBusiness(
            java.util.function.BooleanSupplier modelUnchanged) {
        return new com.aresstack.spaceharvester.app.runtime.program.CobolBusinessUseCase(harvester.enrichments(),
                key -> cachedPage(key.sourceId(), key.pageId()),
                source -> modelUnchanged.getAsBoolean() && sourceEnabled(source) && !sourceDataBeingDeleted(source)
                        && harvester.aiRelease().sourceReleased(source),
                page -> access.require(caller, ResourceScope.space(page.header().key().sourceId(),
                        page.header().metadata().partitionKey()), AccessKind.SYNC),
                this::releasePageForChat, java.time.Clock.systemUTC());
    }

    /** Run only by an explicit request; no remote source fetch or implicit AI release. */
    public Enrichments.Run recomputeCobolBusiness(PageKey key, String rawSha256, String model,
            ModelBudget budget,
            java.util.function.BooleanSupplier modelUnchanged, java.util.function.Consumer<com.aresstack.spaceharvester.application.jobs.JobState> state) {
        var page = cachedPage(key.sourceId(), key.pageId())
                .orElseThrow(() -> new IllegalStateException("Programm nicht im Wissensbestand verfügbar."));
        if (!page.snapshot().payload().rawSha256().equals(rawSha256)) {
            throw new IllegalStateException("Programm nicht mehr aktuell.");
        }
        access.require(caller, ResourceScope.space(key.sourceId(), page.header().metadata().partitionKey()), AccessKind.SYNC);
        return harvester.pageRecomputation().execute(caller.jobOwner(), page.snapshot(),
                com.aresstack.spaceharvester.app.runtime.program.CobolBusinessEnrichment.ID, state,
                context -> {
                    var run = cobolBusiness(modelUnchanged).run(key, rawSha256, harvester::aiChat, model, budget,
                            context);
                    programStatements(key, context);
                    return run;
                });
    }

    /**
     * The fresh business hypotheses of a program as program statements in the one Fachlogik store (ADR 0041, item 7;
     * ADR 0032, update of 26.11.2026): source-location evidence from the stored analyses, read only through the gate,
     * stored by the core only while technically current. Without a sentence analyzer the hypotheses stay in the
     * program analysis alone.
     */
    private void programStatements(PageKey key, com.aresstack.spaceharvester.application.jobs.JobContext context) {
        if (!harvester.statementAnalysis().available()) return;
        var page = cachedPage(key.sourceId(), key.pageId())
                .orElseThrow(() -> new IllegalStateException("Programm nicht im Wissensbestand verfügbar."));
        var gate = programAnalyses();
        var parser = gate.current(page, com.aresstack.spaceharvester.app.runtime.program.CobolProgramEnrichment.ID);
        var business = gate.current(page, com.aresstack.spaceharvester.app.runtime.program.CobolBusinessEnrichment.ID);
        if (parser.isEmpty() || business.isEmpty()) return;
        java.util.function.Function<PageKey, Optional<DeliveredPage>> pages = other -> cachedPage(other.sourceId(),
                other.pageId());
        var mapped = com.aresstack.spaceharvester.app.runtime.program.ProgramHypothesisEvidence.of(page.snapshot(),
                parser.get(), business.get(), other -> pages.apply(other).map(DeliveredPage::snapshot));
        context.checkpoint();
        context.phase("COBOL", "Hypothesen in den Fachlogik-Bestand übernehmen");
        var stored = harvester.statementAnalysis().analyzeProgram(caller, page.snapshot(), mapped.hypotheses(),
                new com.aresstack.spaceharvester.app.runtime.program.ProgramCurrentSources(pages, gate),
                context::cancelRequested);
        int left = mapped.unmapped() + stored.rejected();
        context.record(left == 0
                ? com.aresstack.spaceharvester.application.jobs.JobEvent.Draft.info("COBOL",
                        stored.stored() + " Hypothesen als Aussagen mit Quellstellenbeleg übernommen")
                : com.aresstack.spaceharvester.application.jobs.JobEvent.Draft.warning(
                        com.aresstack.spaceharvester.application.jobs.JobEvent.Category.JOB, "COBOL",
                        "PROGRAM_STATEMENTS_LEFT_OUT", stored.stored() + " Hypothesen übernommen, " + left
                                + " ohne zuordenbaren oder aktuellen Quellstellenbeleg ausgelassen"));
    }

    /**
     * Anreicherungen neu berechnen ({@code POST …/enrichments}); braucht wie im Dienst die sync-Freigabe. Läuft als
     * Auftrag in der Auftragsliste (Fundstelle 21) und kehrt zurück, wenn er endet.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.JobCancelledException wenn der Auftrag abgebrochen wurde
     */
    public Enrichments.Run recomputeEnrichments(String sourceId, String pageId, String processor) {
        return recomputeEnrichments(sourceId, pageId, processor, state -> {
        });
    }

    /**
     * Wie {@link #recomputeEnrichments(String, String, String)}; {@code state} erfährt, ob der Auftrag auf einen freien
     * Platz wartet oder läuft (beliebiger Thread).
     */
    public Enrichments.Run recomputeEnrichments(String sourceId, String pageId, String processor,
                                                java.util.function.Consumer<
                                                        com.aresstack.spaceharvester.application.jobs.JobState> state) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        return harvester.pageRecomputation().recompute(caller.jobOwner(), page.snapshot(), processor, state);
    }

    /** Gespeicherte Bilder und Beschreibungen einer gelieferten Seite ({@code GET …/images}); beschreibt nichts. */
    public PageImages images(DeliveredPage page) {
        return harvester.imageAnalysis().read(page.snapshot());
    }

    /** Ob ein Vision-Modell eingestellt ist, das Bilder beschreiben kann. */
    public boolean imagesAvailable() {
        return harvester.imageAnalysis().available();
    }

    /** Ob die Quelle für das Vision-Modell freigegeben ist; siehe {@code PageImageAnalysis#released}. */
    public boolean imagesReleased(String sourceId) {
        return harvester.imageAnalysis().released(sourceId);
    }

    /**
     * Ob die Bilder dieser Seite an das Vision-Modell dürfen: ihre Quelle ist freigegeben oder sie steht auf der Liste
     * freigegebener Seiten (ADR 0028).
     */
    public boolean imagesReleased(String sourceId, String pageId) {
        return harvester.imageAnalysis().released(new PageKey(sourceId, pageId));
    }

    /**
     * Die Bilder einer Seite vom Vision-Modell beschreiben lassen ({@code POST …/images}, #362); braucht wie im Dienst
     * die sync-Freigabe. Läuft im aufrufenden Thread, nie auf dem EDT.
     */
    public PageImages describeImages(String sourceId, String pageId) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        // D2: der Knopf je Seite als gewöhnlicher Auftrag „Bilder beschreiben“, Vorrang wie bisher.
        PageImages images = harvester.imageDescriptions().describePage(caller.jobOwner(), page.snapshot(),
                entry -> { });
        harvester.enrichments().flush();
        return images;
    }

    /**
     * Wie {@link #describeImages(String, String)}, {@code once} wie {@link #describeImagesOnce}; {@code progress}
     * erfährt jedes Bild, sobald es beschrieben ist (im aufrufenden Thread).
     */
    public PageImages describeImages(String sourceId, String pageId, boolean once,
                                     java.util.function.Consumer<ImagePayload.Entry> progress) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        // „Nur diese Seite einmalig“ bleibt direkt: die Einmal-Erlaubnis darf keinen Auftrag überdauern.
        PageImages images = once ? harvester.imageAnalysis().describeOnce(page.snapshot(), progress)
                : harvester.imageDescriptions().describePage(caller.jobOwner(), page.snapshot(), progress);
        harvester.enrichments().flush();
        return images;
    }

    /** Ob das Vision-Modell geladen ist und sofort antwortet (bei Ollama laut {@code /api/ps}). */
    public boolean visionModelLoaded() {
        return harvester.imageAnalysis().modelLoaded();
    }

    /** Lädt das Vision-Modell und wartet darauf, höchstens die eingestellte Ladefrist. */
    public void loadVisionModel() {
        harvester.imageAnalysis().loadModel();
    }

    /**
     * Beschreibt nur das Bild an {@code index} der Bilderliste und gibt die Bilder der Seite zurück; die übrigen
     * bleiben, wie sie waren. {@code once} wie {@link #describeImagesOnce}; mit {@code fresh} neu statt aus den
     * gespeicherten Beschreibungen.
     */
    public PageImages describeImage(String sourceId, String pageId, int index, boolean once, boolean fresh) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        return harvester.imageAnalysis().describeOne(page.snapshot(), index, once, fresh);
    }

    /**
     * Die Folien der gespeicherten Fassung eines Videos: die Marken, wie der Player sie gefunden hat, ohne
     * Wiederholungen derselben Folie (sobald ihre Standbilder da sind); leer, solange er keine gefunden hat. Fragt die
     * Quelle nicht.
     */
    public List<Long> videoMarks(String sourceId, String pageId) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        return slides().find(new PageKey(sourceId, pageId), page.snapshot().revision());
    }

    /**
     * Speichert die Marken, die der Player im Video gefunden hat; je Marke steht dann ein virtueller Anhang in der
     * Anhangsliste der Seite. Braucht wie das Ablegen von Anhängen die sync-Freigabe.
     */
    public void saveVideoMarks(String sourceId, String pageId, List<Long> marks) {
        DeliveredPage page = syncedPage(sourceId, pageId);
        VideoMarks.in(harvester.enrichments().store()).save(new PageKey(sourceId, pageId), page.snapshot().revision(),
                marks);
    }

    /** Setzt, woher die Werte für den Vergleich zweier Standbilder kommen ({@code space-harvester-app.video.*}). */
    public void slideThresholds(java.util.function.Supplier<
            com.aresstack.spaceharvester.application.marks.SlideFrames.Thresholds> thresholds) {
        harvester.assets().slideThresholds(thresholds);
    }

    /** Die Folien der Videos: gespeicherte Marken ohne Wiederholungen derselben Folie. */
    private VideoMarks slides() {
        return VideoMarks.slides(harvester.enrichments().store(), harvester.assets());
    }

    /** Die Marken des Videos, deren virtueller Anhang noch kein Standbild hat. */
    public List<Long> missingVideoFrames(String sourceId, String pageId) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        String version = page.snapshot().revision();
        List<Long> marks = slides().find(new PageKey(sourceId, pageId), version);
        return harvester.assets().missingVideoFrames(sourceId, pageId, version, marks);
    }

    /**
     * Eine Fassung des Transkripts eines Videos: das Original oder eine Korrektur, die als Ergänzung daneben liegt.
     *
     * @param label Anzeigename, {@code Original} oder etwa {@code Korrektur gemma4:12b}, unvollständige mit Zusatz
     */
    public record TranscriptVersion(String label, String text) {
    }

    /**
     * Das gewählte Original-Transkript des Videos dieser Seite mit seinen Korrekturen (Angelo, 20.11.2026): zuerst das
     * Original, dann je Modell eine Korrektur; leer, solange kein Transkript erzeugt ist. Fragt die Quelle nicht.
     */
    public List<TranscriptVersion> videoTranscriptVersions(String sourceId, String pageId) {
        return videoTranscripts(sourceId, pageId).versions();
    }

    /**
     * Die Original-Transkripte eines Videos (Angelo 20.11.2026: je Whisper-Modell eines) samt den Fassungen des
     * gewählten.
     *
     * @param chosen    das gewählte Original (Whisper-Kennung, {@code ""} für ein älteres ohne Modell); {@code null}
     *                  ohne Transkript
     * @param originals alle abgelegten Originale, zuerst ein älteres ohne Modell
     * @param versions  das gewählte Original und seine Korrekturen
     */
    public record VideoTranscripts(String chosen, List<String> originals, List<TranscriptVersion> versions) {
    }

    /** Die Original-Transkripte des Videos dieser Seite und die Fassungen des gewählten. Fragt die Quelle nicht. */
    public VideoTranscripts videoTranscripts(String sourceId, String pageId) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        return videoTranscripts(sourceId, pageId, page.snapshot().revision());
    }

    private VideoTranscripts videoTranscripts(String sourceId, String pageId, String version) {
        Assets assets = harvester.assets();
        List<String> originals = assets.videoTranscriptOriginals(sourceId, pageId, version).stream()
                .map(Assets.TranscriptOriginal::whisper).toList();
        java.util.Optional<Assets.TranscriptOriginal> chosen = assets.chosenVideoTranscript(sourceId, pageId, version);
        java.util.Optional<String> original = chosen.flatMap(found -> assets.videoTranscript(sourceId, pageId, version,
                found.whisper()));
        if (original.isEmpty()) {
            return new VideoTranscripts(null, originals, List.of());
        }
        String whisper = chosen.get().whisper();
        List<TranscriptVersion> versions = new ArrayList<>();
        versions.add(new TranscriptVersion("Original", original.get()));
        for (Assets.TranscriptCorrection correction : assets.videoTranscriptCorrections(sourceId, pageId, version,
                whisper)) {
            assets.videoTranscriptText(sourceId, pageId, version, correction.attachmentId())
                    .ifPresent(text -> versions.add(new TranscriptVersion(correction.label()
                            + (correction.complete() ? "" : " (unvollständig)"), text)));
        }
        return new VideoTranscripts(whisper, originals, List.copyOf(versions));
    }

    /**
     * Wählt das Original dieses Whisper-Modells für Ansicht, Korrektur und Analysen (Angelo 20.11.2026); die Analysen
     * rechnen danach im Hintergrund neu. Braucht wie das Ablegen die sync-Freigabe.
     *
     * @return die Transkripte danach
     */
    public VideoTranscripts chooseVideoTranscript(String sourceId, String pageId, String whisper) {
        DeliveredPage page = syncedPage(sourceId, pageId);
        String version = page.snapshot().revision();
        harvester.assets().chooseVideoTranscript(sourceId, pageId, version, whisper);
        return videoTranscripts(sourceId, pageId, version);
    }

    /**
     * Die Folienkommentare einer Marke (Angelo, 20.11.2026): je Modell eine Fassung, wie sie an der virtuellen Marke
     * liegen; leer ohne. Fragt die Quelle nicht.
     */
    public List<TranscriptVersion> videoSlideNotes(String sourceId, String pageId, long millis) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        String version = page.snapshot().revision();
        List<TranscriptVersion> notes = new ArrayList<>();
        for (Assets.SlideNote note : harvester.assets().videoSlideNotes(sourceId, pageId, version)) {
            if (note.millis() == millis) {
                harvester.assets().videoSlideNoteText(sourceId, pageId, version, note.attachmentId())
                        .ifPresent(text -> notes.add(new TranscriptVersion(note.label(), text)));
            }
        }
        return List.copyOf(notes);
    }

    /** Das gewählte Original-Transkript des Videos dieser Seite; leer, solange keines erzeugt ist. Fragt die Quelle nicht. */
    public java.util.Optional<String> videoTranscript(String sourceId, String pageId) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        return harvester.assets().videoTranscript(sourceId, pageId, page.snapshot().revision());
    }

    /**
     * Speichert das Original-Transkript dieses Whisper-Modells für das Video dieser Seite, ersetzt das frühere
     * desselben Modells und wählt es (die Ansicht zeigt es gerade). Es steht dann als virtueller Anhang unter
     * „Anhänge“. Braucht wie das Ablegen von Anhängen die sync-Freigabe.
     */
    public void storeVideoTranscript(String sourceId, String pageId, String whisper, String fileName, String text) {
        DeliveredPage page = syncedPage(sourceId, pageId);
        String version = page.snapshot().revision();
        harvester.assets().putVideoTranscript(sourceId, pageId, version, whisper, fileName, text);
        harvester.assets().chooseVideoTranscript(sourceId, pageId, version, whisper);
    }

    /**
     * Löscht das Original-Transkript dieses Whisper-Modells samt allen seinen Korrekturen (Angelo 20.11.2026); die
     * Analysen der Seite fallen mit weg, auch die Aussagen-Analyse, oder rechnen über ein verbliebenes Original neu.
     * Braucht wie das Ablegen die sync-Freigabe.
     *
     * @return ob ein Transkript da war
     */
    public boolean deleteVideoTranscript(String sourceId, String pageId, String whisper) {
        DeliveredPage page = syncedPage(sourceId, pageId);
        boolean deleted = harvester.assets().deleteVideoTranscript(sourceId, pageId, page.snapshot().revision(),
                whisper);
        if (deleted) {
            harvester.transcriptAnalyses().update(page.snapshot().key());
            harvester.knowledgeGraph().delete(caller, page.snapshot().key()::equals);
        }
        return deleted;
    }

    /**
     * Meldet sich, nachdem die Analysen einer Videoseite zu ihrem Transkript neu gerechnet oder entfernt wurden
     * (ADR 0027, kein Polling); gerufen außerhalb des EDT.
     */
    public Subscription subscribeTranscriptAnalyses(java.util.function.Consumer<PageKey> listener) {
        return harvester.transcriptAnalyses().subscribe(listener);
    }

    /** Legt das Standbild einer Marke ab; es füllt ihren virtuellen Anhang unter „Anhänge“. */
    public void storeVideoFrame(String sourceId, String pageId, Assets.VideoFrame frame) {
        DeliveredPage page = syncedPage(sourceId, pageId);
        harvester.assets().putVideoFrame(sourceId, pageId, page.snapshot().revision(), frame);
    }

    /**
     * Was „In PPTX umwandeln“ für das Video dieser Seite jetzt fehlt (Modell, KI-Freigabe, Folienmarken); leer, wenn
     * es starten kann. Fragt weder Quelle noch Modell.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.presentation.PresentationUnavailableException
            .Reason> videoPresentationUnavailable(String sourceId, String pageId) {
        return harvester.videoPresentations().unavailable(caller, sourceId, pageId);
    }

    /**
     * „In PPTX umwandeln“ (Angelo 20.11.2026): startet die fünf Aufträge für das Video dieser Seite; sie erscheinen
     * einzeln unter „Aufträge“. Legt Transkript und Standbilder ab, braucht also wie diese die sync-Freigabe.
     *
     * @param media Whisper und Standbilder des geöffneten Videos
     * @return die Aufträge in der Reihenfolge des Ablaufs; der letzte erzeugt die Datei
     * @throws com.aresstack.spaceharvester.application.presentation.PresentationUnavailableException wenn etwas fehlt
     */
    public com.aresstack.spaceharvester.application.presentation.VideoPresentations.Run startVideoPresentation(
            String sourceId, String pageId,
            com.aresstack.spaceharvester.application.presentation.VideoMedia media) {
        return startVideoPresentation(sourceId, pageId, media,
                com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format.PPTX);
    }

    /**
     * Wie {@link #startVideoPresentation(String, String,
     * com.aresstack.spaceharvester.application.presentation.VideoMedia)}, mit dem Format der Datei: „In PDF umwandeln“
     * läuft durch dieselben Schritte und schreibt am Ende ein PDF; schon Vorhandenes fragt keiner der Schritte neu.
     */
    public com.aresstack.spaceharvester.application.presentation.VideoPresentations.Run startVideoPresentation(
            String sourceId, String pageId,
            com.aresstack.spaceharvester.application.presentation.VideoMedia media,
            com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format format) {
        return startVideoPresentation(sourceId, pageId, media, format, null);
    }

    /**
     * Wie oben, die Datei im Ausgabeordner der Einstellungen (Angelo 20.11.2026: Vorgabe Downloads, nie der
     * Datenordner); sie heißt wie das Video. {@code directory == null}: im Exportordner des Kerns.
     */
    public com.aresstack.spaceharvester.application.presentation.VideoPresentations.Run startVideoPresentation(
            String sourceId, String pageId,
            com.aresstack.spaceharvester.application.presentation.VideoMedia media,
            com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format format,
            java.nio.file.Path directory) {
        syncedPage(sourceId, pageId);
        com.aresstack.spaceharvester.application.presentation.VideoPresentations.Run run =
                harvester.videoPresentations().start(caller, sourceId, pageId, media,
                        harvester.videoPresentations().options(format).withDirectory(directory));
        run.all().forEach(job -> activities.remember(job.id()));
        return run;
    }

    /**
     * „Korrigieren“ in der Transkriptansicht (Angelo 20.11.2026): Transkript, Standbilder, Folien beschreiben und
     * Transkript korrigieren wie vor „In PPTX umwandeln“, ohne Folienkommentare und Datei; die Aufträge erscheinen
     * einzeln unter „Aufträge“, die Analysen rechnen danach über die neue Korrektur.
     *
     * @throws com.aresstack.spaceharvester.application.presentation.PresentationUnavailableException wenn etwas fehlt
     */
    public com.aresstack.spaceharvester.application.presentation.VideoPresentations.Run startVideoCorrection(
            String sourceId, String pageId,
            com.aresstack.spaceharvester.application.presentation.VideoMedia media) {
        syncedPage(sourceId, pageId);
        com.aresstack.spaceharvester.application.presentation.VideoPresentations.Run run =
                harvester.videoPresentations().startCorrection(caller, sourceId, pageId, media,
                        harvester.videoPresentations().options(
                                com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format.PPTX));
        run.all().forEach(job -> activities.remember(job.id()));
        return run;
    }

    /**
     * Wer nach einem Neustart die Videos für „In PPTX umwandeln“ liefert, damit Fortsetzen und „Nur Fehler“ auch dann
     * im selben Eintrag laufen (ADR 0025).
     */
    public void presentationMedia(
            com.aresstack.spaceharvester.application.presentation.VideoPresentations.MediaSource source) {
        harvester.videoPresentations().mediaSource(source);
    }

    /** Die Datei eines fertigen Auftrags „PPTX erzeugen“; leer, solange sie nicht geschrieben ist. */
    public java.util.Optional<java.nio.file.Path> presentationFile(String jobId) {
        JobStatus status = job(jobId);
        return harvester.videoPresentations().file(jobId, status.owner());
    }

    private DeliveredPage syncedPage(String sourceId, String pageId) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        return page;
    }

    /**
     * Wie {@link #describeImages}, aber nur für diese eine Seite einer nicht freigegebenen Quelle, weil der Benutzer
     * es ausdrücklich erlaubt hat („Nur diese Seite einmalig beschreiben“); speichert keine Freigabe.
     */
    public PageImages describeImagesOnce(String sourceId, String pageId) {
        DeliveredPage page = harvester.pages().page(caller, sourceId, pageId, false);
        access.require(caller, ResourceScope.space(sourceId, page.header().metadata().partitionKey()), AccessKind.SYNC);
        PageImages images = harvester.imageAnalysis().describeOnce(page.snapshot());
        harvester.enrichments().flush();
        return images;
    }

    // ------------------------------------------------------------------ Aussagen (#499, ADR 0024)

    /** Ob die App einen Satzanalysator hat (gepinntes Modell bekannt); lädt kein Modell. */
    public boolean statementAnalysisAvailable() {
        return harvester.statementAnalysis().available();
    }

    /** Gespeicherte Aussagenanalyse der Seite mit Stand; rechnet nie. */
    public PageStatements statements(PageSnapshot snapshot) {
        return harvester.statementAnalysis().read(caller, snapshot);
    }

    /**
     * „Analysieren“ im Reiter Aussagen: analysiert genau diese Seite mit dem eingestellten Analysator als Auftrag
     * „Aussagenanalyse“ (Fahrplan D3, derselbe Code wie im Baum, mit dem Vorrang eines normalen Auftrags) und wartet auf
     * sein Ende; ersetzt ihre Analyse samt Graph-Projektion. Nur auf ausdrückliche Anforderung, nie auf dem EDT.
     *
     * @param cancelled eine {@link Cancellation} bricht den Auftrag ab, sobald sie ausgelöst wird
     * @throws AnalyzerUnavailableException wenn der KI-Analysator die Seite bewusst ausließ (ohne Freigabe oder Modell)
     *                                      oder kein Analysator bereitsteht
     * @throws java.util.concurrent.CancellationException nach einem Abbruch; die alte Analyse bleibt
     */
    public PageStatements analyzeStatements(PageSnapshot snapshot, java.util.function.BooleanSupplier cancelled) {
        return analyzePage(snapshot, cancelled, null);
    }

    /**
     * Seite samt Unterseiten analysieren (Reiter Aussagen mit „mit Unterseiten“, Fahrplan D3): startet nach der
     * Rückfrage die Hintergrundgruppe „Aussagen analysieren“ mit dem eingestellten Analysator und kehrt sofort zurück;
     * schon aktuelle Seiten überspringt sie wie der Baum ohne „erneut analysieren“. Fortschritt und Abbruch unter
     * Aufträge.
     *
     * @param simplification Vorstufe „Sätze vereinfachen“ oder {@code null} ohne sie (nur mit dem MaltParser)
     * @param maxPages       Sicherheitsgrenze wie im Baum; 0 nach ausdrücklicher Bestätigung aller Seiten
     */
    public JobStatus startStatementAnalysisBelow(PageSnapshot snapshot,
            com.aresstack.spaceharvester.application.configuration.SimplificationSettings simplification,
            int maxPages) {
        AnalyzerChoice analyzer = statementAnalyzer();
        return harvester.statementAnalysisJobs().start(caller, belowScope(snapshot),
                new StatementAnalysisJobs.Options(false, maxPages, simplification == null
                        || analyzer == AnalyzerChoice.MODEL ? null : com.aresstack.spaceharvester.application
                        .knowledge.StatementAnalysis.SimplificationRequest.of(simplification), analyzer));
    }

    /** Zählt Seite samt Unterseiten für die Rückfrage vor {@link #startStatementAnalysisBelow}; nie auf dem EDT. */
    public StatementAnalysisJobs.Estimate estimateStatementAnalysisBelow(PageSnapshot snapshot, int limit) {
        return estimateStatementAnalysis(belowScope(snapshot), limit);
    }

    private static StatementAnalysisJobs.Scope belowScope(PageSnapshot snapshot) {
        return new StatementAnalysisJobs.Scope.Subtree(snapshot.key().sourceId(), snapshot.spaceKey(),
                snapshot.key().pageId());
    }

    /** Eine Seite als Auftrag rechnen und auf sein Ende warten, über Meldungen des Core, ohne Abfragen. */
    private PageStatements analyzePage(PageSnapshot snapshot, java.util.function.BooleanSupplier cancelled,
            com.aresstack.spaceharvester.application.knowledge.StatementAnalysis.SimplificationRequest stage) {
        AnalyzerChoice analyzer = statementAnalyzer();
        // Die gezeigte Fassung, ohne erneuten Abruf bei der Quelle (wie vor D3)
        JobStatus done = awaitPageJob(() -> harvester.statementAnalysisJobs().startShown(caller, snapshot,
                new StatementAnalysisJobs.Options(true, 0, analyzer == AnalyzerChoice.MODEL ? null : stage,
                        analyzer)), cancelled);
        return pageResult(snapshot, done);
    }

    /**
     * Startet den Auftrag einer Seite (Analysieren, Prüfen) und wartet auf sein Ende, über Meldungen des Core, ohne
     * Abfragen; eine {@link Cancellation} bricht ihn ab.
     *
     * @throws java.util.concurrent.CancellationException wenn er gelöscht wurde, bevor er endete, oder der Thread
     *                                                    unterbrochen wurde
     */
    private JobStatus awaitPageJob(java.util.function.Supplier<JobStatus> start,
            java.util.function.BooleanSupplier cancelled) {
        java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> id = new java.util.concurrent.atomic.AtomicReference<>();
        // Der letzte gemeldete Stand: Wird der Auftrag unter Aufträge gelöscht, gibt es ihn danach nicht mehr.
        java.util.concurrent.atomic.AtomicReference<JobStatus> last = new java.util.concurrent.atomic.AtomicReference<>();
        Subscription watch = harvester.jobs().subscribe(Harvester.OWNER, new JobListener() {
            @Override
            public void changed(JobStatus status) {
                if (status.id().equals(id.get()) && status.state().terminal()) {
                    last.set(status);
                    ended.countDown();
                }
            }

            @Override
            public void removed(JobStatus gone) {
                if (gone.id().equals(id.get())) {
                    last.compareAndSet(null, gone);
                    ended.countDown();
                }
            }
        });
        try {
            JobStatus started = start.get();
            id.set(started.id());
            if (cancelled instanceof Cancellation cancellation) {
                cancellation.onRequest(() -> {
                    try {
                        harvester.jobs().cancel(started.id(), Harvester.OWNER);
                    } catch (RuntimeException e) {
                        // schon beendet: das Ergebnis gilt
                    }
                });
            }
            JobStatus now = currentStatus(started.id()).orElse(null);
            if (now == null || now.state().terminal()) {
                last.compareAndSet(null, now);
                ended.countDown();
            }
            ended.await();
            JobStatus done = currentStatus(started.id()).orElse(last.get());
            if (done == null || !done.state().terminal()) {
                // gelöscht, bevor er endete: wie ein Abbruch, die bisherige Analyse bleibt
                throw new java.util.concurrent.CancellationException("Auftrag gelöscht");
            }
            return done;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Unterbrochen");
        } finally {
            watch.close();
        }
    }

    /** Die Ereignisse eines eigenen Auftrags; leer, wenn er schon gelöscht ist. */
    private List<JobEvent> ownEvents(String jobId) {
        try {
            return harvester.jobs().events(jobId);
        } catch (UnknownJobException e) {
            return List.of();
        }
    }

    /** Das Ende des Auftrags einer Seite als Ergebnis wie beim Rechnen im Reiter: Stand, Abbruch, Auslassen, Fehler. */
    private PageStatements pageResult(PageSnapshot snapshot, JobStatus done) {
        if (done.state() == com.aresstack.spaceharvester.application.jobs.JobState.CANCELLED
                || done.state() == com.aresstack.spaceharvester.application.jobs.JobState.PAUSED) {
            throw new java.util.concurrent.CancellationException("Abgebrochen");
        }
        List<JobEvent> known = ownEvents(done.id()); // schon gelöscht: der letzte Stand gilt
        java.util.function.Predicate<String> has = code -> known.stream().anyMatch(event -> code.equals(event.code()));
        if (done.state() == com.aresstack.spaceharvester.application.jobs.JobState.FAILED) {
            String message = done.error() == null ? "Die Analyse ist gescheitert" : done.error().message();
            if (done.error() != null && StatementAnalysisJobs.ANALYZER_UNAVAILABLE.equals(done.error().code())) {
                AnalyzerUnavailableException.Reason reason = known.stream().map(JobEvent::code)
                        .filter(code -> code != null && code.startsWith(StatementAnalysisJobs.ANALYZER_REASON))
                        .map(code -> reason(code.substring(StatementAnalysisJobs.ANALYZER_REASON.length())))
                        .filter(Objects::nonNull).findFirst()
                        .orElse(AnalyzerUnavailableException.Reason.NOT_CONFIGURED);
                throw new AnalyzerUnavailableException(reason, message, null);
            }
            throw new IllegalStateException(message);
        }
        if (has.test(StatementAnalysisJobs.MODEL_NOT_RELEASED)) {
            throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NOT_RELEASED,
                    "Die Seite hat keine KI-Freigabe", null);
        }
        if (has.test(StatementAnalysisJobs.MODEL_NO_MODEL)) {
            throw new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.NO_AI_MODEL,
                    "Kein Chat-Modell eingestellt", null);
        }
        if (done.progress().failed() > 0) {
            // Der Grund steht im Ereignis der Seite: ohne Leserecht wie früher als Zugriffsfehler, sonst seine Meldung
            JobEvent failed = known.stream().filter(event -> "PAGE_FAILED".equals(event.code())).findFirst()
                    .orElse(null);
            if (failed != null && failed.category() == JobEvent.Category.ACCESS) {
                statements(snapshot); // wirft ohne Leserecht die AccessDeniedException des Core
            }
            throw new IllegalStateException(failed == null || failed.message() == null
                    ? "Die Seite wurde nicht analysiert" : failed.message());
        }
        return statements(snapshot);
    }

    /** Der Stand eines Auftrags; leer, wenn er gelöscht ist. */
    private java.util.Optional<JobStatus> currentStatus(String jobId) {
        try {
            return java.util.Optional.of(harvester.jobs().status(jobId));
        } catch (UnknownJobException e) {
            return java.util.Optional.empty();
        }
    }

    private static AnalyzerUnavailableException.Reason reason(String name) {
        try {
            return name == null ? null : AnalyzerUnavailableException.Reason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Der eingestellte Aussagenanalysator (Einstellungen › Modelle, „Aussagenanalyse“, Fahrplan A3): aus seiner Ablage
     * lesen Aussagen, Graph, Stand, Begriffe und Export, und eine einzelne Seite rechnet er.
     */
    public AnalyzerChoice statementAnalyzer() {
        return harvester.statementAnalysis().selected();
    }

    /**
     * Ob der KI-Analysator der Aussagenanalyse jetzt rechnen könnte: eingerichtet und ein Chat-Modell bereit (W5, für
     * die Rückfrage von „Wissensgraph erzeugen“); lädt und ruft kein Modell.
     */
    public boolean statementModelReady() {
        return harvester.statementAnalysis().modelReady();
    }

    /**
     * Warum der KI-Analysator diese Seite jetzt bewusst auslässt (kein Modell, keine KI-Freigabe); leer, wenn er sie
     * rechnen kann oder der MaltParser eingestellt ist.
     */
    public java.util.Optional<AnalyzerUnavailableException.Reason> statementAnalyzerSkips(PageKey key) {
        if (statementAnalyzer() != AnalyzerChoice.MODEL) {
            return java.util.Optional.empty();
        }
        return harvester.statementAnalysis().modelUnavailable(key);
    }

    /**
     * Wie {@link #analyzeStatements(PageSnapshot, java.util.function.BooleanSupplier)}, mit der Vorstufe „Sätze
     * vereinfachen“ (ADR 0032, FL3) nach {@code simplification}; kann sie für die Seite nicht laufen
     * ({@link #simplificationUnavailable}), analysiert der Core ohne sie.
     */
    public PageStatements analyzeStatements(PageSnapshot snapshot, java.util.function.BooleanSupplier cancelled,
            com.aresstack.spaceharvester.application.configuration.SimplificationSettings simplification) {
        // Der KI-Analysator kennt keine Vorstufe; er rechnet die Seite wie ohne sie.
        return analyzePage(snapshot, cancelled, com.aresstack.spaceharvester.application.knowledge.StatementAnalysis
                .SimplificationRequest.of(simplification));
    }

    /**
     * Warum die Vorstufe „Sätze vereinfachen“ für diese Seite nicht laufen kann ({@code NO_MODEL},
     * {@code NOT_RELEASED}); leer, wenn sie laufen kann.
     */
    public Optional<String> simplificationUnavailable(PageKey key) {
        return harvester.statementAnalysis().simplificationUnavailable(key);
    }

    /**
     * „Aussagen prüfen“ für genau diese Seite (ADR 0032, FL4): Das Chat-Modell beurteilt die Aussagen der gespeicherten,
     * aktuellen Analyse nach {@code check}; nur auf ausdrückliche Anforderung, nie auf dem EDT.
     *
     * @throws com.aresstack.spaceharvester.application.check.CheckUnavailableException ohne Modell, ohne KI-Freigabe
     *         der Seite oder ohne aktuelle Analyse
     */
    public PageStatements checkStatements(PageSnapshot snapshot, java.util.function.BooleanSupplier cancelled,
            com.aresstack.spaceharvester.application.configuration.CheckSettings check) {
        // Ein normaler Auftrag als Klick über denselben Code wie „Prüfen“ im Baum (AT, #1055), mit der gezeigten Fassung
        JobStatus done = awaitPageJob(() -> harvester.statementAnalysisJobs().startCheckShown(caller, snapshot,
                new StatementAnalysisJobs.CheckOptions(0, check.scope(), check.parallel())), cancelled);
        return checkResult(snapshot, done);
    }

    /** Das Ende des Prüfauftrags einer Seite als Ergebnis wie früher beim Prüfen im Reiter. */
    private PageStatements checkResult(PageSnapshot snapshot, JobStatus done) {
        return checkResult(done, done.state().terminal() ? ownEvents(done.id()) : List.of(),
                () -> statements(snapshot));
    }

    /**
     * Wie {@link #checkResult(PageSnapshot, JobStatus)} aus Stand und Ereignissen; {@code statements} liest die
     * Seite (und wirft ohne Leserecht den Zugriffsfehler des Core).
     */
    static PageStatements checkResult(JobStatus done, List<JobEvent> known,
            java.util.function.Supplier<PageStatements> statements) {
        if (done.state() == com.aresstack.spaceharvester.application.jobs.JobState.CANCELLED
                || done.state() == com.aresstack.spaceharvester.application.jobs.JobState.PAUSED) {
            throw new java.util.concurrent.CancellationException("Abgebrochen");
        }
        if (done.state() == com.aresstack.spaceharvester.application.jobs.JobState.FAILED) {
            if (done.error() != null && StatementAnalysisJobs.CHECK_UNAVAILABLE.equals(done.error().code())) {
                throw new com.aresstack.spaceharvester.application.check.CheckUnavailableException(
                        com.aresstack.spaceharvester.application.check.CheckUnavailableException.Reason.NO_MODEL);
            }
            throw new IllegalStateException(done.error() == null ? "Die Prüfung ist gescheitert"
                    : done.error().message());
        }
        if (done.progress().failed() > 0) {
            JobEvent failed = known.stream().filter(event -> "PAGE_FAILED".equals(event.code())).findFirst()
                    .orElse(null);
            if (failed != null && failed.category() == JobEvent.Category.ACCESS) {
                statements.get(); // wirft ohne Leserecht die AccessDeniedException des Core
            }
            throw new IllegalStateException(failed == null || failed.message() == null
                    ? "Die Seite wurde nicht geprüft" : failed.message());
        }
        if (done.progress().written() == 0) {
            if (counted(known, StatementAnalysisJobs.CHECK_NOT_RELEASED) > 0) {
                throw new com.aresstack.spaceharvester.application.check.CheckUnavailableException(
                        com.aresstack.spaceharvester.application.check.CheckUnavailableException.Reason.NOT_RELEASED);
            }
            if (counted(known, StatementAnalysisJobs.CHECK_NOT_CURRENT) > 0) {
                throw new com.aresstack.spaceharvester.application.check.CheckUnavailableException(
                        com.aresstack.spaceharvester.application.check.CheckUnavailableException.Reason.NOT_CURRENT);
            }
        }
        return statements.get();
    }

    /** Die Zahl vorn in der Meldung des Ereignisses mit diesem Code; 0 ohne. */
    private static long counted(List<JobEvent> events, String code) {
        return events.stream().filter(event -> code.equals(event.code()) && event.message() != null)
                .mapToLong(event -> {
                    java.util.regex.Matcher number = java.util.regex.Pattern.compile("^\\s*(\\d+)")
                            .matcher(event.message());
                    return number.find() ? Long.parseLong(number.group(1)) : 0;
                }).sum();
    }

    /** Warum der Prüfschritt für diese Seite nicht laufen kann (kein Modell, keine KI-Freigabe); sonst leer. */
    public Optional<com.aresstack.spaceharvester.application.check.CheckUnavailableException.Reason> checkUnavailable(
            PageKey key) {
        return harvester.statementAnalysis().checkUnavailable(key);
    }

    /** Ob „Aussagen prüfen“ laufen kann: Chat-Modell eingestellt; ruft kein Modell. */
    public boolean statementCheckAvailable() {
        return harvester.statementAnalysisJobs().checkAvailable();
    }

    /**
     * „Aussagen prüfen“ aus dem Baum: startet den Auftrag {@code STATEMENT_CHECK} für Seite, Unterzweig, Space oder
     * Quelle; Fortschritt und Abbruch wie bei jedem Auftrag.
     *
     * @throws com.aresstack.spaceharvester.application.check.CheckUnavailableException ohne eingestelltes Modell
     */
    public JobStatus startStatementCheck(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.CheckOptions options) {
        return harvester.statementAnalysisJobs().startCheck(caller, scope, options);
    }

    /** CoNLL-U der gespeicherten Analyse für die aufklappbaren Syntaxdetails. */
    public Optional<String> syntaxDetails(PageSnapshot snapshot) {
        return harvester.statementAnalysis().syntaxDetails(caller, snapshot);
    }

    /** Verbindungen dieser Seite zu anderen manuell analysierten, sichtbaren Seiten im globalen Graphen. */
    public List<KnowledgeGraph.Link> statementLinks(PageKey key) {
        return harvester.knowledgeGraph().links(caller, key);
    }

    /**
     * Löscht alle Aussagenanalysen aller Quellen ({@code KnowledgeGraph.deleteAll}); synchron, also außerhalb des EDT
     * aufrufen. Danach ist der Graph leer, bis Seiten neu analysiert werden.
     *
     * @return Zahl der gelöschten Seiten
     */
    public int deleteAllStatements() {
        return harvester.knowledgeGraph().deleteAll(caller);
    }

    /**
     * Löscht die Aussagen-Analysen der Auswahl ({@code KnowledgeGraph.delete}, Angelo 17.11.2026): absteigend, also eine
     * ganze Quelle samt allen Spaces und Unterseiten, ein Space oder eine Seite samt Unterseiten; Analysen von Anhängen
     * und enthaltenen Dateien gehen mit, die Seiten selbst bleiben. Bei einer ganzen Quelle zählt jede Analyse dieser
     * Quelle, auch zu Seiten, die der Bestand nicht mehr kennt. Synchron, also außerhalb des EDT aufrufen; danach melden
     * sich die Beobachter von {@link #subscribeStatementAnalyses}.
     *
     * @return Zahl der gelöschten Analysen (Seiten und Dokumente)
     */
    public int deleteStatementAnalyses(com.aresstack.spaceharvester.application.holdings.Selection selection) {
        return deleteStatementAnalyses(selection, false);
    }

    /**
     * Wie {@link #deleteStatementAnalyses(com.aresstack.spaceharvester.application.holdings.Selection)}; mit
     * {@code withChecks} danach auch die KI-Urteile aus „Aussagen prüfen“ derselben Seiten
     * ({@code StatementChecks.delete}, „Alles löschen“, Angelo 17.11.2026). Synchron, also außerhalb des EDT.
     *
     * @return Zahl der gelöschten Analysen (Seiten und Dokumente)
     */
    public int deleteStatementAnalyses(com.aresstack.spaceharvester.application.holdings.Selection selection,
                                       boolean withChecks) {
        return deleteStatementAnalyses(selection, withChecks, true);
    }

    /**
     * Wie {@link #deleteStatementAnalyses(com.aresstack.spaceharvester.application.holdings.Selection, boolean)};
     * {@code wholeSources} false löscht auch bei einer Quellen-Wurzel nur die aufgelösten Seiten: so bei einer Sammlung
     * (ADR 0034 §3, „Löschen“ im Stand wirkt nie auf eine ganze Quelle).
     */
    public int deleteStatementAnalyses(com.aresstack.spaceharvester.application.holdings.Selection selection,
                                       boolean withChecks, boolean wholeSources) {
        java.util.function.Predicate<PageKey> pages = analysesOf(selection, resolveSelection(selection), wholeSources);
        int deleted = harvester.knowledgeGraph().delete(caller, pages);
        if (withChecks) {
            harvester.statementChecks().delete(caller, pages);
        }
        return deleted;
    }

    /**
     * Welche Analysen zur Auswahl gehören: bei einer ganzen Quelle jede mit ihrer {@code sourceId}, auch zu Seiten, die
     * der Bestand nicht mehr kennt (sonst ließen sie sich nie mehr löschen); bei Space, Ordner und Seite nur die Seiten
     * aus dem Bestand ({@code pages}).
     */
    static java.util.function.Predicate<PageKey> analysesOf(
            com.aresstack.spaceharvester.application.holdings.Selection selection,
            com.aresstack.spaceharvester.application.holdings.PageSelection pages) {
        return analysesOf(selection, pages, true);
    }

    /** Wie oben; ohne {@code sources} nur die Seiten aus dem Bestand, auch bei einer Quellen-Wurzel (Sammlung). */
    static java.util.function.Predicate<PageKey> analysesOf(
            com.aresstack.spaceharvester.application.holdings.Selection selection,
            com.aresstack.spaceharvester.application.holdings.PageSelection pages, boolean sources) {
        java.util.Set<String> wholeSources = new java.util.HashSet<>();
        for (com.aresstack.spaceharvester.application.holdings.Selection.Root root : selection.roots()) {
            if (sources && root.spaceKey() == null && root.pageId() == null) {
                wholeSources.add(root.sourceId());
            }
        }
        return key -> wholeSources.contains(key.sourceId()) || pages.contains(key);
    }

    /**
     * Löscht die Aussagen-Analyse einer Seite samt Anhängen und enthaltenen Dateien ({@code KnowledgeGraph.delete});
     * die Seite bleibt. Synchron, also außerhalb des EDT aufrufen.
     *
     * @return Zahl der gelöschten Analysen (Seite und Dokumente)
     */
    public int deleteStatementAnalysis(PageKey key) {
        return deleteStatementAnalysis(key, false);
    }

    /** Wie {@link #deleteStatementAnalysis(PageKey)}; mit {@code withChecks} auch die KI-Urteile dieser Seite. */
    public int deleteStatementAnalysis(PageKey key, boolean withChecks) {
        int deleted = harvester.knowledgeGraph().delete(caller, key::equals);
        if (withChecks) {
            harvester.statementChecks().delete(caller, key::equals);
        }
        return deleted;
    }

    /**
     * Meldet sich, nachdem Aussagen-Analysen gelöscht, neu geschrieben oder ersetzt wurden oder eine andere Ablage
     * eingestellt ist ({@code KnowledgeGraph.subscribe}), auf einem Thread des Core; kein Polling.
     */
    public Subscription subscribeStatementAnalyses(Runnable listener) {
        return harvester.knowledgeGraph().subscribe(listener);
    }

    /** Erwähnungen eines Begriffs auf allen manuell analysierten, sichtbaren Seiten. */
    public KnowledgeGraph.Search searchKnowledgeGraph(String term, int limit) {
        return harvester.knowledgeGraph().search(caller, term, limit);
    }

    /**
     * Wie {@link #searchKnowledgeGraph(String, int)}, nur auf diesen Seiten samt ihren Dokumenten, etwa einer Seite samt
     * Unterseiten aus {@link #resolveSelection} (ADR 0029); Treffer und Zähler beziehen sich nur auf sie.
     */
    public KnowledgeGraph.Search searchKnowledgeGraph(String term, int limit,
                                                      com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.knowledgeGraph().search(caller, term, limit, page -> pages.contains(page.key()));
    }

    /** Ob „Analysieren“ im Baum angeboten werden kann (Analysator eingerichtet); lädt kein Modell. */
    public boolean statementAnalysisJobsAvailable() {
        return harvester.statementAnalysisJobs().available();
    }

    /**
     * Zählt den Umfang eines Analyseauftrags für den Bestätigungsdialog, höchstens {@code limit + 1} Seiten weit;
     * lädt keine Inhalte und analysiert nichts. Fragt die Quelle nach Kindseiten, also nie auf dem EDT.
     */
    public StatementAnalysisJobs.Estimate estimateStatementAnalysis(StatementAnalysisJobs.Scope scope, int limit) {
        return harvester.statementAnalysisJobs().estimate(caller, scope, limit);
    }

    /**
     * „Analysieren“ aus dem Baum: startet den Auftrag {@code STATEMENT_ANALYSIS} für Seite, Unterzweig, Space oder
     * Quelle; Fortschritt und Abbruch wie bei jedem Auftrag.
     */
    public JobStatus startStatementAnalysis(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.Options options) {
        return harvester.statementAnalysisJobs().start(caller, scope, options);
    }

    /**
     * Exportiert vorhandene Analysen als JSON-LD in {@code <Exportordner>/knowledge-graph} (#499, ADR 0024); stößt
     * nie eine Analyse an. Fortschritt und Abbruch wie bei jedem Auftrag.
     */
    public JobStatus startKnowledgeGraphExport(
            com.aresstack.spaceharvester.application.knowledge.export.KnowledgeGraphExportUseCase.Scope scope,
            boolean includeStale) {
        return harvester.startKnowledgeGraphExport(caller, scope, includeStale);
    }

    /** Fokussierter Teilgraph einer Seite für „Analysen → Graph“; begrenzt, rechtegeprüft, rechnet nie. */
    public GraphNeighbourhood.Subgraph knowledgeGraphOfPage(PageKey key, GraphNeighbourhood.Options options) {
        return new GraphNeighbourhood(harvester.knowledgeGraph()).page(caller, key, options);
    }

    /**
     * Teilgraph einer Seite samt Unterseiten („mit Unterseiten“): eine Grenze für die ganze Menge, Seiten ohne Analyse
     * und ausgeblendete veraltete Seiten gezählt; gesperrte und nicht lesbare Unterseiten erscheinen nirgends.
     * {@code subpages} aus {@link #descendants} (ohne die Seite selbst) oder {@code null}. Nie auf dem EDT.
     */
    public GraphNeighbourhood.Stack knowledgeGraphOfPages(PageKey page,
            com.aresstack.spaceharvester.application.holdings.PageSelection subpages,
            GraphNeighbourhood.Options options) {
        return new GraphNeighbourhood(harvester.knowledgeGraph()).pages(caller, page, subpages, options);
    }

    /** Direkte Nachbarn eines Knotens („Nachbarn erweitern“). */
    public GraphNeighbourhood.Subgraph knowledgeGraphOfNode(String nodeId, GraphNeighbourhood.Options options) {
        return new GraphNeighbourhood(harvester.knowledgeGraph()).node(caller, nodeId, options);
    }

    /** Erwähnungen eines Begriffs mit ihren Aussagen über alle sichtbaren analysierten Seiten. */
    public GraphNeighbourhood.Subgraph knowledgeGraphOfTerm(String term, GraphNeighbourhood.Options options) {
        return new GraphNeighbourhood(harvester.knowledgeGraph()).term(caller, term, options);
    }

    /**
     * Fachsicht des Wissensgraphen um einen Startbegriff (Paket W1): bestätigte Fachbegriffe und Synonyme als Knoten,
     * Verben der Aussagen als Kanten. Rechtegeprüft, rechnet nie; nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.View domainTermGraph(String start,
            com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Options options,
            java.util.function.BooleanSupplier cancelled) {
        return harvester.domainTermGraph().around(caller, start, options, cancelled);
    }

    /**
     * Seitenweite Fachsicht ohne Startbegriff (Paket W4b) über {@code options.pages()} (Pflicht): die bestätigten
     * Fachbegriffe und Synonyme dieser Seiten, die durch ein Verb verbunden sind, höchstens
     * {@code DomainTermGraph.MAX_NODES}. Rechtegeprüft, rechnet nie; nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.View domainTermGraphOfPages(
            com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Options options,
            java.util.function.BooleanSupplier cancelled) {
        return harvester.domainTermGraph().onPages(caller, options, cancelled);
    }

    /**
     * Begriffswissen für Chat und Suche (Paket W6): was der Wissensgraph im Bereich {@code scope} über einen
     * bestätigten Fachbegriff weiß, begrenzt und mit Stufe je Aussage. Jede gelieferte Seite geht vorher an
     * {@code delivery} (für den Chat die KI-Freigabe, ADR 0033 Punkt 8). Rechtegeprüft, rechnet nie; nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.knowledge.TermKnowledge.Answer termKnowledge(String query,
            com.aresstack.spaceharvester.application.snapshot.PageSet scope,
            com.aresstack.spaceharvester.application.knowledge.TermKnowledge.Delivery delivery,
            java.util.function.BooleanSupplier cancelled) {
        return harvester.termKnowledge().answer(caller, query, scope, delivery, cancelled);
    }

    /** Vorschläge für das Startfeld der Fachsicht: nur bestätigte Fachbegriffe und Synonyme. Nie auf dem EDT. */
    public java.util.List<String> domainTermSuggestions(String query, int limit) {
        return harvester.domainTermGraph().suggestions(caller, query, limit);
    }

    /**
     * Der Wortlaut zu Aussagen je Seite, etwa den Belegen der Fachsicht: Originalsatz, vereinfachter Satz, Satzgrenzen
     * im Lesetext. Ohne Leserecht am Inhalt fehlt der Eintrag. Nie auf dem EDT.
     */
    public java.util.Map<String, com.aresstack.spaceharvester.application.standing.SentenceText> statementSentences(
            java.util.Map<com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph.PageRef,
                    java.util.List<com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Statement>>
                    byPage) {
        return harvester.statusQuo().statementSentences(caller, byPage);
    }

    /** Die abgelegten Bytes eines beschriebenen Bildes, ohne die Quelle zu fragen; leer, wenn nichts abgelegt ist. */
    public Optional<byte[]> storedImage(String sourceId, ImagePayload.Entry image) {
        return storedImage(sourceId, image, null);
    }

    /**
     * Wie {@link #storedImage(String, ImagePayload.Entry)} mit der beim Beschreiben gemerkten Anhangsversion: mit ihr
     * kennt die Ablage die Größe, und kleine Vorschaubilder belegen keinen Platz im LargeFileGate.
     */
    public Optional<byte[]> storedImage(String sourceId, ImagePayload.Entry image, String version) {
        return harvester.assets().stored(sourceId, image.assetId(), version, image.sha256());
    }

    public List<String> enrichmentProcessors() {
        return harvester.enrichments().processorIds();
    }

    // ------------------------------------------------------------------ Suche

    /** Titel- und Metadatensuche im Cache ({@code GET /api/v1/search}); ohne {@code sourceId} global (#114). */
    public SearchResults search(PageSearchQuery query, String cursor, int limit) {
        return harvester.search().search(caller, query, cursor, limit);
    }

    /**
     * Schlagwortsuche über Titel und Inhalt in den Quellen selbst ({@code GET /api/v1/search?text=…}); jeder Treffer
     * ist live auf Freigabe geprüft. {@code cursor} gilt nur für dieselbe Anfrage.
     */
    public KeywordSearch.Results keywordSearch(KeywordSearch.Query query, String cursor, int limit) {
        return harvester.keywordSearch().search(caller, query, cursor, limit);
    }

    /**
     * Wie {@link #keywordSearch(KeywordSearch.Query, String, int)}; {@code found} erfährt die Funde jeder Quelle, sobald
     * sie geantwortet hat, aus den Suchthreads, nie vom EDT.
     */
    public KeywordSearch.Results keywordSearch(KeywordSearch.Query query, String cursor, int limit,
                                               KeywordSearch.Found found) {
        return harvester.keywordSearch().search(caller, query, cursor, limit, null, found);
    }

    /**
     * Wie {@link #keywordSearch(KeywordSearch.Query, String, int, KeywordSearch.Found)} als Teil der Suche
     * {@code searchId} der Trefferliste (Fundstelle 23): Ihre Stapel und ihr Neusortieren bieten jeden Fund nur einmal
     * zum Katalogisieren an. {@code found} darf {@code null} sein.
     */
    public KeywordSearch.Results keywordSearchIn(String searchId, KeywordSearch.Query query, String cursor, int limit,
                                                 KeywordSearch.Found found) {
        return harvester.keywordSearch().search(caller, query, cursor, limit, null, found, searchId);
    }

    /**
     * Suchmethode „Synonyme der Fachbegriffe“ (K5a): wie viele Synonymvorschläge die Suche {@code query} je Quelle
     * höchstens stellen würde, vor der Grenze {@code SearchExpansion.MAX_EXPANSIONS}; derselbe Umfang und dieselbe
     * Entdoppelung wie die Suche. Wirft, wenn das Glossar nicht lesbar ist; nie auf dem EDT.
     */
    public int glossarySynonymsWanted(KeywordSearch.Query query) {
        harvester.glossarySynonyms().requireReadable(caller);
        return harvester.keywordSearch().glossarySynonymsWanted(caller, query);
    }

    /** Wie weit die Seiten eines Stapels der Schlagwortsuche geladen sind ({@code Results.progress()}). */
    public java.util.Optional<KeywordSearch.Progress> keywordSearchProgress(String id) {
        return harvester.keywordSearch().progress(id);
    }

    /**
     * Meldet einen Empfänger für den Ladestand eines Stapels der Suche an (ADR 0027), statt
     * {@link #keywordSearchProgress} in Abständen zu lesen: nach jeder geladenen oder gescheiterten Seite und nach einem
     * Abbruch der jüngste Stand, zuletzt mit {@link KeywordSearch.Progress#done()}; auf dem Zustell-Thread des Core.
     * Erst anmelden, dann {@link #keywordSearchProgress} lesen.
     */
    public Subscription subscribeSearchProgress(String id, java.util.function.Consumer<KeywordSearch.Progress> listener) {
        return harvester.keywordSearch().subscribe(id, listener);
    }

    /** Bricht das Laden eines Stapels ab; Geladenes bleibt im Cache. */
    public boolean cancelKeywordSearch(String id) {
        return harvester.keywordSearch().cancel(id);
    }

    /**
     * Progressive Suche (ADR 0017) mit dem Standardprofil des Core: die ursprüngliche Anfrage sofort, dann Varianten
     * in Wellen bis zum Qualitätsziel. Die Ereignisse kommen aus dem Suchvorgang, nie vom EDT.
     */
    public ProgressiveSearch progressiveSearch(KeywordSearch.Query query, ProgressiveSearchListener listener) {
        return harvester.progressiveSearch().searchAsync(caller, query, harvester.searchProfile(), listener);
    }

    /**
     * Wie {@link #progressiveSearch(KeywordSearch.Query, ProgressiveSearchListener)}, mit bzw. ohne die Suchmethode
     * „Nachbarseiten“ (R2).
     */
    public ProgressiveSearch progressiveSearch(KeywordSearch.Query query, ProgressiveSearchListener listener,
                                               boolean neighbors) {
        return progressiveSearch(query, listener, neighbors, false);
    }

    /** Wie oben, zusätzlich mit bzw. ohne die Suchmethode „Fachbegriffe“ (W8). */
    public ProgressiveSearch progressiveSearch(KeywordSearch.Query query, ProgressiveSearchListener listener,
                                               boolean neighbors, boolean technicalTerms) {
        return harvester.progressiveSearch().searchAsync(caller, query,
                harvester.searchProfile().withNeighbors(neighbors).withTechnicalTerms(technicalTerms), listener);
    }

    /**
     * Suchmethode „Fachbegriffe“ (W8) zur Schlagwortsuche nach einem Stapel: Seiten mit Aussagen zu Fachbegriffen der
     * Anfrage, im selben Umfang, ohne die schon angezeigten. Läuft nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.query.progressive.TermSearch.Result termSearch(
            KeywordSearch.Query query, java.util.Collection<com.aresstack.spaceharvester.application.snapshot.PageKey> shown,
            java.util.function.BooleanSupplier cancelled) {
        return harvester.termSearch().search(caller, query, shown, cancelled);
    }

    /**
     * Suchmethode „Nachbarseiten“ (R2) zur Schlagwortsuche nach einem Stapel: Nachbarn der angezeigten Treffer, die
     * dieselbe Anfrage im selben Umfang treffen; beste Treffer zuerst übergeben. Läuft nie auf dem EDT.
     */
    public List<KeywordSearch.Hit> neighborSearch(KeywordSearch.Query query, List<KeywordSearch.Hit> shown) {
        return harvester.neighborSearch().search(caller, query, shown);
    }

    /**
     * Wie {@link #neighborSearch(KeywordSearch.Query, List)}; mit {@code load} laden fehlende Nachbarn als
     * Hintergrundauftrag nach, ohne dass die Suche wartet.
     */
    public com.aresstack.spaceharvester.application.query.progressive.NeighborSearch.Result neighborSearch(
            KeywordSearch.Query query, List<KeywordSearch.Hit> shown, boolean load) {
        return harvester.neighborSearch().search(caller, query, shown, load);
    }

    /**
     * Liest einen Testbestand der Suchqualität (Q1) zur Vorschau: gültige Fragen und Zeilenfehler. Seitenadressen löst
     * es über die Adressmuster der Quellen auf, ohne Seiten zu laden.
     */
    public com.aresstack.spaceharvester.application.quality.QualitySuite readQualitySuite(java.nio.file.Path suite)
            throws java.io.IOException {
        return harvester.searchQuality().read(caller, suite);
    }

    /**
     * Startet „Suchqualität messen“ (Q1) als Auftrag über genau den Suchweg des Chats: so viele Treffer im
     * Chat-Kontext wie {@link com.aresstack.spaceharvester.app.chat.ChatKnowledge#MAX_SOURCES}, dieselbe Frist wie
     * {@link com.aresstack.spaceharvester.app.chat.ChatTurn#SEARCH_SECONDS}. Ergebnis und Bericht liegen im
     * Unterordner „Suchqualität“ des Ausgabeordners.
     *
     * @param previous früheres Ergebnis zum Vergleich oder {@code null}
     * @param output   Ausgabeordner der Einstellungen ({@code AppSettings.outputDirectory()})
     */
    public JobStatus startSearchQuality(java.nio.file.Path suite, java.nio.file.Path previous,
                                        java.nio.file.Path output, String version) {
        return startSearchQuality(suite, previous, output, version, false);
    }

    /** Wie oben, mit bzw. ohne die Suchmethode „Nachbarseiten“ (R2), damit ihr Nutzen messbar ist. */
    public JobStatus startSearchQuality(java.nio.file.Path suite, java.nio.file.Path previous,
                                        java.nio.file.Path output, String version, boolean neighbors) {
        return startSearchQuality(suite, previous, output, version, neighbors, false);
    }

    /** Wie oben, zusätzlich mit bzw. ohne die Suchmethode „Synonyme der Fachbegriffe“ (K5a). */
    public JobStatus startSearchQuality(java.nio.file.Path suite, java.nio.file.Path previous,
                                        java.nio.file.Path output, String version, boolean neighbors,
                                        boolean synonyms) {
        return startSearchQuality(suite, previous, output, version, neighbors, synonyms, false);
    }

    /** Wie oben, zusätzlich mit bzw. ohne die Suchmethode „Fachbegriffe“ (W8). */
    public JobStatus startSearchQuality(java.nio.file.Path suite, java.nio.file.Path previous,
                                        java.nio.file.Path output, String version, boolean neighbors,
                                        boolean synonyms, boolean technicalTerms) {
        JobStatus status = harvester.searchQuality().submit(caller,
                new com.aresstack.spaceharvester.application.quality.SearchQualityRuns.Request(suite, previous, output,
                        com.aresstack.spaceharvester.app.chat.ChatKnowledge.MAX_SOURCES,
                        java.time.Duration.ofSeconds(com.aresstack.spaceharvester.app.chat.ChatTurn.SEARCH_SECONDS),
                        version, neighbors, synonyms, technicalTerms));
        activities.remember(status.id());
        return status;
    }

    /**
     * Ob die progressive Suche semantisch ordnet ({@code SearchProfile.Ranking.SEMANTIC}): nur mit einem wirksamen
     * Embedding-Modell; ohne Modell gibt es keine semantische Suche (ADR 0017).
     */
    public boolean semanticRanking() {
        return harvester.searchProfile().ranking() == SearchProfile.Ranking.SEMANTIC;
    }

    /** Ob die Varianten der progressiven Suche vom Chat-Modell kommen; sonst nach Regeln. */
    public boolean aiVariants() {
        return harvester.searchProfile().expansion() == SearchProfile.Expansion.AI;
    }

    /** Seiten einer Quelle über ihren Titel, auch nie abgeglichene (Confluence per CQL); leer ohne Titelsuche. */
    public PageNavigation.TitleMatches findByTitle(String sourceId, Set<String> spaceKeys, String title, int limit) {
        return harvester.navigation().findByTitle(caller, sourceId, spaceKeys, title, limit);
    }

    /** Offene Einmalcode-Anforderungen der Quellen (ADR 0018); Senden und Neu-Senden blockieren, nie auf dem EDT. */
    public SignInRequests signIns() {
        return harvester.signIns();
    }

    /**
     * Der Netzzustand des Core, für den Hinweis „Keine Netzverbindung“ in der Statuszeile (Angelo 22.11.2026); Änderungen
     * über {@code network().subscribe(...)}, ohne Abfragen.
     */
    public com.aresstack.spaceharvester.application.network.NetworkState network() {
        return harvester.network();
    }

    /** Die Proxy-Route aller Quellen, für den Hinweis „Proxy-Skript seit … nicht erneuert“ in der Statuszeile. */
    public com.aresstack.spaceharvester.source.network.ProxyRoutes proxyRoutes() {
        return harvester.proxyRoutes();
    }

    /** Ob {@code sort=relevance} semantisch sucht (Embedding-Modell eingerichtet). */
    public boolean semanticSearch() {
        return harvester.relevance().semantic();
    }

    /** Suche nach Relevanz ({@code sort=relevance}). */
    public RelevanceSearch.RelevanceResults relevance(PageSearchQuery query, String text, int limit) {
        return harvester.relevance().search(caller, query, text, limit);
    }

    /**
     * Die Modelle des KI-Dienstes für „Modelle laden“, auch mit noch nicht gespeicherten Eingaben; über dieselbe
     * Proxy-Route wie die Quellen.
     */
    public List<AiModel> aiModels(AiServiceSettings settings) {
        return harvester.aiModels(settings);
    }

    /** Die Modelle des KI-Dienstes mit den gespeicherten Einstellungen (Modellwahl im Menü am Dreieck); ohne Dienst leer. */
    public List<AiModel> aiModels() {
        return harvester.aiModels();
    }

    /** Misst die Vektordimension eines Embedding-Modells für {@code embedding-dimensions}. */
    public int aiEmbeddingDimension(AiServiceSettings settings, String embeddingModel) {
        return harvester.aiEmbeddingDimension(settings, embeddingModel);
    }

    /**
     * Der Chat-Port des KI-Dienstes für den Reiter „Chat“ (streamend, {@code POST /chat/completions}) mit den
     * wirksamen Einstellungen; {@code null} ohne Dienst oder Chat-Modell. Der Chat selbst lebt in der App (ADR 0012).
     */
    public AiChat aiChat() {
        return harvester.aiChat();
    }

    // ------------------------------------------------------------------ Abgleich

    /** Vollständiger Abgleich eines Space mit dem Cache ({@link SyncMode#SYNC_CACHE}). */
    public JobStatus startSync(String sourceId, String spaceKey) {
        return startSync(sourceId, spaceKey, SyncMode.SYNC_CACHE);
    }

    /** Abgleich eines Space in der Betriebsart {@code mode} starten ({@code POST …/spaces/{key}/syncs?mode=…}). */
    public JobStatus startSync(String sourceId, String spaceKey, SyncMode mode) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        harvester.spaceKeys().requireExisting(sourceId, spaceKey);
        JobStatus status = harvester.syncs().start(sourceId, spaceKey, Harvester.OWNER, mode);
        activities.remember(status.id());
        return status;
    }

    /**
     * Änderungen eines Space seit dem letzten Lauf übernehmen ({@code POST …/syncs?listing=changes}); ohne
     * Änderungssuche der Quelle gleicht der Core stattdessen den Bestand ab.
     */
    public JobStatus catchUp(String sourceId, String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        harvester.spaceKeys().requireExisting(sourceId, spaceKey);
        JobStatus status = harvester.syncs().catchUp(sourceId, spaceKey, Harvester.OWNER, null);
        activities.remember(status.id());
        return status;
    }

    /** Wie {@link #catchUp(String, String)}, in der Betriebsart {@code mode} (etwa der wirksamen der Quelle). */
    public JobStatus catchUp(String sourceId, String spaceKey, SyncMode mode) {
        return catchUp(sourceId, spaceKey, mode, null);
    }

    /**
     * Wie {@link #catchUp(String, String, SyncMode)}, als Teil der Sammlung {@code group} ({@link SpaceSyncJobs#GROUP}):
     * Der Abgleich einer ganzen Quelle steht in der Auftragsliste als eine aufklappbare Zeile über seinen Spaces.
     *
     * @param group Kennung der Sammlung, oder {@code null} für einen einzelnen Abgleich
     */
    public JobStatus catchUp(String sourceId, String spaceKey, SyncMode mode, String group) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        harvester.spaceKeys().requireExisting(sourceId, spaceKey);
        JobStatus status = harvester.syncs().catchUp(sourceId, spaceKey, Harvester.OWNER, null, mode,
                com.aresstack.spaceharvester.application.changelog.ChangeTrigger.MANUAL, group);
        activities.remember(status.id());
        return status;
    }

    /**
     * Eine Zustandsaktion für alle Abgleiche einer Sammlung ({@link SpaceSyncJobs#GROUP}), etwa ■ auf der Zeile „Abgleich
     * der Quelle“: wie {@link #performBackgroundGroupAction}, über denselben Weg wie je Zeile ({@link #performJobAction}).
     * Nie auf dem EDT.
     */
    public AllJobs.Outcome performSyncGroupAction(String group, JobAction action) {
        List<JobStatus> parts = jobs().stream().filter(job -> job.type() == JobType.SYNC
                && group.equals(job.parameter(SpaceSyncJobs.GROUP))).toList();
        return switch (action) {
            case PAUSE -> AllJobs.apply(AllJobs.toPause(parts), job -> pauseJob(job.id()));
            case RESUME -> AllJobs.apply(parts.stream().filter(job -> job.lifecycle().allows(JobAction.RESUME))
                    .toList(), job -> performJobAction(job.id(), JobAction.RESUME));
            case CANCEL -> AllJobs.apply(AllJobs.toCancel(parts), job -> performJobAction(job.id(), JobAction.CANCEL));
            case START_NOW, DELETE, RETRY_FAILED, FORCE_REFRESH -> AllJobs.apply(parts.stream()
                    .filter(job -> job.lifecycle().allows(action)).toList(), job -> performJobAction(job.id(), action));
            default -> new AllJobs.Outcome(0, 0, null);
        };
    }

    /**
     * Indexieren (Angelo, 20.11.2026): markiert die Seite samt Unterseiten, ohne {@code pageId} den ganzen Space, und
     * startet den Abgleich in der Betriebsart {@code mode}. Die markierten Seiten lädt er vollständig, Bilder und
     * Anhänge folgen als eigene Aufträge; jede spätere Synchronisierung hält sie aktuell.
     *
     * @throws com.aresstack.spaceharvester.application.sync.SyncAlreadyRunningException wenn der Space gerade abgleicht;
     *                                                                                   markiert ist er trotzdem
     */
    public JobStatus index(String sourceId, String spaceKey, String pageId, SyncMode mode) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        harvester.spaceKeys().requireExisting(sourceId, spaceKey);
        JobStatus status = harvester.indexing().index(sourceId, spaceKey, pageId, Harvester.OWNER, mode);
        activities.remember(status.id());
        return status;
    }

    /** Hebt die Markierung genau dieser Wurzel auf; Geladenes bleibt und wird weiter aktuell gehalten. */
    public void unindex(String sourceId, String spaceKey, String pageId) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        harvester.indexing().unindex(sourceId, spaceKey, pageId);
    }

    /**
     * Ausschließen (Angelo 26.11.2026): die Seite samt allem darunter, ohne {@code pageId} der ganze Space, erscheint
     * nicht mehr im Baum und in der Suche und wird nicht indexiert. Löscht nichts; wieder einblenden über
     * {@link #include}.
     */
    public void exclude(String sourceId, String spaceKey, String pageId) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        harvester.exclusions().exclude(sourceId, spaceKey, pageId);
    }

    /** Blendet genau diesen Ausschluss wieder ein; die Inhalte erscheinen wieder, neu geladen wird nichts. */
    public void include(String sourceId, String spaceKey, String pageId) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.SYNC);
        harvester.exclusions().include(sourceId, spaceKey, pageId);
    }

    /** Die Ausschlüsse der Quelle für die Übersicht in den Einstellungen; liest nur die Ablage. */
    public List<com.aresstack.spaceharvester.application.sync.Exclusions.Exclusion> exclusions(String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        return harvester.exclusions().of(sourceId);
    }

    /**
     * Die oberste Ebene eines Space mit den ausgeschlossenen Knoten, live, für die Übersicht in den Einstellungen
     * (Mehrfachauswahl wie bei den NDV-Bibliotheken).
     */
    public PageNavigation.Children allChildren(String sourceId, String spaceKey, String parentPageId, String cursor) {
        return harvester.navigation().children(caller, sourceId, spaceKey, parentPageId, cursor, true);
    }

    /** Ob genau diese Wurzel zum Indexieren markiert ist, ohne {@code pageId} der ganze Space; liest nur die Ablage. */
    public boolean indexed(String sourceId, String spaceKey, String pageId) {
        return harvester.indexing().marks().marked(sourceId, spaceKey, pageId);
    }

    /**
     * Katalogstand eines Space im Cache, ohne Quellenzugriff ({@code GET …/spaces/{key}/catalog}): erfasst oder nicht,
     * bekannte Seiten und Seiten mit Inhalt.
     */
    public SpaceCatalog catalog(String sourceId, String spaceKey) {
        access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
        return harvester.syncs().catalog(sourceId, spaceKey);
    }

    /** Status und Delta eines Abgleichs ({@code GET /syncs/{id}}). */
    public SpaceSyncJobs.SyncJob syncStatus(String jobId) {
        SpaceSyncJobs.SyncJob job = harvester.syncs().status(jobId, Harvester.OWNER);
        requireVisible(job.status(), AccessKind.SYNC);
        return job;
    }

    /** Abgleich abbrechen ({@code POST /syncs/{id}/cancel}). */
    public SpaceSyncJobs.SyncJob cancelSync(String jobId) {
        requireVisible(harvester.syncs().status(jobId, Harvester.OWNER).status(), AccessKind.SYNC);
        return harvester.syncs().cancel(jobId, Harvester.OWNER);
    }

    // ------------------------------------------------------------------ Export

    /** Angebotene Ausgabeformate zusätzlich zum Originalexport. */
    public List<String> exportFormats() {
        return harvester.exports().formats();
    }

    /** Export eines Space starten ({@code POST …/spaces/{key}/exports}); braucht export und content. */
    public JobStatus startExport(String sourceId, String spaceKey, List<String> formats) {
        requireExport(sourceId, spaceKey);
        JobStatus status = harvester.exports().start(sourceId, spaceKey, Harvester.OWNER, formats);
        activities.remember(status.id());
        return status;
    }

    /**
     * Export einer Seite samt Unterseiten starten ({@code ExportRequest.withRoot}); {@code rootPageId == null} heißt
     * ganzer Space. Eine unbekannte oder nicht lesbare Startseite lässt den Auftrag mit ROOT_NOT_FOUND scheitern.
     */
    public JobStatus startExport(String sourceId, String spaceKey, String rootPageId, List<String> formats) {
        return startExport(sourceId, spaceKey, rootPageId, null, formats);
    }

    /**
     * Wie {@link #startExport(String, String, String, List)}, aber nur bis {@code depth} Ebenen unter der Startseite
     * ({@code ExportRequest.withDepth}); {@code null} heißt alle, {@code 0} nur die Startseite.
     */
    public JobStatus startExport(String sourceId, String spaceKey, String rootPageId, Integer depth,
                                 List<String> formats) {
        requireExport(sourceId, spaceKey);
        JobStatus status = harvester.exports().start(sourceId, spaceKey, rootPageId, depth, Harvester.OWNER,
                formats);
        activities.remember(status.id());
        return status;
    }

    /**
     * Eine Seite samt Unterseiten neu aus der Quelle lesen und ausgeben (Kreispfeile im Baum, Angelo 16.11.2026):
     * wie {@link #startExport(String, String, String, List)}, aber mit verlangter Aktualität
     * ({@code ExportRequest.refresh}), statt einen vorhandenen Bestand wiederzuverwenden.
     */
    public JobStatus refreshSubtree(String sourceId, String spaceKey, String rootPageId, List<String> formats) {
        requireExport(sourceId, spaceKey);
        JobStatus status = harvester.exports().start(ExportRequest.of(sourceId, spaceKey, Harvester.OWNER, formats)
                .withRoot(rootPageId).withRefresh(true));
        activities.remember(status.id());
        return status;
    }

    /**
     * Ausgabe der Auswahl aus dem Bestand starten (ADR 0029): je gewähltem Space bzw. Teilbaum ein Export-Auftrag
     * ({@code Holdings.outputs}), der nur den Seiten-Cache liest, nie die Quelle; braucht je Space export und content,
     * geprüft vor dem ersten Auftrag. Gespeicherte Seiten kommen mit, veraltete im gespeicherten Stand; nur aus dem
     * Katalog bekannte fehlen und stehen mit ihrer Zahl in den Warnungen des Ergebnisses. Artefakt, Dateien, Formate
     * und Folgeaufträge wie bei {@link #startExport(String, String, List)}. Haben die Seiten einer Ausgabe denselben
     * Stand wie bei einer fertigen mit gleichem Umfang und gleichen Formaten, kommt deren Auftrag zurück, ohne neuen Lauf
     * ({@code Exports.startFromHoldings}).
     *
     * @return die Aufträge, erst ganze Spaces, dann Teilbäume; ein wiederverwendeter ist schon beendet
     */
    public List<JobStatus> startExportFromHoldings(
            com.aresstack.spaceharvester.application.holdings.Selection selection, List<String> formats) {
        List<com.aresstack.spaceharvester.application.holdings.Selection.Root> outputs =
                harvester.holdings().outputs(caller, selection);
        outputs.forEach(root -> requireExport(root.sourceId(), root.spaceKey()));
        List<JobStatus> started = new ArrayList<>(outputs.size());
        for (com.aresstack.spaceharvester.application.holdings.Selection.Root root : outputs) {
            JobStatus status = harvester.exports().startFromHoldings(ExportRequest.fromHoldings(root.sourceId(),
                    root.spaceKey(), Harvester.OWNER, formats).withRoot(root.pageId()));
            activities.remember(status.id());
            started.add(status);
        }
        return started;
    }

    /**
     * Bilder und Anhänge einer Auswahl beschaffen, ohne Export-Auftrag (ADR 0029, Schritt 7): je gewähltem Space bzw.
     * Teilbaum ({@code Holdings.outputs}) ein Auftrag „Bilder beschaffen“ und, bei eingeschalteten Anhängen, ein Auftrag
     * „Anhänge beschaffen“ für die lokal gespeicherten Seiten; braucht je Space die sync-Freigabe, geprüft vor dem
     * ersten Auftrag. Was schon im Bestand liegt, ruft der Lauf nicht ab. Danach findet eine Ausgabe aus dem Bestand
     * die Dateien ({@link #startExportFromHoldings}). Die Aufträge tragen {@code selection=true} und, für einen
     * Teilbaum, {@code selectionRoot}, aber keine {@code exportId}.
     *
     * @return die eingeplanten Aufträge; ein schon offener für dieselbe Wurzel kommt zurück
     */
    public List<JobStatus> collectAssets(com.aresstack.spaceharvester.application.holdings.Selection selection) {
        List<com.aresstack.spaceharvester.application.holdings.Selection.Root> outputs =
                harvester.holdings().outputs(caller, selection);
        outputs.forEach(root -> access.require(caller, ResourceScope.space(root.sourceId(), root.spaceKey()),
                AccessKind.SYNC));
        List<JobStatus> started = new ArrayList<>();
        for (com.aresstack.spaceharvester.application.holdings.Selection.Root root : outputs) {
            for (JobStatus status : harvester.assetCollection().collect(Harvester.OWNER, root)) {
                activities.remember(status.id());
                started.add(status);
            }
        }
        return started;
    }

    /**
     * „Confluence-Metadaten exportieren“ (Angelo 16.11.2026): je gewähltem Space bzw. Teilbaum ein Auftrag, der für
     * jede im Bestand bekannte Seite Historie, Versionen, Kommentare, Properties und Restriktionen über die
     * dokumentierten REST-Schnittstellen der Quelle holt und als eigene JSON-Datei in {@code directory} ablegt;
     * der Seitenexport und seine Hashes bleiben unverändert. Nur für Quellen mit REST-Abfragen
     * ({@link #sourceQueryExportsSupported}).
     *
     * @return die eingeplanten Aufträge; der Dateiname je Auftrag steht in
     *         {@code SourceQueryExports.fileName}
     */
    public List<JobStatus> startMetadataExport(com.aresstack.spaceharvester.application.holdings.Selection selection,
                                               Path directory) {
        List<JobStatus> started = harvester.sourceQueryExports().startMetadata(caller, selection, directory);
        started.forEach(status -> activities.remember(status.id()));
        return started;
    }

    /**
     * „Analytics exportieren“ (Angelo 16.11.2026): wie {@link #startMetadataExport}, aber Aktivität und Seitenaufrufe
     * über die Endpunkte des Confluence-Analytics-Plugins ({@code queries}, aus Einstellungen › Analytics-Export);
     * antwortet die Quelle nicht, scheitert nur dieser Auftrag.
     */
    public List<JobStatus> startAnalyticsExport(
            com.aresstack.spaceharvester.application.holdings.Selection selection, Path directory,
            com.aresstack.spaceharvester.application.metadata.AnalyticsQueries queries) {
        List<JobStatus> started = harvester.sourceQueryExports().startAnalytics(caller, selection, directory,
                queries);
        started.forEach(status -> activities.remember(status.id()));
        return started;
    }

    /** Ob die Quelle REST-Abfragen beantwortet, also Metadaten- und Analytics-Export anbietet (nur Confluence). */
    public boolean sourceQueryExportsSupported(String sourceId) {
        return harvester.sourceQueryExports().supports(sourceId);
    }

    /** Artefakt eines fertigen Exports ({@code GET /exports/{id}}). */
    public Optional<ExportArtifact> exportArtifact(String exportId) {
        ownExport(exportId);
        return harvester.exports().artifact(exportId, Harvester.OWNER);
    }

    /** Eine Datei des Artefakts ({@code GET /exports/{id}/files/{name}}). */
    public Optional<InputStream> exportFile(String exportId, String name) {
        ownExport(exportId);
        return harvester.exports().artifactFile(exportId, Harvester.OWNER, name);
    }

    /**
     * Datenbestand eines abgeschlossenen Exports ({@code GET /exports/{id}/dataset}); leer, solange keiner vorliegt.
     * Nur dann bietet die Oberfläche „Exportieren als …“ an.
     */
    public Optional<ExportDatasetInfo> exportDataset(String exportId) {
        ownExport(exportId);
        return harvester.conversions().dataset(exportId, Harvester.OWNER);
    }

    /**
     * Erzeugt aus dem Datenbestand eines Exports eine Datei im Ordner {@code directory}, ohne die Quelle erneut zu
     * lesen ({@code GET /exports/{id}/conversions/{format}}); überschreibt nie.
     */
    public ExportConversions.ConvertedFile convertExport(String exportId, String format, Path directory) {
        ownExport(exportId);
        return harvester.conversions().convert(exportId, Harvester.OWNER, format, directory);
    }

    /**
     * „Datei erzeugen“ als eigener Auftrag (Angelo 16.11.2026): die Datei im Format aus dem Datenbestand des eigenen
     * Exports im Ordner, sobald der Export erfolgreich endet; ein offener Auftrag mit gleichem Export, Format und Ordner
     * kommt zurück. Die erzeugte Datei liest {@link #producedFile}.
     */
    public JobStatus startConversion(String exportId, String format, Path directory) {
        ownExport(exportId);
        JobStatus status = harvester.conversions().startConversion(exportId, Harvester.OWNER, format, directory);
        activities.remember(status.id());
        return status;
    }

    /** Die Datei eines beendeten eigenen Auftrags „Datei erzeugen“ aus diesem Lauf; leer solange er läuft. */
    public Optional<ExportConversions.ConvertedFile> producedFile(String jobId) {
        return harvester.conversions().produced(jobId, Harvester.OWNER);
    }

    /**
     * Ein Format-Knopf (Angelo 16.11.2026): je gewähltem Space bzw. Teilbaum ({@code Holdings.outputs}) zwei Aufträge,
     * erst die Daten, dann die Datei. Liegen für die Wurzel Seiten samt ihren Bildern und Anhängen im Bestand
     * ({@code ExportAssetCollection.inHoldings}), ist der erste die Ausgabe aus dem Bestand
     * ({@link #startExportFromHoldings}, wiederverwendet, wenn sich nichts geändert hat); sonst lädt er von der Quelle,
     * was fehlt ({@link #startExport(String, String, String, Integer, List)}). Der zweite erzeugt die Datei, sobald der
     * erste und seine Bilder und Anhänge erfolgreich enden ({@link #startConversion}).
     *
     * @param datasetFormats Formate des Datenbestands, wie bei einem Download
     * @return je Wurzel der Daten-Auftrag und danach der Auftrag „Datei erzeugen“
     */
    public List<JobStatus> exportFiles(com.aresstack.spaceharvester.application.holdings.Selection selection,
                                       List<String> datasetFormats, String format, Path directory) {
        Objects.requireNonNull(directory, "directory");
        harvester.conversions().converter(format);
        List<com.aresstack.spaceharvester.application.holdings.Selection.Root> outputs =
                harvester.holdings().outputs(caller, selection);
        outputs.forEach(root -> requireExport(root.sourceId(), root.spaceKey()));
        List<JobStatus> started = new ArrayList<>(2 * outputs.size());
        for (com.aresstack.spaceharvester.application.holdings.Selection.Root root : outputs) {
            // Aus dem Bestand nur, wenn jede lesbare Seite aktuell dort liegt, samt Bildern und Anhängen; sonst indexiert
            // der Export erst, was fehlt oder veraltet ist (Angelo, 20.11.2026: gespeicherte, aktuelle Seiten liest auch
            // er nur aus dem Cache), und legt Bilder und Anhänge im Bestand ab.
            com.aresstack.spaceharvester.application.holdings.HoldingCounts counts = harvester.holdings().resolve(caller,
                    com.aresstack.spaceharvester.application.holdings.Selection.of(root)).counts();
            boolean stored = counts.local() > 0 && counts.local() >= counts.pages() - counts.locked()
                    && harvester.assetCollection() != null
                    && harvester.assetCollection().inHoldings(Harvester.OWNER, root);
            JobStatus data = stored
                    ? harvester.exports().startFromHoldings(ExportRequest.fromHoldings(root.sourceId(),
                    root.spaceKey(), Harvester.OWNER, datasetFormats).withRoot(root.pageId()))
                    : harvester.exports().start(root.sourceId(), root.spaceKey(), root.pageId(), null,
                    Harvester.OWNER, datasetFormats);
            activities.remember(data.id());
            started.add(data);
            started.add(startConversion(data.id(), format, directory));
        }
        return started;
    }

    /** Export abbrechen ({@code POST /exports/{id}/cancel}). */
    public JobStatus cancelExport(String exportId) {
        ownExport(exportId);
        return harvester.jobs().cancel(exportId, Harvester.OWNER);
    }

    // ------------------------------------------------------------------ Aufträge

    /** Status eines Auftrags dieser App, unabhängig von seiner Art. */
    public JobStatus job(String jobId) {
        JobStatus status = ownStatus(jobId);
        requireVisible(status, visibility(status));
        return status;
    }

    /**
     * Die Eigentümer, deren Aufträge die Auftragsliste zeigt (Angelo: Aufträge sind global sichtbar): der Core für
     * Abgleiche, Exporte und Downloads, der Aufrufer der App für Zusammenfassen, Synonymlauf und Aussagenanalyse.
     */
    private List<String> jobOwners() {
        String own = caller.jobOwner();
        return own == null || own.equals(Harvester.OWNER) ? List.of(Harvester.OWNER) : List.of(Harvester.OWNER, own);
    }

    /** Stand eines Auftrags eines der {@link #jobOwners()}; ein fremder gilt als unbekannt. */
    private JobStatus ownStatus(String jobId) {
        JobStatus status = harvester.jobs().status(jobId);
        if (!jobOwners().contains(status.owner())) {
            throw new UnknownJobException(jobId);
        }
        return status;
    }

    /** Welches Recht ein Auftrag zum Anzeigen braucht: Exporte die Export-Freigabe, alle anderen die zum Abgleichen. */
    private static AccessKind visibility(JobStatus status) {
        return status.type() == JobType.EXPORT || status.type() == JobType.CONVERSION ? AccessKind.EXPORT
                : AccessKind.SYNC;
    }

    /**
     * Hält einen Download oder Abgleich an (Pause, ADR 0025): er beendet seinen Schritt und steht dann auf
     * „Angehalten“; was er geladen hat, bleibt.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.JobNotResumableException bei anderen Auftragsarten
     */
    public JobStatus pauseJob(String jobId) {
        JobStatus status = job(jobId);
        return status.type() == JobType.SYNC ? harvester.syncs().pause(jobId, Harvester.OWNER).status()
                : harvester.jobs().pause(jobId, status.owner());
    }

    /**
     * Setzt einen angehaltenen, gescheiterten oder abgebrochenen Download oder Abgleich im selben Eintrag fort (ADR
     * 0025); er holt nur, was noch fehlt. Geht das nicht ({@link #canResume}), startet die App ihn mit denselben
     * Angaben neu, was ebenfalls nur den Rest holt.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.JobNotResumableException wenn er sich nicht im selben
     *                                                                               Eintrag fortsetzen lässt
     */
    public JobStatus resumeJob(String jobId) {
        JobStatus status = job(jobId);
        if (!Harvester.OWNER.equals(status.owner())) {
            // Aufträge des Aufrufers (Zusammenfassen, Synonymlauf, Aussagenanalyse) setzt der Core selbst fort.
            return harvester.jobActions().perform(jobId, status.owner(), JobAction.RESUME,
                    java.util.function.UnaryOperator.identity(), caller);
        }
        if (imageRun(status)) {
            return harvester.imageDescriptions().resume(caller, jobId);
        }
        if (graphRun(status)) {
            return harvester.knowledgeGraphJobs().resume(caller, jobId);
        }
        return switch (status.type()) {
            case SYNC -> harvester.syncs().resume(jobId, Harvester.OWNER).status();
            case ASSETS, ATTACHMENTS -> harvester.conversions().resumeImages(jobId, Harvester.OWNER);
            // Metadaten und Analytics: ein neuer Lauf im selben Eintrag, auch nach einem Neustart (Angelo 17.11.2026).
            case METADATA_EXPORT, ANALYTICS_EXPORT -> harvester.jobActions().perform(jobId, Harvester.OWNER,
                    JobAction.RESUME);
            // „Daten bereinigen“, auch das Löschen einer entfernten Quelle: im selben Eintrag von vorn (Fundstelle 22).
            case PURGE -> harvester.jobActions().perform(jobId, Harvester.OWNER, JobAction.RESUME);
            default -> harvester.resumeExport(jobId);
        };
    }

    /** Ein Unterauftrag „Bilder beschreiben“ im Hintergrund (D2). */
    private boolean imageRun(JobStatus status) {
        return status.type() == JobType.BACKGROUND && harvester.imageDescriptions() != null
                && com.aresstack.spaceharvester.application.images.ImageDescriptionJobs.KIND.equals(
                status.parameter(com.aresstack.spaceharvester.application.background.BackgroundJobs.KIND));
    }

    /**
     * Ein Abschlussteil „Wissensgraph erzeugen“ (W5): ▶ setzt ihn nur mit den Rechten seines Eigentümers fort, sonst
     * hält er wieder gelb an.
     */
    private boolean graphRun(JobStatus status) {
        return status.type() == JobType.BACKGROUND && harvester.knowledgeGraphJobs() != null
                && com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND.equals(
                status.parameter(com.aresstack.spaceharvester.application.background.BackgroundJobs.KIND));
    }

    /**
     * Der wirksame Zustand eines Auftrags mit den Aktionen, die die Oberfläche jetzt anbietet, in Anzeigereihenfolge
     * ({@link JobLifecycleState#actions()}).
     */
    public JobLifecycleState jobState(String jobId) {
        return job(jobId).lifecycle();
    }

    /**
     * Führt eine Aktion auf einem Auftrag dieser App aus, nachdem der Core sie am Zustand geprüft hat
     * ({@link com.aresstack.spaceharvester.application.jobcontrol.JobActions}). Fortsetzen bleibt im selben Eintrag,
     * auch nach einem Neustart der App; Löschen entfernt nur den Auftrag, nie die geladenen Daten.
     *
     * @return der Zustand danach; bei {@link JobAction#DELETE} der letzte vor dem Löschen
     * @throws com.aresstack.spaceharvester.application.jobs.JobActionNotAllowedException wenn der Zustand die Aktion
     *                                                                                   nicht anbietet
     */
    public JobStatus performJobAction(String jobId, JobAction action) {
        JobStatus current = job(jobId);
        String owner = current.owner();
        if (action == JobAction.RESUME
                && com.aresstack.spaceharvester.application.assets.PageAssetHarvests.pageHarvest(current)) {
            // Fundstelle 24: Die Anhangsliste samt Rechten kennt nur die App; sie wird erst geholt, wenn er läuft.
            return harvester.pageAssetHarvests().resume(jobId, owner,
                    () -> visibleAttachments(current.sourceId(), current.rootPageId(), AccessKind.SYNC));
        }
        if (action == JobAction.RESUME && imageRun(current)) {
            // D2: ein vom Benutzer gestarteter Lauf „Bilder beschreiben“ läuft mit dessen Rechten weiter.
            return harvester.imageDescriptions().resume(caller, jobId);
        }
        if (action == JobAction.RESUME && graphRun(current)) {
            return harvester.knowledgeGraphJobs().resume(caller, jobId);
        }
        JobStatus status = Harvester.OWNER.equals(owner) ? harvester.performJobAction(jobId, action)
                : harvester.jobActions().perform(jobId, owner, action, java.util.function.UnaryOperator.identity(),
                        caller);
        if (action == JobAction.DELETE) {
            activities.forget(jobId);
        }
        return status;
    }

    /**
     * „Alle anhalten“ (Angelo 11.11.2026): hält alle wartenden und laufenden Aufträge an, die sich fortsetzen lassen
     * ({@link AllJobs#toPause}), wartende vor laufenden, damit keiner in einen frei werdenden Platz nachrückt. Ein
     * laufender beendet noch seinen Schritt; was er geladen hat, bleibt.
     */
    public AllJobs.Outcome pauseAllJobs() {
        return AllJobs.apply(AllJobs.toPause(jobs()), job -> pauseJob(job.id()));
    }

    /**
     * Wie {@link #pauseAllJobs()}, aber nur für einen Reiter der Auftragsverwaltung: {@code background} nur
     * Hintergrundaufträge, sonst nur die übrigen (UX 21.11.2026).
     */
    public AllJobs.Outcome pauseAllJobs(boolean background) {
        return AllJobs.apply(AllJobs.toPause(jobs(background)), job -> pauseJob(job.id()));
    }

    /** Wie {@link #resumeAllJobs()}, nur für einen Reiter ({@link #pauseAllJobs(boolean)}). */
    public AllJobs.Outcome resumeAllJobs(boolean background) {
        return AllJobs.apply(AllJobs.toResume(jobs(background)),
                job -> performJobAction(job.id(), JobAction.RESUME));
    }

    /** Wie {@link #cancelAllJobs()}, nur für einen Reiter ({@link #pauseAllJobs(boolean)}). */
    public AllJobs.Outcome cancelAllJobs(boolean background) {
        return AllJobs.apply(AllJobs.toCancel(jobs(background)),
                job -> performJobAction(job.id(), JobAction.CANCEL));
    }

    /**
     * Folgt einem eigenen Auftrag bis zu seinem Ende (Fundstelle 24): {@code listener} läuft auf dem Event Dispatch
     * Thread mit dem Stand, wenn er angehalten wird (danach geht es weiter), und einmal mit dem Endstand (fertig,
     * gescheitert, abgebrochen oder gelöscht); danach ist er abgemeldet. Anmelden und erste Prüfung laufen nicht auf
     * dem EDT; gemeldet vom Core, ohne Abfragen (ADR 0027).
     */
    public Subscription whenJobFinished(String jobId, java.util.function.Consumer<JobStatus> listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<com.aresstack.spaceharvester.application.jobs.JobState> last =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Subscription> registered = new java.util.concurrent.atomic
                .AtomicReference<>();
        Runnable unsubscribe = () -> {
            Subscription own = registered.getAndSet(Subscription.NONE);
            if (own != null) {
                own.close();
            }
        };
        java.util.function.BiConsumer<JobStatus, Boolean> report = (status, removed) -> {
            if (done.get()) {
                return;
            }
            com.aresstack.spaceharvester.application.jobs.JobState state = status.state();
            if (!removed && state == com.aresstack.spaceharvester.application.jobs.JobState.PAUSED) {
                if (last.getAndSet(state) != com.aresstack.spaceharvester.application.jobs.JobState.PAUSED) {
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        if (!done.get()) {
                            listener.accept(status);
                        }
                    });
                }
                return;
            }
            if (!removed && !state.terminal()) {
                last.set(state);
                return;
            }
            if (done.compareAndSet(false, true)) {
                // Nach dem Ende abgemeldet: Der Zuhörer hielt sonst seinen Aufrufer (etwa ein Panel) fest.
                unsubscribe.run();
                javax.swing.SwingUtilities.invokeLater(() -> listener.accept(status));
            }
        };
        Thread.ofVirtual().name("job-watch").start(() -> {
            try {
                Subscription watch = harvester.jobs().subscribe(Harvester.OWNER, new JobListener() {
                    @Override
                    public void changed(JobStatus status) {
                        if (status.id().equals(jobId)) {
                            report.accept(status, false);
                        }
                    }

                    @Override
                    public void removed(JobStatus gone) {
                        if (gone.id().equals(jobId)) {
                            report.accept(gone, true);
                        }
                    }
                });
                if (!registered.compareAndSet(null, watch)) {
                    // Schon geendet oder vom Aufrufer abgemeldet, bevor die Anmeldung zurückkam.
                    watch.close();
                }
                currentStatus(jobId).ifPresent(status -> report.accept(status, false));
            } catch (RuntimeException e) {
                System.getLogger(Workspace.class.getName()).log(System.Logger.Level.DEBUG,
                        "Auftrag {0} nicht beobachtet ({1})", jobId,
                        e.getClass().getName());
            }
        });
        return () -> {
            done.set(true);
            unsubscribe.run();
        };
    }

    /**
     * Abonniert das Ende einer Hintergrundgruppe (Angelo 21.11.2026): {@code listener} läuft einmal auf dem Event
     * Dispatch Thread, sobald kein Unterauftrag mehr wartet oder läuft, sofort, wenn sie schon fertig ist. Bis dahin
     * zeigt die abonnierende Stelle etwa den drehenden Ring ({@code controls.BackgroundWait}).
     */
    public com.aresstack.spaceharvester.application.observe.Subscription whenBackgroundFinished(String groupId,
            java.util.function.Consumer<com.aresstack.spaceharvester.application.background.BackgroundGroup> listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        return harvester.backgroundJobs().whenFinished(groupId,
                group -> javax.swing.SwingUtilities.invokeLater(() -> listener.accept(group)));
    }

    /**
     * Verfolgt eine Hintergrundgruppe (Reiter „Aussagen“, „mit Unterseiten“): {@code listener} läuft auf dem Event
     * Dispatch Thread mit dem jetzigen Stand und danach bei jeder Änderung eines Unterauftrags, über Meldungen des
     * Core, ohne Abfragen (ADR 0027), auch nach dem Anhalten, bis {@link Subscription#close()}.
     */
    public Subscription watchBackground(String groupId,
            java.util.function.Consumer<com.aresstack.spaceharvester.application.background.BackgroundGroup> listener) {
        return watchBackground(groupId, listener, () -> { });
    }

    /**
     * Wie {@link #watchBackground(String, java.util.function.Consumer)}; kennt diese Laufzeit die Gruppe beim Anmelden
     * nicht (etwa nach dem Umschalten der Ablage, W5), läuft stattdessen {@code missing} auf dem Event Dispatch Thread,
     * einmal, sofern bis dahin keine Änderung gemeldet wurde.
     */
    public Subscription watchBackground(String groupId,
            java.util.function.Consumer<com.aresstack.spaceharvester.application.background.BackgroundGroup> listener,
            Runnable missing) {
        java.util.Objects.requireNonNull(listener, "listener");
        java.util.Objects.requireNonNull(missing, "missing");
        com.aresstack.spaceharvester.application.background.BackgroundJobs background = harvester.backgroundJobs();
        java.util.concurrent.atomic.AtomicBoolean pushed = new java.util.concurrent.atomic.AtomicBoolean();
        Subscription watch = background.watch(groupId, group -> {
            pushed.set(true);
            javax.swing.SwingUtilities.invokeLater(() -> listener.accept(group));
        });
        // Den jetzigen Stand liest ein eigener Thread aus dem Auftragsspeicher, nie der EDT; eine schon gemeldete
        // Änderung ist neuer und gilt.
        Thread.ofVirtual().name("watch-background").start(() -> {
            Optional<com.aresstack.spaceharvester.application.background.BackgroundGroup> found =
                    background.group(groupId);
            if (pushed.get()) {
                return;
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (!pushed.get()) {
                    if (found.isPresent()) {
                        listener.accept(found.get());
                    } else {
                        missing.run();
                    }
                }
            });
        });
        return watch;
    }

    /**
     * Wie viele Seiten der KI-Analysator in einer Gruppe der Aussagenanalyse bewusst ausließ (ohne Freigabe oder Modell,
     * {@link StatementAnalysisJobs#omitted}); nie auf dem EDT. Die Teile gehören dem Core: gelesen wird mit dem Recht,
     * das auch der Lauf braucht ({@code metadata} auf den Space), nicht mit {@code sync} wie in der Auftragsliste; ein
     * schon gelöschter Teil zählt nicht.
     */
    public long statementGroupOmitted(
            com.aresstack.spaceharvester.application.background.BackgroundGroup group) {
        long omitted = 0;
        for (JobStatus part : group.parts()) {
            if (StatementAnalysisJobs.isAnalysis(part) && access.permits(caller,
                    scope(part.sourceId(), part.partitionKey()), AccessKind.METADATA)) {
                omitted += StatementAnalysisJobs.omitted(ownEvents(part.id()));
            }
        }
        return omitted;
    }

    /**
     * Bricht eine Gruppe der Aussagenanalyse ab („Abbrechen“ im Reiter „Aussagen“), mit demselben Recht wie
     * {@link #statementGroupOmitted}; die fertigen Analysen bleiben.
     */
    public void cancelStatementGroup(String groupId) {
        com.aresstack.spaceharvester.application.background.BackgroundJobs background = harvester.backgroundJobs();
        com.aresstack.spaceharvester.application.background.BackgroundGroup group = background.group(groupId)
                .orElse(null);
        if (group == null || !StatementAnalysisJobs.KIND.equals(group.kind())) {
            return;
        }
        for (JobStatus part : group.parts()) {
            if (!part.state().terminal() && access.permits(caller, scope(part.sourceId(), part.partitionKey()),
                    AccessKind.METADATA)) {
                try {
                    harvester.jobs().cancel(part.id(), Harvester.OWNER);
                } catch (RuntimeException e) {
                    // schon beendet
                }
            }
        }
    }

    /**
     * Was „Wissensgraph erzeugen“ (W5) über {@code pages} jetzt anlegen würde: je Stufe des Dienst-Planers die Zahl der
     * Seiten, erst zu ladende, schon laufende und abgeschaltete; startet nichts. Nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.background.services.ServicePreview previewKnowledgeGraph(
            com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.servicePlanner().preview(caller,
                com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND, pages);
    }

    /**
     * Startet „Wissensgraph erzeugen“ (W5) über {@code pages}: die fehlenden Vorstufen und je Space den Abschlussteil als
     * eine Hintergrundgruppe, sichtbar im Reiter „Hintergrund“. Nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.background.services.ServicePlan startKnowledgeGraph(
            com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.servicePlanner().start(caller,
                com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND, pages);
    }

    /**
     * Wie viele Seiten von {@code pages} „Wissensgraph erzeugen“ (W5) ohne KI-Freigabe ausließe, jede nur einmal: mit
     * dem KI-Analysator die der Aussagenanalyse, dazu immer die Seiten mit Bildern, die „Bilder beschreiben“ ohne
     * Freigabe nicht beschreibt ({@code Harvester#knowledgeGraphNotReleased}). Nie auf dem EDT.
     */
    public int knowledgeGraphNotReleased(com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.knowledgeGraphNotReleased(caller, pages).size();
    }

    /**
     * Eine laufende oder angehaltene Gruppe von „Wissensgraph erzeugen“ (W5) des Aufrufers, die eine Seite von
     * {@code pages} betrifft, etwa nach einem Neustart weiterlaufend oder in einem anderen Fenster gestartet; die
     * jüngste. Des Aufrufers heißt: Ihr Abschlussteil gehört ihm ({@code KnowledgeGraphJobs.OWNER}) und er darf ihn
     * sehen. Einmal gefragt, danach folgt der Reiter ihr über {@link #watchBackground}; nie auf dem EDT.
     */
    public Optional<com.aresstack.spaceharvester.application.background.BackgroundGroup> runningKnowledgeGraph(
            com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        String kind = com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND;
        for (com.aresstack.spaceharvester.application.background.BackgroundGroup group
                : harvester.backgroundJobs().groups()) {
            if (!kind.equals(group.kind()) || group.parts().stream().noneMatch(part -> !part.state().terminal()
                    || part.state() == com.aresstack.spaceharvester.application.jobs.JobState.PAUSED)) {
                continue;
            }
            boolean mine = group.parts().stream()
                    .filter(part -> kind.equals(part.parameter(
                            com.aresstack.spaceharvester.application.background.BackgroundJobs.KIND)))
                    .filter(part -> caller.jobOwner().equals(part.parameter(
                            com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.OWNER)))
                    .filter(part -> access.permits(caller, scope(part.sourceId(), part.partitionKey()),
                            AccessKind.METADATA))
                    .anyMatch(part -> com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs
                            .pages(part).asMap().getOrDefault(part.sourceId(), java.util.Set.of()).stream()
                            .anyMatch(pageId -> pages.contains(part.sourceId(), pageId)));
            if (mine) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    /**
     * Die gelben Hinweise aus den Ereignissen einer Gruppe von „Wissensgraph erzeugen“ (W5): die Abdeckung je Space
     * („in ‚DEV‘: 3 von 5 Seiten …“), wenn etwas fehlt ({@code KnowledgeGraphJobs.COVERAGE}, nur Zahlen), angehaltene
     * fremde Teile ({@code RESUME_BY_HAND}), die Obergrenze von „Bilder beschreiben“ ({@code LIMIT_REACHED}) und Seiten,
     * die der KI-Analysator bewusst ausließ. Gelesen mit demselben Recht wie
     * {@link #statementGroupOmitted}; nie auf dem EDT, ein schon gelöschter Teil zählt nicht.
     */
    public List<String> knowledgeGraphNotes(
            com.aresstack.spaceharvester.application.background.BackgroundGroup group) {
        java.util.Set<String> notes = new java.util.LinkedHashSet<>();
        long omitted = 0;
        for (JobStatus part : group.parts()) {
            if (!access.permits(caller, scope(part.sourceId(), part.partitionKey()), AccessKind.METADATA)) {
                continue;
            }
            List<JobEvent> events = ownEvents(part.id());
            if (StatementAnalysisJobs.isAnalysis(part)) {
                omitted += StatementAnalysisJobs.omitted(events);
            }
            for (JobEvent event : events) {
                if (event.severity() != JobEvent.Severity.WARNING || event.message() == null) {
                    continue;
                }
                if (com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.COVERAGE
                        .equals(event.code())) {
                    String coverage = event.message();
                    if (com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs
                            .coverageOutdated(part, group.parts())) {
                        // Eine Vorstufe wurde nach dem Abschlussteil wiederholt: neu zählen statt des alten Stands.
                        coverage = currentCoverage(part);
                    }
                    if (coverage != null) {
                        // Der Ort ist der Space, wie bei „gescheitert in ‚DEV‘“ (UX 22.11.2026).
                        notes.add((part.partitionKey() == null ? "" : "in ‚" + part.partitionKey() + "‘: ")
                                + coverage);
                    }
                } else if (com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.RESUME_BY_HAND
                        .equals(event.code())) {
                    notes.add(event.message());
                } else if (com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions.LIMIT_REACHED
                        .equals(event.code())) {
                    // „Bilder beschreiben“ an der Obergrenze: fertig, aber unvollständig (Codex #1081); nur Zahlen.
                    notes.add((part.partitionKey() == null ? "" : "in ‚" + part.partitionKey() + "‘: ")
                            + event.message());
                }
            }
        }
        if (omitted > 0) {
            notes.add("Aussagenanalyse: " + (omitted == 1 ? "1 Seite" : omitted + " Seiten")
                    + " bewusst ausgelassen (ohne KI-Freigabe oder Modell)");
        }
        return List.copyOf(notes);
    }

    /**
     * Die Abdeckung eines Abschlussteils von „Wissensgraph erzeugen“ jetzt, mit seinem Analysator und über seine
     * Seiten; {@code null}, wenn nichts fehlt (dann gibt es keinen gelben Hinweis) oder sie sich nicht zählen lässt.
     */
    private String currentCoverage(JobStatus goal) {
        try {
            StatementAnalysisJobs.Coverage coverage = harvester.statementAnalysisJobs().coverage(caller,
                    com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.pages(goal),
                    com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.parse(goal.parameter(
                            com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.ANALYZER)));
            return coverage.complete() ? null
                    : com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.message(coverage);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Die Aufträge eines Reiters: nur die des Reiters „Hintergrund“ ({@link #inBackgroundTab}) oder nur die übrigen. */
    private List<JobStatus> jobs(boolean background) {
        return jobs().stream().filter(job -> inBackgroundTab(job) == background).toList();
    }

    /**
     * Ob ein Auftrag im Reiter „Hintergrund“ steht: die Hintergrundaufträge ({@link JobType#background()}) und die
     * Anreicherung, die die App nach jedem Abgleich von sich aus anstößt
     * ({@link com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync#TASK}). Deren Netzarbeit läuft
     * ohnehin als „KI-Anreicherung“ im Hintergrund; Angelo 23.11.2026: „etwas läuft entweder im Vordergrund oder im
     * Hintergrund, aber nicht beides“. Unter „Aufträge“ bleibt, was der Benutzer gestartet hat. „Alle anhalten“ und
     * „Alle abbrechen“ eines Reiters treffen genau seine Zeilen.
     */
    public static boolean inBackgroundTab(JobStatus job) {
        return job.type().background() || job.type() == JobType.ENRICHMENT
                && com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync.TASK.equals(
                job.parameter(com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync
                        .TASK_PARAMETER));
    }

    /**
     * Eine Aktion für alle Unteraufträge einer Hintergrundgruppe (ADR 0025 Nachtrag), die Zeile der Gruppe im Reiter
     * „Hintergrund“: Anhalten, Fortsetzen, Abbrechen, Jetzt starten und Löschen wirken auf jeden Unterauftrag, für den
     * es jetzt geht.
     */
    public AllJobs.Outcome performBackgroundGroupAction(String groupId, JobAction action) {
        com.aresstack.spaceharvester.application.background.BackgroundJobs background = harvester.backgroundJobs();
        List<JobStatus> parts = background.group(groupId)
                .map(com.aresstack.spaceharvester.application.background.BackgroundGroup::parts).orElse(List.of());
        return switch (action) {
            case PAUSE -> AllJobs.apply(AllJobs.toPause(parts), job -> pauseJob(job.id()));
            case RESUME -> AllJobs.apply(parts.stream().filter(job -> job.lifecycle().allows(JobAction.RESUME))
                    .toList(), job -> performJobAction(job.id(), JobAction.RESUME));
            case CANCEL -> AllJobs.apply(AllJobs.toCancel(parts), job -> performJobAction(job.id(), JobAction.CANCEL));
            case START_NOW, DELETE -> AllJobs.apply(parts.stream().filter(job -> job.lifecycle().allows(action))
                    .toList(), job -> performJobAction(job.id(), action));
            default -> new AllJobs.Outcome(0, 0, null);
        };
    }

    /**
     * Wiederholt einzelne Unteraufträge einer Hintergrundgruppe (W5: der Kreispfeil hinter einer gescheiterten Stufe
     * von „Wissensgraph erzeugen“): nur die aus {@code jobIds}, die zu dieser Gruppe gehören, gescheitert sind (nie
     * vom Benutzer abgebrochene, Auflage W5) und sich fortsetzen lassen, über denselben Weg wie ▶ in der Auftragsverwaltung
     * ({@link #performJobAction}, mit den Rechten des Aufrufers; ein fremder Teil hält dort wieder gelb an). Andere
     * Teile der Gruppe bleiben, wie sie sind. Nie auf dem EDT.
     */
    public AllJobs.Outcome retryBackgroundParts(String groupId, java.util.Collection<String> jobIds) {
        java.util.Set<String> wanted = java.util.Set.copyOf(jobIds);
        List<JobStatus> parts = harvester.backgroundJobs().group(groupId)
                .map(com.aresstack.spaceharvester.application.background.BackgroundGroup::parts).orElse(List.of());
        return AllJobs.apply(parts.stream().filter(job -> wanted.contains(job.id()))
                .filter(job -> job.state() == com.aresstack.spaceharvester.application.jobs.JobState.FAILED)
                .filter(job -> job.lifecycle().allows(JobAction.RESUME)).toList(),
                job -> performJobAction(job.id(), JobAction.RESUME));
    }

    /**
     * „Alle fortsetzen“: setzt alle angehaltenen Aufträge im selben Eintrag fort ({@link AllJobs#toResume}), älteste
     * zuerst; fertige, gescheiterte und abgebrochene bleiben, wie sie sind.
     */
    public AllJobs.Outcome resumeAllJobs() {
        return AllJobs.apply(AllJobs.toResume(jobs()), job -> performJobAction(job.id(), JobAction.RESUME));
    }

    /**
     * „Alle abbrechen“: bricht alle wartenden und laufenden Aufträge ab ({@link AllJobs#toCancel}), wartende zuerst.
     * Angehaltene und beendete bleiben, wie sie sind.
     */
    public AllJobs.Outcome cancelAllJobs() {
        return AllJobs.apply(AllJobs.toCancel(jobs()), job -> performJobAction(job.id(), JobAction.CANCEL));
    }

    /**
     * Für die Restzeit im Fortschrittsbalken: durchschnittliche Dauer je tatsächlich geladener Datei, global über alle
     * Exporte und Neustarts, getrennt für Bilder ({@link JobType#ASSETS}) und Anhänge ({@link JobType#ATTACHMENTS});
     * vor der ersten Datei ein Startwert. Ein einzelner Transfer, ohne Parallelität; Treffer aus dem Speicher und
     * Seitentexte zählen nicht.
     *
     * @throws IllegalArgumentException für andere Auftragsarten
     */
    public java.time.Duration averageDownload(JobType type) {
        return harvester.averageDownload(type);
    }

    /** Wie viele Bilder- und Anhängeaufträge gleichzeitig vorbereiten ({@code jobs.parallel-preparations}). */
    public int parallelPreparations() {
        return harvester.parallelPreparations();
    }

    /**
     * Ändert {@link #parallelPreparations()} sofort, ohne die Konfigurationsdatei
     * ({@link com.aresstack.spaceharvester.app.settings.ConfigFileEditor#withParallel}).
     *
     * @throws IllegalArgumentException außerhalb von 1 bis 16
     */
    public void setParallelPreparations(int value) {
        harvester.parallelPreparations(value);
    }

    /** Wie viele Bilder- und Anhängeaufträge gleichzeitig laden ({@code jobs.parallel-downloads}); 0 lädt nichts. */
    public int parallelDownloads() {
        return harvester.parallelDownloads();
    }

    /**
     * Ändert {@link #parallelDownloads()} sofort, ohne die Konfigurationsdatei. Bei 0 werden Bilder und Anhänge nur
     * vorbereitet (Zustand „Vorbereitet“) und laden erst, wenn wieder ein Platz frei ist.
     *
     * @throws IllegalArgumentException außerhalb von 0 bis 64
     */
    public void setParallelDownloads(int value) {
        harvester.parallelDownloads(value);
    }

    /**
     * Gleichzeitige Verbindungen je ladendem Bilder- oder Anhängeauftrag ({@code jobs.download-connections}, Angelo
     * 11.11.2026).
     */
    public int downloadConnections() {
        return harvester.downloadConnections();
    }

    /**
     * Ändert {@link #downloadConnections()} sofort, ohne die Konfigurationsdatei; die Verteilung auf die ladenden
     * Aufträge gilt sofort, ein laufender Ladedurchgang nutzt höchstens so viele Verbindungen wie bei seinem Start.
     *
     * @throws IllegalArgumentException außerhalb von 0 bis 64
     */
    public void setDownloadConnections(int value) {
        harvester.downloadConnections(value);
    }

    /** Ob sich der Auftrag jetzt im selben Eintrag fortsetzen lässt ({@link #resumeJob}). */
    public boolean canResume(String jobId) {
        job(jobId);
        return harvester.jobs().resumable(jobId, job(jobId).owner());
    }

    /**
     * Alle Aufträge dieser App, die die Auftragsablage noch kennt, neueste zuerst. Abgelaufene oder fremde
     * Aufträge vergisst die Liste.
     */
    public List<JobStatus> jobs() {
        List<JobStatus> result = new ArrayList<>();
        for (String id : activities.ids()) {
            JobStatus status;
            try {
                status = ownStatus(id);
            } catch (UnknownJobException e) {
                // Nur was die Auftragsablage nicht mehr kennt, fällt aus der Liste.
                activities.forget(id);
                continue;
            } catch (RuntimeException e) {
                continue;
            }
            try {
                requireVisible(status, visibility(status));
                result.add(status);
            } catch (RuntimeException e) {
                // Ohne Recht auf den Space (z. B. nach geänderter Konfiguration) bleibt der Auftrag unsichtbar, aber
                // gemerkt: eine kurz verweigerte Prüfung (etwa beim Start) löscht ihn nicht aus der Liste.
            }
        }
        // Alle Aufträge der Eigentümer auch direkt aus der Auftragsablage, jeder Art (Fundstelle 4, Angelo 21.11.2026:
        // nichts läuft unsichtbar): auch Abgleiche und Neueinlesen, die der Core selbst anstößt (Nachholen beim Start,
        // Abgleich vor der Suche, Inventur, Wiederaufnahme), und Exporte samt ihren Bildern, Anhängen und Dateien, die
        // als Unterzeilen nie fehlen dürfen, auch wenn die gemerkte Liste sie verloren hat. Hintergrundaufträge
        // (ADR 0025 Nachtrag) stehen im Reiter „Hintergrund“.
        List<JobStatus> followUps = new ArrayList<>();
        for (String owner : jobOwners()) {
            for (JobType type : JobType.values()) {
                followUps.addAll(harvester.jobs().list(owner, type));
            }
        }
        for (JobStatus followUp : followUps) {
            if (result.stream().noneMatch(known -> known.id().equals(followUp.id()))) {
                try {
                    requireVisible(followUp, visibility(followUp));
                    result.add(followUp);
                } catch (RuntimeException e) {
                    // wie oben: ohne Recht auf den Space unsichtbar
                }
            }
        }
        result.sort(Comparator.comparing(JobStatus::createdAt).reversed());
        return result;
    }

    /** Bricht einen eigenen Auftrag beliebiger Art ab, etwa eine Anreicherung. */
    public JobStatus cancelJob(String jobId) {
        return harvester.jobs().cancel(jobId, job(jobId).owner());
    }

    /**
     * Diagnoseereignisse eines Auftrags in ihrer Reihenfolge (docs/DIAGNOSTICS.md); bereits maskiert. Ein fremder
     * oder nicht mehr sichtbarer Auftrag gilt als unbekannt.
     */
    public List<JobEvent> events(String jobId) {
        JobStatus status = job(jobId);
        // Läufe des Aufrufers (Aussagenanalyse, „In PPTX umwandeln“ …) gehören ihm, nicht dem Core.
        return Harvester.OWNER.equals(status.owner()) ? harvester.events(jobId)
                : harvester.jobs().events(jobId, status.owner());
    }

    /**
     * Bestandsübersicht eines Space (ADR 0027): je Seite des Katalogs der eigene Zustand (nur in der Quelle, lokal,
     * veraltet, gesperrt) und die Zähler ihres Teilbaums, dazu Generation und Stand vom; ohne Quellenzugriff. Einmal
     * beim Aufklappen lesen, danach nur nach Meldungen von {@link #subscribeHoldings}.
     */
    public com.aresstack.spaceharvester.application.holdings.SpaceHoldings holdings(String sourceId, String spaceKey) {
        return harvester.holdings().overview(caller, sourceId, spaceKey);
    }

    /**
     * Nach einer Meldung nur diese Seiten samt ihrer Vorfahren, damit nur der Pfad nach oben neu gezeichnet wird; eine
     * Seite, die es im Space nicht mehr gibt, steht dort als entfernt.
     */
    public com.aresstack.spaceharvester.application.holdings.SpaceHoldings holdings(String sourceId, String spaceKey,
                                                                                java.util.Collection<String> pageIds) {
        return harvester.holdings().overview(caller, sourceId, spaceKey, pageIds);
    }

    /** Je Space der Quelle, den der Cache kennt, Zähler und Stand vom, ohne Seitenliste (Zeilen einer Quelle). */
    public List<com.aresstack.spaceharvester.application.holdings.SpaceSummary> holdingSpaces(String sourceId) {
        return harvester.holdings().spaces(caller, sourceId);
    }

    /**
     * Löst die Auswahl im Baum zu Seiten auf (ADR 0029): eine Seite samt allen Unterseiten, ein Space oder eine Quelle,
     * auch mehrere zugleich. Liefert die Seiten, die Abdeckung („n von m Seiten lokal“) und ob die Auflösung lückenhaft
     * ist, weil ein Space keine Inventur hat; ohne Quellenzugriff. Nach Meldungen von {@link #subscribeHoldings} für
     * einen berührten Space neu auflösen.
     */
    public com.aresstack.spaceharvester.application.holdings.PageSelection resolveSelection(
            com.aresstack.spaceharvester.application.holdings.Selection selection) {
        return harvester.holdings().resolve(caller, selection);
    }

    /**
     * {@link #resolveSelection} nachsichtig (ADR 0034 §4): Wurzeln, die der Aufrufer nicht sehen kann oder die der
     * Katalog nicht mehr kennt (etwa ein veralteter Favorit), fallen heraus und stehen in {@code skippedRoots}, statt die
     * ganze Auflösung zu brechen. Für Sammlungen; nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.holdings.Holdings.Lenient resolveLenient(
            com.aresstack.spaceharvester.application.holdings.Selection selection) {
        return harvester.holdings().resolveLenient(caller, selection);
    }

    /**
     * Die Nachfahren einer Seite ohne die Seite selbst ({@code Holdings.descendants}, Fahrplan P1): für „mit
     * Unterseiten“ in den Analyse-Reitern. Nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.holdings.PageSelection descendants(String sourceId,
                                                                                       String spaceKey,
                                                                                       String pageId) {
        return harvester.holdings().descendants(caller, sourceId, spaceKey, pageId);
    }

    /**
     * Die Verweise der Unterseiten einer Seite aus dem Verzeichnis ({@code PageNavigation.subpageReferences}, Fahrplan
     * P3). Nie auf dem EDT.
     */
    public List<PageNavigation.SubpageReference> subpageReferences(
            com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.navigation().subpageReferences(caller, pages);
    }

    /**
     * Je Suchwert die Seiten einer Menge ({@code TermQueries.pagesByTerm}, Fahrplan P3), etwa für die Herkunft einer
     * Beziehung unter „Aus Unterseiten“. Nie auf dem EDT.
     */
    public java.util.Map<String, List<com.aresstack.spaceharvester.application.snapshot.PageKey>> termPages(
            com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.terms().pagesByTerm(caller, pages);
    }

    /** Titel und Space von Seiten aus dem Cache ({@code PageNavigation.nodes}); Seiten ohne Recht fehlen. */
    public java.util.Map<com.aresstack.spaceharvester.application.snapshot.PageKey,
            com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode> pageNodes(
            java.util.Collection<com.aresstack.spaceharvester.application.snapshot.PageKey> keys) {
        return harvester.navigation().nodes(caller, keys);
    }

    /**
     * Je Begriff die Zahl der Seiten einer Menge ({@code TermQueries.pageCounts}, Fahrplan P1), etwa für „auch auf N
     * Unterseiten“. Nie auf dem EDT.
     */
    public java.util.Map<TermQueries.TermRef, Integer> termPageCounts(
            com.aresstack.spaceharvester.application.snapshot.PageSet pages) {
        return harvester.terms().pageCounts(caller, pages);
    }

    // --- KI-Freigabe je Seite (ADR 0028) ----------------------------------------------------------------------------

    /**
     * Trägt genau eine lokal gespeicherte Seite ein, ohne Unterseiten: so, wenn der Chat sie per Werkzeug an das Modell
     * liefert (ADR 0033). Schreibt in die Ablage: nie auf dem EDT aufrufen.
     *
     * @return der neue Eintrag oder leer
     */
    public List<com.aresstack.spaceharvester.application.release.PageReleases.Entry> releasePageForChat(
            String spaceKey, com.aresstack.spaceharvester.application.snapshot.PageKey key) {
        return harvester.pageReleaseService().releasePage(caller, spaceKey, key);
    }

    /**
     * Die Liste freigegebener Seiten gebündelt nach Quelle, Space und Knoten, für die Tabelle unter Einstellungen ›
     * Sicherheit. Einmal lesen, danach nur nach Meldungen von {@link #subscribePageReleases}.
     */
    public List<com.aresstack.spaceharvester.application.release.PageReleaseService.Group> pageReleaseGroups() {
        return harvester.pageReleaseService().groups(caller);
    }

    /**
     * Die Seiten einer Gruppe mit Titel, Zeitpunkt und ob sie indexiert sind.
     *
     * @param node Seite des Knotens oder {@code null} für den ganzen Space
     */
    public List<com.aresstack.spaceharvester.application.release.PageReleaseService.ReleasedPage> releasedPages(
            String sourceId, String spaceKey, String node) {
        return harvester.pageReleaseService().pages(caller, sourceId, spaceKey, node);
    }

    /** Entfernt Seiten von der Liste; ihre KI-Ergebnisse entfallen im Hintergrund. Nie auf dem EDT. */
    public int removePageReleases(java.util.Collection<PageKey> pages) {
        return harvester.pageReleaseService().remove(caller, pages).size();
    }

    /** Entfernt eine Gruppe der Liste ({@code node} wie bei {@link #releasedPages}). Nie auf dem EDT. */
    public int removePageReleaseGroup(String sourceId, String spaceKey, String node) {
        return harvester.pageReleaseService().removeGroup(caller, sourceId, spaceKey, node).size();
    }

    /** Entfernt alle Seiten eines Space von der Liste. Nie auf dem EDT. */
    public int removePageReleaseSpace(String sourceId, String spaceKey) {
        return harvester.pageReleaseService().removeSpace(caller, sourceId, spaceKey).size();
    }

    /**
     * Meldet einen Empfänger für jede Änderung der Liste freigegebener Seiten an (ADR 0027): neu eingetragene und
     * entfernte Seiten, die diese App sehen darf, nach dem Speichern, auf dem Thread, der die Liste änderte (nie über
     * den EDT gereicht).
     */
    public Subscription subscribePageReleases(
            java.util.function.Consumer<com.aresstack.spaceharvester.application.release.PageReleases.Change>
                    listener) {
        return harvester.pageReleaseService().subscribe(caller, listener);
    }

    /** Die Updates einer Quelle aus dem Änderungsprotokoll (ADR 0030), neuestes zuerst, das offene vorn. */
    public java.util.List<com.aresstack.spaceharvester.application.changelog.ChangeUpdate> changeUpdates(
            String sourceId) {
        return harvester.changeLog().updates(caller, sourceId);
    }

    /**
     * Die Updates einer Ebene (ADR-0030-Nachtrag, TZ1): {@code spaceKey == null} die ganze Quelle, sonst derselbe
     * Satz Marken mit Zählern nur für diesen Space ({@code pages() == 0}: keine Änderung im Space). Nie auf dem EDT.
     */
    public java.util.List<com.aresstack.spaceharvester.application.changelog.ChangeUpdate> changeUpdates(
            String sourceId, String spaceKey) {
        return harvester.changeLog().updates(caller, sourceId, spaceKey);
    }

    /** Der Änderungsbericht eines Updates in einer Ebene; {@code spaceKey == null} die ganze Quelle. Nie auf dem EDT. */
    public com.aresstack.spaceharvester.application.changelog.ChangeReport changeReport(String sourceId,
                                                                                       String updateId,
                                                                                       String spaceKey) {
        return harvester.changeLog().report(caller, sourceId, updateId, spaceKey);
    }

    /**
     * Ein Ausschnitt des Update-Texts einer Ebene (ADR 0031, {@code update-text/v1}): {@code limit} Seiten ab
     * {@code offset} samt Bericht mit Unterschieden und Lage jeder Seite im Text. Nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.changelog.UpdateText updateText(String sourceId, String updateId,
                                                                                    String spaceKey, int offset,
                                                                                    int limit) {
        return harvester.changeLog().updateText(caller, sourceId, updateId, spaceKey, offset, limit);
    }

    /** Der Änderungsbericht eines Updates (ADR 0030): die geänderten Seiten nach Art; bei der Erstbefüllung nur Zähler. */
    public com.aresstack.spaceharvester.application.changelog.ChangeReport changeReport(String sourceId,
                                                                                       String updateId) {
        return harvester.changeLog().report(caller, sourceId, updateId);
    }

    /**
     * Die jüngste Update-Marke einer Quelle (ADR 0030); liest nur die Marken, keine Protokollzeilen, für
     * „Nach dem Update Änderungen anzeigen“. Nie auf dem EDT.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.changelog.UpdateMark> latestChangeMark(
            String sourceId) {
        return harvester.changeLog().latestMark(caller, sourceId);
    }

    /**
     * Jüngste Inhaltsänderung je Seite seit {@code from} in einer Quelle und, wenn angegeben, einem Space (ADR 0030),
     * aus dem Änderungsprotokoll; nur Seiten, die der Aufrufer sehen darf. Für „Was ist neu?“ im Chat, das die
     * Schnittmenge mit den Seiten der Auswahl bildet. Nie auf dem EDT.
     */
    public java.util.Map<com.aresstack.spaceharvester.application.snapshot.PageKey, java.time.Instant>
            latestContentChanges(String sourceId, String spaceKey, java.time.Instant from) {
        return harvester.changeLog().latestContentChanges(caller, sourceId, spaceKey, from);
    }

    /** Ob ein Chat-Modell für Update-Zusammenfassungen eingestellt ist (ADR 0030, Abschnitt 5). */
    public boolean changeSummariesAvailable() {
        return harvester.changeLog().summariesAvailable();
    }

    /**
     * Die gespeicherte Zusammenfassung eines Updates, wie diese App sie sehen darf; leer ohne, wenn verfallen oder wenn
     * eine eingegangene Seite nicht sichtbar ist. Nie auf dem EDT.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.changelog.UpdateSummary> changeSummary(
            String sourceId, String updateId) {
        return harvester.changeLog().summary(caller, sourceId, updateId);
    }

    /**
     * Die Status-quo-Sicht der Auswahl links (ADR 0032, FL6): alle Aussagen mit Stand, fertig sortiert und gezählt.
     * Reine Auswertung im Core; trotzdem nie auf dem EDT rufen. {@code cancelled} bricht mit
     * {@link java.util.concurrent.CancellationException} ab.
     */
    public com.aresstack.spaceharvester.application.standing.StatusQuoView statusQuo(
            com.aresstack.spaceharvester.application.holdings.PageSelection selection,
            java.util.function.BooleanSupplier cancelled) {
        return harvester.statusQuo().view(caller, selection, cancelled);
    }

    /**
     * Die Leseempfehlung der Auswahl links (ADR 0032, FL6): Seiten mit Summe und offenen Teilzahlen. Mit Thema
     * ({@code topic}) nur Seiten, die es erwähnen; {@code null} oder leer für die ganze Auswahl. Nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.standing.ReadingList readingList(
            com.aresstack.spaceharvester.application.holdings.PageSelection selection, String topic,
            java.util.function.BooleanSupplier cancelled) {
        return harvester.readingAdvice().recommend(caller, selection, topic, cancelled);
    }

    /**
     * Der Wortlaut zu Einträgen der Status-quo-Sicht (FL6): Originalsatz, vereinfachter Satz, Satzgrenzen im Lesetext.
     * Ohne Leserecht am Inhalt fehlt der Eintrag. Nie auf dem EDT, am besten nur für sichtbare Zeilen.
     */
    public java.util.Map<String, com.aresstack.spaceharvester.application.standing.SentenceText> standingSentences(
            java.util.Collection<com.aresstack.spaceharvester.application.standing.StatusQuoEntry> entries) {
        return harvester.statusQuo().sentences(caller, entries);
    }

    /** Setzt den Stand einer Aussage ({@code statementId}) oder Seite ({@code null}) von Hand (ADR 0032). */
    public com.aresstack.spaceharvester.application.standing.StandingDecision decideStanding(
            PageKey page, String statementId, com.aresstack.spaceharvester.application.standing.Standing standing,
            long expectedRevision) {
        return harvester.statusQuo().decide(caller, page, statementId, standing, expectedRevision);
    }

    /** Löscht den Stand von Hand; danach gelten wieder die Regeln. Eine Prüfung im selben Eintrag bleibt. */
    public boolean clearStanding(PageKey page, String statementId, long expectedRevision) {
        return harvester.statusQuo().clear(caller, page, statementId, expectedRevision);
    }

    /**
     * Bestätigt oder verwirft eine Aussage von Hand (ADR 0032 W3), für die Ablage des Analysators, in deren Analyse sie
     * steht. Revision des Eintrags gemeinsam mit dem Stand.
     */
    public com.aresstack.spaceharvester.application.standing.StandingDecision reviewStatement(PageKey page,
            String statementId, com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review review,
            long expectedRevision) {
        return harvester.statusQuo().review(caller, page, statementId, review, expectedRevision);
    }

    /**
     * Wie {@link #reviewStatement(PageKey, String, com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review, long)};
     * {@code expectedRevision == null} setzt den festen Wert ohne Revisionsprüfung (✓/✕ im Overlay „Belege“, W3).
     */
    public com.aresstack.spaceharvester.application.standing.StandingDecision reviewStatement(PageKey page,
            String statementId, com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review review,
            Long expectedRevision) {
        return harvester.statusQuo().review(caller, page, statementId, review, expectedRevision);
    }

    /** Nimmt die Prüfung einer Aussage zurück (eigener Eintrag im Protokoll); ein Stand von Hand bleibt. */
    public boolean withdrawStatementReview(PageKey page, String statementId, long expectedRevision) {
        return harvester.statusQuo().withdrawReview(caller, page, statementId, expectedRevision);
    }

    /**
     * Prüfstand von Aussagen je Seite für ✓/✕ im Overlay „Belege“ (W3): ob der Nutzer prüfen darf (sync, nicht
     * gesperrt) und die gespeicherten Entscheidungen samt Revision ({@code StatusQuo#reviews}). Nie auf dem EDT.
     */
    public java.util.Map<PageKey, com.aresstack.spaceharvester.application.standing.StatusQuo.Reviews>
            statementReviews(java.util.Map<PageKey, ? extends java.util.Collection<String>> statements) {
        return harvester.statusQuo().reviews(caller, statements);
    }

    /**
     * Wie {@link #statementReviews(java.util.Map)}, in der Ablage genau des genannten Analysators: der eines Belegs im
     * Overlay, gleich welcher gerade eingestellt ist (W3).
     */
    public java.util.Map<PageKey, com.aresstack.spaceharvester.application.standing.StatusQuo.Reviews>
            statementReviews(com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice analyzer,
                             java.util.Map<PageKey, ? extends java.util.Collection<String>> statements) {
        return harvester.statusQuo().reviews(caller, analyzer, statements);
    }

    /** Setzt ✓/✕ in der Ablage genau des genannten Analysators, ohne Revisionsprüfung (W3). */
    public com.aresstack.spaceharvester.application.standing.StandingDecision reviewStatement(PageKey page,
            String statementId, com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice analyzer,
            com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review review) {
        return harvester.statusQuo().review(caller, page, statementId, analyzer, review, null);
    }

    /** Nimmt die Prüfung in der Ablage genau des genannten Analysators zurück, gegen die gelesene Revision (W3). */
    public boolean withdrawStatementReview(PageKey page, String statementId,
            com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice analyzer, long expectedRevision) {
        return harvester.statusQuo().withdrawReview(caller, page, statementId, analyzer, expectedRevision);
    }

    /** Das Protokoll der Entscheidungen zu Aussage oder Seite, älteste zuerst. */
    public List<com.aresstack.spaceharvester.application.standing.StandingDecisionStore.AuditEntry> standingHistory(
            PageKey page, String statementId) {
        return harvester.statusQuo().history(caller, page, statementId);
    }

    /** Meldet Entscheidungen zum Stand (ADR 0027), auf dem Zustell-Thread des Core; danach die Sicht neu lesen. */
    public Subscription subscribeStanding(Runnable listener) {
        return harvester.statusQuo().subscribe(Objects.requireNonNull(listener, "listener"));
    }

    /** Die gespeicherte Zusammenfassung eines Updates in einer Ebene (ADR 0031); {@code spaceKey == null} die Quelle. */
    public java.util.Optional<com.aresstack.spaceharvester.application.changelog.UpdateSummary> changeSummary(
            String sourceId, String updateId, String spaceKey) {
        return harvester.changeLog().summary(caller, sourceId, updateId, spaceKey);
    }

    /** Ob der Podcast zur Update-Zusammenfassung erzeugt werden kann: Chat-Modell und Sprachausgabe (TTS) eingerichtet. */
    public boolean podcastsAvailable() {
        return harvester.podcasts().available();
    }

    /**
     * Der Stand des Podcasts einer Zusammenfassung, leer, solange nichts erzeugt ist. Die Zusammenfassung kommt aus
     * {@link #changeSummary}, ist also schon für diese App geprüft. Liest Dateien, nie auf dem EDT.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.podcast.Podcast> podcast(
            com.aresstack.spaceharvester.application.changelog.UpdateSummary summary) {
        return harvester.podcasts().podcast(summary);
    }

    /**
     * Die Grenzen der Parallelität beider Podcast-Arten aus dem Menü der Ansicht (Angelo 24.11.2026): Texte und Audio,
     * und der eigene Pool der Bilder; je Lauf frisch gelesen.
     */
    public void podcastParallel(java.util.function.IntSupplier texts, java.util.function.IntSupplier pictures) {
        harvester.podcasts().parallel(texts, pictures);
        harvester.pagePodcasts().parallel(texts, pictures);
    }

    /** Startet den Auftrag „Podcast erzeugen“ oder setzt ihn fort; ein offener Auftrag desselben Podcasts ist die Antwort. */
    public JobStatus startPodcast(com.aresstack.spaceharvester.application.changelog.UpdateSummary summary) {
        return harvester.podcasts().start(caller, summary);
    }

    /**
     * „Podcast neu erstellen“: Plan, Texte, Audio und Bilder fallen weg, ein neuer Auftrag beginnt; wirft, während der
     * Podcast entsteht.
     */
    public JobStatus renewPodcast(com.aresstack.spaceharvester.application.changelog.UpdateSummary summary) {
        return harvester.podcasts().renew(caller, summary);
    }

    /** Meldet jede abgelegte Stufe eines Podcasts (Plan, Abschnitte, Audio), auf einem Thread des Core. */
    public Subscription subscribePodcast(String key, Runnable listener) {
        return harvester.podcasts().subscribe(key, listener);
    }

    /**
     * Wovon Thema {@code topic} des Podcasts erzählt, als Nummern der Themen der Zusammenfassung; die Leseansicht
     * rollt dorthin und markiert sie. Liest eine Datei, nie auf dem EDT.
     */
    public List<Integer> podcastStatements(com.aresstack.spaceharvester.application.changelog.UpdateSummary summary,
                                           int topic) {
        return harvester.podcasts().statements(summary, topic);
    }

    /**
     * Die fertigen Bilder der Themen des Podcasts einer Zusammenfassung, in Themenreihenfolge; die Leseansicht stellt
     * sie neben die Meldung, von der sie erzählen. Liest Dateien, nie auf dem EDT.
     */
    public List<com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.TopicPicture> podcastPictures(
            com.aresstack.spaceharvester.application.changelog.UpdateSummary summary) {
        return harvester.podcasts().topicPictures(summary);
    }

    /** Ob der Erklär-Podcast einer Seite erzeugt werden kann: Chat-Modell und Sprachausgabe (TTS) eingerichtet. */
    public boolean pagePodcastsAvailable() {
        return harvester.pagePodcasts().available();
    }

    /**
     * Der Text, den die Leseansicht einer Seite vorliest (Angelo 23.11.2026); leer ohne Leserecht, ohne lokalen Stand
     * oder ohne KI-Freigabe (ADR 0028). Liest, nie auf dem EDT.
     */
    public java.util.Optional<String> pageReadAloudText(String sourceId, String pageId) {
        return harvester.pagePodcasts().readAloudText(caller, sourceId, pageId);
    }

    /** Ob ein Dienst mit Sprachausgabe eingerichtet ist (auch auf dem EDT). */
    public boolean pageReadAloudAvailable() {
        return harvester.pagePodcasts().readAloudAvailable();
    }

    /** Vorlesen über die Sprachausgabe mit Modell und Stimme des Podcasts. Fragt den Dienst, nie auf dem EDT. */
    public com.aresstack.spaceharvester.application.podcast.ReadAloud pageReadAloud(
            com.aresstack.spaceharvester.application.podcast.ReadAloud.Playback playback) {
        return harvester.pagePodcasts().readAloud(playback);
    }

    /** Der Schlüssel des Erklär-Podcasts einer Seite, ohne zu lesen (auch auf dem EDT). */
    public String pagePodcastKey(String sourceId, String pageId) {
        return com.aresstack.spaceharvester.application.podcast.PagePodcasts.key(caller, sourceId, pageId);
    }

    /**
     * Der Stand des Erklär-Podcasts einer Seite (Angelo 21.11.2026), leer, solange nichts erzeugt ist oder wenn sich
     * der Inhalt seither geändert hat. Liest Seiten und Dateien, nie auf dem EDT.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.podcast.Podcast> pagePodcast(String sourceId,
                                                                                                   String pageId) {
        return harvester.pagePodcasts().podcast(caller, sourceId, pageId);
    }

    /** Der gesprochene Text des Podcasts einer Zusammenfassung, soweit geschrieben. Liest Dateien, nie auf dem EDT. */
    public java.util.Optional<String> podcastTranscript(
            com.aresstack.spaceharvester.application.changelog.UpdateSummary summary) {
        return harvester.podcasts().transcript(summary);
    }

    /** Der gesprochene Text des Erklär-Podcasts einer Seite, soweit geschrieben. Liest Dateien, nie auf dem EDT. */
    public java.util.Optional<String> pagePodcastTranscript(String sourceId, String pageId) {
        return harvester.pagePodcasts().transcript(caller, sourceId, pageId);
    }

    /**
     * Wovon ein Thema des Erklär-Podcasts erzählt, als Stellen im Lesetext der Seite (für Markierung und Bild). Liest
     * Seite und Datei, nie auf dem EDT.
     */
    public java.util.List<com.aresstack.spaceharvester.application.podcast.PagePodcasts.Span> pagePodcastFocus(
            String sourceId, String pageId, int topic) {
        return harvester.pagePodcasts().focus(caller, sourceId, pageId, topic);
    }

    /** Startet den Erklär-Podcast einer Seite oder setzt ihn fort. */
    public JobStatus startPagePodcast(String sourceId, String pageId) {
        return harvester.pagePodcasts().start(caller, sourceId, pageId);
    }

    /** Meldet jede abgelegte Stufe eines Erklär-Podcasts, auf einem Thread des Core. */
    public Subscription subscribePagePodcast(String key, Runnable listener) {
        return harvester.pagePodcasts().subscribe(key, listener);
    }

    /**
     * Ob die abgelegte Zusammenfassung eines Updates noch gilt (Rechte, KI-Freigabe), ohne Inhalt; einmal beim Wechsel
     * der Update-Kennung (Abnahme #1037). Nie auf dem EDT.
     */
    public boolean changeSummaryStillValid(String sourceId, String updateId, String spaceKey) {
        return harvester.changeLog().summaryStillValid(caller, sourceId, updateId, spaceKey);
    }

    /** Startet „Zusammenfassen“ für ein Update in einer Ebene (ADR 0031); {@code spaceKey == null} die ganze Quelle. */
    public JobStatus summarizeUpdate(String sourceId, String updateId, String spaceKey) {
        return harvester.changeLog().summarize(caller, sourceId, updateId, spaceKey);
    }

    /**
     * Die Bereichszusammenfassung einer Quelle ({@code spaceKey == null}) oder eines Space (Angelo 24.11.2026: Umschalter
     * „Änderungen | Bereich“, ADR 0034 §7): der ganze Bestand statt der Änderungen. Nie auf dem EDT.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.changelog.UpdateSummary> areaSummary(
            String sourceId, String spaceKey) {
        return harvester.changeLog().areaSummary(caller, area(sourceId, spaceKey));
    }

    /** Ob Bereichszusammenfassungen gehen (Chat-Modell eingestellt). */
    public boolean areaSummariesAvailable() {
        return harvester.changeLog().areasAvailable();
    }

    /**
     * Startet „Zusammenfassen“ für den Bestand einer Quelle oder eines Space.
     *
     * @param interactive ein Klick auf „Zusammenfassen“; {@code false} für den Lauf beim Öffnen
     */
    public JobStatus summarizeArea(String sourceId, String spaceKey, boolean interactive) {
        return harvester.changeLog().summarizeArea(caller, area(sourceId, spaceKey), interactive);
    }

    /**
     * Die Bereichszusammenfassung einer beliebigen Auswahl, etwa eines Favoriten-Ordners (ADR 0034 §7, Paket 5c):
     * abgelegt je Aufrufer nach den aufgelösten Wurzeln, nachsichtig aufgelöst. Nie auf dem EDT.
     */
    public java.util.Optional<com.aresstack.spaceharvester.application.changelog.UpdateSummary> areaSummary(
            com.aresstack.spaceharvester.application.holdings.Selection selection) {
        return harvester.changeLog().areaSummary(caller, selection);
    }

    /**
     * Startet „Zusammenfassen“ für den Bestand einer beliebigen Auswahl.
     *
     * @param interactive ein Klick auf „Zusammenfassen“; {@code false} für den Lauf beim Öffnen
     */
    public JobStatus summarizeArea(com.aresstack.spaceharvester.application.holdings.Selection selection,
                                   boolean interactive) {
        return harvester.changeLog().summarizeArea(caller, selection, interactive);
    }

    private static com.aresstack.spaceharvester.application.holdings.Selection area(String sourceId, String spaceKey) {
        return com.aresstack.spaceharvester.application.holdings.Selection.of(spaceKey == null
                ? com.aresstack.spaceharvester.application.holdings.Selection.Root.source(sourceId)
                : com.aresstack.spaceharvester.application.holdings.Selection.Root.space(sourceId, spaceKey));
    }

    /**
     * Startet „Zusammenfassen“ für ein Update als Auftrag (ADR 0030); nur auf Wunsch. Fertig meldet sich das Ergebnis
     * über {@link #subscribeChangeLog(String, com.aresstack.spaceharvester.application.changelog.ChangeLogListener)}.
     */
    public JobStatus summarizeUpdate(String sourceId, String updateId) {
        return harvester.changeLog().summarize(caller, sourceId, updateId);
    }

    /**
     * Meldet einen Empfänger für neue Zeilen und Marken im Änderungsprotokoll einer Quelle an (ADR 0027/0030),
     * gebündelt auf dem Zustell-Thread des Core, nie auf dem EDT. Erst anmelden, dann lesen.
     */
    public Subscription subscribeChangeLog(String sourceId,
                                           com.aresstack.spaceharvester.application.changelog.ChangeLogListener
                                                   listener) {
        return harvester.changeLog().subscribe(caller, sourceId, listener);
    }

    /**
     * Rohe Zeilen des Änderungsprotokolls (ADR 0030) für den Dialog unter Einstellungen › Cache, neueste zuerst; nur
     * Zeilen, die diese App sehen darf. Nie auf dem EDT.
     */
    public com.aresstack.spaceharvester.application.changelog.ChangeEntries changeEntries(
            com.aresstack.spaceharvester.application.changelog.ChangeFilter filter) {
        return harvester.changeLog().entries(caller, filter);
    }

    /**
     * Meldet einen Empfänger für neue Zeilen und Marken im Änderungsprotokoll aller Quellen an (ADR 0027); Meldungen
     * kommen gebündelt auf dem Zustell-Thread des Core, nie auf dem EDT.
     */
    public Subscription subscribeChangeLog(
            com.aresstack.spaceharvester.application.changelog.ChangeLogListener listener) {
        return harvester.changeLog().subscribe(caller, null, listener);
    }

    /**
     * Meldet einen Empfänger für den Bestand eines Space an, mit leerem {@code spaceKey} für alle Spaces der Quelle
     * (ADR 0027). Meldungen kommen gebündelt auf dem Zustell-Thread des Core, nie auf dem EDT. Erst anmelden, dann
     * {@link #holdings} lesen.
     */
    public Subscription subscribeHoldings(String sourceId, String spaceKey,
                                          com.aresstack.spaceharvester.application.holdings.HoldingsListener listener) {
        return harvester.holdings().subscribe(caller, sourceId, spaceKey, listener);
    }

    /**
     * Meldet einen Empfänger für die Aufträge dieser App an (ADR 0027), statt die Liste in Abständen zu lesen: jede
     * Änderung von Zustand, Zählern, Phase oder Pause, jede Löschung und jedes neue Diagnoseereignis, gebündelt auf dem
     * Zustell-Thread des Core (nie auf dem EDT). Wie bei {@link #jobs()} und {@link #recentEvents} nur sichtbare
     * Aufträge. Erst anmelden, dann {@link #jobs()} lesen, dann geht nichts verloren.
     */
    public Subscription subscribeJobs(JobListener listener) {
        Objects.requireNonNull(listener, "listener");
        JobListener filtered = new JobListener() {
            @Override
            public void changed(JobStatus status) {
                if (visible(status)) {
                    listener.changed(status);
                }
            }

            @Override
            public void removed(JobStatus last) {
                // Erst die Rechte am letzten Stand verbergen eine Löschung, deren Auftrag schon unsichtbar war.
                if (visible(last)) {
                    listener.removed(last);
                }
            }

            @Override
            public void event(JobEvent event) {
                if (access.permits(caller, scope(event.sourceId(), event.partitionKey()),
                        event.type() == JobType.EXPORT ? AccessKind.EXPORT : AccessKind.SYNC)) {
                    listener.event(event);
                }
            }
        };
        // Je Eigentümer eine Anmeldung (Core und Aufrufer der App), gemeinsam abgemeldet.
        List<Subscription> subscriptions = new ArrayList<>();
        for (String owner : jobOwners()) {
            subscriptions.add(harvester.jobs().subscribe(owner, filtered));
        }
        return () -> subscriptions.forEach(Subscription::close);
    }

    private boolean visible(JobStatus status) {
        try {
            requireVisible(status, visibility(status));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Die jüngsten Diagnoseereignisse aller sichtbaren Aufträge dieser App, neueste zuerst. */
    public List<JobEvent> recentEvents(int limit) {
        return harvester.recentEvents(limit).stream()
                .filter(event -> access.permits(caller, scope(event.sourceId(), event.partitionKey()),
                        event.type() == JobType.EXPORT ? AccessKind.EXPORT : AccessKind.SYNC))
                .toList();
    }

    /**
     * Löscht einen Auftrag in jedem Zustand samt Diagnoseereignissen und nimmt ihn aus der Liste; einen wartenden oder
     * laufenden bricht das vorher ab. Exportdateien, die der Benutzer gespeichert hat, und der Seitenbestand bleiben.
     */
    public void deleteJob(String jobId) {
        harvester.jobs().delete(jobId, job(jobId).owner());
        activities.forget(jobId);
    }

    /**
     * Löscht alle sichtbaren Aufträge in jedem Zustand („Alle Aufträge löschen“); wartende und laufende werden vorher
     * abgebrochen. @return Anzahl
     */
    public int deleteAllJobs() {
        return deleteAllJobs(jobs().stream().map(JobStatus::id).toList());
    }

    /**
     * Wie {@link #deleteAllJobs()}, nur für die genannten Aufträge, etwa die eines Reiters der Auftragsliste: laufende
     * und wartende werden vorher abgebrochen. Ein offenes Löschen der Daten einer Quelle bleibt (Fundstelle 22, Abnahme
     * #1090); die übrigen werden trotzdem gelöscht. @return Anzahl
     */
    public int deleteAllJobs(java.util.Collection<String> jobIds) {
        int deleted = 0;
        for (String id : jobIds) {
            try {
                deleteJob(id);
                deleted++;
            } catch (UnknownJobException e) {
                activities.forget(id);
            } catch (JobStillRunningException e) {
                // ein offenes Löschen der Daten einer Quelle bleibt
            }
        }
        return deleted;
    }

    /** Löscht alle sichtbaren beendeten Aufträge; wartende und laufende bleiben. @return Anzahl */
    public int deleteFinishedJobs() {
        int deleted = 0;
        for (JobStatus status : jobs()) {
            if (status.state().terminal()) {
                try {
                    deleteJob(status.id());
                    deleted++;
                } catch (UnknownJobException e) {
                    activities.forget(status.id());
                } catch (JobStillRunningException e) {
                    // ein offenes Löschen der Daten einer Quelle bleibt
                }
            }
        }
        return deleted;
    }

    /**
     * Löscht genau diese Aufträge, soweit sie sichtbar und beendet sind; schon gelöschte oder wieder laufende bleiben
     * unberührt. @return Anzahl
     */
    public int deleteJobs(List<String> jobIds) {
        int deleted = 0;
        for (String id : jobIds) {
            try {
                if (job(id).state().terminal()) {
                    deleteJob(id);
                    deleted++;
                }
            } catch (UnknownJobException | JobStillRunningException e) {
                // Inzwischen gelöscht oder wieder aktiv: bleibt unberührt.
            }
        }
        return deleted;
    }

    /** Löscht ein Diagnoseereignis eines beendeten, sichtbaren Auftrags; {@code false}, wenn es schon fehlt. */
    public boolean deleteEvent(String jobId, long sequence) {
        job(jobId);
        return harvester.deleteEvent(jobId, sequence);
    }

    /**
     * Löscht die Ereignisse sichtbarer beendeter Aufträge vor {@code before}; die Aufträge bleiben. Aufträge, die die
     * App nicht (mehr) sehen darf, z. B. einer entfernten Quelle, bleiben unberührt. @return Anzahl
     */
    public int deleteEventsBefore(Instant before) {
        int deleted = 0;
        for (JobStatus status : jobs()) {
            if (status.state().terminal()) {
                try {
                    deleted += harvester.jobs().deleteEventsBefore(status.id(), before, status.owner());
                } catch (UnknownJobException | JobStillRunningException e) {
                    // Inzwischen gelöscht oder (neu gestartet) wieder aktiv: dann bleibt er unberührt.
                }
            }
        }
        return deleted;
    }

    // ------------------------------------------------------------------ Anhänge

    /** Anhänge einer Seite mit Art und Ablagestand ({@code …/pages/{pageId}/assets}). */
    public PageAssets assets(String sourceId, String pageId) {
        return harvester.assets().overview(visibleAttachments(sourceId, pageId, AccessKind.METADATA));
    }

    /**
     * „Anhänge ablegen“: alle Anhänge einer Seite als Auftrag „Anhänge beschaffen“ ablegen (Fundstelle 24, Angelo
     * 21.11.2026: nichts läuft unsichtbar); braucht sync. Der Fortschritt steht in der Auftragsliste, Abbrechen geht
     * dort; „Fortsetzen“ holt nur, was fehlt ({@link #performJobAction}).
     */
    public JobStatus startAssetHarvest(String sourceId, String pageId) {
        JobStatus status = harvester.pageAssetHarvests().start(visibleAttachments(sourceId, pageId, AccessKind.SYNC),
                Harvester.OWNER);
        activities.remember(status.id());
        return status;
    }

    /** Inhalt eines Anhangs ({@code …/assets/{id}/content}); legt nur mit sync-Freigabe ab. */
    public Assets.AssetContent assetContent(String sourceId, String pageId, String attachmentId) {
        PageAttachmentList list = visibleAttachments(sourceId, pageId, AccessKind.CONTENT);
        boolean mayStore = access.permits(caller, ResourceScope.page(sourceId, list.spaceKey(), pageId), AccessKind.SYNC);
        return harvester.assets().content(list, attachmentId, mayStore, Harvester.OWNER);
    }

    /**
     * Gibt Anhänge einer Seite zum Vorladen an den Hintergrundteil „Anhänge vorladen“ ihres Space ab (Fundstelle 18);
     * geladen und abgelegt wird wie mit {@link #assetContent}. Leer bei nur einem Platz: Dann lädt die App selbst vor.
     */
    public java.util.Optional<JobStatus> prefetchAttachments(String sourceId, String pageId, List<String> attachmentIds) {
        PageAttachmentList list = visibleAttachments(sourceId, pageId, AccessKind.CONTENT);
        return harvester.attachmentPrefetches().request(sourceId, list.spaceKey(), pageId, attachmentIds,
                (source, page, attachment) -> assetContent(source, page, attachment));
    }

    /**
     * Wie {@link #assetContent}, aber als Strom ({@code Assets.stream}, #571): ein abgelegter Anhang kommt aus der
     * Ablage, ohne ganz im Speicher zu liegen. Der Aufrufer schließt den Strom; ein Hashfehler kommt beim Lesen am
     * Ende als {@link java.io.IOException}.
     */
    public Assets.AssetStream assetStream(String sourceId, String pageId, String attachmentId) {
        PageAttachmentList list = visibleAttachments(sourceId, pageId, AccessKind.CONTENT);
        boolean mayStore = access.permits(caller, ResourceScope.page(sourceId, list.spaceKey(), pageId), AccessKind.SYNC);
        return harvester.assets().stream(list, attachmentId, mayStore, Harvester.OWNER);
    }

    /**
     * Vorschau eines Anhangs, wie die Quelle sie erzeugt ({@code …/assets/{id}/preview}, #183): die erste verfügbare
     * in der Reihenfolge {@link com.aresstack.spaceharvester.application.sources.AttachmentPreview#order()}; legt
     * nur mit sync-Freigabe ab.
     */
    public Assets.AssetContent assetPreview(String sourceId, String pageId, String attachmentId) {
        PageAttachmentList list = visibleAttachments(sourceId, pageId, AccessKind.CONTENT);
        boolean mayStore = access.permits(caller, ResourceScope.page(sourceId, list.spaceKey(), pageId), AccessKind.SYNC);
        return harvester.assets().preview(list, attachmentId, null, mayStore, Harvester.OWNER);
    }

    /** Ob die Quelle Vorschauen von Anhängen erzeugt ({@code SourceCapability.ATTACHMENT_PREVIEWS}). */
    public boolean attachmentPreviews(String sourceId) {
        return sources().stream().anyMatch(source -> source.id().equals(sourceId)
                && source.capabilities().contains(
                        com.aresstack.spaceharvester.application.sources.SourceCapability.ATTACHMENT_PREVIEWS));
    }

    // ------------------------------------------------------------------ intern

    private PageAttachmentList visibleAttachments(String sourceId, String pageId, AccessKind kind) {
        // Die Marken eines Videos stehen als virtuelle Anhänge mit in der Liste.
        if (access.permits(caller, ResourceScope.page(sourceId, null, pageId), kind)) {
            return sourceAttachments(sourceId, pageId);
        }
        access.require(caller, ResourceScope.source(sourceId), kind);
        PageAttachmentList list = sourceAttachments(sourceId, pageId);
        access.requireConcealed(caller, ResourceScope.page(sourceId, list.spaceKey(), pageId), kind);
        return list;
    }

    /**
     * Die Anhänge der Quelle samt den virtuellen Anhängen eines Videos: je gespeicherter Marke einer und das
     * gespeicherte Transkript. Eine Quelle ohne Anhangslisten (etwa ein BSCW-Dokument) hat dann nur diese; ohne sie
     * bleibt es bei {@code UNSUPPORTED}.
     */
    private PageAttachmentList sourceAttachments(String sourceId, String pageId) {
        DeliveredPage page = null;
        List<Long> marks = List.of();
        boolean transcript = false;
        try {
            page = harvester.pages().page(caller, sourceId, pageId, false);
            marks = slides().find(new PageKey(sourceId, pageId), page.snapshot().revision());
            transcript = harvester.assets().hasVideoTranscript(sourceId, pageId, page.snapshot().revision());
        } catch (RuntimeException notCached) {
            // Eine Seite ohne gespeicherten Stand hat keine gespeicherten Marken und kein Transkript.
        }
        PageAttachmentList list;
        try {
            list = harvester.sources().attachments(sourceId, pageId);
        } catch (com.aresstack.spaceharvester.application.sources.SourceAccessException e) {
            if (marks.isEmpty() && !transcript
                    || e.reason() != com.aresstack.spaceharvester.application.sources.SourceAccessException.Reason.UNSUPPORTED) {
                throw e;
            }
            java.time.Instant now = java.time.Instant.now();
            list = new PageAttachmentList(sourceId, page.header().metadata().partitionKey(),
                    new com.aresstack.spaceharvester.application.sources.PageAttachments(pageId, List.of(), List.of(),
                            "application/json", "video-marks", List.of(), now), now);
        }
        if (!marks.isEmpty()) {
            list = harvester.assets().withVideoMarks(list, page.snapshot().revision(), marks);
        }
        return transcript ? harvester.assets().withVideoTranscript(list, page.snapshot().revision()) : list;
    }

    private void requireVisible(JobStatus status, AccessKind kind) {
        if (!Harvester.OWNER.equals(status.owner())) {
            // Läufe des Aufrufers (Zusammenfassen, Synonymlauf, Aussagenanalyse) gehören ihm selbst, auch
            // quellenübergreifende; die Rechte hat der Core schon beim Start geprüft.
            return;
        }
        if (status.type() == JobType.PURGE && status.parameter(
                com.aresstack.spaceharvester.application.sources.SourceDataPurge.REASON) != null) {
            // Das Löschen einer entfernten oder umgestellten Quelle sieht, wer die Konfiguration verwalten darf, wie
            // {@link #removeSource}; nach dem Neuladen steht die entfernte Quelle in keiner Richtlinie mehr.
            return;
        }
        if (!access.permits(caller, scope(status.sourceId(), status.partitionKey()), kind)) {
            throw new UnknownJobException(status.id());
        }
    }

    /** Space eines Auftrags oder, ohne Space (etwa „Daten bereinigen“), die ganze Quelle. */
    private static ResourceScope scope(String sourceId, String partitionKey) {
        return partitionKey == null ? ResourceScope.source(sourceId) : ResourceScope.space(sourceId, partitionKey);
    }

    private void ownExport(String exportId) {
        JobStatus job = harvester.jobs().status(exportId, Harvester.OWNER);
        if (job.type() != JobType.EXPORT) {
            throw new UnknownJobException(exportId);
        }
        requireExport(job.sourceId(), job.partitionKey());
    }

    private void requireExport(String sourceId, String spaceKey) {
        ResourceScope space = ResourceScope.space(sourceId, spaceKey);
        access.require(caller, space, AccessKind.EXPORT);
        access.require(caller, space, AccessKind.CONTENT);
    }
}
