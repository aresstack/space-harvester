package com.aresstack.spaceharvester.application.standing;

import static com.aresstack.spaceharvester.application.standing.StandingFixtures.features;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.header;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Temporality;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Leseempfehlung (ADR 0032, FL6): offene Teilzahlen mit Grund, erfundene Seiten, kein Modell. */
class ReadingAdviceTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("desktop");
    private static final Clock CLOCK = Clock.fixed(StandingFixtures.AT, ZoneOffset.UTC);

    private final List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
    private final Map<PageKey, PageHeader> headers = new HashMap<>();
    private final Map<PageKey, Instant> changes = new HashMap<>();
    private final List<Map<String, java.util.Set<String>>> asked = new ArrayList<>();
    private final StatusQuo statusQuo = new StatusQuo(caller -> List.copyOf(pages),
            key -> Optional.ofNullable(headers.get(key)), StandingDecisionStore.inMemory(),
            new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), () -> StandingWords.DEFAULT, CLOCK);
    private final ReadingAdvice advice = new ReadingAdvice(statusQuo, caller -> List.copyOf(pages),
            (caller, spaces, from, cancelled) -> {
                asked.add(spaces);
                Map<PageKey, Instant> recent = new HashMap<>();
                changes.forEach((key, at) -> {
                    if (!at.isBefore(from)) {
                        recent.put(key, at);
                    }
                });
                return recent;
            }, CLOCK);

    @Test
    void everyBonusAndPenaltyIsAnOpenPart() {
        pages.add(page("1", "Förderung aktuell", 'a')
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .action("Kasse", "prüft", "Antrag", features(Temporality.GILT))
                .action("Amt", "prüfte", "Antrag", features(Temporality.FRUEHER))
                .build());
        pages.add(page("2", "Förderung alt", 'b')
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .build());
        headers.put(new PageKey("wiki", "2"), header("2", "Förderung alt", null, Map.of("confluence.labels", "archiv")));
        changes.put(new PageKey("wiki", "1"), StandingFixtures.AT.minusSeconds(3600));
        changes.put(new PageKey("wiki", "2"), StandingFixtures.AT.minus(ReadingAdvice.RECENT).minusSeconds(1));

        ReadingList list = advice.recommend(EDITOR, key -> true, null, () -> false);

        assertThat(list.topic()).isNull();
        assertThat(list.topTerms()).containsExactlyInAnyOrder("Kasse", "Zuschuss");
        assertThat(list.changesSince()).isEqualTo(StandingFixtures.AT.minus(ReadingAdvice.RECENT));
        assertThat(list.pages()).extracting(r -> r.page().key().pageId()).containsExactly("1", "2");
        ReadingList.Recommendation first = list.pages().getFirst();
        assertThat(first.parts()).extracting(ReadingList.Part::reason, ReadingList.Part::count,
                ReadingList.Part::points).containsExactly(
                tuple(ReadingReason.CURRENT_STATEMENTS, 2, 2),
                tuple(ReadingReason.OUTDATED_STATEMENTS, 1, -1),
                tuple(ReadingReason.TOP_TERMS, 2, 4),
                tuple(ReadingReason.RECENT_CHANGE, 1, 3));
        assertThat(first.score()).isEqualTo(8);
        assertThat(first.parts().get(2).terms()).containsExactlyInAnyOrder("Kasse", "Zuschuss");
        ReadingList.Recommendation archived = list.pages().get(1);
        assertThat(archived.standing().standing()).isEqualTo(Standing.UEBERHOLT);
        assertThat(archived.parts()).extracting(ReadingList.Part::reason).containsExactly(
                ReadingReason.OUTDATED_STATEMENTS, ReadingReason.TOP_TERMS, ReadingReason.PAGE_OUTDATED);
        assertThat(archived.score()).isEqualTo(archived.parts().stream().mapToInt(ReadingList.Part::points).sum())
                .isEqualTo(-1 + 4 - 10);
    }

    @Test
    void topicKeepsOnlyPagesThatMentionIt() {
        pages.add(page("1", "Förderung", 'a')
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT))
                .action("Kasse", "prüft", "Zuschuss", features(Temporality.GILT))
                .build());
        pages.add(page("2", "Reisen", 'b')
                .action("Amt", "bucht", "Reise", features(Temporality.GILT))
                .build());

        ReadingList list = advice.recommend(EDITOR, key -> true, " zuschuss ", () -> false);

        assertThat(list.topic()).isEqualTo("zuschuss");
        assertThat(list.pages()).singleElement().satisfies(recommendation -> {
            assertThat(recommendation.page().key().pageId()).isEqualTo("1");
            assertThat(recommendation.parts()).extracting(ReadingList.Part::reason, ReadingList.Part::points)
                    .contains(tuple(ReadingReason.TOPIC_MENTIONS, 2));
        });
    }

    @Test
    void draftAndStaleAnalysisCostPoints() {
        StandingFixtures.PageBuilder stale = page("1", "Entwurf Förderung", 'a')
                .action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT));
        stale.state = PageStatements.State.STALE;
        pages.add(stale.build());
        headers.put(new PageKey("wiki", "1"), header("1", "Entwurf Förderung", null, Map.of()));

        ReadingList.Recommendation only = advice.recommend(EDITOR, key -> true, null, () -> false).pages()
                .getFirst();

        assertThat(only.parts()).extracting(ReadingList.Part::reason, ReadingList.Part::points).contains(
                tuple(ReadingReason.PAGE_DRAFT, ReadingAdvice.PAGE_DRAFT),
                tuple(ReadingReason.STALE_ANALYSIS, ReadingAdvice.STALE_ANALYSIS));
    }

    @Test
    void selectionLimitsPagesAndCancelStops() {
        pages.add(page("1", "Eins", 'a').action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT)).build());
        pages.add(page("2", "Zwei", 'b').action("Kasse", "zahlt", "Zuschuss", features(Temporality.GILT)).build());

        assertThat(advice.recommend(EDITOR, key -> key.pageId().equals("2"), null, () -> false).pages())
                .extracting(r -> r.page().key().pageId()).containsExactly("2");
        assertThat(asked).as("Protokoll nur für Quelle und Space der Auswahl")
                .containsExactly(Map.of("wiki", java.util.Set.of("FOE")));
        asked.clear();
        advice.recommend(EDITOR, key -> false, null, () -> false);
        assertThat(asked).as("leere Auswahl fragt das Protokoll nicht").isEmpty();
        assertThatThrownBy(() -> advice.recommend(EDITOR, key -> true, null, () -> true))
                .isInstanceOf(CancellationException.class);
    }
}
