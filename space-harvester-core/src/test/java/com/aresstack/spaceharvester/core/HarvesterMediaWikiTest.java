package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.FakeMediaWikiTransport;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.MediaWikiSources;
import com.aresstack.spaceharvester.source.SourceSecrets;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.Auth;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import com.aresstack.spaceharvester.source.WikiFront;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Quelltyp {@code mediawiki} (#187) Ende zu Ende gegen die Fake-Wiki aus den Test-Fixtures der Kopie über echtes HTTP:
 * Abgleich ab den Startseiten, Katalog, Cache, Volltextsuche und Export; Anmeldung über die Konfiguration und über den
 * Anbieter des Programms (#188); Seiten, die das Konto oder der Aufrufer nicht lesen darf, bleiben unsichtbar.
 */
@ExtendWith(OutputCaptureExtension.class)
class HarvesterMediaWikiTest {

    @TempDir
    Path data;

    private FakeMediaWikiTransport wiki;
    private WikiFront front;

    @BeforeEach
    void start() throws Exception {
        wiki = new FakeMediaWikiTransport();
        wiki.page("Hauptseite", "<p>Willkommen im <b>Betriebshandbuch</b>.</p>", 1, 101, "Betrieb", "Urlaub");
        wiki.page("Betrieb", "<h2>Nachtlauf</h2><p>Der Nachtlauf startet um zwei Uhr.</p>"
                + "<ul><li>Pumpe prüfen</li><li>Ventil schließen</li></ul>", 2, 102, "Hauptseite", "Tresorraum");
        wiki.page("Urlaub", "<p>Urlaubsplanung für das Team</p>", 3, 103);
        wiki.page("Tresorraum", "<p>Zahlenkombination des Tresorraums</p>", 4, 104);
        wiki.page("Verwaist", "<p>Nicht verlinkter Artikel</p>", 5, 105);
        front = new WikiFront(wiki);
    }

    @AfterEach
    void stop() {
        front.close();
    }

    @Test
    void sourceWithoutStartPointsVerifiesApiAndDiscoversLocalizedMainPage() throws Exception {
        wiki.mainPage = "Willkommen";
        wiki.page("Willkommen", "<p>Start</p>", 6, 106, "Betrieb");
        SourceSettingsConfig config = new SourceSettingsConfig("wiki", MediaWikiSources.TYPE,
                "Testwiki", null, Auth.anonymous(),
                Map.of("apiUrl", front.apiUrl(), "maxDepth", "0"));
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            SourceManagement.ConnectionCheck check = harvester.sourceManagement().test(config, false);
            assertThat(check.successful()).isTrue();
            assertThat(front.queries()).anySatisfy(query -> assertThat(query).contains("meta=siteinfo"))
                    .noneSatisfy(query -> assertThat(query).contains("prop=links"));
            wiki.requiredUser = "test";
            wiki.requiredPassword = "test";
            assertThat(harvester.sourceManagement().test(config, false).successful()).isFalse();
            wiki.requiredUser = null;
            wiki.requiredPassword = null;
        }
        try (Harvester harvester = Harvester.builder().source(config).dataDirectory(data).build()) {
            JobStatus sync = harvester.await(harvester.sync("wiki", "wiki").id(), Duration.ofSeconds(30));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "wiki", null, 100).pages())
                    .extracting(page -> page.header().metadata().title()).containsExactlyInAnyOrder(
                            "Willkommen", "Hauptseite", "Betrieb", "Urlaub", "Tresorraum", "Verwaist");
        }
    }

    @Test
    void liveNavigationAndReadingWorkWithoutSynchronization() throws Exception {
        wiki.inventoryPageSize = 2;
        wiki.categories.put("Betrieb", List.of("Betrieb", "Verwaist"));
        wiki.categories.put("Handbuch", List.of("Verwaist"));
        try (Harvester harvester = Harvester.builder().source(wikiSource(Auth.anonymous(),
                Map.of("maxDepth", "0", "maxResources", "1"))).dataDirectory(data).build()) {
            PageNavigation.Children root = harvester.navigation().children(harvester.caller(), "wiki", "wiki", null, null);
            assertThat(root.pages()).extracting(PageNode::title).containsExactly("Hauptseite", "Alle Seiten", "Kategorien");
            assertThat(root.pages().get(1).kind()).isEqualTo(PageNode.Kind.GROUP);
            String all = root.pages().get(1).pageId();
            List<String> titles = new ArrayList<>();
            String cursor = null;
            do {
                PageNavigation.Children batch = harvester.navigation().children(harvester.caller(), "wiki", "wiki", all, cursor);
                titles.addAll(batch.pages().stream().map(PageNode::title).toList());
                cursor = batch.nextCursor();
            } while (cursor != null);
            assertThat(titles).containsExactlyInAnyOrder("Hauptseite", "Betrieb", "Urlaub", "Tresorraum", "Verwaist");
            String categories = root.pages().get(2).pageId();
            PageNavigation.Children groups = harvester.navigation().children(harvester.caller(), "wiki", "wiki", categories, null);
            assertThat(groups.pages()).extracting(PageNode::title).containsExactly("Betrieb", "Handbuch");
            PageNavigation.Children members = harvester.navigation().children(harvester.caller(), "wiki", "wiki",
                    groups.pages().get(0).pageId(), null);
            assertThat(members.pages()).extracting(PageNode::pageId).contains(id("Verwaist"));
            assertThat(harvester.navigation().position(harvester.caller(), "wiki", id("Verwaist")).spaceKey())
                    .isEqualTo("wiki");
            assertThat(harvester.navigation().cachedChildren(harvester.caller(), "wiki", "wiki", null).pages()).isEmpty();
            assertThat(front.queries()).noneSatisfy(query -> assertThat(query).contains("action=parse"));
            String article = new String(harvester.pages().page(harvester.caller(), "wiki", id("Verwaist"), false)
                    .snapshot().payload().bytes(), StandardCharsets.UTF_8);
            assertThat(article).contains("Nicht verlinkter Artikel");
            var reading = harvester.readingViews().view(harvester.caller(), "wiki", id("Hauptseite"), false);
            assertThat(reading.status()).isEqualTo(
                    com.aresstack.spaceharvester.application.reading.ReadingView.Status.AVAILABLE);
            assertThat(reading.html()).contains("<b>Betriebshandbuch</b>");
        }
    }

    @Test
    void explicitCrawlScopeRestrictsSyncButNeverNavigation() throws Exception {
        try (Harvester harvester = Harvester.builder().source(wikiSource(Auth.anonymous(),
                Map.of("maxDepth", "0", "maxResources", "1"))).dataDirectory(data).build()) {
            JobStatus sync = harvester.await(harvester.sync("wiki", "wiki").id(), Duration.ofSeconds(30));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "wiki", null, 100).pages())
                    .extracting(page -> page.header().metadata().title()).containsExactly("Hauptseite");
            assertThat(front.queries()).noneSatisfy(query -> assertThat(query).contains("list=allpages"));
            PageNavigation.Children root = harvester.navigation().children(harvester.caller(), "wiki", "wiki", null, null);
            PageNavigation.Children all = harvester.navigation().children(harvester.caller(), "wiki", "wiki",
                    root.pages().get(1).pageId(), null);
            assertThat(all.pages()).extracting(PageNode::title).contains("Verwaist", "Betrieb");
        }
    }

    @Test
    void aWikiIsSyncedCachedSearchedAndExportedAsOnePartition() throws Exception {
        try (Harvester harvester = Harvester.builder().source(wikiSource(Auth.anonymous(), Map.of()))
                .dataDirectory(data).build()) {
            assertThat(harvester.sources().partitions("wiki")).containsExactly(new Partition("wiki", "Intranet-Wiki",
                    null));
            assertThat(harvester.sources().sources()).singleElement().satisfies(source -> assertThat(
                    source.capabilities()).contains(SourceCapability.PAGE_LISTING, SourceCapability.STORED_PAGE,
                    SourceCapability.LIVE_SEARCH));

            JobStatus sync = harvester.await(harvester.sync("wiki", "wiki").id(), Duration.ofSeconds(30));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);

            // Katalog und Cache: die Startseite und was bis Tiefe 2 verlinkt ist, mit Titeln aus dem Wiki.
            List<ListedPage> pages = harvester.pages().pages(harvester.caller(), "wiki", "wiki", null, 100).pages();
            assertThat(pages).extracting(page -> page.header().metadata().title())
                    .containsExactlyInAnyOrder("Hauptseite", "Betrieb", "Urlaub", "Tresorraum");
            assertThat(pages).extracting(page -> page.header().key().pageId())
                    .contains(id("Betrieb"), id("Hauptseite"));
            String betrieb = new String(harvester.pages().page(harvester.caller(), "wiki", id("Betrieb"), false)
                    .snapshot().payload().bytes(), StandardCharsets.UTF_8);
            assertThat(betrieb).startsWith("# Betrieb").contains("Der Nachtlauf startet um zwei Uhr.");

            // Volltextsuche über den Index des Cache (#180).
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 10);
            assertThat(found.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly(id("Betrieb"));

            // Export aus dem Lesetext des Adapters.
            JobStatus export = harvester.await(harvester.export("wiki", "wiki", List.of("markdown")).id(),
                    Duration.ofSeconds(30));
            assertThat(export.state()).isEqualTo(JobState.COMPLETED);
            assertThat(export.progress().written()).isEqualTo(4);
            ExportArtifact artifact = harvester.exportArtifact(export.id()).orElseThrow();
            ExportArtifact.File markdown = artifact.files().stream().filter(file -> file.name().endsWith(".md"))
                    .findFirst().orElseThrow();
            try (InputStream in = harvester.exports().artifactFile(export.id(), Harvester.OWNER, markdown.name())
                    .orElseThrow()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("Der Nachtlauf startet um zwei Uhr.").contains("Pumpe prüfen");
            }
        }
        // Gelesen wurde über die Action-API, ohne Anmeldung.
        assertThat(front.queries()).anySatisfy(query -> assertThat(query).contains("action=parse"))
                .noneSatisfy(query -> assertThat(query).contains("action=login"));
    }

    @Test
    void aWikiWithLoginIsReadWithTheConfiguredCredentialsWithoutLoggingThem(CapturedOutput output)
            throws Exception {
        wiki.requiredUser = "harvester-bot";
        wiki.requiredPassword = "bot-geheim-4711";
        try (Harvester harvester = Harvester.builder()
                .source(wikiSource(new Auth(AuthType.BASIC, "harvester-bot", "bot-geheim-4711", null), Map.of()))
                .dataDirectory(data).build()) {
            JobStatus sync = harvester.await(harvester.sync("wiki", "wiki").id(), Duration.ofSeconds(30));

            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "wiki", null, 100).pages()).hasSize(4);
        }
        assertThat(wiki.logins).isPositive();
        assertThat(output.getAll()).doesNotContain("bot-geheim-4711").doesNotContain("Tresorraum");
    }

    @Test
    void withoutPasswordTheProgramsSecretProviderIsAskedAtLogin() throws Exception {
        wiki.requiredUser = "harvester-bot";
        wiki.requiredPassword = "aus-dem-tresor";
        List<String> asked = new ArrayList<>();
        SourceSecrets vault = ref -> {
            asked.add(ref);
            return Optional.of(new SourceSecrets.Credential("harvester-bot", "aus-dem-tresor".toCharArray()));
        };
        try (Harvester harvester = Harvester.builder().secretProvider(vault)
                .source(wikiSource(new Auth(AuthType.BASIC, null, null, null), Map.of()))
                .dataDirectory(data).build()) {
            assertThat(asked).isEmpty();

            JobStatus sync = harvester.await(harvester.sync("wiki", "wiki").id(), Duration.ofSeconds(30));

            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
            assertThat(asked).containsOnly("source:wiki");
        }
    }

    @Test
    void wrongCredentialsFailTheSyncWithoutPages() throws Exception {
        wiki.requiredUser = "harvester-bot";
        wiki.requiredPassword = "richtig";
        try (Harvester harvester = Harvester.builder()
                .source(wikiSource(new Auth(AuthType.BASIC, "harvester-bot", "falsch", null), Map.of()))
                .dataDirectory(data).build()) {
            JobStatus sync = harvester.await(harvester.sync("wiki", "wiki").id(), Duration.ofSeconds(30));

            assertThat(sync.state()).isEqualTo(JobState.FAILED);
            assertThatThrownBy(() -> harvester.pages().pages(harvester.caller(), "wiki", "wiki", null, 100))
                    .isInstanceOf(CacheMissException.class);
        }
    }

    @Test
    void pagesTheWikiAccountMayNotReadStayInvisible() throws Exception {
        front.unreadable("Tresorraum");
        try (Harvester harvester = Harvester.builder().source(wikiSource(Auth.anonymous(), Map.of()))
                .dataDirectory(data).build()) {
            JobStatus sync = harvester.await(harvester.sync("wiki", "wiki").id(), Duration.ofSeconds(30));
            // Die Discovery findet die Seite über den Link, lesen darf das Konto sie nicht: Der Lauf bestätigt den
            // Stand ohne sie und meldet sie als nicht abgeglichen, wie jede Seite, die die Quelle nicht liefert.
            assertThat(sync.state()).isEqualTo(JobState.FAILED);
            assertThat(sync.error().code()).isEqualTo("SYNC_PARTIAL");
            assertThat(sync.progress().written()).isEqualTo(3);
            assertThat(sync.progress().failed()).isEqualTo(1);

            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "wiki", null, 100).pages())
                    .extracting(page -> page.header().metadata().title())
                    .containsExactlyInAnyOrder("Hauptseite", "Betrieb", "Urlaub");
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Zahlenkombination"),
                    null, 10).hits()).isEmpty();
            assertThatThrownBy(() -> harvester.pages().page(harvester.caller(), "wiki", id("Tresorraum"), false))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void pagesTheCallerMayNotReadStayInvisible() throws Exception {
        String hidden = id("Urlaub");
        try (Harvester harvester = Harvester.builder().accessPolicy((context, scope, kind) ->
                        hidden.equals(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW)
                .source(wikiSource(Auth.anonymous(), Map.of())).dataDirectory(data).build()) {
            harvester.await(harvester.sync("wiki", "wiki").id(), Duration.ofSeconds(30));

            assertThat(harvester.pages().pages(harvester.caller(), "wiki", "wiki", null, 100).pages())
                    .extracting(page -> page.header().key().pageId()).doesNotContain(hidden).hasSize(3);
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Urlaubsplanung"),
                    null, 10).hits()).isEmpty();
        }
    }

    @Test
    void brokenSettingsAreReportedByKeyBeforeAnythingIsOpened() {
        assertThatThrownBy(() -> Harvester.builder().source(new SourceSettingsConfig("wiki", MediaWikiSources.TYPE,
                null, URI.create("https://wiki.example/w"), new Auth(AuthType.PAT, null, null, "token"),
                Map.of("credentialRef", "keepass:Wiki", "maxDepth", "x")))
                .dataDirectory(data).build())
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems()).containsExactly(
                        "sources[0] (id=wiki): base-url passt nicht zu type mediawiki; die Adresse steht unter "
                                + "options",
                        "sources[0] (id=wiki): auth.type pat passt nicht zu type mediawiki (none oder basic)",
                        "sources[0] (id=wiki): options.credentialRef ist nicht erlaubt; dafür gibt es auth"));
        assertThatThrownBy(() -> Harvester.builder().source(new SourceSettingsConfig("wiki", MediaWikiSources.TYPE,
                null, null, null, Map.of("apiUrl", front.apiUrl(), "maxDepth", "-1"))).dataDirectory(data).build())
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems())
                        .singleElement().asString().startsWith("sources[0] (id=wiki): options.maxDepth"));
    }

    private SourceSettingsConfig wikiSource(Auth auth, Map<String, String> options) {
        Map<String, String> values = new LinkedHashMap<>(Map.of("apiUrl", front.apiUrl(), "startPoints",
                "Hauptseite", "maxDepth", "2"));
        values.putAll(options);
        return new SourceSettingsConfig("wiki", MediaWikiSources.TYPE, "Intranet-Wiki", null, auth, values);
    }

    /** Seiten-ID einer Wiki-Seite: ihre Ressourcen-ID {@code wiki:<Host:Port>/w/<Titel>} als Base64url. */
    private String id(String title) {
        String locator = URI.create(front.apiUrl()).getAuthority() + "/w";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("wiki:" + locator + "/" + title)
                .getBytes(StandardCharsets.UTF_8));
    }
}
