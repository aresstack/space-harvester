package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessContext.PrincipalKind;
import com.aresstack.spaceharvester.application.deadlines.LegalKnowledgeRepository.Kind;
import com.aresstack.spaceharvester.application.deadlines.LegalKnowledgeRepository.LegalHit;
import com.aresstack.spaceharvester.application.deadlines.LegalKnowledgeRepository.LegalQuery;
import com.aresstack.spaceharvester.application.deadlines.LegalKnowledgeRepository.State;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Rechtswissen über die Stichwortsuche; Quellen und Texte sind erfunden. */
class SearchLegalKnowledgeTest {

    private static final AccessContext CALLER = new AccessContext("tester", PrincipalKind.DEVELOPMENT);

    private final KeywordSearch search = mock(KeywordSearch.class);

    @Test
    void withoutMarkedSourcesNothingIsConfiguredAndNothingIsSearched() {
        SearchLegalKnowledge knowledge = new SearchLegalKnowledge(search, () -> CALLER, Set::of, id -> true);

        assertThat(knowledge.state()).isEqualTo(State.NOT_CONFIGURED);
        assertThat(knowledge.search(query(), 5)).isEmpty();
        verifyNoInteractions(search);
    }

    @Test
    void markedButUnreadSourcesAreEmpty() {
        SearchLegalKnowledge knowledge = new SearchLegalKnowledge(search, () -> CALLER, () -> Set.of("gesetze"),
                id -> false);

        assertThat(knowledge.state()).isEqualTo(State.EMPTY);
        assertThat(knowledge.search(query(), 5)).isEmpty();
        verifyNoInteractions(search);
    }

    @Test
    void searchesOnlyTheLegalSourcesAsTheCaller() {
        KeywordSearch.Hit norm = hit("gesetze", "p-60", "Muster-Haushaltsordnung § 60");
        KeywordSearch.Hit stray = hit("wiki", "p-1", "Kantinenplan");
        when(search.search(eq(CALLER), any(), isNull(), anyInt()))
                .thenReturn(new KeywordSearch.Results(null, List.of(norm, stray), List.of(), null, true, 0));
        SearchLegalKnowledge knowledge = new SearchLegalKnowledge(search, () -> CALLER, () -> Set.of("gesetze"),
                id -> true);

        List<LegalHit> hits = knowledge.search(query(), 5);

        ArgumentCaptor<KeywordSearch.Query> sent = ArgumentCaptor.forClass(KeywordSearch.Query.class);
        verify(search).search(eq(CALLER), sent.capture(), isNull(), eq(5));
        assertThat(sent.getValue().sourceIds()).containsExactly("gesetze");
        assertThat(sent.getValue().text()).isEqualTo("Vorschuss abwickeln § 60 MHO Vorschuss");
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.documentId()).isEqualTo("p-60");
            assertThat(hit.excerpt()).isEqualTo("Auszug");
            assertThat(hit.kind()).as("Art steht nicht in der Suche").isEqualTo(Kind.UNKNOWN);
            assertThat(hit.validFrom()).isNull();
            assertThat(hit.provenance()).contains("https://example.test/p-60").contains("Version 7");
        });
    }

    @Test
    void longQueriesAreCutAtAWordBoundary() {
        String text = SearchLegalKnowledge.text(new LegalQuery("wort ".repeat(60), "", "", "", "", null));

        assertThat(text.length()).isLessThanOrEqualTo(KeywordSearch.MAX_TEXT_LENGTH);
        assertThat(text).doesNotEndWith(" ").endsWith("wort");
    }

    private static LegalQuery query() {
        return new LegalQuery("Vorschuss abwickeln", "§ 60 MHO", "Vorschuss", "vorschuss", "",
                LocalDate.of(2026, 7, 27));
    }

    private static KeywordSearch.Hit hit(String source, String page, String title) {
        return new KeywordSearch.Hit(source, page, "S", title, "Auszug", URI.create("https://example.test/" + page),
                "7", Instant.parse("2026-01-02T03:04:05Z"), null, null, null, List.of());
    }
}
