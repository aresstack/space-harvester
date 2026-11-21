package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
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
 * zu treffen oder zu löschen braucht zusätzlich {@link AccessKind#SYNC} auf die Seite wie bei geprüften Fakten.
 *
 * <p>Jede Änderung einer Entscheidung meldet {@link #subscribe} nach dem Speichern (ADR 0027, kein Polling).
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
        Map<String, StandingDecision> statementDecisions = new HashMap<>();
        Map<PageKey, List<StandingDecision>> decisionsByPage = new HashMap<>();
        for (StandingDecision decision : store.all()) {
            if (decision.ofPage()) {
                pageDecisions.put(decision.page(), decision);
            } else {
                statementDecisions.put(decision.statementId(), decision);
                decisionsByPage.computeIfAbsent(decision.page(), key -> new ArrayList<>()).add(decision);
            }
        }
        PageMarks marker = new PageMarks(headers, words.get());
        Map<PageKey, List<PageMark>> marksByPage = new HashMap<>();
        Map<PageKey, Set<String>> statementIds = new HashMap<>();
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
            Set<String> ids = statementIds.computeIfAbsent(ref.key(), key -> new HashSet<>());
            List<PageMark> marks = marksByPage.computeIfAbsent(ref.key(), marker::of);
            StandingDecision pageDecision = pageDecisions.get(ref.key());
            StandingRules.PageCap cap = StandingRules.cap(pageDecision, marks);
            boolean stale = ref.state() == PageStatements.State.STALE;
            Map<String, ParsedStatements.Mention> mentions = new HashMap<>();
            page.analysis().result().mentions().forEach(mention -> mentions.put(mention.id(), mention));
            Map<Standing, Integer> counts = new EnumMap<>(Standing.class);
            Map<Standing, Integer> certain = new EnumMap<>(Standing.class);
            for (ParsedStatements.Statement statement : page.analysis().result().statements()) {
                ids.add(statement.id());
                Optional<ParsedStatements.Quality> quality = StandingRules.effectiveQuality(statement);
                if (quality.isEmpty()) {
                    continue;
                }
                StandingDecision decision = statementDecisions.get(statement.id());
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
        List<StandingDecision> staleDecisions = new ArrayList<>();
        statementIds.forEach((key, ids) -> decisionsByPage.getOrDefault(key, List.of()).stream()
                .filter(decision -> !ids.contains(decision.statementId())).forEach(staleDecisions::add));
        staleDecisions.sort(Comparator.comparing((StandingDecision decision) -> decision.page().sourceId())
                .thenComparing(decision -> decision.page().pageId()).thenComparing(StandingDecision::statementId));
        return new StatusQuoView(entries, states(current), pageStandings, counts, certain, staleDecisions,
                entries.size() - current.size());
    }

    /**
     * Setzt den Stand einer Seite oder einer Aussage von Hand.
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
        requireWritable(caller, page, statementId, true);
        StandingDecision decision;
        synchronized (this) {
            StandingDecision.Target target = new StandingDecision.Target(page, statementId);
            Optional<StandingDecision> stored = store.find(target);
            long revision = stored.map(StandingDecision::revision).orElse(0L);
            check(expectedRevision, revision);
            String actor = caller.jobOwner();
            Instant now = clock.instant();
            decision = new StandingDecision(page, statementId, standing, revision + 1,
                    stored.map(StandingDecision::createdBy).orElse(actor),
                    stored.map(StandingDecision::createdAt).orElse(now), actor, now);
            store.write(List.of(decision), List.of(), List.of(new StandingDecisionStore.AuditEntry(target,
                    StandingDecisionStore.AuditEntry.SET, actor, now, standing, decision.revision())));
        }
        changed();
        return decision;
    }

    /**
     * Löscht eine Entscheidung; danach gelten wieder die Regeln. Auch eine veraltete Entscheidung zu einer Aussage
     * lässt sich so löschen.
     *
     * @return ob es eine gab
     * @throws StandingConflictException wenn die Revision nicht passt
     */
    public boolean clear(AccessContext caller, PageKey page, String statementId, Long expectedRevision) {
        requireWritable(caller, page, statementId, false);
        synchronized (this) {
            StandingDecision.Target target = new StandingDecision.Target(page, statementId);
            Optional<StandingDecision> stored = store.find(target);
            check(expectedRevision, stored.map(StandingDecision::revision).orElse(0L));
            if (stored.isEmpty()) {
                return false;
            }
            store.write(List.of(), List.of(target), List.of(new StandingDecisionStore.AuditEntry(target,
                    StandingDecisionStore.AuditEntry.REMOVE, caller.jobOwner(), clock.instant(),
                    stored.get().standing(), stored.get().revision())));
        }
        changed();
        return true;
    }

    /** Das Protokoll der Entscheidungen zu Seite oder Aussage, älteste zuerst. Rechte wie die Sicht. */
    public List<StandingDecisionStore.AuditEntry> history(AccessContext caller, PageKey page, String statementId) {
        visible(caller, page);
        return store.history(new StandingDecision.Target(page, statementId));
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
                            decision.revision())).toList());
        }
        changed();
        return gone.size();
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
        Map<KnowledgeGraph.PageRef, List<ParsedStatements.Statement>> byPage = new LinkedHashMap<>();
        for (StatusQuoEntry entry : Objects.requireNonNull(entries, "entries")) {
            byPage.computeIfAbsent(entry.page(), ignored -> new ArrayList<>()).add(entry.statement());
        }
        return statementSentences(caller, byPage);
    }

    /**
     * Wie {@link #sentences}, für Aussagen je Seite ohne Eintrag der Sicht, etwa die Belege der Fachsicht (Paket W1).
     * Dieselben Rechte und dieselbe Regel für den Originalsatz.
     *
     * @return Wortlaut je Aussage-ID
     */
    public Map<String, SentenceText> statementSentences(AccessContext caller,
                                                        Map<KnowledgeGraph.PageRef, List<ParsedStatements.Statement>>
                                                                byPage) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(byPage, "byPage");
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
            for (ParsedStatements.Statement statement : list) {
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

    private void requireWritable(AccessContext caller, PageKey page, String statementId, boolean mustExist) {
        List<KnowledgeGraph.AnalysedPage> visible = visible(caller, page);
        if (mustExist && statementId != null && visible.stream().noneMatch(candidate -> candidate.analysis()
                .result().statements().stream().anyMatch(statement -> statement.id().equals(statementId)))) {
            throw new UnknownStandingTargetException("Aussage gehört nicht zur aktuellen Analyse der Seite");
        }
        String spaceKey = visible.getFirst().page().spaceKey();
        access.require(caller, spaceKey == null ? ResourceScope.source(page.sourceId())
                : ResourceScope.page(page.sourceId(), spaceKey, page.pageId()), AccessKind.SYNC);
    }

    private static void check(Long expected, long revision) {
        if (expected != null && expected != revision) {
            throw new StandingConflictException(revision);
        }
    }

    private void changed() {
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
