package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Leseempfehlung je Auswahl und je Thema (ADR 0032, FL6): welche Seiten sich zuerst lohnen. Jede Auf- und Abwertung ist
 * eine offene Teilzahl mit Grund ({@link ReadingList.Part}); die Punkte je Grund stehen hier als Konstanten. Reine
 * Auswertung wie die Status-quo-Sicht: abbrechbar, speichert nichts, liest das Änderungsprotokoll nur. Keine KI.
 *
 * <p>Verweise zwischen Seiten und Aufrufe (Analytics) zählen noch nicht: Der Core hat dafür keinen abgelegten Bestand.
 */
public final class ReadingAdvice {

    public static final int CURRENT_STATEMENT = 1;
    public static final int OUTDATED_STATEMENT = -1;
    public static final int TOP_TERM = 2;
    public static final int TOPIC_MENTION = 1;
    public static final int RECENT_CHANGE = 3;
    public static final int PAGE_OUTDATED = -10;
    public static final int PAGE_DRAFT = -5;
    public static final int STALE_ANALYSIS = -2;
    /** So viele häufigste Begriffe zählen. */
    public static final int TOP_TERMS = 10;
    /** Zeitraum für {@link ReadingReason#RECENT_CHANGE}. */
    public static final Duration RECENT = Duration.ofDays(90);

    private final StatusQuo statusQuo;
    private final Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed;
    private final RecentChanges changes;
    private final Clock clock;

