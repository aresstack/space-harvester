package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.testing.InMemoryKnowledgeSource;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class InMemoryKnowledgeSourceTest {

    private final InMemoryKnowledgeSource source = new InMemoryKnowledgeSource("mem")
            .add("root", "Root", "Wurzel", "x", "y")
            .addChild("root", "x", "X", "Kind x", "z")
            .add("y", "Y", "Kind y")
            .add("z", "Z", "Enkel z");

    @Test
    public void resourcesCarryIdSourceRevisionParentAndMetadata() throws Exception {
        KnowledgeResource x = source.load(source.idOf("x")).resource();
        assertEquals("memory:mem/x", x.id().value());
        assertEquals("mem", x.sourceId().value());
        assertEquals("X", x.title());
        assertEquals("1", x.revision().version());
        assertTrue(x.revision().modifiedAt().isPresent());
        assertEquals(source.idOf("root"), x.parentId().get());
        assertEquals("x", x.metadata().get("key").get());
    }

    @Test
    public void breadthFirstOrder() throws Exception {
        List<KnowledgeResource> resources =
                source.discover(SourceScope.builder().startPoint("root").maxDepth(2).build());
        List<String> titles = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            titles.add(resource.title());
        }
        assertEquals(Arrays.asList("Root", "X", "Y", "Z"), titles);
    }

    @Test
    public void childResourcesAreDiscoveredAndLinkedWithoutExplicitLinks() throws Exception {
        InMemoryKnowledgeSource space = new InMemoryKnowledgeSource("space")
                .add("home", "Home", "Startseite")
                .addChild("home", "child", "Kind", "Kindseite");

        List<KnowledgeResource> resources =
                space.discover(SourceScope.builder().startPoint("home").maxDepth(1).build());

        assertEquals(2, resources.size());
        assertEquals(space.idOf("child"), resources.get(1).id());
        assertEquals(space.idOf("child"), space.discoverLinks(space.idOf("home")).get(0).target());
    }

    @Test
    public void searchIsCaseInsensitiveWithUnicodeThatChangesLengthWhenLowercased() throws Exception {
        InMemoryKnowledgeSource unicode = new InMemoryKnowledgeSource("u").add("k", "Titel", "İİİİ Treffer hier");
        List<SourceSearchHit> hits = unicode.search(SourceQuery.of("treffer"));
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).snippet().contains("Treffer"));
    }

    @Test
    public void updateChangesRevisionAndContentHash() throws Exception {
        KnowledgeDocument before = source.load(source.idOf("y"));
        source.update("y", "Neuer Text");
        KnowledgeDocument after = source.load(source.idOf("y"));
        assertEquals("2", after.resource().revision().version());
        assertNotEquals(before.resource().revision(), after.resource().revision());
        assertNotEquals(before.contentHash(), after.contentHash());
    }

    @Test
    public void removeAndFailureInjection() throws Exception {
        source.remove("y");
        try {
            source.load(source.idOf("y"));
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
        source.failWith("x", Kind.UNAVAILABLE);
        try {
            source.load(source.idOf("x"));
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNAVAILABLE, e.kind());
        }
        source.clearFailures();
        assertEquals("Kind x", source.load(source.idOf("x")).text());
        assertTrue(source.calls().contains("load:x"));
    }
}
