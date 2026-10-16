package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Metadata of a single page without content ({@code ResourceMetadataSource}), against the fake. */
public class ConfluenceResourceMetadataTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.space("OPS", "Operations");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.page("101", "Guide", "DEV", "<p>Guide</p>").labels("howto");
        confluence.child("100", "101");
        confluence.homepage("DEV", "100");
        source = new ConfluenceKnowledgeSource(SOURCE, ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).build(), confluence, new RecordingSecretProvider());
    }

    private static KnowledgeResourceId page(String id) {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/" + id);
    }

    @Test
    public void deliversTheSameMetadataAsTheListingWithoutContent() throws Exception {
        KnowledgeResource single = source.resourceMetadata("101");
        KnowledgeResource listed = null;
        for (KnowledgeResource resource : source.resources("DEV", "", 10).resources()) {
            if (resource.id().equals(page("101"))) {
                listed = resource;
            }
        }

        assertEquals(page("101"), single.id());
        assertEquals("Guide", single.title());
        assertEquals("DEV", single.scope());
        assertEquals(page("100"), single.parentId().get());
        assertEquals(listed.revision(), single.revision());
        assertEquals(listed.metadata(), single.metadata());
        assertEquals("howto", single.metadata().get("confluence.labels").get());
        for (URI request : confluence.requests()) {
            assertFalse(request.toString(), request.toString().contains("body."));
        }
    }

    @Test
    public void includesCreationFromHistory() throws Exception {
        KnowledgeResource single = source.resourceMetadata("101");

        assertEquals("2026-09-01T07:00:00Z", single.metadata().get("confluence.createdAt").get());
        assertEquals("autorin", single.metadata().get("confluence.createdBy").get());
        String last = confluence.requests().get(confluence.requests().size() - 1).toString();
        assertTrue(last, last.contains("history"));
    }

    @Test
    public void findsAPageAfterItMovedToAnotherSpace() throws Exception {
        confluence.move("101", "OPS");

        KnowledgeResource moved = source.resourceMetadata("101");

        assertEquals(page("101"), moved.id());
        assertEquals("OPS", moved.scope());
        assertFalse(moved.parentId().isPresent());
    }

    @Test
    public void unknownPageIsNotFound() throws Exception {
        try {
            source.resourceMetadata("999");
            fail("expected NOT_FOUND");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    public void pageRestrictedForTheAccountIsNotFoundAndMissingFromTheListing() throws Exception {
        confluence.restrict("101");
        try {
            source.resourceMetadata("101");
            fail("expected NOT_FOUND");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
            assertFalse(e.getMessage(), e.getMessage().contains("Guide"));
        }
        for (KnowledgeResource resource : source.resources("DEV", "", 100).resources()) {
            assertFalse(resource.id().equals(page("101")));
        }
    }

    @Test
    public void forbiddenOutsideASinglePageStaysAccessDenied() throws Exception {
        confluence.failNext(401, 1);
        try {
            source.resourceMetadata("101");
            fail("expected ACCESS_DENIED");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
        }
    }

    @Test
    public void idOfAnotherFormIsUnsupported() throws Exception {
        try {
            source.resourceMetadata("att123");
            fail("expected UNSUPPORTED");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
    }

    @Test
    public void localIdIsTheContentIdOfPagesOfThisSource() {
        assertEquals("101", source.localId(page("101")));
        assertNull(source.localId(KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/attachment/att5")));
        assertNull(source.localId(KnowledgeResourceId.of("confluence", "other.example.org/wiki/page/101")));
        assertNull(source.localId(null));
    }
}
