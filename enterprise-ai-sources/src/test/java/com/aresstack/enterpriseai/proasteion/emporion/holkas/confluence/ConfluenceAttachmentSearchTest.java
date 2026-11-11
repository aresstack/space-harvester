package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.AttachmentListSource;
import com.aresstack.enterpriseai.source.api.AttachmentSearchSource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.StoredAttachmentList;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Attachment lists of many pages from the CQL search ({@code AttachmentSearchSource}) instead of one call per page. */
public class ConfluenceAttachmentSearchTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");
    private static final String SEARCH = "/rest/api/content/search";

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.space("OPS", "Operations");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.page("101", "Guide", "DEV", "<p>Guide</p>");
        confluence.page("102", "Notes", "DEV", "<p>Notes</p>");
        confluence.page("200", "Runbook", "OPS", "<p>Ops</p>");
        confluence.child("100", "101");
        confluence.child("100", "102");
        confluence.homepage("DEV", "100");
        confluence.attachment("att1", "101", "bild.png", "image/png", "PNG");
        confluence.attachment("att2", "101", "nicht-eingebettet.pdf", "application/pdf", "PDF-Inhalt");
        confluence.attachment("att3", "101", "diagramm.drawio", "application/vnd.jgraph.mxfile", "<mxfile/>");
        confluence.attachment("att4", "102", "notiz.txt", "text/plain", "Notiz");
        confluence.attachment("att5", "200", "betrieb.txt", "text/plain", "Betrieb");
        source = source(2, Integer.MAX_VALUE);
    }

    private ConfluenceKnowledgeSource source(int pageSize, int maxResponseBytes) {
        return new ConfluenceKnowledgeSource(SOURCE, ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).pageSize(pageSize).maxResponseBytes(maxResponseBytes)
                .build(), confluence, new RecordingSecretProvider());
    }

    @Test
    public void sourceOffersTheCapability() {
        assertTrue(source instanceof AttachmentSearchSource);
    }

    @Test
    public void withASpaceOnlyPagesStillInThatSpaceKeepTheirAttachments() throws Exception {
        confluence.move("102", "OPS");

        Map<String, StoredAttachmentList> lists = source.storedAttachmentsOf(Arrays.asList("101", "102"), "DEV");

        assertEquals("type=attachment AND container in (101,102) AND space=\"DEV\" ORDER BY created",
                confluence.lastCql);
        assertEquals(Arrays.asList("att1", "att2", "att3"), localIds(lists.get("101").attachments()));
        // Verschoben: leer wie eine Seite ohne Anhänge, ohne eigenen Abruf.
        assertTrue(lists.get("102").attachments().isEmpty());
        assertEquals(0, confluence.requestCount("/child/attachment"));
    }

    @Test
    public void aSpaceKeyWithoutAValidFormIsRejectedBeforeAnyRequest() {
        try {
            source.storedAttachmentsOf(Collections.singletonList("101"), "DEV\" OR type=page");
            fail("expected UNSUPPORTED");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
        assertEquals(0, confluence.requestCount(SEARCH));
    }

    @Test
    public void listsTheSameAttachmentsAsThePerPageListWithFewerCalls() throws Exception {
        Map<String, StoredAttachmentList> lists = source.storedAttachmentsOf(Arrays.asList("101", "100", "102", "101"));

        assertEquals(Arrays.asList("101", "100", "102"), new ArrayList<String>(lists.keySet()));
        assertEquals(0, confluence.requestCount("/child/attachment"));
        assertEquals(0, confluence.requestCount("/download/"));
        assertEquals(2, confluence.requestCount(SEARCH)); // 4 Anhänge, je Antwort 2
        assertEquals("type=attachment AND container in (101,100,102) ORDER BY created", confluence.lastCql);
        for (URI request : confluence.requests()) {
            assertTrue(request.toString(), request.getRawQuery().contains("expand=version,container"));
        }
        for (String id : lists.keySet()) {
            StoredAttachmentList searched = lists.get(id);
            StoredAttachmentList listed = source.storedAttachments(id);
            assertEquals(id, ids(listed.attachments()), ids(searched.attachments()));
            for (int i = 0; i < listed.attachments().size(); i++) {
                KnowledgeResource expected = listed.attachments().get(i);
                KnowledgeResource actual = searched.attachments().get(i);
                assertEquals(expected.title(), actual.title());
                assertEquals(expected.contentType(), actual.contentType());
                assertEquals(expected.parentId(), actual.parentId());
                assertEquals(expected.revision(), actual.revision());
                assertEquals(expected.metadata().asMap(), actual.metadata().asMap());
            }
            assertTrue(searched.complete());
            assertEquals("application/json", searched.mediaType());
            assertEquals("confluence-rest-content-search+json;expand=container,version", searched.nativeFormat());
            assertFalse(searched.nativeFormat().equals(listed.nativeFormat()));
            assertFalse(searched.toString().contains("bild.png"));
        }
        assertTrue(lists.get("100").attachments().isEmpty());
    }

    @Test
    public void eachListCarriesTheSearchResponsesWithItsEntriesByteForByte() throws Exception {
        Map<String, StoredAttachmentList> lists = source.storedAttachmentsOf(Arrays.asList("101", "100", "102"));

        List<URI> requests = confluence.requests();
        byte[] first = body(requests.get(0));
        byte[] second = body(requests.get(1));
        // Antwort 1: att1, att2 (101); Antwort 2: att3 (101), att4 (102).
        assertPayloads(lists.get("101"), first, second);
        assertPayloads(lists.get("102"), second);
        // Ohne Eintrag: alle Antworten des Stapels, die zusammen zeigen, dass nichts gefunden wurde.
        assertPayloads(lists.get("100"), first, second);
        assertTrue(lists.get("101").retrievedAt().equals(lists.get("102").retrievedAt()));
    }

    @Test
    public void searchesInChunksOfFiftyPages() throws Exception {
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 120; i++) {
            String id = String.valueOf(1000 + i);
            confluence.page(id, "Seite " + i, "DEV", "<p/>");
            ids.add(id);
        }
        confluence.attachment("att9", "1119", "spaet.txt", "text/plain", "x");

        Map<String, StoredAttachmentList> lists = source(50, Integer.MAX_VALUE).storedAttachmentsOf(ids);

        assertEquals(ids, new ArrayList<String>(lists.keySet()));
        assertEquals(3, confluence.requestCount(SEARCH));
        for (URI request : confluence.requests()) {
            String cql = URLDecoder.decode(request.getRawQuery().replaceAll(".*cql=([^&]*).*", "$1"), "UTF-8");
            String inner = cql.substring(cql.indexOf('(') + 1, cql.indexOf(')'));
            assertTrue(cql, inner.split(",").length <= ConfluenceRestClient.ATTACHMENT_SEARCH_CHUNK);
        }
        assertEquals(Collections.singletonList("att9"), localIds(lists.get("1119").attachments()));
        assertTrue(lists.get("1000").attachments().isEmpty());
    }

    @Test
    public void emptyRequestCostsNoCallAndForeignIdsFailBeforeAnyCall() throws Exception {
        assertTrue(source.storedAttachmentsOf(Collections.<String>emptyList()).isEmpty());
        try {
            source.storedAttachmentsOf(Arrays.asList("101", "att1"));
            fail("UNSUPPORTED erwartet");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.UNSUPPORTED, e.kind());
        }
        assertEquals(0, confluence.requests().size());
    }

    @Test
    public void restrictedAndUnknownPagesLookLikePagesWithoutAttachments() throws Exception {
        confluence.restrict("102");

        Map<String, StoredAttachmentList> lists = source.storedAttachmentsOf(Arrays.asList("101", "102", "999"));

        assertTrue(lists.get("102").attachments().isEmpty());
        assertTrue(lists.get("102").complete());
        assertTrue(lists.get("999").attachments().isEmpty());
        assertEquals(lists.get("999").payloads().size(), lists.get("102").payloads().size());
        for (int i = 0; i < lists.get("999").payloads().size(); i++) {
            assertArrayEquals(lists.get("999").payloads().get(i), lists.get("102").payloads().get(i));
            assertFalse(new String(lists.get("102").payloads().get(i), "UTF-8").contains("att4"));
        }
        Map<String, StoredAttachmentList> space = source.storedAttachmentsInPartition("DEV");
        assertEquals(Collections.singletonList("101"), new ArrayList<String>(space.keySet()));
    }

    @Test
    public void entryWithoutUsableIdIsReportedByPositionWithinTheListsOwnResponses() throws Exception {
        confluence.attachment("kaputt", "102", "ohne-id.txt", "text/plain", "x");

        Map<String, StoredAttachmentList> lists = source.storedAttachmentsOf(Arrays.asList("101", "102"));

        // Antwort 3 der Suche ist die zweite Antwort der Liste von 102.
        StoredAttachmentList notes = lists.get("102");
        assertEquals(Collections.singletonList("att4"), localIds(notes.attachments()));
        assertEquals(2, notes.payloads().size());
        assertFalse(notes.complete());
        assertEquals(1, notes.warnings().size());
        assertTrue(notes.warnings().get(0), notes.warnings().get(0).startsWith("Listenseite 2, Eintrag 1: "));
        assertFalse(notes.warnings().get(0).contains("ohne-id.txt"));
        assertFalse(notes.warnings().get(0).contains("kaputt"));
        assertTrue(lists.get("101").complete());
    }

    @Test
    public void entryWithoutContainerMarksEveryListOfItsChunkButFailsAWholeSpace() throws Exception {
        confluence.attachment("att6", "102", "geheim.txt", "text/plain", "x").containerType = null;

        Map<String, StoredAttachmentList> lists = source.storedAttachmentsOf(Arrays.asList("101", "102", "100"));

        for (StoredAttachmentList list : lists.values()) {
            assertFalse(list.complete());
            assertEquals(1, list.warnings().size());
            assertFalse(list.warnings().get(0).contains("geheim.txt"));
        }
        assertEquals(3, lists.get("101").attachments().size());
        try {
            source.storedAttachmentsInPartition("DEV");
            fail("INVALID_RESPONSE erwartet");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
            assertFalse(e.getMessage().contains("geheim.txt"));
        }
    }

    @Test
    public void partitionGroupsTheAttachmentsOfASpaceByPage() throws Exception {
        confluence.page("103", "Blog", "DEV", "<p/>");
        confluence.attachment("att7", "103", "blog.png", "image/png", "PNG").containerType = "blogpost";

        Map<String, StoredAttachmentList> lists = source.storedAttachmentsInPartition("DEV");

        assertEquals(Arrays.asList("101", "102"), new ArrayList<String>(lists.keySet()));
        assertEquals(Arrays.asList("att1", "att2", "att3"), localIds(lists.get("101").attachments()));
        assertEquals(Collections.singletonList("att4"), localIds(lists.get("102").attachments()));
        assertEquals("space=\"DEV\" AND type=attachment ORDER BY created", confluence.lastCql);
        assertEquals(0, confluence.requestCount("/child/attachment"));
        assertEquals(3, confluence.requestCount(SEARCH)); // 5 Einträge, je Antwort 2
        assertEquals("confluence:confluence.example.org/wiki/page/102",
                lists.get("102").attachments().get(0).parentId().get().value());
        List<URI> searches = new ArrayList<URI>();
        for (URI request : confluence.requests()) {
            if (request.getRawPath().endsWith(SEARCH)) {
                searches.add(request);
            }
        }
        assertPayloads(lists.get("101"), body(searches.get(0)), body(searches.get(1)));
        assertPayloads(lists.get("102"), body(searches.get(1)));
        try {
            lists.put("x", null);
            fail("unveränderliche Map erwartet");
        } catch (UnsupportedOperationException expected) {
            // erwartet
        }
    }

    @Test
    public void unknownOrInvalidSpaceIsNotFound() throws Exception {
        assertPartitionKind(Kind.NOT_FOUND, "NOPE");
        assertPartitionKind(Kind.NOT_FOUND, "DEV\" OR type=page");
        assertEquals(0, confluence.requestCount(SEARCH));
    }

    @Test
    public void repeatingPaginationFailsInsteadOfLooping() throws Exception {
        // Der Folgelink zeigt wieder auf die erste Antwort.
        confluence.nextLinkOverride = SEARCH + "?cql="
                + java.net.URLEncoder.encode(ConfluenceRestClient.attachmentsOfPagesCql(Arrays.asList("101")), "UTF-8")
                .replace("+", "%20") + "&expand=" + ConfluenceRestClient.ATTACHMENT_LIST_EXPAND + "&start=0&limit=2";
        try {
            source.storedAttachmentsOf(Arrays.asList("101"));
            fail("INVALID_RESPONSE erwartet");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
        }
    }

    @Test
    public void tooLargeResponseIsNotTruncated() throws Exception {
        try {
            source(2, 200).storedAttachmentsOf(Arrays.asList("101"));
            fail("ResponseTooLargeException erwartet");
        } catch (ResponseTooLargeException e) {
            assertEquals(200, e.maxBytes());
        }
    }

    @Test
    public void moreThanMaxAttachmentsOfOnePageFails() throws Exception {
        for (int i = 0; i < AttachmentListSource.MAX_ATTACHMENTS; i++) {
            confluence.attachment("att" + (100000 + i), "101", "f" + i + ".txt", "text/plain", "x");
        }
        try {
            source(500, Integer.MAX_VALUE).storedAttachmentsOf(Arrays.asList("101"));
            fail("INVALID_RESPONSE erwartet");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
            assertTrue(e.getMessage(), e.getMessage().contains(String.valueOf(AttachmentListSource.MAX_ATTACHMENTS)));
        }
    }

    @Test
    public void moreThanTheTotalCapFailsInsteadOfBeingCut() throws Exception {
        int perPage = AttachmentListSource.MAX_ATTACHMENTS - 10;
        int pages = AttachmentSearchSource.MAX_TOTAL_ATTACHMENTS / perPage + 1;
        for (int p = 0; p < pages; p++) {
            String pageId = String.valueOf(5000 + p);
            confluence.page(pageId, "Voll " + p, "DEV", "<p/>");
            for (int i = 0; i < perPage; i++) {
                confluence.attachment("att" + (1000000 + p * perPage + i), pageId, "f.txt", "text/plain", "x");
            }
        }
        try {
            source(500, Integer.MAX_VALUE).storedAttachmentsInPartition("DEV");
            fail("INVALID_RESPONSE erwartet");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
            assertTrue(e.getMessage(),
                    e.getMessage().contains(String.valueOf(AttachmentSearchSource.MAX_TOTAL_ATTACHMENTS)));
        }
    }

    @Test
    public void accessDeniedIsNeverAnEmptyCatalog() throws Exception {
        confluence.failNext(401, 1);
        try {
            source.storedAttachmentsOf(Arrays.asList("101"));
            fail("ACCESS_DENIED erwartet");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
        }
    }

    private void assertPartitionKind(Kind kind, String key) {
        try {
            source.storedAttachmentsInPartition(key);
            fail("Fehler erwartet für " + key);
        } catch (KnowledgeSourceException e) {
            assertEquals(key, kind, e.kind());
        }
    }

    private byte[] body(URI request) throws Exception {
        return confluence.get(request, Collections.<String, String>emptyMap(), Integer.MAX_VALUE).body();
    }

    private static void assertPayloads(StoredAttachmentList list, byte[]... expected) {
        assertEquals(expected.length, list.payloads().size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals("Antwort " + (i + 1), expected[i], list.payloads().get(i));
        }
    }

    private static List<String> ids(List<KnowledgeResource> resources) {
        List<String> ids = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            ids.add(resource.id().value());
        }
        return ids;
    }

    private static List<String> localIds(List<KnowledgeResource> resources) {
        List<String> ids = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            String value = resource.id().value();
            ids.add(value.substring(value.lastIndexOf('/') + 1));
        }
        return ids;
    }
}