    /** @param analysed dieselben sichtbaren Analysen wie für {@code statusQuo}, etwa {@code knowledgeGraph::analysed} */
    public ReadingAdvice(StatusQuo statusQuo, Function<AccessContext, List<KnowledgeGraph.AnalysedPage>> analysed,
                         RecentChanges changes, Clock clock) {
        this.statusQuo = Objects.requireNonNull(statusQuo, "statusQuo");
        this.analysed = Objects.requireNonNull(analysed, "analysed");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Über eine im Bestand aufgelöste Auswahl (ADR 0029). */
    public ReadingList recommend(AccessContext caller, PageSelection selection, String topic,
                                 BooleanSupplier cancelled) {
        Objects.requireNonNull(selection, "selection");
        return recommend(caller, selection::contains, topic, cancelled);
    }

    /**
     * Die Leseempfehlung.
     *
     * @param topic Thema (Grundform oder Wortlaut eines Begriffs, verglichen wie das Glossar) oder {@code null}; mit Thema
     *              erscheinen nur Seiten, die es erwähnen
     * @throws CancellationException wenn {@code cancelled} zutrifft
     */
    public ReadingList recommend(AccessContext caller, Predicate<PageKey> pages, String topic,
                                 BooleanSupplier cancelled) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(cancelled, "cancelled");
        String wanted = topic == null || topic.isBlank() ? null : TermText.normalize(topic);
        StatusQuoView view = statusQuo.view(caller, pages, cancelled);
        List<KnowledgeGraph.AnalysedPage> selected = new ArrayList<>();
        Map<KnowledgeGraph.PageRef, Map<String, Integer>> terms = new LinkedHashMap<>();
        for (KnowledgeGraph.AnalysedPage page : analysed.apply(caller)) {
            check(cancelled);
            if (!pages.test(page.page().key())) {
                continue;
            }
            selected.add(page);
            Map<String, Integer> counts = new HashMap<>();
            for (ParsedStatements.Mention mention : page.analysis().result().mentions()) {
                if (!GlossaryDerivation.isTerm(mention)) {
                    continue;
                }
                String key = GlossaryDerivation.keyOf(mention);
                counts.merge(key, 1, Integer::sum);
                // Das Thema trifft auch die Wortform im Text („Anträge“), gezählt unter dem Thema.
                if (wanted != null && !key.equals(wanted)
                        && TermText.normalize(mention.headForm() == null ? "" : mention.headForm()).equals(wanted)) {
                    counts.merge(wanted, 1, Integer::sum);
                }
            }
            terms.put(page.page(), counts);
        }
        // Rangfolge und Schreibweise der Begriffe aus der einen Zählstelle (FL5), nicht selbst gezählt.
        Map<String, String> spelling = new LinkedHashMap<>();
        GlossaryDerivation.of(selected, cancelled).terms().stream()
                .filter(term -> term.importance().pages() >= 2).limit(TOP_TERMS)
                .forEach(term -> spelling.put(term.normalized(), term.term()));
        List<String> top = List.copyOf(spelling.keySet());
        Instant since = clock.instant().minus(RECENT);
        Map<String, Set<String>> spaces = new TreeMap<>();
        for (PageStanding standing : view.pages()) {
            spaces.computeIfAbsent(standing.page().key().sourceId(), ignored -> new HashSet<>())
                    .add(standing.page().spaceKey());
        }
        Map<PageKey, Instant> recent = spaces.isEmpty() ? Map.of() : changes.since(caller, spaces, since, cancelled);
        List<ReadingList.Recommendation> recommendations = new ArrayList<>();
        for (PageStanding standing : view.pages()) {
            check(cancelled);
            KnowledgeGraph.PageRef ref = standing.page();
            Map<String, Integer> counts = terms.getOrDefault(ref, Map.of());
            int mentions = wanted == null ? 0 : counts.getOrDefault(wanted, 0);
            if (wanted != null && mentions == 0) {
                continue;
            }
            List<ReadingList.Part> parts = new ArrayList<>();
            add(parts, ReadingReason.CURRENT_STATEMENTS, standing.certainCounts().get(Standing.GILT),
                    CURRENT_STATEMENT, List.of());
            add(parts, ReadingReason.OUTDATED_STATEMENTS, standing.certainCounts().get(Standing.UEBERHOLT),
                    OUTDATED_STATEMENT, List.of());
            List<String> found = top.stream().filter(counts::containsKey).map(spelling::get).toList();
            add(parts, ReadingReason.TOP_TERMS, found.size(), TOP_TERM, found);
            add(parts, ReadingReason.TOPIC_MENTIONS, mentions, TOPIC_MENTION, List.of());
            add(parts, ReadingReason.RECENT_CHANGE, recent.containsKey(ref.key()) ? 1 : 0, RECENT_CHANGE, List.of());
            add(parts, ReadingReason.PAGE_OUTDATED, outdated(standing) ? 1 : 0, PAGE_OUTDATED, List.of());
            add(parts, ReadingReason.PAGE_DRAFT, draft(standing) ? 1 : 0, PAGE_DRAFT, List.of());
            add(parts, ReadingReason.STALE_ANALYSIS, ref.state() == PageStatements.State.STALE ? 1 : 0,
                    STALE_ANALYSIS, List.of());
            int score = parts.stream().mapToInt(ReadingList.Part::points).sum();
            recommendations.add(new ReadingList.Recommendation(ref, score, parts, standing));
        }
        recommendations.sort(Comparator.comparingInt((ReadingList.Recommendation r) -> -r.score())
                .thenComparing(r -> r.page().title() == null ? "" : r.page().title())
                .thenComparing(r -> r.page().key().pageId()));
        return new ReadingList(wanted == null ? null : topic.strip(), List.copyOf(spelling.values()), since,
                recommendations);
    }

    private static boolean outdated(PageStanding standing) {
        return standing.standing() == Standing.UEBERHOLT && standing.basis() != StandingBasis.STATEMENTS;
    }

    private static boolean draft(PageStanding standing) {
        return standing.reason() == StandingReason.PAGE_DRAFT || standing.reason() == StandingReason.PAGE_DECISION
                || standing.basis() == StandingBasis.DECISION && (standing.standing() == Standing.GEPLANT
                || standing.standing() == Standing.UNKLAR);
    }

    private static void add(List<ReadingList.Part> parts, ReadingReason reason, int count, int each,
                            List<String> terms) {
        if (count > 0) {
            parts.add(new ReadingList.Part(reason, count, count * each, terms));
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Leseempfehlung abgebrochen");
        }
    }
}
