package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.SourceLink;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.testing.InMemoryKnowledgeSource;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.KnowledgeSourceProviderAdapter;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Quellen ohne Seitenliste je Partition (#187), wie MediaWiki, Git oder FTP im Enterprise AI Client: über
 * {@code discover} und {@code load} als eine Partition je Quelle, durchgehend durch Abgleich, Katalog, Cache, Suche
 * und Export. Die Quelle ist die Fake-Quelle aus den Test-Fixtures der Kopie, ohne Partitionen und ohne eigene Suche.
 */
class HarvesterDiscoveredSourceTest {

    @TempDir
    Path data;

    private final InMemoryKnowledgeSource notes = new InMemoryKnowledgeSource("notes://notes.intern", "notes")
            .add("start", "Startseite", "# Start\n\nWillkommen im Betriebshandbuch.", "betrieb", "urlaub")
            .add("betrieb", "Betrieb", "# Betrieb\n\nDer Nachtlauf startet um zwei Uhr.\n\n- Pumpe prüfen\n"
                    + "- Ventil schließen")
            .add("urlaub", "Urlaub", "Urlaubsplanung für das Team")
            .add("verwaist", "Verwaist", "Nicht verlinkt und deshalb außerhalb des Umfangs");

    @Test
    void aSourceWithoutPartitionsIsSyncedCachedSearchedAndExportedAsOnePartition() throws Exception {
        try (Harvester harvester = harvester(Harvester.builder())) {
            assertThat(harvester.sources().partitions("notizen")).containsExactly(new Partition("notizen",
                    "Notizen", null));

            JobStatus sync = harvester.await(harvester.sync("notizen", "notizen").id(), Duration.ofSeconds(30));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);

            // Katalog und Cache: genau der Umfang der Quelle, Titel und Text aus der Quelle.
            List<ListedPage> pages = harvester.pages().pages(harvester.caller(), "notizen", "notizen", null, 100)
                    .pages();
            assertThat(pages).extracting(page -> page.header().key().pageId())
                    .containsExactlyInAnyOrder(id("start"), id("betrieb"), id("urlaub"));
            assertThat(pages).extracting(page -> page.header().metadata().title())
                    .containsExactlyInAnyOrder("Startseite", "Betrieb", "Urlaub");
            assertThat(new String(harvester.pages().page(harvester.caller(), "notizen", id("betrieb"), false)
                    .snapshot().payload().bytes(), StandardCharsets.UTF_8)).contains("Der Nachtlauf startet");

            // Suche über den Volltextindex des Cache (#180); die Quelle sucht nicht selbst.
            KeywordSearch.Results found = harvester.keywordSearch().search(harvester.caller(),
                    KeywordSearch.Query.of("Nachtlauf"), null, 10);
            assertThat(found.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly(id("betrieb"));
            assertThat(found.sources()).containsExactly(new KeywordSearch.SourceState("notizen",
                    KeywordSearch.SourceState.Status.INDEXED, null));

            // Export: alle Seiten, Markdown aus dem Lesetext statt eines Hinweises auf fehlendes Storage-Format.
            JobStatus export = harvester.await(harvester.export("notizen", "notizen", List.of("markdown")).id(),
                    Duration.ofSeconds(30));
            assertThat(export.state()).isEqualTo(JobState.COMPLETED);
            assertThat(export.progress().written()).isEqualTo(3);
            ExportArtifact artifact = harvester.exportArtifact(export.id()).orElseThrow();
            assertThat(artifact.complete()).isTrue();
            ExportArtifact.File markdown = artifact.files().stream().filter(file -> file.name().endsWith(".md"))
                    .findFirst().orElseThrow();
            try (InputStream in = harvester.exports().artifactFile(export.id(), Harvester.OWNER, markdown.name())
                    .orElseThrow()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(text).contains("Der Nachtlauf startet um zwei Uhr.").contains("Pumpe prüfen")
                        .doesNotContain("keinen Inhalt im Storage-Format");
            }
        }
    }

    @Test
    void pagesTheCallerMayNotReadStayInvisible() throws Exception {
        String hidden = id("urlaub");
        try (Harvester harvester = harvester(Harvester.builder().accessPolicy((context, scope, kind) ->
                hidden.equals(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW))) {
            harvester.await(harvester.sync("notizen", "notizen").id(), Duration.ofSeconds(30));

            assertThat(harvester.pages().pages(harvester.caller(), "notizen", "notizen", null, 100).pages())
                    .extracting(page -> page.header().key().pageId()).doesNotContain(hidden).hasSize(2);
            assertThat(harvester.keywordSearch().search(harvester.caller(), KeywordSearch.Query.of("Urlaubsplanung"),
                    null, 10).hits()).isEmpty();
        }
    }

    @Test
    void capabilitiesFollowThePathAndABrokenConfigurationIsReportedByKey() {
        try (Harvester harvester = harvester(Harvester.builder())) {
            assertThat(harvester.sources().sources()).singleElement().satisfies(source -> assertThat(
                    source.capabilities()).contains(SourceCapability.PAGE_LISTING, SourceCapability.STORED_PAGE)
                    .doesNotContain(SourceCapability.LIVE_SEARCH));
        }
        SourceSettingsConfig withoutHost = new SourceSettingsConfig("notizen", "notes", "Notizen", null, null,
                Map.of());
        assertThatThrownBy(() -> Harvester.builder().sourceAdapter(new KnowledgeSourceProviderAdapter(
                new Provider(notes))).source(withoutHost).dataDirectory(data).build())
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems())
                        .containsExactly("sources[0] (id=notizen): options.host: fehlt"));
    }

    private Harvester harvester(Harvester.Builder builder) {
        return builder.sourceAdapter(new KnowledgeSourceProviderAdapter(new Provider(notes)))
                .source(new SourceSettingsConfig("notizen", "notes", "Notizen", null, null,
                        Map.of("host", "notes.intern")))
                .dataDirectory(data).build();
    }

    /** Seiten-ID einer Ressource der Discovery: ihre Ressourcen-ID als Base64url. */
    private String id(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(notes.idOf(key).value()
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Ein Quelltyp wie im Enterprise AI Client: Adresse als Pflichtfeld, Umfang ab der Startseite. */
    private static final class Provider implements KnowledgeSourceProvider {

        private static final KnowledgeSourceType TYPE = KnowledgeSourceType.builder("notes", "notes", "Notizen")
                .summaryKey("host")
                .field(SourceSettingField.text("host", "Adresse", "Rechner der Notizen").required())
                .build();

        private final InMemoryKnowledgeSource notes;

        Provider(InMemoryKnowledgeSource notes) {
            this.notes = notes;
        }

        @Override
        public KnowledgeSourceType type() {
            return TYPE;
        }

        @Override
        public List<String> validate(SourceSettings settings) {
            List<String> problems = new ArrayList<>();
            if (settings.get("host") == null || settings.get("host").isBlank()) {
                problems.add("Adresse: fehlt");
            }
            return problems;
        }

        @Override
        public SourceScope scope(SourceSettings settings) {
            return SourceScope.builder().startPoints("start").maxDepth(2).build();
        }

        @Override
        public KnowledgeSourcePort open(SourceAddress sourceId, SourceSettings settings) {
            if (!sourceId.equals(notes.sourceId())) {
                throw new IllegalArgumentException("andere Quelle");
            }
            return new WithoutSearch(notes);
        }
    }

    /** Die Fake-Quelle nur mit {@code discover} und {@code load}, ohne Suche und ohne Partitionen. */
    private record WithoutSearch(InMemoryKnowledgeSource notes) implements KnowledgeSourcePort {

        @Override
        public SourceAddress sourceId() {
            return notes.sourceId();
        }

        @Override
        public List<KnowledgeResource> discover(SourceScope scope) throws KnowledgeSourceException {
            return notes.discover(scope);
        }

        @Override
        public KnowledgeDocument load(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
            return notes.load(resourceId);
        }

        @Override
        public List<SourceLink> discoverLinks(KnowledgeResourceId resourceId) throws KnowledgeSourceException {
            return notes.discoverLinks(resourceId);
        }
    }
}
