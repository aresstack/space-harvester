package com.aresstack.spaceharvester.application.query.progressive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.TermKnowledge;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Suchmethode „Fachbegriffe“ (W8): Belegseiten von Fachbegriffen der Anfrage kommen nur über dieselbe Schlagwortsuche
 * im Umfang der Anfrage herein. Alle Begriffe, Seiten und Spaces sind erfunden.
 */
class TermSearchTest {

    private static final AccessContext USER = AccessContext.technicalClient("pruefer");
    private static final PageSet SCOPE = PageSet.of(Map.of("wiki", List.of("p1", "p2", "p3", "p4", "p5")));

    private final KeywordSearch keywords = mock(KeywordSearch.class);
    /** Gestellte Proben in ihrer Reihenfolge. */
    private final List<String> probes = Collections.synchronizedList(new ArrayList<>());

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    private static TermKnowledge.Page page(String pageId, String spaceKey) {
        return new TermKnowledge.Page(key(pageId), spaceKey, "Seite " + pageId);
    }

    private static KeywordSearch.Hit hit(String pageId) {
        return new KeywordSearch.Hit("wiki", pageId, "ABL", "Seite " + pageId, "Auszug", null, "1", null, null);
    }

    private static TermKnowledge.Answer found(String term, List<TermKnowledge.Page> pages, int inScope, int read) {
        return new TermKnowledge.Answer(TermKnowledge.Outcome.FOUND,
                List.of(new TermKnowledge.Term(term, DomainTermGraph.NodeKind.TERM, List.of())), List.of(), List.of(),
                pages, new TermKnowledge.Limits(inScope, read, 0, 0, 0, 0, false, 0));
    }

    private static TermKnowledge.Answer notATerm() {
        return new TermKnowledge.Answer(TermKnowledge.Outcome.NOT_A_TERM, List.of(), List.of(), List.of(), List.of(),
                new TermKnowledge.Limits(5, 5, 0, 0, 0, 0, false, 0));
    }

    /** Wissen mit festen Antworten je Wortgruppe (normalisiert klein); alles andere ist kein Fachbegriff. */
    private TermSearch.Knowledge knowledge(Map<String, TermKnowledge.Answer> answers) {
        return (caller, term, scope, cancelled) -> {
            probes.add(term);
            return answers.getOrDefault(term.toLowerCase(java.util.Locale.ROOT), notATerm());
        };
    }

    private TermSearch search(TermSearch.Knowledge knowledge) {
        return new TermSearch(keywords, (caller, query) -> query.pages() != null ? query.pages() : SCOPE, knowledge);
    }

    /** Die Schlagwortsuche liefert aus der Seitengrenze der Anfrage genau diese Seiten, sofern sie darin liegen. */
    private void keywordsReturn(String... pageIds) {
        when(keywords.search(any(), any(), isNull(), anyInt())).thenAnswer(call -> {
            KeywordSearch.Query query = call.getArgument(1);
            List<KeywordSearch.Hit> hits = java.util.Arrays.stream(pageIds)
                    .filter(id -> query.pages() == null || query.pages().contains("wiki", id))
                    .map(TermSearchTest::hit).toList();
            return new KeywordSearch.Results("wiki", hits, List.of(), null, true, 0);
        });
    }

    @Test
    void aSynonymInTheQueryBringsTheEvidencePagesSearchedWithItAndMarked() {
        // „Fallmappe“ ist bestätigtes Synonym des Fachbegriffs „Leistungsakte“; die Fachsicht antwortet mit ihm.
        keywordsReturn("p2", "p3");
        TermSearch.Result result = search(knowledge(Map.of("fallmappe",
                found("Fallmappe", List.of(page("p2", "ABL"), page("p3", "ABL")), 5, 5))))
                .search(USER, KeywordSearch.Query.of("Wo liegt die Fallmappe?"), List.of(), () -> false);

        assertThat(result.terms()).containsExactly("Fallmappe");
        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("p2", "p3");
        assertThat(result.hits().getFirst().reasons()).contains(HitReason.term("Fallmappe"));
        assertThat(result.complete()).isTrue();
        assertThat(result.capped()).isFalse();
        ArgumentCaptor<KeywordSearch.Query> sent = ArgumentCaptor.forClass(KeywordSearch.Query.class);
        verify(keywords).search(any(), sent.capture(), isNull(), anyInt());
        assertThat(sent.getValue().text()).isEqualTo("Fallmappe");
        assertThat(sent.getValue().pages().asMap()).isEqualTo(Map.of("wiki", Set.of("p2", "p3")));
    }

