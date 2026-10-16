package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.CrossReferences;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.SourceLink;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Issue #97: space tree and cross references of the Confluence source against the fake. */
public class ConfluenceNavigationTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");
    private static final int PAGE_SIZE = 7;
    private static final int MANY_CHILDREN = 60;

    private final FakeConfluence confluence = new FakeConfluence();
    private final RecordingSecretProvider secrets = new RecordingSecretProvider();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.page("101", "Guide", "DEV", "<p>Guide</p>");
        confluence.page("102", "Setup", "DEV", "<p>Setup</p>");
        confluence.page("103", "Details", "DEV",
                "<p><a href=\"/wiki/pages/viewpage.action?pageId=102\">setup</a>"
                        + " <a href=\"/wiki/display/DEV/Start\">home by title</a>"
                        + " <a href=\"#section\">anchor</a>"
                        + " <a href=\"https://confluence.example.org/wiki/pages/viewpage.action?pageId=103\">self</a>"
                        + " <a data-linked-resource-id=\"200\" data-linked-resource-type=\"page\" href=\"/wiki/x\">ops</a>"
                        + " <a data-linked-resource-id=\"201\" data-linked-resource-type=\"page\" href=\"/wiki/y\">secret</a>"
                        + " <a data-linked-resource-id=\"999\" data-linked-resource-type=\"page\" href=\"/wiki/z\">gone</a>"
                        + " <a data-linked-resource-id=\"900\" data-linked-resource-type=\"attachment\" href=\"/wiki/d\">file</a>"
                        + " <a href=\"/wiki/pages/viewpage.action?pageId=102\">setup again</a>"
                        + " <a href=\"https://elsewhere.invalid/page\">extern</a></p>");
        confluence.page("104", "Second root", "DEV", "<p>Root two</p>");
        confluence.child("100", "101");
        confluence.child("100", "102");
        confluence.child("101", "103");
        confluence.homepage("DEV", "100");
        confluence.space("OPS", "Operations");
        confluence.page("200", "Runbook", "OPS", "<p>Ops</p>");
        confluence.page("201", "Restricted", "OPS", "<p>Hidden</p>");
        confluence.restrict("201");
        source = new ConfluenceKnowledgeSource(SOURCE, ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).pageSize(PAGE_SIZE).build(), confluence, secrets);
    }

    private static KnowledgeResourceId page(String id) {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/" + id);
    }

    @Test
    public void positionHasTheSpaceAsRootAndTheAncestorsTopDown() throws Exception {
        PagePosition position = source.position(page("103"));

        assertTrue(position.root().isRoot());
        assertEquals("space:DEV", position.root().key());
        assertEquals("Development", position.root().title());
        assertEquals("space:DEV", position.root().startPoint());
        assertEquals(2, position.ancestors().size());
        assertEquals(page("100"), position.ancestors().get(0).resource());
        assertEquals("Start", position.ancestors().get(0).title());
        assertEquals(page("101"), position.ancestors().get(1).resource());
        assertEquals(page("103"), position.page().resource());
        assertEquals(NavigationNode.Children.NO, position.page().children());
    }

    @Test
    public void anotherSpaceGivesAnotherRoot() throws Exception {
        PagePosition position = source.position(page("200"));

        assertEquals("space:OPS", position.root().key());
        assertTrue(position.ancestors().isEmpty());
    }

    @Test
    public void rootChildrenAreTheTopLevelPagesOfTheSpace() throws Exception {
        NavigationNode root = source.position(page("101")).root();

        NavigationChildren top = source.children(root, "");

        assertTrue(top.complete());
        assertEquals(2, top.nodes().size());
        assertEquals(page("100"), top.nodes().get(0).resource());
        assertEquals(NavigationNode.Children.YES, top.nodes().get(0).children());
        assertEquals(page("104"), top.nodes().get(1).resource());
        assertEquals(NavigationNode.Children.NO, top.nodes().get(1).children());
    }

    @Test
    public void manyChildrenArriveCompletelyInBatches() throws Exception {
        for (int i = 0; i < MANY_CHILDREN; i++) {
            confluence.page(String.valueOf(5000 + i), "Child " + i, "DEV", "<p/>");
            confluence.child("104", String.valueOf(5000 + i));
        }
        NavigationNode parent = NavigationNode.page(page("104"), "Second root", NavigationNode.Children.YES);

        List<NavigationNode> all = new ArrayList<NavigationNode>();
        String cursor = "";
        int batches = 0;
        do {
            NavigationChildren batch = source.children(parent, cursor);
            assertTrue(batch.nodes().size() <= PAGE_SIZE);
            all.addAll(batch.nodes());
            cursor = batch.nextCursor();
            batches++;
        } while (!cursor.isEmpty());

        assertEquals(MANY_CHILDREN, all.size());
        assertEquals((MANY_CHILDREN + PAGE_SIZE - 1) / PAGE_SIZE, batches);
        assertEquals("Child 0", all.get(0).title());
        assertEquals("Child " + (MANY_CHILDREN - 1), all.get(MANY_CHILDREN - 1).title());
    }

    @Test
    public void aCursorOfAnotherParentIsRefused() throws Exception {
        for (int i = 0; i < MANY_CHILDREN; i++) {
            confluence.page(String.valueOf(5000 + i), "Child " + i, "DEV", "<p/>");
            confluence.child("104", String.valueOf(5000 + i));
        }
        String foreignCursor = source.children(NavigationNode.page(page("104"), "", null), "").nextCursor();
        try {
            source.children(NavigationNode.page(page("100"), "", null), foreignCursor);
            fail("cursor of another parent accepted");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
    }

    @Test
    public void restrictedChildrenAreLeftOut() throws Exception {
        confluence.page("105", "Hidden child", "DEV", "<p/>");
        confluence.child("100", "105");
        confluence.restrict("105");

        NavigationChildren children = source.children(NavigationNode.page(page("100"), "Start", null), "");

        assertEquals(2, children.nodes().size());
        for (NavigationNode node : children.nodes()) {
            assertFalse(node.resource().equals(page("105")));
        }
    }

    @Test
    public void restrictedOrMissingPagesHaveNoPosition() throws Exception {
        try {
            source.position(page("201"));
            fail("restricted page located");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
        }
        try {
            source.position(page("424242"));
            fail("missing page located");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    public void crossReferencesAreTheVisiblePageLinksOnceEachWithRealTitles() throws Exception {
        CrossReferences references = source.crossReferences(page("103"));

        List<KnowledgeResourceId> targets = new ArrayList<KnowledgeResourceId>();
        List<String> titles = new ArrayList<String>();
        for (SourceLink link : references.outgoing()) {
            targets.add(link.target());
            titles.add(link.label());
        }
        assertEquals(java.util.Arrays.asList(page("102"), page("100"), page("200")), targets);
        assertEquals(java.util.Arrays.asList("Setup", "Start", "Runbook"), titles);
        assertFalse(references.incomingSupported());
        assertTrue(references.incoming().isEmpty());
    }

    @Test
    public void aPageWithoutLinksHasNoCrossReferences() throws Exception {
        CrossReferences references = source.crossReferences(page("100"));

        assertTrue(references.outgoing().isEmpty());
        assertNull(confluence.lastCql == null ? null : (confluence.lastCql.startsWith("type=page AND id in")
                ? "searched without targets" : null));
    }
}
