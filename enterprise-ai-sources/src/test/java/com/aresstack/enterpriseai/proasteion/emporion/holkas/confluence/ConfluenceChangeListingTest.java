package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.PartitionResources;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Recently changed and trashed pages of a space ({@code ChangeListingSource}), against the fake. */
public class ConfluenceChangeListingTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>").version(3, "2026-10-01T08:00:00.000Z");
        confluence.page("101", "Guide", "DEV", "<p>Guide</p>").version(5, "2026-10-18T11:58:30.000Z");
        confluence.page("102", "Setup", "DEV", "<p>Setup</p>").version(2, "2026-10-18T11:30:00.000Z");
        confluence.page("103", "Old", "DEV", "<p>Old</p>").version(1, "2026-10-18T11:59:00.000Z");
        confluence.child("100", "101");
        confluence.space("OPS", "Operations");
        confluence.page("200", "Runbook", "OPS", "<p>Ops</p>").version(9, "2026-10-18T11:59:00.000Z");
        source = new ConfluenceKnowledgeSource(SOURCE, ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).build(), confluence, new RecordingSecretProvider());
        source.millis = new java.util.function.LongSupplier() {
            @Override
            public long getAsLong() {
                return 0L;
            }
        };
    }

    private static KnowledgeResourceId page(String id) {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/" + id);
    }

    private static List<KnowledgeResourceId> ids(PartitionResources batch) {
        List<KnowledgeResourceId> ids = new ArrayList<KnowledgeResourceId>();
        for (KnowledgeResource resource : batch.resources()) {
            ids.add(resource.id());
        }
        return ids;
    }

    @Test
    public void listsPagesOfTheSpaceChangedWithinTheWindowNewestFirstWithOneQuery() throws Exception {
        int before = confluence.requests().size();

        PartitionResources batch = source.changedResources("DEV", Duration.ofMinutes(5), "", 100);

        assertEquals(java.util.Arrays.asList(page("103"), page("101")), ids(batch));
        assertTrue(batch.complete());
        String cql = confluence.lastCql;
        assertEquals("space=\"DEV\" AND type=page AND lastmodified >= now(\"-5m\") ORDER BY lastmodified DESC",
                cql);
        // Space-Prüfung und genau eine CQL-Anfrage
        assertEquals(2, confluence.requests().size() - before);
    }

    @Test
    public void deliversRevisionAndParentButNoContent() throws Exception {
        KnowledgeResource guide = source.changedResources("DEV", Duration.ofMinutes(5), "", 100).resources().get(1);

        assertEquals("Guide", guide.title());
        assertEquals(page("100"), guide.parentId().get());
        assertEquals("5", guide.revision().version());
        for (URI request : confluence.requests()) {
            assertFalse(request.toString(), request.toString().contains("body."));
        }
    }

    @Test
    public void roundsTheWindowUpToWholeMinutes() throws Exception {
        source.changedResources("DEV", Duration.ofSeconds(61), "", 100);
        assertTrue(confluence.lastCql, confluence.lastCql.contains("now(\"-2m\")"));

        source.changedResources("DEV", Duration.ofMillis(1), "", 100);
        assertTrue(confluence.lastCql, confluence.lastCql.contains("now(\"-1m\")"));
    }

    @Test
    public void pagesThroughChangesAndKeepsTheWindowOfTheFirstBatch() throws Exception {
        PartitionResources first = source.changedResources("DEV", Duration.ofHours(1), "", 1);
        assertEquals(java.util.Arrays.asList(page("103")), ids(first));
        assertFalse(first.complete());

        List<KnowledgeResourceId> rest = new ArrayList<KnowledgeResourceId>();
        String cursor = first.nextCursor();
        while (!cursor.isEmpty()) {
            PartitionResources batch = source.changedResources("DEV", Duration.ofMinutes(1), cursor, 1);
            rest.addAll(ids(batch));
            cursor = batch.nextCursor();
        }

        assertEquals(java.util.Arrays.asList(page("101"), page("102")), rest);
        assertTrue(confluence.lastCql, confluence.lastCql.contains("now(\"-60m\")"));
    }

    @Test
    public void laterBatchesWidenTheWindowByTheTimeSpentPaging() throws Exception {
        final long[] now = {1_000_000L};
        source.millis = new java.util.function.LongSupplier() {
            @Override
            public long getAsLong() {
                return now[0];
            }
        };
        String cursor = source.changedResources("DEV", Duration.ofMinutes(31), "", 1).nextCursor();
        // Confluence's now() moved on by two and a half minutes; the window grows by three.
        now[0] += 150_000L;
        confluence.now = confluence.now.plusSeconds(150);

        PartitionResources second = source.changedResources("DEV", Duration.ofMinutes(1), cursor, 1);

        assertTrue(confluence.lastCql, confluence.lastCql.contains("now(\"-34m\")"));
        assertEquals(java.util.Arrays.asList(page("101")), ids(second));
    }

    @Test
    public void aPageChangedAgainWhilePagingDoesNotPushAnUnreadOneOut() throws Exception {
        String cursor = source.changedResources("DEV", Duration.ofHours(1), "", 1).nextCursor();
        // 102 changes again and moves to the front; 101 and 102 must still come.
        confluence.pages.get("102").version(3, "2026-10-18T11:59:59.000Z");

        List<KnowledgeResourceId> rest = new ArrayList<KnowledgeResourceId>();
        while (!cursor.isEmpty()) {
            PartitionResources batch = source.changedResources("DEV", Duration.ofMinutes(1), cursor, 1);
            rest.addAll(ids(batch));
            cursor = batch.nextCursor();
        }

        assertTrue(rest.toString(), rest.contains(page("101")));
    }

    @Test
    public void restrictedAndTrashedPagesAreNotReportedAsChanged() throws Exception {
        confluence.restrict("101");
        confluence.trash("103");

        assertTrue(source.changedResources("DEV", Duration.ofMinutes(5), "", 100).resources().isEmpty());
    }

    @Test
    public void listsTheTrashOfTheSpace() throws Exception {
        confluence.trash("102");
        confluence.trash("200");

        PartitionResources batch = source.removedResources("DEV", "", 100);

        assertEquals(java.util.Arrays.asList(page("102")), ids(batch));
        assertTrue(batch.complete());
        String last = confluence.requests().get(confluence.requests().size() - 1).toString();
        assertTrue(last, last.contains("status=trashed"));
        assertFalse(last, last.contains("body."));
    }

    @Test
    public void pagesThroughTheTrash() throws Exception {
        confluence.trash("100");
        confluence.trash("102");
        confluence.trash("103");

        List<KnowledgeResourceId> all = new ArrayList<KnowledgeResourceId>();
        String cursor = "";
        do {
            PartitionResources batch = source.removedResources("DEV", cursor, 2);
            all.addAll(ids(batch));
            cursor = batch.nextCursor();
        } while (!cursor.isEmpty());

        assertEquals(java.util.Arrays.asList(page("100"), page("102"), page("103")), all);
    }

    @Test
    public void unknownSpaceIsNotFound() throws Exception {
        try {
            source.changedResources("NOPE", Duration.ofMinutes(5), "", 10);
            fail("expected NOT_FOUND");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
        try {
            source.removedResources("NOPE", "", 10);
            fail("expected NOT_FOUND");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }
    }

    @Test
    public void cursorsOfAnotherSpaceOrListingAreUnsupported() throws Exception {
        confluence.page("201", "Runbook 2", "OPS", "<p>Ops</p>").version(1, "2026-10-18T11:59:30.000Z");
        String opsChanges = source.changedResources("OPS", Duration.ofMinutes(5), "", 1).nextCursor();
        assertFalse(opsChanges.isEmpty());
        confluence.trash("100");
        confluence.trash("102");
        String devTrash = source.removedResources("DEV", "", 1).nextCursor();
        assertFalse(devTrash.isEmpty());

        String partition = source.resources("DEV", "", 1).nextCursor();
        String dev = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("DEV".getBytes("UTF-8"));
        for (String cursor : new String[] {opsChanges, devTrash, partition, "2", "c1." + dev + ".5.0",
                "c1." + dev + ".5.0.1.2", "c1." + dev + ".-5.0.1", "c1.%%%.5.0.1"}) {
            try {
                source.changedResources("DEV", Duration.ofMinutes(5), cursor, 1);
                fail("expected UNSUPPORTED for changes with " + cursor);
            } catch (KnowledgeSourceException e) {
                assertEquals(Kind.UNSUPPORTED, e.kind());
            }
        }
        for (String cursor : new String[] {opsChanges, partition, "2", "t1." + dev, "t1." + dev + ".x",
                "t1.T1BT.1"}) {
            try {
                source.removedResources("DEV", cursor, 1);
                fail("expected UNSUPPORTED for the trash with " + cursor);
            } catch (KnowledgeSourceException e) {
                assertEquals(Kind.UNSUPPORTED, e.kind());
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void theWindowMustBePositive() throws Exception {
        source.changedResources("DEV", Duration.ZERO, "", 10);
    }
}