    @Test
    void pagesAlreadyFoundGetNoSecondRow() {
        keywordsReturn("p1", "p2");
        TermSearch.Result result = search(knowledge(Map.of("leistungsakte",
                found("Leistungsakte", List.of(page("p1", "ABL"), page("p2", "ABL")), 5, 5))))
                .search(USER, KeywordSearch.Query.of("Leistungsakte anlegen"), List.of(key("p1")), () -> false);

        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("p2");
    }

    @Test
    void nothingNewMeansNoSearchAtAll() {
        TermSearch.Result result = search(knowledge(Map.of("leistungsakte",
                found("Leistungsakte", List.of(page("p1", "ABL")), 5, 5))))
                .search(USER, KeywordSearch.Query.of("Leistungsakte"), List.of(key("p1")), () -> false);

        assertThat(result.hits()).isEmpty();
        assertThat(result.searches()).isZero();
        verify(keywords, never()).search(any(), any(), any(), anyInt());
    }

    @Test
    void anExcludedSpaceIsNoCandidateAndAPageWithoutReadAccessDoesNotAppear() {
        // p4 liegt im ausgeschlossenen Space ARCHIV; p5 darf der Aufrufer nicht lesen, also liefert die Suche sie nie.
        keywordsReturn("p3");
        KeywordSearch.Query query = new KeywordSearch.Query("Leistungsakte", null, Set.of(), Set.of(),
                Set.of("ARCHIV"));
        TermSearch.Result result = search(knowledge(Map.of("leistungsakte", found("Leistungsakte",
                List.of(page("p3", "ABL"), page("p4", "ARCHIV"), page("p5", "ABL")), 5, 5))))
                .search(USER, query, List.of(), () -> false);

        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("p3");
        ArgumentCaptor<KeywordSearch.Query> sent = ArgumentCaptor.forClass(KeywordSearch.Query.class);
        verify(keywords).search(any(), sent.capture(), isNull(), anyInt());
        assertThat(sent.getValue().pages().asMap()).isEqualTo(Map.of("wiki", Set.of("p3", "p5")));
        assertThat(sent.getValue().excludedSpaceKeys()).containsExactly("ARCHIV");
    }

    @Test
    void aHitOutsideTheCandidatesIsNeverTaken() {
        when(keywords.search(any(), any(), isNull(), anyInt())).thenReturn(new KeywordSearch.Results("wiki",
                List.of(hit("p2"), hit("fremd")), List.of(), null, true, 0));
        TermSearch.Result result = search(knowledge(Map.of("leistungsakte",
                found("Leistungsakte", List.of(page("p2", "ABL")), 5, 5))))
                .search(USER, KeywordSearch.Query.of("Leistungsakte"), List.of(), () -> false);

        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("p2");
    }

    @Test
    void atMostTwentyNewHits() {
        String[] ids = IntStream.rangeClosed(1, 30).mapToObj(i -> "s" + i).toArray(String[]::new);
        keywordsReturn(ids);
        List<TermKnowledge.Page> pages = java.util.Arrays.stream(ids).map(id -> page(id, "ABL")).toList();
        TermSearch.Result result = search(knowledge(Map.of("leistungsakte", found("Leistungsakte", pages, 30, 30))))
                .search(USER, KeywordSearch.Query.of("Leistungsakte"), List.of(), () -> false);

        assertThat(result.hits()).hasSize(TermSearch.MAX_HITS);
        assertThat(result.complete()).isFalse();
    }

    @Test
    void aCappedScopeIsReportedButKeepsTheHits() {
        keywordsReturn("p2");
        TermSearch.Result result = search(knowledge(Map.of("leistungsakte",
                found("Leistungsakte", List.of(page("p2", "ABL")), 900, TermKnowledge.MAX_PAGES))))
                .search(USER, KeywordSearch.Query.of("Leistungsakte"), List.of(), () -> false);

        assertThat(result.capped()).isTrue();
        assertThat(result.complete()).isTrue();
        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("p2");
    }

