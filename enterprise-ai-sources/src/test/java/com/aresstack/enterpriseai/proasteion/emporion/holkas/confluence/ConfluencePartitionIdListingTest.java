package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.PartitionIds;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Only the content IDs of all pages of a space ({@code PartitionIdListingSource}), against the fake. */
public class ConfluencePartitionIdListingTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.page("101", "Guide", "DEV", "<p>Guide</p>");
        confluence.page("102", "Setup", "DEV", "<p>Setup</p>");
        confluence.page("104", "Second root", "DEV", "<p>Root two</p>");
        confluence.child("100", "101");
        confluence.child("101", "102");
        confluence.homepage("DEV", "100");
        confluence.space("OPS", "Operations");
        confluence.page("200", "Runbook", "OPS", "<p>Ops</p>");
        source = new ConfluenceKnowledgeSource(SOURCE, ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).build(), confluence, new RecordingSecretProvider());
    }

    @Test
    public void listsTheContentIdsOfAllPagesOfTheSpaceWithoutExpand() throws Exception {
        PartitionIds batch = source.resourceIds("DEV", "", 200);

        assertEquals(new HashSet<String>(Arrays.asList("100", "101", "102", "104")),
                new HashSet<String>(batch.localIds()));
        assertTrue(batch.complete());
        boolean listed = false;
        for (java.net.URI request : confluence.requests()) {
            String text = request.toString();
            if (text.contains("/rest/api/content?") && text.contains("spaceKey=DEV")) {
                listed = true;
                assertFalse(text, text.contains("expand"));
                assertTrue(text, text.contains("limit=200"));
            }
        }
        assertTrue(listed);
    }

    @Test
    public void capsTheBatchAt200() throws Exception {
        source.resourceIds("DEV", "", 1000);

        for (java.net.URI request : confluence.requests()) {
            String text = request.toString();
            if (text.contains("/rest/api/content?") && text.contains("spaceKey=DEV")) {
                assertTrue(text, text.contains("limit=200"));
            }
        }
    }

    @Test
    public void pagesThroughTheSpaceWithTheCursor() throws Exception {
        List<String> ids = new ArrayList<String>();
        String cursor = "";
        int batches = 0;
        do {
            PartitionIds batch = source.resourceIds("DEV", cursor, 3);
            assertTrue(batch.localIds().size() <= 3);
            ids.addAll(batch.localIds());
            cursor = batch.nextCursor();
            batches++;
        } while (!cursor.isEmpty());

        assertEquals(2, batches);
        assertEquals(4, ids.size());
        assertEquals(4, new HashSet<String>(ids).size());
    }

    @Test
    public void unknownSpaceIsNotFound() throws Exception {
        try {
            source.resourceIds("NOPE", "", 10);
            fail("expected NOT_FOUND");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    public void cursorOfAnotherSpaceIsUnsupported() throws Exception {
        String devCursor = source.resourceIds("DEV", "", 2).nextCursor();
        assertFalse(devCursor.isEmpty());

        try {
            source.resourceIds("OPS", devCursor, 2);
            fail("expected UNSUPPORTED");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
    }
}
