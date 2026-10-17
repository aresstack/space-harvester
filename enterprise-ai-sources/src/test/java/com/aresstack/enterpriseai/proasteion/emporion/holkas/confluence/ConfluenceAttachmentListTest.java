package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.StoredAttachmentList;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Complete attachment lists ({@code AttachmentListSource}): metadata only, native responses byte for byte. */
public class ConfluenceAttachmentListTest {

    private static final SourceAddress SOURCE = SourceAddress.of("confluence-dc");

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.page("101", "Guide", "DEV",
                "<p><ac:image><ri:attachment ri:filename=\"bild.png\"/></ac:image></p>");
        confluence.child("100", "101");
        confluence.homepage("DEV", "100");
        confluence.attachment("att1", "101", "bild.png", "image/png", "PNG");
        confluence.attachment("att2", "101", "nicht-eingebettet.pdf", "application/pdf", "PDF-Inhalt");
        confluence.attachment("att3", "101", "diagramm.drawio", "application/vnd.jgraph.mxfile", "<mxfile/>");
        source = new ConfluenceKnowledgeSource(SOURCE, ConfluenceConfig.builder(FakeConfluence.BASE)
                .credentialRef(RecordingSecretProvider.REF).pageSize(2).build(), confluence,
                new RecordingSecretProvider());
    }

    @Test
    public void listsEveryAttachmentOverAllListPagesWithoutLoadingBytes() throws Exception {
        StoredAttachmentList list = source.storedAttachments("101");

        assertEquals(3, list.attachments().size());
        KnowledgeResource pdf = list.attachments().get(1);
        assertEquals("confluence:confluence.example.org/wiki/attachment/att2", pdf.id().value());
        assertEquals("nicht-eingebettet.pdf", pdf.title());
        assertEquals("application/pdf", pdf.contentType());
        assertEquals("10", pdf.metadata().asMap().get("confluence.fileSize"));
        assertEquals("1", pdf.revision().version());
        assertEquals("confluence:confluence.example.org/wiki/page/101", pdf.parentId().get().value());
        assertTrue(list.complete());
        assertEquals(Collections.emptyList(), list.warnings());
        assertEquals("application/json", list.mediaType());
        assertEquals("confluence-rest-attachment-list+json;expand=container,version", list.nativeFormat());
        assertEquals(0, confluence.requestCount("/download/"));
        assertFalse(list.toString().contains("bild.png"));
    }

    @Test
    public void payloadsAreTheListResponsesByteForByte() throws Exception {
        StoredAttachmentList list = source.storedAttachments("101");

        List<URI> requests = confluence.requests();
        assertEquals(2, list.payloads().size());
        URI first = requests.get(requests.size() - 2);
        URI second = requests.get(requests.size() - 1);
        assertTrue(first.toString(), first.getRawQuery().contains("start=0"));
        assertArrayEquals(confluence.get(first, Collections.<String, String>emptyMap(), Integer.MAX_VALUE).body(),
                list.payloads().get(0));
        assertArrayEquals(confluence.get(second, Collections.<String, String>emptyMap(), Integer.MAX_VALUE).body(),
                list.payloads().get(1));
    }

    @Test
    public void pageWithoutAttachmentsHasAnEmptyCompleteList() throws Exception {
        StoredAttachmentList list = source.storedAttachments("100");

        assertTrue(list.attachments().isEmpty());
        assertTrue(list.complete());
        assertEquals(1, list.payloads().size());
    }

    @Test
    public void entryWithoutUsableIdIsLeftOutAndReported() throws Exception {
        confluence.attachment("kaputt", "101", "ohne-id.txt", "text/plain", "x");

        StoredAttachmentList list = source.storedAttachments("101");

        assertEquals(3, list.attachments().size());
        assertFalse(list.complete());
        assertEquals(1, list.warnings().size());
        assertTrue(list.warnings().get(0), list.warnings().get(0).contains("Listenseite 2, Eintrag 2"));
        assertFalse(list.warnings().get(0).contains("ohne-id.txt"));
    }

    @Test
    public void retrievedAtIsTheTimeOfTheFirstAnswerAfterRetries() throws Exception {
        ConfluenceKnowledgeSource retrying = new ConfluenceKnowledgeSource(SOURCE,
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF).pageSize(2)
                        .maxRetries(1).retryBackoffMillis(200).build(), confluence, new RecordingSecretProvider());
        confluence.failNext(503, 1);
        java.time.Instant before = java.time.Instant.now();

        StoredAttachmentList list = retrying.storedAttachments("101");

        assertTrue(list.retrievedAt().toString(), !list.retrievedAt().isBefore(before.plusMillis(100)));
        assertEquals(3, list.attachments().size());
    }

    @Test
    public void failuresAreNeverAnEmptyList() throws Exception {
        confluence.restricted.add("101");
        assertKind(Kind.NOT_FOUND, "101");
        assertKind(Kind.NOT_FOUND, "999");
        assertKind(Kind.UNSUPPORTED, "att1");
        confluence.restricted.clear();
        confluence.failNext(401, 1);
        assertKind(Kind.ACCESS_DENIED, "101");
    }

    private void assertKind(Kind kind, String localId) {
        try {
            source.storedAttachments(localId);
            fail("Fehler erwartet für " + localId);
        } catch (KnowledgeSourceException e) {
            assertEquals(localId, kind, e.kind());
        }
    }
}
