package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermOccurrence;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Begriffsindex (#119) von Ende zu Ende: Eine Seite wird aus Confluence geladen, der Prozessor {@code terms} läuft dabei
 * mit, und Akronym und Bezeichner sind über {@code harvester.terms()} und in der Leseansicht über {@link PageTerms}
 * mit denselben Fundstellen erreichbar. Vorlage für die Desktop-App (#339).
 */
class HarvesterTermsTest {

    private static final String STORAGE = "<h1>Rückmeldung</h1><p>Der RSL (Rückmelde-Schlüssel-Liste) steuert die "
            + "Sachbearbeitung.</p><p>Ohne RSL bleibt die Sachbearbeitung offen.</p>";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("102", "Rückmeldungen", "DEV", STORAGE).storage(STORAGE);
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void aLoadedPageIsInTheGlobalIndexAndInItsReaderTabWithTheSameOccurrences() {
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).build()) {
            DeliveredPage page = harvester.pages().page(harvester.caller(), "wiki", "102", false);

            TermQueries.TermList acronyms = harvester.terms().terms(harvester.caller(),
                    new TermQueries.TermSearch(null, null, TermKind.ACRONYM, null, false, 50, null));
            assertThat(acronyms.terms()).singleElement().satisfies(rsl -> {
                assertThat(rsl.term()).isEqualTo("RSL");
                assertThat(rsl.occurrences()).isEqualTo(2);
                assertThat(rsl.expansions()).containsExactly("Rückmelde-Schlüssel-Liste");
            });
            TermQueries.TermList nouns = harvester.terms().terms(harvester.caller(),
                    new TermQueries.TermSearch("wiki", "DEV", TermKind.NOUN, "sachb", false, 50, null));
            assertThat(nouns.terms()).extracting(TermQueries.TermSummary::term).containsExactly("Sachbearbeitung");

            TermQueries.OccurrenceList occurrences = harvester.terms().occurrences(harvester.caller(),
                    new TermQueries.OccurrenceSearch("sachbearbeitung", TermKind.NOUN, null, null, 50, null, 20));
            assertThat(occurrences.occurrences()).hasSize(2).allSatisfy(occurrence -> {
                assertThat(occurrence.title()).isEqualTo("Rückmeldungen");
                assertThat(occurrence.spaceKey()).isEqualTo("DEV");
                assertThat(occurrence.snippet().match()).isEqualTo("Sachbearbeitung");
            });

            // Leseansicht: dieselbe Projektion, derselbe Eingabe-Hash, dieselben Positionen.
            PageText text = new ConfluenceStorageTextProjector().project(page.snapshot()).orElseThrow();
            List<Enrichments.View> views = harvester.enrichments().view(page.snapshot());
            PageTerms local = PageTerms.read(new PageKey("wiki", "102"), views, text);
            assertThat(local.state()).isEqualTo(PageTerms.State.CURRENT);
            assertThat(local.acronymCount()).isEqualTo(1);
            assertThat(local.identifierCount()).isPositive();
            TermPayload.Entry sachbearbeitung = local.of(TermKind.NOUN).stream()
                    .filter(entry -> entry.normalized().equals("sachbearbeitung")).findFirst().orElseThrow();
            assertThat(sachbearbeitung.occurrences()).extracting(TermOccurrence::start)
                    .containsExactlyElementsOf(occurrences.occurrences().stream()
                            .map(TermQueries.Occurrence::start).toList());
            for (TermOccurrence occurrence : sachbearbeitung.occurrences()) {
                assertThat(text.text().substring(occurrence.start(), occurrence.end())).isEqualTo("Sachbearbeitung");
            }
            assertThat(occurrences.occurrences().getFirst().inputSha256()).isEqualTo(text.inputSha256());
        }
    }

    @Test
    void relationsSurviveACacheRebuild() throws IOException {
        String id;
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).build()) {
            harvester.pages().page(harvester.caller(), "wiki", "102", false);
            TermQueries.Occurrence rsl = harvester.terms().occurrences(harvester.caller(),
                    new TermQueries.OccurrenceSearch("RSL", TermKind.ACRONYM, null, null, 1, null, 0))
                    .occurrences().get(0);
            TermRelation created = harvester.termRelations().add(harvester.caller(), new TermRelations.NewRelation(
                    "RSL", "Rückmeldeschlüssel", RelationType.ABBREVIATION, RelationScope.GLOBAL, null,
                    List.of(new RelationEvidence("wiki", "102", rsl.start(), rsl.end(), rsl.inputSha256()))));
            id = created.id();
        }
        try (Stream<Path> files = Files.walk(data.resolve("cache"))) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).build()) {
            assertThat(harvester.termRelations().relation(harvester.caller(), id).toTerm())
                    .isEqualTo("Rückmeldeschlüssel");
            assertThat(harvester.termRelations().expansions(harvester.caller(), "rsl", "wiki"))
                    .containsExactly("Rückmeldeschlüssel");
        }
    }

    @Test
    void withoutContentRightsTheIndexShowsNothing() {
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).build()) {
            harvester.pages().page(harvester.caller(), "wiki", "102", false);
        }
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data)
                .accessPolicy((context, scope, kind) -> kind == AccessKind.CONTENT && scope.pageId() != null
                        ? AccessDecision.DENY : AccessDecision.ALLOW).build()) {
            assertThat(harvester.terms().terms(harvester.caller(), new TermQueries.TermSearch(null, null, null, null,
                    false, 50, null)).terms()).isEmpty();
        }
    }

    private static SourceSettingsConfig wiki() {
        URI base = SERVER.baseUrl();
        return new SourceSettingsConfig("wiki", "confluence", "Wiki", base, SourceSettingsConfig.Auth.anonymous(),
                Map.of("connectTimeoutMillis", "2000"));
    }
}
