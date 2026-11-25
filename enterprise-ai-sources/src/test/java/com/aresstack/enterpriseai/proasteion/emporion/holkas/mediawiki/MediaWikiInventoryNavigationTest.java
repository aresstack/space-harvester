package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaWikiInventoryNavigationTest {
    private final FakeMediaWikiTransport wiki = new FakeMediaWikiTransport();
    private final MediaWikiKnowledgeSource source = new MediaWikiKnowledgeSource(SourceAddress.of("wiki"),
            MediaWikiSiteConfig.builder("wiki", "https://wiki.example/w").build(), wiki,
            MediaWikiCredentialsProvider.anonymous());

    @Test
    public void singleBatchAndEmptyInventoryFinishNormally() throws Exception {
        assertTrue(source.resources(source.partitionKey(), "", 100).complete());
        wiki.page("Hauptseite", "<p>Start</p>", 1, 1);
        PartitionResources only = source.resources(source.partitionKey(), "", 100);
        assertTrue(only.complete());
        assertEquals(1, only.resources().size());
    }

    @Test
    public void emptyContentNamespacesDoNotCreateEmptyContinuationBatches() throws Exception {
        wiki.page("Hauptseite", "<p>Start</p>", 1, 1);
        wiki.namespaceTitles.put(0, java.util.Collections.<String>emptyList());
        wiki.namespaceTitles.put(100, Arrays.asList("Hauptseite"));
        wiki.namespaceTitles.put(102, java.util.Collections.<String>emptyList());
        PartitionResources only = source.resources(source.partitionKey(), "", 100);
        assertTrue(only.complete());
        assertEquals(1, only.resources().size());
        assertEquals("Hauptseite", only.resources().get(0).title());
    }

    @Test
    public void inventoryFindsUnlinkedPagesAcrossContinuationWithoutLoadingContents() throws Exception {
        wiki.page("Hauptseite", "<p>Start</p>", 1, 1, "B");
        wiki.page("B", "<p>Linked</p>", 2, 2);
        wiki.page("C", "<p>Orphan</p>", 3, 3);
        wiki.inventoryPageSize = 1;
        List<String> titles = new ArrayList<String>();
        String cursor = "";
        do {
            PartitionResources batch = source.resources(source.partitionKey(), cursor, 100);
            for (KnowledgeResource resource : batch.resources()) {
                titles.add(resource.title());
                assertFalse(resource.parentId().isPresent());
            }
            cursor = batch.nextCursor();
        } while (!cursor.isEmpty());
        assertEquals(Arrays.asList("B", "C", "Hauptseite"), titles);
        assertFalse(wiki.requests.stream().anyMatch(p -> "parse".equals(p.get("action"))));
        assertFalse(wiki.requests.stream().anyMatch(p -> "links".equals(p.get("prop"))));
        assertTrue(wiki.requests.stream().anyMatch(p -> "-||".equals(p.get("continue"))));
    }

    @Test
    public void renderedHtmlIsAvailableWithoutActiveContent() throws Exception {
        wiki.page("Hauptseite", "<h2>Abschnitt</h2><p>Text <b>fett</b></p>"
                + "<script>alert(1)</script><img src='https://other.example/pixel' alt='Bild'>"
                + "<a href='javascript:alert(1)'>Link</a><p onclick='alert(1)'>Klick</p>", 1, 1);
        String html = source.pageView(source.children(
                NavigationNode.root("space:wiki", "Wiki", "", NavigationNode.Children.YES), "")
                .nodes().get(0).resource()).html();
        assertTrue(html.contains("<h2>Abschnitt</h2>"));
        assertTrue(html.contains("<b>fett</b>"));
        assertTrue(html.contains("Bild"));
        assertFalse(html.contains("alert("));
        assertFalse(html.contains("<img"));
    }

    @Test
    public void navigationGroupsAreNotDocumentsAndMembersKeepTheirIdentity() throws Exception {
        wiki.page("Hauptseite", "<p>Start</p>", 1, 1);
        wiki.page("Unverlinkt", "<p>Article</p>", 2, 2);
        wiki.categories.put("A", Arrays.asList("Unverlinkt"));
        wiki.categories.put("B", Arrays.asList("Unverlinkt"));
        wiki.inventoryPageSize = 1;
        NavigationNode root = NavigationNode.root("space:wiki", "Wiki", "", NavigationNode.Children.YES);
        NavigationChildren tree = source.children(root, "");
        assertEquals(3, tree.nodes().size());
        NavigationNode all = tree.nodes().get(1);
        NavigationNode categories = tree.nodes().get(2);
        assertTrue(all.isFolder());
        assertTrue(categories.isFolder());
        NavigationChildren first = source.children(categories, "");
        assertFalse(first.complete());
        NavigationNode a = first.nodes().get(0);
        NavigationNode b = source.children(categories, first.nextCursor()).nodes().get(0);
        NavigationNode article = source.children(a, "").nodes().get(0);
        assertEquals(article.resource(), source.children(b, "").nodes().get(0).resource());
        assertEquals("Unverlinkt", source.resourceMetadata(source.localId(article.resource())).title());
        assertEquals("Unverlinkt", source.load(article.resource()).resource().title());
        assertEquals(all.resource(), source.position(article.resource()).ancestors().get(0).resource());
        try {
            source.children(all, first.nextCursor());
            fail("A category cursor must not be accepted for the article inventory");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.UNSUPPORTED, expected.kind());
        }
    }
}
