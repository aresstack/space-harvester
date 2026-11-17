package com.aresstack.spaceharvester.application.standing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Laufzeit der Leseempfehlung an denselben erzeugten Seiten wie die Status-quo-Sicht (ADR 0032, FL6, Auflage AT).
 * Gemessene Zeit in {@code build/reports/status-quo/leseempfehlung.txt}.
 */
class ReadingAdviceLaufzeitTest {

    @Test
    void thousandsOfPagesStayFast() throws IOException {
        List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
        Map<PageKey, PageHeader> headers = new HashMap<>();
        StatusQuoLaufzeitTest.generate(pages, headers);
        Clock clock = Clock.fixed(StandingFixtures.AT, ZoneOffset.UTC);
        StatusQuo statusQuo = new StatusQuo(caller -> pages, key -> Optional.ofNullable(headers.get(key)),
                StandingDecisionStore.inMemory(), new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                () -> StandingWords.DEFAULT, clock);
        ReadingAdvice advice = new ReadingAdvice(statusQuo, caller -> pages, RecentChanges.NONE, clock);
        AccessContext caller = AccessContext.technicalClient("desktop");
        advice.recommend(caller, key -> true, null, () -> false);

        long best = Long.MAX_VALUE;
        long bestTopic = Long.MAX_VALUE;
        ReadingList list = null;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            list = advice.recommend(caller, key -> true, null, () -> false);
            best = Math.min(best, System.nanoTime() - start);
            start = System.nanoTime();
            advice.recommend(caller, key -> true, "Kasse3", () -> false);
            bestTopic = Math.min(bestTopic, System.nanoTime() - start);
        }

        assertThat(list.pages()).hasSize(StatusQuoLaufzeitTest.PAGES);
        long millis = best / 1_000_000;
        long topicMillis = bestTopic / 1_000_000;
        String line = StatusQuoLaufzeitTest.PAGES + " Seiten, "
                + StatusQuoLaufzeitTest.PAGES * StatusQuoLaufzeitTest.STATEMENTS_PER_PAGE + " Aussagen: " + millis
                + " ms ganze Auswahl, " + topicMillis + " ms mit Thema (bester von 3 Läufen)";
        Path report = Path.of("build", "reports", "status-quo", "leseempfehlung.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, line + "\n");
        System.out.println("Leseempfehlung: " + line);
        assertThat(millis).as("großzügige Obergrenze").isLessThan(20_000);
    }
}
