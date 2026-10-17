package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.enterpriseai.source.api.SourcePartition;
import com.aresstack.enterpriseai.source.api.SourceScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Der übernommene Confluence-Adapter arbeitet headless im Space Harvester: über echtes HTTP gegen eine
 * Fake-Confluence, ohne Desktop-Komponenten.
 */
class ConfluenceSourcesTest {

    private FakeConfluence confluence;
    private FakeConfluenceServer server;
    private KnowledgeSourcePort source;

    @BeforeEach
    void startConfluence() throws Exception {
        confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        confluence.space("OPS", "Betrieb");
        confluence.page("100", "Start", "DEV", "<p>Willkommen im <b>DEV</b>-Space.</p>");
        confluence.page("101", "Kapitel 1", "DEV", "<p>Text eins.</p>");
        confluence.child("100", "101");
        confluence.homepage("DEV", "100");
        server = new FakeConfluenceServer(confluence);

        KnowledgeSourceProvider provider = ConfluenceSources.anonymousProvider("space-harvester-test");
        SourceSettings settings = SourceSettings.of(Map.of("baseUrl", server.baseUrl().toString()));
        assertThat(provider.validate(settings)).isEmpty();
        source = provider.open(SourceAddress.of("confluence-test"), settings);
    }

    @AfterEach
    void stopConfluence() {
        server.close();
    }

    @Test
    void listsSpacesAsPartitions() throws Exception {
        List<SourcePartition> spaces = source.partitions();

        assertThat(spaces).extracting(SourcePartition::key).contains("DEV", "OPS");
        assertThat(spaces).extracting(SourcePartition::title).contains("Entwicklung", "Betrieb");
    }

    @Test
    void discoversAndLoadsPagesOfASpace() throws Exception {
        List<KnowledgeResource> pages = source.discover(
                SourceScope.builder().startPoint("space:DEV").maxDepth(5).build());

        assertThat(pages).extracting(KnowledgeResource::title).contains("Start", "Kapitel 1");

        KnowledgeResource start = pages.stream().filter(page -> page.title().equals("Start")).findFirst().orElseThrow();
        KnowledgeDocument document = source.load(start.id());
        assertThat(document.text()).contains("Willkommen");
    }
}
