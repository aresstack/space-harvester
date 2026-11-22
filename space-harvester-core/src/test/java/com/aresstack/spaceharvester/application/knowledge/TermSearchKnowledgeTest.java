package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.TermSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Suchmethode „Fachbegriffe“ (W8) über das echte Begriffswissen (W6): ein Synonym der Anfrage bringt eine Seite, die
 * nur den Hauptbegriff nennt. Begriffe, Seiten und Texte erfunden (Quelle „wiki“, Space „DEV“).
 */
class TermSearchKnowledgeTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Map<PageKey, String> texts = new HashMap<>();
    private final AccessGuard access = new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW);
    private final StatementAnalysis statements = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store, access, () -> parser,
            StatementAnalysisTest.clock());
    private final KnowledgeGraph graph = new KnowledgeGraph(store, access, key -> Optional.ofNullable(cache.get(key)),
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), statements,
            KnowledgeGraph.TermExpansion.NONE);
    private final List<DomainTermGraph.Term> terms = new ArrayList<>();
    private final List<DomainTermGraph.Synonym> synonyms = new ArrayList<>();
    private final KeywordSearch keywords = mock(KeywordSearch.class);
    private final AtomicInteger created = new AtomicInteger();

    @Test
    void aSynonymInTheQueryFindsThePageThatOnlyNamesTheMainTerm() {
        term("Plan");
        term("Ziel");
        synonyms.add(new DomainTermGraph.Synonym("Plan", "Vorhaben", new RelationScope(null, null)));
        analyse("1", "Der Plan prüft das Ziel.");
        analyse("2", "Das Ziel bleibt offen.");
        // Die Schlagwortsuche findet Seiten, deren Text den Suchtext oder ein bestätigtes Synonym davon enthält
        // (wie die echte über die bestätigten Beziehungen).
        when(keywords.search(any(), any(), isNull(), anyInt())).thenAnswer(call -> {
            KeywordSearch.Query query = call.getArgument(1);
            Set<String> wanted = new HashSet<>(Set.of(query.text()));
            synonyms.forEach(synonym -> {
                if (synonym.fromTerm().equals(query.text())) {
                    wanted.add(synonym.toTerm());
                }
                if (synonym.toTerm().equals(query.text())) {
                    wanted.add(synonym.fromTerm());
                }
            });
            List<KeywordSearch.Hit> hits = texts.entrySet().stream()
                    .filter(entry -> query.pages() == null || query.pages().contains(entry.getKey()))
                    .filter(entry -> wanted.stream().anyMatch(entry.getValue()::contains))
                    .map(entry -> new KeywordSearch.Hit("wiki", entry.getKey().pageId(), "DEV",
                            "Seite " + entry.getKey().pageId(), entry.getValue(), null, "1", null, null))
                    .toList();
            return new KeywordSearch.Results("wiki", hits, List.of(), null, true, 0);
        });
        TermSearch search = new TermSearch(keywords, (caller, query) -> PageSet.ofKeys(texts.keySet()),
                TermSearch.Knowledge.of(() -> {
                    created.incrementAndGet();
                    return new TermKnowledge(new DomainTermGraph(graph, vocabulary()));
                }, this::words));

        TermSearch.Result result = search.search(CALLER, KeywordSearch.Query.of("Wie läuft das Vorhaben heute"),
                List.of(), () -> false);

        assertThat(created).as("ein Begriffswissen je Suche").hasValue(1);
        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("1");
        assertThat(result.hits().getFirst().reasons()).anyMatch(reason -> reason.kind() == HitReason.Kind.TERM);
        assertThat(result.searches()).as("eine Suche je gefundenem Begriff").isEqualTo(1);
        assertThat(result.complete()).isTrue();
    }

    private Set<String> words(AccessContext caller) {
        Set<String> words = new HashSet<>();
        terms.forEach(term -> words.add(TermText.normalize(term.term()).strip()));
        synonyms.forEach(synonym -> {
            words.add(TermText.normalize(synonym.fromTerm()).strip());
            words.add(TermText.normalize(synonym.toTerm()).strip());
        });
        return words;
    }

    private DomainTermGraph.Vocabulary vocabulary() {
        return new DomainTermGraph.Vocabulary() {
            @Override
            public List<DomainTermGraph.Term> terms(AccessContext caller) {
                return terms;
            }

            @Override
            public List<DomainTermGraph.Synonym> synonyms(AccessContext caller) {
                return synonyms;
            }
        };
    }

    private void term(String term) {
        terms.add(new DomainTermGraph.Term(term, new RelationScope(null, null)));
    }

    private void analyse(String pageId, String text) {
        PageSnapshot snapshot = EnrichmentTestPages.text("wiki", pageId, "1", text);
        cache.put(snapshot.key(), snapshot);
        texts.put(snapshot.key(), text);
        statements.analyze(CALLER, snapshot, () -> false);
    }
}
