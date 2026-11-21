package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Stand und Status-quo-Sicht (ADR 0032, FL6). Spring-, REST- und Swing-frei; reine Auswertung wie die Valenz: rechnet
 * nur aus gespeicherten Analysen ({@link KnowledgeGraph#analysed}) und den Kopfdaten im Bestand, ist abbrechbar,
 * speichert nichts und ruft nie Parser, KI oder die Quelle.
 *
 * <p>Rechte: Sichtbar ist nur, was der Aufrufer im Knowledge Graph sieht (Leserecht auf die Seite). Eine Entscheidung
 * zu treffen oder zu löschen braucht zusätzlich {@link AccessKind#SYNC} auf die Seite wie bei geprüften Fakten; das
 * gilt ebenso für die Prüfung einer Aussage durch Menschen ({@link #review}, {@link #withdrawReview}, ADR 0032 W3).
 *
 * <p>Stand und Prüfung liegen im selben Eintrag ({@link StandingDecision}) und teilen sich dessen Revision; sie werden
 * unabhängig gesetzt und zurückgenommen, ein Eintrag ohne beides wird gelöscht. Eine Entscheidung zu einer Aussage
 * gehört zur Ablage des Analysators, in deren Analyse die Aussage gefunden wurde, nie zur gerade eingestellten Wahl.
 * Die Prüfung wirkt nicht hier, sondern beim Lesen der Analysen ({@link DecisionReviews}); die Sicht bekommt die
 * Aussagen schon mit ihr.
 *
 * <p>Jede Änderung einer Entscheidung, auch einer Prüfung, meldet {@link #subscribe} nach dem Speichern (ADR 0027,
 * kein Polling).
 */
public final class StatusQuo {

    private static final System.Logger LOG = System.getLogger(StatusQuo.class.getName());

    private final Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed;
    private final StandingDecisionStore store;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Supplier<StandingWords> words;
    private final Clock clock;
    private final SentenceSource sentences;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Beobachter nur für Änderungen von Prüfungen (sie ändern die wirksame Prüfung der Analysen). */
    private final List<Runnable> reviewListeners = new CopyOnWriteArrayList<>();

    /**
     * @param analysed sichtbare analysierte Seiten des Aufrufers, etwa {@code knowledgeGraph::analysed}
     * @param headers  Kopfdaten einer Seite aus dem Bestand (Labels, Titel, Elternseite), nie ein Abruf bei der Quelle
     * @param words    die Wortlisten für überholte Seiten und Entwürfe (eine Stelle: Vorgabe bzw. Einstellung)
     */
    public StatusQuo(Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed,
                     Function<PageKey, Optional<PageHeader>> headers, StandingDecisionStore store, AccessGuard access,
                     Supplier<StandingWords> words, Clock clock) {
        this(analysed, headers, store, access, words, clock, SentenceSource.NONE);
    }

    /** Wie oben, mit Satzgrenzen und Lesetexten für {@link #sentences}. */
    public StatusQuo(Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed,
                     Function<PageKey, Optional<PageHeader>> headers, StandingDecisionStore store, AccessGuard access,
                     Supplier<StandingWords> words, Clock clock, SentenceSource sentences) {
        this.sentences = Objects.requireNonNull(sentences, "sentences");
        this.analysed = Objects.requireNonNull(analysed, "analysed");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.store = Objects.requireNonNull(store, "store");
        this.access = Objects.requireNonNull(access, "access");
        this.words = Objects.requireNonNull(words, "words");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Die Sicht über eine im Bestand aufgelöste Auswahl (ADR 0029). */
    public StatusQuoView view(AccessContext caller, PageSelection selection, BooleanSupplier cancelled) {
        Objects.requireNonNull(selection, "selection");
        return view(caller, selection::contains, cancelled);
    }

    /** Wie {@link #view(AccessContext, Predicate, BooleanSupplier)}, ohne Abbruch. */
    public StatusQuoView view(AccessContext caller, Predicate<PageKey> pages) {
        return view(caller, pages, () -> false);
    }

    /**
     * Die Sicht über alle sichtbaren analysierten Seiten, die {@code pages} annimmt. Seiten und ihre Anhänge mit
     * eigener Analyse zählen je für sich.
     *
     * @param cancelled wird je Seite gefragt
     * @throws CancellationException wenn {@code cancelled} zutrifft; es bleibt nichts zurück
     */
    public StatusQuoView view(AccessContext caller, Predicate<PageKey> pages, BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(cancelled, "cancelled");
        Map<PageKey, StandingDecision> pageDecisions = new HashMap<>();
        // Je Ziel mit Analysator: IDs von MaltParser und KI-Analysator können gleich lauten.
        Map<StandingDecision.Target, StandingDecision> statementDecisions = new HashMap<>();
        Map<PageKey, List<StandingDecision>> decisionsByPage = new HashMap<>();
        for (StandingDecision decision : store.all()) {
            if (decision.ofPage()) {
                pageDecisions.put(decision.page(), decision);
            } else {
                statementDecisions.put(decision.target(), decision);
                decisionsByPage.computeIfAbsent(decision.page(), key -> new ArrayList<>()).add(decision);
            }
        }
        PageMarks marker = new PageMarks(headers, words.get());
        Map<PageKey, List<PageMark>> marksByPage = new HashMap<>();
        Map<PageKey, Set<StandingDecision.Target>> statementIds = new HashMap<>();
        Map<PageKey, Set<AnalyzerChoice>> analyzers = new HashMap<>();
        List<StandingDecision> rejectedDecisions = new ArrayList<>();
        List<StatusQuoEntry> entries = new ArrayList<>();
        List<PageStanding> pageStandings = new ArrayList<>();
        Map<KnowledgeGraph.PageRef, Integer> order = new HashMap<>();
        for (KnowledgeGraph.AnalysedPage page : analysed.apply(caller)) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Status-quo-Sicht abgebrochen");
            }
            KnowledgeGraph.PageRef ref = page.page();
            if (!pages.test(ref.key())) {
                continue;
            }
            order.putIfAbsent(ref, order.size());
            Set<StandingDecision.Target> ids = statementIds.computeIfAbsent(ref.key(), key -> new HashSet<>());
            AnalyzerChoice analyzer = AnalyzerChoice.of(page.analysis().analyzerId());
            analyzers.computeIfAbsent(ref.key(), key -> EnumSet.noneOf(AnalyzerChoice.class)).add(analyzer);
            List<PageMark> marks = marksByPage.computeIfAbsent(ref.key(), marker::of);
            StandingDecision pageDecision = pageDecisions.get(ref.key());
            StandingRules.PageCap cap = StandingRules.cap(pageDecision, marks);
            boolean stale = ref.state() == PageStatements.State.STALE;
            Map<String, ParsedStatements.Mention> mentions = new HashMap<>();
            page.analysis().result().mentions().forEach(mention -> mentions.put(mention.id(), mention));
            Map<Standing, Integer> counts = new EnumMap<>(Standing.class);
            Map<Standing, Integer> certain = new EnumMap<>(Standing.class);
            for (ParsedStatements.Statement statement : page.analysis().result().statements()) {
                StandingDecision.Target target = new StandingDecision.Target(ref.key(), statement.id(), analyzer);
                ids.add(target);
                StandingDecision decision = statementDecisions.get(target);
                Optional<ParsedStatements.Quality> quality = StandingRules.effectiveQuality(statement);
                if (quality.isEmpty()) {
                    // Von Hand verworfen (die Aussage trägt die wirksame Prüfung): zählt nicht, bleibt aber erreichbar.
                    if (decision != null && decision.review() == ParsedStatements.Review.REJECTED) {
                        rejectedDecisions.add(decision);
                    }
                    continue;
                }
                // Trägt die Entscheidung nur die Prüfung, gelten für den Stand die Regeln (StandingRules#statement).
                StatementStanding standing = StandingRules.statement(statement, decision, cap);
                // Eine veraltete Analyse zeigt ihre Einträge (analysisStale), zählt aber nicht als aktuelle Aussage.
                if (!stale) {
                    counts.merge(standing.standing(), 1, Integer::sum);
                    if (quality.get() == ParsedStatements.Quality.PLAUSIBLE) {
                        certain.merge(standing.standing(), 1, Integer::sum);
                    }
                }
                StatementKind kind = StandingRules.kind(statement);
                ParsedStatements.Mention subject = subject(statement, kind, mentions);
                entries.add(new StatusQuoEntry(ref, statement, kind, subject == null ? null : subject.text(),
                        subject == null ? null : subject.key(), situation(statement, kind, mentions), quality.get(),
                        standing, decision, stale));
            }
            pageStandings.add(pageStanding(ref, pageDecision, marks, cap, counts, certain));
        }
        entries.sort(Comparator.comparing((StatusQuoEntry entry) -> entry.standing().standing())
                .thenComparing(entry -> order.get(entry.page()))
                .thenComparingInt(entry -> entry.statement().sentence())
                .thenComparingInt(entry -> entry.statement().start()));
        Map<Standing, Integer> counts = new EnumMap<>(Standing.class);
        Map<Standing, Integer> certain = new EnumMap<>(Standing.class);
        List<StatusQuoEntry> current = entries.stream().filter(entry -> !entry.analysisStale()).toList();
        for (StatusQuoEntry entry : current) {
            counts.merge(entry.standing().standing(), 1, Integer::sum);
            if (!entry.uncertain()) {
                certain.merge(entry.standing().standing(), 1, Integer::sum);
            }
        }
        // Veraltet ist nur, was zur gelesenen Ablage gehört: Einträge des anderen Analysators warten still.
        List<StandingDecision> staleDecisions = new ArrayList<>();
        statementIds.forEach((key, ids) -> decisionsByPage.getOrDefault(key, List.of()).stream()
                .filter(decision -> analyzers.get(key).contains(decision.analyzer()))
                .filter(decision -> !ids.contains(decision.target())).forEach(staleDecisions::add));
        Comparator<StandingDecision> byTarget = Comparator.comparing((StandingDecision decision) ->
                        decision.page().sourceId()).thenComparing(decision -> decision.page().pageId())
                .thenComparing(StandingDecision::statementId);
        staleDecisions.sort(byTarget);
        rejectedDecisions.sort(byTarget);
        return new StatusQuoView(entries, states(current), pageStandings, counts, certain, staleDecisions,
                entries.size() - current.size(), rejectedDecisions);
    }

    /**
     * Setzt den Stand einer Seite oder einer Aussage von Hand. Eine Prüfung im selben Eintrag bleibt.
     *
     * @param statementId      ID einer Aussage der aktuellen Analyse der Seite oder eines ihrer Dokumente, oder
     *                         {@code null} für die Seite selbst
     * @param expectedRevision die zuletzt gelesene Revision (0 ohne Entscheidung) oder {@code null} ohne Prüfung
     * @throws UnknownStandingTargetException wenn Seite oder Aussage für den Aufrufer nicht analysiert sichtbar ist
     * @throws StandingConflictException      wenn die Revision nicht passt
     */
    public StandingDecision decide(AccessContext caller, PageKey page, String statementId, Standing standing,
                                   Long expectedRevision) {
        Objects.requireNonNull(standing, "standing");
        StandingDecision.Target target = requireWritable(caller, page, statementId, true);
        StandingDecision decision;
        synchronized (this) {
            Optional<StandingDecision> stored = store.find(target);
            long revision = stored.map(StandingDecision::revision).orElse(0L);
            check(expectedRevision, revision);
            String actor = caller.jobOwner();
            Instant now = clock.instant();
            decision = next(target, stored, standing, stored.map(StandingDecision::review).orElse(null), actor, now);
            store.write(List.of(decision), List.of(), List.of(new StandingDecisionStore.AuditEntry(target,
                    StandingDecisionStore.AuditEntry.SET, actor, now, standing, decision.review(),
                    decision.revision())));
        }
        changed();
        return decision;
    }

    /**
     * Löscht den Stand von Hand; danach gelten wieder die Regeln. Auch der Stand einer veralteten Entscheidung zu einer
     * Aussage lässt sich so löschen. Trägt der Eintrag eine Prüfung, bleibt er mit ihr und neuer Revision stehen; sonst
     * wird er gelöscht.
     *
     * @return ob es einen Stand von Hand gab
     * @throws StandingConflictException wenn die Revision nicht passt
     */
    public boolean clear(AccessContext caller, PageKey page, String statementId, Long expectedRevision) {
        StandingDecision.Target target = requireWritable(caller, page, statementId, false);
        synchronized (this) {
            Optional<StandingDecision> stored = store.find(target);
            check(expectedRevision, stored.map(StandingDecision::revision).orElse(0L));
            if (stored.isEmpty() || !stored.get().hasStanding()) {
                return false;
            }
            StandingDecision old = stored.get();
            write(target, old, old.review() == null ? null : next(target, stored, null, old.review(),
                    caller.jobOwner(), clock.instant()), StandingDecisionStore.AuditEntry.REMOVE, caller.jobOwner());
        }
        changed();
        return true;
    }

    /**
     * Setzt die fachliche Prüfung einer Aussage von Hand (ADR 0032, W3): bestätigt oder verworfen. Nur für Aussagen,
     * nie für die Seite; ein Stand im selben Eintrag bleibt. Die Prüfung gilt nur für die Ablage des Analysators, in
     * deren Analyse die Aussage gefunden wurde. Rechte, Revision und Konflikte wie bei {@link #decide}.
     *
     * @param review           {@code CONFIRMED} oder {@code REJECTED}; zurücknehmen mit {@link #withdrawReview}
     * @param expectedRevision die zuletzt gelesene Revision des Eintrags (0 ohne Eintrag) oder {@code null} ohne Prüfung
     * @throws IllegalArgumentException       ohne Aussage-ID oder mit {@code UNREVIEWED}
     * @throws UnknownStandingTargetException wenn Seite oder Aussage für den Aufrufer nicht analysiert sichtbar ist
     * @throws StandingConflictException      wenn die Revision nicht passt
     */
    public StandingDecision review(AccessContext caller, PageKey page, String statementId,
                                   ParsedStatements.Review review, Long expectedRevision) {
        Objects.requireNonNull(review, "review");
        if (review == ParsedStatements.Review.UNREVIEWED) {
            throw new IllegalArgumentException("Zurücknehmen mit withdrawReview");
        }
        requireStatement(statementId);
        StandingDecision.Target target = requireWritable(caller, page, statementId, true);
        StandingDecision decision;
        synchronized (this) {
            Optional<StandingDecision> stored = store.find(target);
            check(expectedRevision, stored.map(StandingDecision::revision).orElse(0L));
            String actor = caller.jobOwner();
            Instant now = clock.instant();
            decision = next(target, stored, stored.map(StandingDecision::standing).orElse(null), review, actor, now);
            store.write(List.of(decision), List.of(), List.of(new StandingDecisionStore.AuditEntry(target,
                    StandingDecisionStore.AuditEntry.REVIEW, actor, now, decision.standing(), review,
                    decision.revision())));
        }
        changed();
        reviewsChanged();
        return decision;
    }

    /**
     * Nimmt die Prüfung einer Aussage zurück; danach gilt wieder die gespeicherte Prüfung der Analyse. Ein eigener
     * Eintrag im Protokoll ({@code unreview}). Trägt der Eintrag einen Stand, bleibt er mit ihm und neuer Revision
     * stehen; sonst wird er gelöscht. Auch die Prüfung einer veralteten Aussage lässt sich so zurücknehmen.
     *
     * @return ob es eine Prüfung gab
     * @throws IllegalArgumentException  ohne Aussage-ID
     * @throws StandingConflictException wenn die Revision nicht passt
     */
    public boolean withdrawReview(AccessContext caller, PageKey page, String statementId, Long expectedRevision) {
        requireStatement(statementId);
        StandingDecision.Target target = requireWritable(caller, page, statementId, false);
        synchronized (this) {
            Optional<StandingDecision> stored = store.find(target);
            check(expectedRevision, stored.map(StandingDecision::revision).orElse(0L));
            if (stored.isEmpty() || stored.get().review() == null) {
                return false;
            }
            StandingDecision old = stored.get();
            write(target, old, old.hasStanding() ? next(target, stored, old.standing(), null, caller.jobOwner(),
                    clock.instant()) : null, StandingDecisionStore.AuditEntry.UNREVIEW, caller.jobOwner());
        }
        changed();
        reviewsChanged();
        return true;
    }

    /**
     * Das Protokoll der Entscheidungen zu Seite oder Aussage (Stand und Prüfung), älteste zuerst. Rechte wie die Sicht;
     * bei einer Aussage das Protokoll der Ablage, in deren Analyse sie steht.
     */
    public List<StandingDecisionStore.AuditEntry> history(AccessContext caller, PageKey page, String statementId) {
        return store.history(target(visible(caller, page), page, statementId, false));
    }

    /** Entfernt alle Entscheidungen einer Quelle („Daten bereinigen“, „Quelle entfernen“). */
    public int removeSource(String sourceId, String actor) {
        Objects.requireNonNull(sourceId, "sourceId");
        List<StandingDecision> gone;
        synchronized (this) {
            gone = store.all().stream().filter(decision -> decision.page().sourceId().equals(sourceId)).toList();
            if (gone.isEmpty()) {
                return 0;
            }
            Instant now = clock.instant();
            store.write(List.of(), gone.stream().map(StandingDecision::target).toList(), gone.stream()
                    .map(decision -> new StandingDecisionStore.AuditEntry(decision.target(),
                            StandingDecisionStore.AuditEntry.REMOVE, actor, now, decision.standing(),
                            decision.review(), decision.revision())).toList());
        }
        changed();
        if (gone.stream().anyMatch(decision -> decision.review() != null)) {
            reviewsChanged();
        }
        return gone.size();
    }

    /**
     * Meldet einen Beobachter nur für gesetzte, zurückgenommene oder mit der Quelle entfernte Prüfungen an; damit zieht
     * der Harvester den Knowledge Graph nach ({@code KnowledgeGraph#analysesChanged}), denn eine Prüfung ändert die
     * wirksame Prüfung der Analysen. Eine Änderung nur des Stands meldet sich hier nicht.
     */
    public Subscription subscribeReviews(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        reviewListeners.add(listener);
        return () -> reviewListeners.remove(listener);
    }

    /** Meldet einen Beobachter für jede Änderung einer Entscheidung an. */
    public Subscription subscribe(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private List<KnowledgeGraph.AnalysedPage> visible(AccessContext caller, PageKey page) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(page, "page");
        List<KnowledgeGraph.AnalysedPage> visible = analysed.apply(caller).stream()
                .filter(candidate -> candidate.page().key().equals(page)).toList();
        if (visible.isEmpty()) {
            throw new UnknownStandingTargetException("Seite ist nicht analysiert oder nicht sichtbar");
        }
        return visible;
    }

    /**
     * Der Wortlaut zu Einträgen der Sicht, auch zu denen möglicher Widersprüche und Ablösungen, für den sichtbaren
     * Ausschnitt einer Liste (nicht in der Sicht selbst, damit sie schnell bleibt). Nur mit Leserecht
     * {@link AccessKind#CONTENT} auf die Seite; ohne fehlt der Eintrag. Der Originalsatz nur, wenn der Lesetext genau
     * der analysierte ist; der vereinfachte Satz aus der Aussage. Speichert nichts.
     *
     * @return Wortlaut je Aussage-ID
     */
    public Map<String, SentenceText> sentences(AccessContext caller, Collection<StatusQuoEntry> entries) {
        Objects.requireNonNull(caller, "caller");
        Map<KnowledgeGraph.PageRef, List<StatusQuoEntry>> byPage = new LinkedHashMap<>();
        for (StatusQuoEntry entry : Objects.requireNonNull(entries, "entries")) {
            byPage.computeIfAbsent(entry.page(), ignored -> new ArrayList<>()).add(entry);
        }
        Map<String, SentenceText> texts = new LinkedHashMap<>();
        byPage.forEach((page, list) -> {
            PageKey key = page.key();
            ResourceScope scope = page.spaceKey() == null ? ResourceScope.source(key.sourceId())
                    : ResourceScope.page(key.sourceId(), page.spaceKey(), key.pageId());
            if (!access.permits(caller, scope, AccessKind.CONTENT)) {
                return;
            }
            Optional<KnowledgeGraph.AnalysedPage> analysis = sentences.analysis(caller, page);
            Map<Integer, ParsedStatements.Sentence> bounds = new HashMap<>();
            analysis.ifPresent(found -> found.analysis().result().sentences()
                    .forEach(sentence -> bounds.put(sentence.index(), sentence)));
            Optional<String> text = analysis.flatMap(found -> sentences.text(page,
                    found.analysis().inputSha256()));
            for (StatusQuoEntry entry : list) {
                ParsedStatements.Statement statement = entry.statement();
                ParsedStatements.Sentence sentence = bounds.get(statement.sentence());
                boolean known = analysis.isPresent() && analysis.get().analysis().result().statements().stream()
                        .anyMatch(candidate -> candidate.id().equals(statement.id()));
                String original = null;
                if (known && sentence != null && text.isPresent() && sentence.end() <= text.get().length()
                        && sentence.start() >= 0 && sentence.start() <= sentence.end()) {
                    original = text.get().substring(sentence.start(), sentence.end()).strip();
                }
                texts.put(statement.id(), new SentenceText(original, statement.derivation().simplifiedSentence(),
                        sentence == null ? -1 : sentence.start(), sentence == null ? -1 : sentence.end(),
                        statement.start(), statement.end(), original == null));
            }
        });
        return texts;
    }

    /** Prüft Sichtbarkeit und {@link AccessKind#SYNC} und gibt das Ziel samt Analysator zurück. */
    private StandingDecision.Target requireWritable(AccessContext caller, PageKey page, String statementId,
                                                    boolean mustExist) {
        List<KnowledgeGraph.AnalysedPage> visible = visible(caller, page);
        StandingDecision.Target target = target(visible, page, statementId, mustExist);
        String spaceKey = visible.getFirst().page().spaceKey();
        access.require(caller, spaceKey == null ? ResourceScope.source(page.sourceId())
                : ResourceScope.page(page.sourceId(), spaceKey, page.pageId()), AccessKind.SYNC);
        return target;
    }

    /**
     * Das Ziel; bei einer Aussage mit dem Analysator der Analyse, in der sie steht. Steht sie in keiner (veraltete
     * Entscheidung), der Analysator der gelesenen Analysen der Seite: genau deren veraltete Einträge zeigt die Sicht.
     */
    private static StandingDecision.Target target(List<KnowledgeGraph.AnalysedPage> visible, PageKey page,
                                                  String statementId, boolean mustExist) {
        if (statementId == null) {
            return new StandingDecision.Target(page, null);
        }
        Optional<KnowledgeGraph.AnalysedPage> found = visible.stream().filter(candidate -> candidate.analysis()
                .result().statements().stream().anyMatch(statement -> statement.id().equals(statementId)))
                .findFirst();
        if (mustExist && found.isEmpty()) {
            throw new UnknownStandingTargetException("Aussage gehört nicht zur aktuellen Analyse der Seite");
        }
        return new StandingDecision.Target(page, statementId,
                AnalyzerChoice.of(found.orElse(visible.getFirst()).analysis().analyzerId()));
    }

    private static void requireStatement(String statementId) {
        if (statementId == null) {
            throw new IllegalArgumentException("Eine Prüfung gibt es nur zu Aussagen, nicht zur Seite");
        }
    }

    /** Der Eintrag nach einer Änderung mit der nächsten Revision. */
    private static StandingDecision next(StandingDecision.Target target, Optional<StandingDecision> stored,
                                         Standing standing, ParsedStatements.Review review, String actor,
                                         Instant now) {
        return new StandingDecision(target.page(), target.statementId(), target.analyzer(), standing, review,
                stored.map(StandingDecision::revision).orElse(0L) + 1,
                stored.map(StandingDecision::createdBy).orElse(actor),
                stored.map(StandingDecision::createdAt).orElse(now), actor, now);
    }

    /**
     * Schreibt das Entfernen eines Felds: {@code kept} bleibt stehen oder, wenn {@code null}, wird der Eintrag gelöscht.
     * Die Protokollzeile trägt die zuletzt gültigen Werte und die Revision danach bzw. die zuletzt gültige.
     */
    private void write(StandingDecision.Target target, StandingDecision old, StandingDecision kept, String action,
                       String actor) {
        StandingDecisionStore.AuditEntry entry = new StandingDecisionStore.AuditEntry(target, action, actor,
                kept == null ? clock.instant() : kept.decidedAt(), old.standing(), old.review(),
                kept == null ? old.revision() : kept.revision());
        if (kept == null) {
            store.write(List.of(), List.of(target), List.of(entry));
        } else {
            store.write(List.of(kept), List.of(), List.of(entry));
        }
    }

    private static void check(Long expected, long revision) {
        if (expected != null && expected != revision) {
            throw new StandingConflictException(revision);
        }
    }

    private void reviewsChanged() {
        deliver(reviewListeners);
    }

    private void changed() {
        deliver(listeners);
    }

    private static void deliver(List<Runnable> listeners) {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Beobachter des Stands fehlgeschlagen: {0}",
                        e.getClass().getName());
            }
        }
    }

    private static PageStanding pageStanding(KnowledgeGraph.PageRef ref, StandingDecision decision,
                                             List<PageMark> marks, StandingRules.PageCap cap,
                                             Map<Standing, Integer> counts, Map<Standing, Integer> certain) {
        if (decision != null) {
            return new PageStanding(ref, counts, certain, decision.standing(), StandingBasis.DECISION,
                    StandingReason.DECISION, marks, decision);
        }
        if (cap != null) {
            return new PageStanding(ref, counts, certain, cap.standing(), StandingBasis.PAGE, cap.reason(), marks,
                    null);
        }
        Standing majority = StandingRules.majority(certain);
        boolean none = certain.values().stream().mapToInt(Integer::intValue).sum() == 0;
        return new PageStanding(ref, counts, certain, majority, StandingBasis.STATEMENTS,
                majority == Standing.UNKLAR && (none || isTie(certain)) ? StandingReason.NO_MAJORITY
                        : StandingReason.MAJORITY, marks, null);
    }

    private static boolean isTie(Map<Standing, Integer> certain) {
        int best = certain.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return best > 0 && certain.values().stream().filter(count -> count == best).count() > 1;
    }

    private static ParsedStatements.Mention subject(ParsedStatements.Statement statement, StatementKind kind,
                                                    Map<String, ParsedStatements.Mention> mentions) {
        return first(statement, kind == StatementKind.STATE ? ParsedStatements.Role.BEARER
                : ParsedStatements.Role.ACTOR, mentions);
    }

    /** Die Sachlage oder {@code null}, wenn ein Beteiligter fehlt, offen ist oder kein Begriff ist. */
    private static Situation situation(ParsedStatements.Statement statement, StatementKind kind,
                                       Map<String, ParsedStatements.Mention> mentions) {
        ParsedStatements.Role role = kind == StatementKind.STATE ? ParsedStatements.Role.BEARER
                : ParsedStatements.Role.ACTOR;
        String predicate = statement.predicateLemma();
        if (predicate == null || predicate.isBlank()) {
            return null;
        }
        String subject = null;
        List<Situation.Participant> others = new ArrayList<>();
        for (ParsedStatements.Argument argument : statement.arguments()) {
            String key = termKey(mentions.get(argument.mention()));
            if (key == null) {
                return null;
            }
            if (subject == null && argument.role() == role) {
                subject = key;
            } else {
                String marker = argument.marker() == null || argument.marker().isBlank() ? null
                        : argument.marker().strip().toLowerCase(Locale.ROOT);
                others.add(new Situation.Participant(argument.role(), marker, key));
            }
        }
        return subject == null ? null
                : new Situation(kind, subject, predicate.strip().toLowerCase(Locale.ROOT), others);
    }

    private static String termKey(ParsedStatements.Mention mention) {
        if (mention == null || mention.unresolved() || mention.kind() == ParsedStatements.MentionKind.CLAUSE
                || mention.kind() == ParsedStatements.MentionKind.PRONOUN) {
            return null;
        }
        String key = com.aresstack.spaceharvester.application.glossary.GlossaryDerivation.keyOf(mention);
        return key == null || key.isBlank() ? null : key;
    }

    private static ParsedStatements.Mention first(ParsedStatements.Statement statement, ParsedStatements.Role role,
                                                  Map<String, ParsedStatements.Mention> mentions) {
        return statement.withRole(role).stream().map(argument -> mentions.get(argument.mention()))
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static List<BearerStates> states(List<StatusQuoEntry> entries) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<StatusQuoEntry>> grouped = new LinkedHashMap<>();
        Map<String, Integer> current = new HashMap<>();
        for (StatusQuoEntry entry : entries) {
            if (entry.kind() != StatementKind.STATE || entry.subjectKey() == null || entry.subjectKey().isBlank()) {
                continue;
            }
            String key = entry.subjectKey().strip().toLowerCase(Locale.ROOT);
            names.putIfAbsent(key, entry.subjectKey().strip());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            if (entry.standing().standing() == Standing.GILT) {
                current.merge(key, 1, Integer::sum);
            }
        }
        return grouped.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<StatusQuoEntry>> group) ->
                                -current.getOrDefault(group.getKey(), 0))
                        .thenComparing(Map.Entry::getKey))
                .map(group -> new BearerStates(names.get(group.getKey()), group.getValue())).toList();
    }
}
