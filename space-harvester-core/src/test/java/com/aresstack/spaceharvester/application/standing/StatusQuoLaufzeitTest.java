package com.aresstack.spaceharvester.application.standing;

import static com.aresstack.spaceharvester.application.standing.StandingFixtures.features;
import static com.aresstack.spaceharvester.application.standing.StandingFixtures.header;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Temporality;
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
 * Laufzeit der Status-quo-Sicht an einigen tausend erzeugten Seiten (ADR 0032, FL6, Auflage AT). Prüft nur eine
 * großzügige Obergrenze; die gemessene Zeit steht in {@code build/reports/status-quo/laufzeit.txt}.
 */
class StatusQuoLaufzeitTest {

    static final int PAGES = 3000;
    static final int STATEMENTS_PER_PAGE = 20;
    private static final Temporality[] TEMPORALITIES = Temporality.values();

    @Test
    void thousandsOfPagesStayFast() throws IOException {
        List<KnowledgeGraph.AnalysedPage> pages = new ArrayList<>();
        Map<PageKey, PageHeader> headers = new HashMap<>();
        for (int p = 0; p < PAGES; p++) {
            String id = Integer.toString(p);
            String parent = p % 10 == 0 ? null : Integer.toString(p - p % 10);
            headers.put(new PageKey("wiki", id), header(id, p % 100 == 0 ? "Archiv " + p : "Seite " + p, parent,
                    p % 7 == 0 ? Map.of("confluence.labels", "entwurf,foerderung") : Map.of()));
            StandingFixtures.PageBuilder builder = StandingFixtures.page(id, "Seite " + p, 'a');
            for (int s = 0; s < STATEMENTS_PER_PAGE; s++) {
                Temporality temporality = TEMPORALITIES[(p + s) % TEMPORALITIES.length];
                if (s % 4 == 0) {
                    builder.state("Antrag" + (s % 13), "vollständig", features(temporality));
                } else {
                    builder.action("Kasse" + (s % 11), "prüft", "Beleg" + s, features(temporality));
                }
            }
            pages.add(builder.build());
        }
        StatusQuo statusQuo = new StatusQuo(caller -> pages, key -> Optional.ofNullable(headers.get(key)),
                StandingDecisionStore.inMemory(), new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW),
                () -> StandingWords.DEFAULT, Clock.fixed(StandingFixtures.AT, ZoneOffset.UTC));
        AccessContext caller = AccessContext.technicalClient("desktop");
        statusQuo.view(caller, key -> true);

        long best = Long.MAX_VALUE;
        StatusQuoView view = null;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            view = statusQuo.view(caller, key -> true);
            best = Math.min(best, System.nanoTime() - start);
        }

        assertThat(view.entries()).hasSize(PAGES * STATEMENTS_PER_PAGE);
        long millis = best / 1_000_000;
        Path report = Path.of("build", "reports", "status-quo", "laufzeit.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, PAGES + " Seiten, " + PAGES * STATEMENTS_PER_PAGE + " Aussagen: " + millis
                + " ms (bester von 3 Läufen)\n");
        System.out.println("Status-quo-Sicht: " + PAGES + " Seiten, " + PAGES * STATEMENTS_PER_PAGE
                + " Aussagen in " + millis + " ms");
        assertThat(millis).as("großzügige Obergrenze").isLessThan(20_000);
    }
}
