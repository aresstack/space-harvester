package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** All pages of a space as metadata, batch by batch ({@code PartitionListingSource}), against the fake. */
public class ConfluencePartitionListingTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.page("101", "Guide", "DEV", "<p>Guide</p>");
        confluence.page("102", "Setup", "DEV", "<p>Setup</p>");
        // Top-level page outside the tree of the homepage: discover("space:DEV") does not reach it.
        confluence.page("104", "Second root", "DEV", "<p>Root two</p>");
        confluence.child("100", "101");
        confluence.child("101", "102");
        confluence.homepage("DEV", "100");
        confluence.space("OPS", "Operations");
        confluence.page("200", "Runbook", "OPS", "<p>Ops</p>");
        source = new ConfluenceKnowledgeSource(SOURCE, ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).build(), confluence, new RecordingSecretProvider());
    }

    private static KnowledgeResourceId page(String id) {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/" + id);
    }

    @Test
    public void listsAllPagesOfTheSpaceIncludingTopLevelPagesOutsideTheHomepageTree() throws Exception {
        PartitionResources batch = source.resources("DEV", "", 100);

        List<KnowledgeResourceId> ids = new ArrayList<KnowledgeResourceId>();
        for (KnowledgeResource resource : batch.resources()) {
            ids.add(resource.id());
        }
        assertEquals(4, ids.size());
        assertTrue(ids.contains(page("104")));
        assertFalse(ids.contains(page("200")));
        assertTrue(batch.complete());
    }

    @Test
    public void deliversMetadataWithRevisionAndParentButWithoutLoadingContent() throws Exception {
        KnowledgeResource setup = null;
        for (KnowledgeResource resource : source.resources("DEV", "", 100).resources()) {
            if (resource.id().equals(page("102"))) {
                setup = resource;
            }
        }

        assertEquals("Setup", setup.title());
        assertEquals("DEV", setup.scope());
        assertEquals(page("101"), setup.parentId().get());
        assertTrue(setup.revision().version().length() > 0);
        for (java.net.URI request : confluence.requests()) {
            assertFalse(request.toString(), request.toString().contains("body."));
        }
    }

    @Test
    public void pagesThroughTheSpaceWithTheCursor() throws Exception {
        List<KnowledgeResourceId> ids = new ArrayList<KnowledgeResourceId>();
        String cursor = "";
        int batches = 0;
        do {
            PartitionResources batch = source.resources("DEV", cursor, 3);
            assertTrue(batch.resources().size() <= 3);
            for (KnowledgeResource resource : batch.resources()) {
                ids.add(resource.id());
            }
            cursor = batch.nextCursor();
            batches++;
        } while (!cursor.isEmpty());

        assertEquals(2, batches);
        assertEquals(4, ids.size());
        assertEquals(4, new java.util.HashSet<KnowledgeResourceId>(ids).size());
    }

    @Test
    public void unknownSpaceIsNotFound() throws Exception {
        try {
            source.resources("NOPE", "", 10);
            fail("expected NOT_FOUND");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    public void foreignCursorIsUnsupported() throws Exception {
        try {
            source.resources("DEV", "/rest/api/content/100", 10);
            fail("expected UNSUPPORTED");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
    }

    @Test
    public void cursorOfAnotherSpaceIsUnsupported() throws Exception {
        String devCursor = source.resources("DEV", "", 2).nextCursor();
        assertFalse(devCursor.isEmpty());

        try {
            source.resources("OPS", devCursor, 2);
            fail("expected UNSUPPORTED");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
    }

    @Test
    public void bareNumericPositionIsNotACursor() throws Exception {
        try {
            source.resources("DEV", "2", 2);
            fail("expected UNSUPPORTED");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
    }

    @Test
    public void hugeLimitIsCappedPerRequestButNotOverall() throws Exception {
        source.resources("DEV", "", Integer.MAX_VALUE);

        String last = confluence.requests().get(confluence.requests().size() - 1).toString();
        assertTrue(last, last.endsWith("&limit=" + ConfluenceKnowledgeSource.MAX_PARTITION_BATCH));
    }

    @Test
    public void emptySpaceIsCompleteRightAway() throws Exception {
        confluence.space("EMPTY", "Nothing yet");

        PartitionResources batch = source.resources("EMPTY", "", 10);

        assertTrue(batch.resources().isEmpty());
        assertTrue(batch.complete());
    }

    @Test
    public void nextLinkWithoutFurtherPositionIsAnErrorNotTheEnd() throws Exception {
        confluence.nextLinkOverride = "/rest/api/content?type=page&spaceKey=DEV&limit=2";

        try {
            source.resources("DEV", "", 2);
            fail("expected INVALID_RESPONSE");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
        }
    }

    @Test
    public void failureWhileLoadingALaterBatchIsReported() throws Exception {
        String cursor = source.resources("DEV", "", 2).nextCursor();
        confluence.forcedFailure = new java.io.IOException("connection reset");

        try {
            source.resources("DEV", cursor, 2);
            fail("expected an exception");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNAVAILABLE, e.kind());
        }
    }
}
