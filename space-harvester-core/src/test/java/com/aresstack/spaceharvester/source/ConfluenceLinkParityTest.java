package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceConfig;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceKnowledgeSource;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.links.InMemoryLinkIndex;
import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Auflage des Architekten zu V1: EINE Wahrheit für ausgehende Verweise. Der Confluence-Anschluss liest sie live aus
 * {@code body.view}, das Verzeichnis aus dem gespeicherten {@code body.storage}; auf derselben Seite ergeben beide
 * dieselben Ziele.
 */
class ConfluenceLinkParityTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("test");

    private final FakeConfluence confluence = new FakeConfluence();
    private final HarvestSource source = new KnowledgeSourceHarvestSource("wiki", "confluence", "Wiki",
            new ConfluenceKnowledgeSource(SourceAddress.of("wiki"), ConfluenceConfig.builder(FakeConfluence.BASE)
                    .build(), confluence, null));

    @Test
    void theStoredPageYieldsTheSameTargetsAsTheConnector() throws InterruptedException {
        confluence.space("DEV", "Entwicklung");
        confluence.space("OPS", "Betrieb");
        confluence.page("101", "Start", "DEV", "<p>"
                + "<a href=\"/wiki/display/DEV/Zwei\" data-linked-resource-id=\"102\" "
                + "data-linked-resource-type=\"page\">Zwei</a> "
                + "<a href=\"https://confluence.example.org/wiki/pages/viewpage.action?pageId=103\">Drei</a> "
                + "<a href=\"/wiki/display/OPS/Betrieb+und+Wartung\" data-linked-resource-id=\"104\" "
                + "data-linked-resource-type=\"page\">Betrieb</a> "
                + "<a href=\"/wiki/download/attachments/101/a.txt\" data-linked-resource-type=\"attachment\">a</a> "
                + "<a href=\"#oben\">oben</a> "
                + "<a href=\"https://andere.example.org/wiki/pages/viewpage.action?pageId=105\">fremd</a>"
                + "</p>")
                .storage("<p>"
                        + "<ac:link><ri:page ri:content-title=\"Zwei\"/>"
                        + "<ac:plain-text-link-body><![CDATA[Zwei]]></ac:plain-text-link-body></ac:link> "
                        + "<a href=\"https://confluence.example.org/wiki/pages/viewpage.action?pageId=103\">Drei</a> "
                        + "<ac:link><ri:page ri:space-key=\"OPS\" ri:content-title=\"Betrieb und Wartung\"/>"
                        + "<ac:link-body>Betrieb</ac:link-body></ac:link> "
                        + "<ac:link><ri:attachment ri:filename=\"a.txt\"/></ac:link> "
                        + "<ac:link ac:anchor=\"oben\"><ac:plain-text-link-body><![CDATA[oben]]>"
                        + "</ac:plain-text-link-body></ac:link> "
                        + "<a href=\"https://andere.example.org/wiki/pages/viewpage.action?pageId=105\">fremd</a>"
                        + "</p>");
        confluence.page("102", "Zwei", "DEV", "<p>2</p>").storage("<p>2</p>");
        confluence.page("103", "Drei", "DEV", "<p>3</p>").storage("<p>3</p>");
        confluence.page("104", "Betrieb und Wartung", "OPS", "<p>4</p>").storage("<p>4</p>");
        confluence.page("105", "Fremd", "DEV", "<p>5</p>").storage("<p>5</p>");

        List<String> connector = source.references("101").outgoing().stream()
                .map(HarvestSource.PageNode::pageId).toList();

        Snapshots snapshots = new Snapshots();
        for (String pageId : List.of("101", "102", "103", "104", "105")) {
            snapshots.add(source.storedPage(pageId));
        }
        PageLinks links = new PageLinks(snapshots, new InMemoryLinkIndex(), List.of(new ConfluenceStorageLinks()),
                new AccessGuard(new AllowAll()));
        try {
            CountDownLatch reconciled = new CountDownLatch(1);
            links.subscribe(event -> reconciled.countDown());
            links.reconcileOnStart(List.of("wiki"));
            assertThat(reconciled.await(10, TimeUnit.SECONDS)).isTrue();
            List<String> local = links.links(CALLER, new PageKey("wiki", "101")).outgoing().stream()
                    .map(PageKey::pageId).toList();

            assertThat(connector).containsExactly("102", "103", "104");
            assertThat(local).isEqualTo(connector);
            assertThat(links.links(CALLER, new PageKey("wiki", "104")).incoming())
                    .containsExactly(new PageKey("wiki", "101"));
        } finally {
            links.close();
        }
    }

    private static final class AllowAll implements AccessPolicy {

        @Override
        public AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind) {
            return AccessDecision.ALLOW;
        }

        @Override
        public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
            return AccessDecision.ALLOW;
        }
    }

    private static final class Snapshots implements PageLinks.StoredPages {

        private final Map<String, PageSnapshot> snapshots = new TreeMap<>();

        void add(StoredPage page) {
            PageSnapshot snapshot = PageSnapshot.of("wiki", page.metadata(),
                    new NativePayload(page.payload(), page.mediaType(), page.nativeFormat()), page.retrievedAt());
            snapshots.put(snapshot.key().pageId(), snapshot);
        }

        @Override
        public Optional<PageHeader> header(PageKey key) {
            return Optional.ofNullable(snapshots.get(key.pageId())).map(PageSnapshot::header);
        }

        @Override
        public Optional<PageSnapshot> snapshot(PageKey key) {
            return Optional.ofNullable(snapshots.get(key.pageId()));
        }

        @Override
        public List<PageLinks.StoredPage> pages(String sourceId, String afterPageId, int limit) {
            List<PageLinks.StoredPage> listed = new ArrayList<>();
            for (PageSnapshot snapshot : snapshots.values()) {
                if (afterPageId == null || snapshot.key().pageId().compareTo(afterPageId) > 0) {
                    listed.add(new PageLinks.StoredPage(snapshot.header(), snapshot.payload().rawSha256()));
                }
            }
            return listed.subList(0, Math.min(limit, listed.size()));
        }

        @Override
        public long known(String sourceId) {
            return snapshots.size();
        }
    }
}