    @Test
    void longestGroupsFirstWithoutOverlapAndWithoutLeadingOrTrailingFillers() {
        keywordsReturn("p1", "p2");
        TermSearch.Result result = search(knowledge(Map.of(
                "akte der leistung", found("Akte der Leistung", List.of(page("p1", "ABL")), 5, 5),
                "akte", found("Akte", List.of(page("p2", "ABL")), 5, 5))))
                .search(USER, KeywordSearch.Query.of("Wie wird die Akte der Leistung geschlossen?"), List.of(),
                        () -> false);

        assertThat(result.terms()).containsExactly("Akte der Leistung");
        // Keine Wortgruppe beginnt oder endet mit einem Füllwort; „Akte“ allein ist schon vergeben.
        assertThat(probes).doesNotContain("Akte", "die Akte", "der Leistung", "wird die Akte");
        assertThat(probes.getFirst()).isEqualTo("Akte der Leistung");
    }

    @Test
    void atMostTheProbeLimit() {
        String longQuestion = IntStream.rangeClosed(1, 15).mapToObj(i -> "Begriff" + i)
                .reduce((a, b) -> a + " " + b).orElseThrow();
        TermSearch.Result result = search(knowledge(Map.of()))
                .search(USER, KeywordSearch.Query.of(longQuestion), List.of(), () -> false);

        assertThat(probes).hasSize(TermSearch.MAX_PROBES);
        assertThat(result.complete()).isFalse();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void cancellingBetweenProbesStopsAndKeepsWhatWasFound() {
        keywordsReturn("p1");
        AtomicBoolean cancelled = new AtomicBoolean();
        List<BooleanSupplier> handedOn = new ArrayList<>();
        TermSearch.Knowledge knowledge = (caller, term, scope, stop) -> {
            probes.add(term);
            handedOn.add(stop);
            if (!term.equals("Leistungsakte")) {
                return notATerm();
            }
            // Mit dem ersten Fund läuft die Frist ab.
            cancelled.set(true);
            return found("Leistungsakte", List.of(page("p1", "ABL")), 5, 5);
        };

        TermSearch.Result result = search(knowledge).search(USER,
                KeywordSearch.Query.of("Leistungsakte und Fristablauf"), List.of(), cancelled::get);

        assertThat(result.complete()).isFalse();
        // Erst die ganze Gruppe, dann „Leistungsakte“; „Fristablauf“ wird nach dem Abbruch nicht mehr geprüft.
        assertThat(probes).containsExactly("Leistungsakte und Fristablauf", "Leistungsakte");
        assertThat(handedOn.getFirst().getAsBoolean()).isTrue();
        // Gesucht wird nach Abbruch nicht mehr; der Fund bleibt Begriff, aber ohne neue Suchanfrage.
        assertThat(result.terms()).containsExactly("Leistungsakte");
        verify(keywords, never()).search(any(), any(), any(), anyInt());
    }

    @Test
    void aCancellationInsideTheKnowledgeEndsTheSearchAsIncomplete() {
        TermSearch.Knowledge knowledge = (caller, term, scope, stop) -> {
            throw new CancellationException("Fachsicht abgebrochen");
        };

        TermSearch.Result result = search(knowledge).search(USER, KeywordSearch.Query.of("Leistungsakte"), List.of(),
                () -> false);

        assertThat(result.complete()).isFalse();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void aFailingKeywordSearchKeepsTheOtherTermsHits() {
        when(keywords.search(any(), any(), isNull(), anyInt())).thenAnswer(call -> {
            KeywordSearch.Query query = call.getArgument(1);
            if (query.text().equals("Leistungsakte")) {
                throw new IllegalStateException("Index belegt");
            }
            return new KeywordSearch.Results("wiki", List.of(hit("p2")), List.of(), null, true, 0);
        });
        TermSearch.Result result = search(knowledge(Map.of(
                "leistungsakte", found("Leistungsakte", List.of(page("p1", "ABL")), 5, 5),
                "fristablauf", found("Fristablauf", List.of(page("p2", "ABL")), 5, 5))))
                .search(USER, KeywordSearch.Query.of("Leistungsakte Fristablauf"), List.of(), () -> false);

        assertThat(result.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("p2");
        assertThat(result.complete()).isFalse();
    }

    @Test
    void wordsLoseOnlyEdgePunctuation() {
        assertThat(TermSearch.words("„Leistungsakte“, Ü-Bescheid; v1.2?")).containsExactly("Leistungsakte",
                "Ü-Bescheid", "v1.2");
    }
}
