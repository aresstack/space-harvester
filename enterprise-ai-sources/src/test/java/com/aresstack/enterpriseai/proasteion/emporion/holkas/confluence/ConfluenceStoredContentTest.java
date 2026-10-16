package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.StoredContent;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Native page responses ({@code StoredContentSource}), byte for byte, against the fake. */
public class ConfluenceStoredContentTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    /** Whitespace, key order and a Unicode escape that parsing and writing JSON again would all change. */
    private static final String RAW = "{ \"type\" : \"page\",\n  \"id\": \"102\",\n"
            + "  \"title\": \"Pr\\u00fcfung & \\\"Freigabe\\\"\",\n"
            + "  \"space\": {\"key\": \"DEV\", \"name\": \"Development\"},\n"
            + "  \"version\": {\"number\": 7, \"when\": \"2026-10-15T12:30:00.000+02:00\"},\n"
            + "  \"ancestors\": [{\"id\": \"100\", \"type\": \"page\", \"title\": \"Start\"}],\n"
            + "  \"body\": {\"storage\": {\"value\": \"<p>Inhalt</p><ac:structured-macro ac:name=\\\"info\\\"/>\","
            + " \"representation\": \"storage\"}},\n"
            + "  \"metadata\": {\"labels\": {\"results\": [{\"name\": \"api\"}]}},\n"
            + "  \"history\": {\"createdDate\": \"2026-09-01T09:00:00.000+02:00\","
            + " \"createdBy\": {\"username\": \"autorin\"}},\n"
            + "  \"_links\": {\"webui\": \"/pages/viewpage.action?pageId=102\"}\n}\n";

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.page("101", "Guide", "DEV", "<p>Guide</p>").labels("howto").storage("<p>Guide</p>");
        confluence.child("100", "101");
        confluence.page("102", "Prüfung & \"Freigabe\"", "DEV", "<p>Inhalt</p>").rawJson(RAW);
        confluence.child("100", "102");
        confluence.homepage("DEV", "100");
        source = source(ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF));
    }

    private ConfluenceKnowledgeSource source(ConfluenceConfig.Builder config) {
        return new ConfluenceKnowledgeSource(SOURCE, config.build(), confluence, new RecordingSecretProvider());
    }

    private static KnowledgeResourceId page(String id) {
        return KnowledgeResourceId.of("confluence", "confluence.example.org/wiki/page/" + id);
    }

    @Test
    public void payloadIsTheResponseBodyByteForByte() throws Exception {
        StoredContent stored = source.storedContent("102");

        byte[] expected = RAW.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, stored.payload());
        assertEquals(expected.length, stored.length());
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(expected)), stored.sha256());
        assertEquals("application/json", stored.mediaType());
        assertNotNull(stored.retrievedAt());
    }

    @Test
    public void metadataIsReadFromTheSameResponse() throws Exception {
        KnowledgeResource resource = source.storedContent("102").resource();

        assertEquals(page("102"), resource.id());
        assertEquals("Prüfung & \"Freigabe\"", resource.title());
        assertEquals("7", resource.revision().version());
        assertEquals("DEV", resource.scope());
        assertEquals(page("100"), resource.parentId().get());
        assertEquals("api", resource.metadata().get("confluence.labels").get());
        assertEquals("autorin", resource.metadata().get("confluence.createdBy").get());
    }

    @Test
    public void requestsStorageAndNoRenderedOrUserSpecificViews() throws Exception {
        StoredContent stored = source.storedContent("101");

        assertArrayEquals(confluence.lastPageBody(), stored.payload());
        assertTrue(new String(stored.payload(), StandardCharsets.UTF_8).contains("\"storage\""));
        URI request = confluence.requests().get(confluence.requests().size() - 1);
        assertTrue(request.toString(), request.getRawQuery().contains("body.storage"));
        assertFalse(request.toString(), request.getRawQuery().contains("body.view"));
        assertFalse(request.toString(), request.getRawQuery().contains("export_view"));
        assertFalse(request.toString(), request.getRawQuery().contains("currentuser"));
        assertFalse(request.toString(), request.getRawQuery().contains("restrictions"));
        assertEquals("confluence-rest-content+json;expand=ancestors,body.storage,history,history.lastUpdated,"
                + "metadata.labels,space,version", stored.nativeFormat());
    }

    @Test
    public void metadataAgreesWithTheSingleLookup() throws Exception {
        KnowledgeResource stored = source.storedContent("101").resource();
        KnowledgeResource single = source.resourceMetadata("101");

        assertEquals(single.id(), stored.id());
        assertEquals(single.revision(), stored.revision());
        assertEquals(single.parentId(), stored.parentId());
        assertEquals(single.metadata(), stored.metadata());
    }

    @Test
    public void responseOverTheLimitIsAnErrorNeverTruncated() throws Exception {
        ConfluenceKnowledgeSource small = source(ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).maxResponseBytes(100));
        try {
            small.storedContent("102");
            fail("expected ResponseTooLargeException");
        } catch (ResponseTooLargeException e) {
            assertEquals(Kind.INVALID_RESPONSE, e.kind());
            assertEquals(100, e.maxBytes());
        }
    }

    @Test
    public void restrictedPagesLookLikeUnknownOnesAndForeignIdsAreUnsupported() throws Exception {
        confluence.restricted.add("101");
        assertKind(Kind.NOT_FOUND, "999");
        assertKind(Kind.NOT_FOUND, "101");
        assertKind(Kind.UNSUPPORTED, "att123");
    }

    @Test
    public void toStringNeverShowsThePayload() throws Exception {
        String text = source.storedContent("102").toString();

        assertFalse(text, text.contains("Inhalt"));
        assertTrue(text, text.contains("bytes"));
    }

    private void assertKind(Kind expected, String localId) {
        try {
            source.storedContent(localId);
            fail("expected " + expected + " for " + localId);
        } catch (KnowledgeSourceException e) {
            assertEquals(localId, expected, e.kind());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
