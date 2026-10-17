package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SourceFile;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Anhang-Downloads mit einer Grenze des Aufrufers: Abbruch statt Kürzung, ohne die Datei erst ganz zu laden. */
public class ConfluenceAttachmentFileLimitTest {

    private static final KnowledgeResourceId PDF =
            KnowledgeResourceId.of("confluence:confluence.example.org/wiki/attachment/att1");

    private final FakeConfluence confluence = new FakeConfluence();
    private ConfluenceKnowledgeSource source;

    @Before
    public void setUp() {
        confluence.space("DEV", "Development");
        confluence.page("100", "Start", "DEV", "<p>Home</p>");
        confluence.homepage("DEV", "100");
        confluence.attachment("att1", "100", "bericht.pdf", "application/pdf", "%PDF-0123456789");
        source = new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF).build(),
                confluence, new RecordingSecretProvider());
    }

    @Test
    public void withinTheLimitTheOriginalBytesArrive() throws Exception {
        SourceFile file = source.loadAttachmentFile(PDF, 15);

        assertArrayEquals("%PDF-0123456789".getBytes(StandardCharsets.UTF_8), file.content());
    }

    @Test
    public void aKnownLargerSizeIsRejectedBeforeTheDownload() throws Exception {
        int before = confluence.requestCount("/download/");
        try {
            source.loadAttachmentFile(PDF, 14);
            fail("zu groß");
        } catch (ResponseTooLargeException e) {
            assertEquals(14, e.maxBytes());
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, e.kind());
        }
        assertEquals(before, confluence.requestCount("/download/"));
    }

    @Test
    public void aTransportOverTheLimitIsAbortedAsTooLarge() throws Exception {
        // Die Größe in der Liste kann veraltet sein; dann bricht der Transport beim Lesen ab.
        ConfluenceHttpTransport aborting = new ConfluenceHttpTransport() {
            @Override
            public ConfluenceHttpResponse get(java.net.URI uri, java.util.Map<String, String> headers, int maxBytes)
                    throws java.io.IOException {
                if (uri.getRawPath().contains("/download/")) {
                    throw new ConfluenceResponseTooLargeException(maxBytes);
                }
                return confluence.get(uri, headers, maxBytes);
            }
        };
        ConfluenceKnowledgeSource limited = new ConfluenceKnowledgeSource(SourceAddress.of("confluence-dc"),
                ConfluenceConfig.builder(FakeConfluence.BASE).credentialRef(RecordingSecretProvider.REF).build(),
                aborting, new RecordingSecretProvider());
        try {
            limited.loadAttachmentFile(PDF, 20);
            fail("zu groß");
        } catch (ResponseTooLargeException e) {
            assertEquals(20, e.maxBytes());
        }
    }
}
