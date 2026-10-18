package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Titelsuche der Navigation gegen eine Fake-Confluence über echtes HTTP und gegen ein Verzeichnis: Seiten über ihren
 * Titel finden, ohne Abgleich, ohne Laden, nur sichtbare.
 */
class HarvesterTitleSearchTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("OPS", "Betrieb");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Nachtlauf erwähnt</p>");
        CONFLUENCE.page("102", "Nachtlauf prüfen", "DEV", "<p>P</p>");
        CONFLUENCE.page("200", "Bereitschaft im NACHTLAUF", "OPS", "<p>B</p>");
        CONFLUENCE.homepage("DEV", "100");
        // Die Wortsuche findet auch Seiten, die das Wort nur im Inhalt tragen (100).
        CONFLUENCE.searchResults("100", "102", "200");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;

    @TempDir
    Path files;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void confluenceFindsPagesByTitleWithTheirSpaceWithoutLoadingThem() {
        try (Harvester harvester = wiki(Harvester.builder())) {
            assertThat(harvester.sources().sources().getFirst().capabilities())
                    .contains(SourceCapability.TITLE_SEARCH);

            PageNavigation.TitleMatches matches = harvester.navigation().findByTitle(harvester.caller(), "wiki",
                    Set.of(), "nachtlauf", 10);

            assertThat(matches.pages()).extracting(PageNode::pageId, PageNode::partitionKey, PageNode::title)
                    .containsExactly(tuple("102", "DEV", "Nachtlauf prüfen"),
                            tuple("200", "OPS", "Bereitschaft im NACHTLAUF"));
            assertThat(matches.truncated()).isFalse();
            assertThat(lastCql()).isEqualTo("siteSearch ~ \"nachtlauf\" AND type=page");
            // Reine Navigation: nichts landet im Cache, keine Seite wird geladen.
            assertThatThrownBy(() -> harvester.pages().pages(harvester.caller(), "wiki", "DEV", null, 10))
                    .isInstanceOf(CacheMissException.class);
            assertThat(CONFLUENCE.requests()).noneMatch(uri -> String.valueOf(uri.getQuery()).contains("body."));
        }
    }

    @Test
    void theSpaceFilterAndTheSearchTextAreEscapedIntoTheCqlOfTheSource() {
        try (Harvester harvester = wiki(Harvester.builder())) {
            harvester.navigation().findByTitle(harvester.caller(), "wiki", Set.of("OPS", "D\"EV"),
                    "Nacht\" OR title ~ \"x\\", 10);

            String cql = lastCql();
            assertThat(cql).startsWith("siteSearch ~ \"Nacht\\\" OR title ~ \\\"x\\\\\" AND type=page AND space in (");
            assertThat(cql).contains("\"OPS\"").contains("\"D\\\"EV\"");
        }
    }

    @Test
    void lockedPagesNeverAppearNotEvenAsTruncated() {
        try (Harvester harvester = wiki(Harvester.builder().accessPolicy((context, scope, kind) ->
                "102".equals(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW))) {
            PageNavigation.TitleMatches one = harvester.navigation().findByTitle(harvester.caller(), "wiki",
                    Set.of(), "Nachtlauf", 1);

            assertThat(one.pages()).extracting(PageNode::pageId).containsExactly("200");
            assertThat(one.truncated()).isFalse();
        }
        try (Harvester harvester = wiki(Harvester.builder())) {
            PageNavigation.TitleMatches one = harvester.navigation().findByTitle(harvester.caller(), "wiki",
                    Set.of(), "Nachtlauf", 1);

            assertThat(one.pages()).extracting(PageNode::pageId).containsExactly("102");
            assertThat(one.truncated()).isTrue();
        }
    }

    @Test
    void aFolderMatchesFileNamesIgnoringCase() throws IOException {
        Files.writeString(files.resolve("Betriebshandbuch.md"), "# Handbuch", StandardCharsets.UTF_8);
        Files.writeString(files.resolve("urlaub.txt"), "Betrieb im Urlaub", StandardCharsets.UTF_8);
        Files.createDirectories(files.resolve("alt"));
        Files.writeString(files.resolve("alt/NOTIZEN-BETRIEB.txt"), "N", StandardCharsets.UTF_8);
        try (Harvester harvester = Harvester.builder().source(new SourceSettingsConfig("docs", "files", "Dokumente",
                null, null, Map.of("directory", files.toString()))).dataDirectory(data).build()) {
            assertThat(harvester.sources().sources().getFirst().capabilities())
                    .contains(SourceCapability.TITLE_SEARCH);

            PageNavigation.TitleMatches matches = harvester.navigation().findByTitle(harvester.caller(), "docs",
                    Set.of(), "betrieb", 10);

            assertThat(matches.pages()).extracting(PageNode::title)
                    .containsExactlyInAnyOrder("Betriebshandbuch.md", "NOTIZEN-BETRIEB.txt");
            assertThat(matches.pages()).extracting(PageNode::partitionKey).containsOnly("docs");
            assertThat(harvester.navigation().findByTitle(harvester.caller(), "docs", Set.of("anders"), "betrieb",
                    10).pages()).isEmpty();
            PageNavigation.TitleMatches one = harvester.navigation().findByTitle(harvester.caller(), "docs",
                    Set.of("docs"), "BETRIEB", 1);
            assertThat(one.pages()).hasSize(1);
            assertThat(one.truncated()).isTrue();
        }
    }

    private Harvester wiki(Harvester.Builder builder) {
        return builder.source(new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                .dataDirectory(data)
                .build();
    }

    private static String lastCql() {
        URI search = CONFLUENCE.requests().reversed().stream()
                .filter(uri -> uri.getPath().endsWith("/search")).findFirst().orElseThrow();
        for (String pair : search.getRawQuery().split("&")) {
            if (pair.startsWith("cql=")) {
                return URLDecoder.decode(pair.substring(4), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("keine CQL in " + search);
    }
}
