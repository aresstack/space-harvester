package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook.PstTestMailbox;
import com.aresstack.spaceharvester.application.sources.AttachmentSink;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Outlook-Anhänge über die normale Quellen-API (ADR 0026 Punkt 9, Abnahme 10): ein im Test erzeugtes Postfach lesen,
 * Anhänge listen, Bytes abrufen und den Hash vergleichen; zwei Nachrichten mit je einem ersten Anhang ergeben zwei
 * verschiedene Kennungen. Keine Postfachdatei im Repository: {@link PstTestMailbox} schreibt sie.
 */
class OutlookAttachmentsTest {

    @TempDir
    Path data;

    @TempDir
    Path mailbox;

    private final byte[] report = random(300 * 1024 + 17, 7);
    private final byte[] note = random(5000, 8);

    @Test
    void attachmentsOfAMailboxAreListedAndFetchedByteForByte() throws Exception {
        PstTestMailbox box = new PstTestMailbox();
        PstTestMailbox.Folder inbox = box.folder("Posteingang");
        inbox.message("Bericht", "Siehe Anhang").deliveredAt(Instant.parse("2026-11-12T10:00:00Z"))
                .attachment("bericht.pdf", "application/pdf", report);
        inbox.message("Notiz", "Siehe Anhang").deliveredAt(Instant.parse("2026-11-12T11:00:00Z"))
                .attachment("notiz.txt", "text/plain", note);
        box.write(mailbox.resolve("archiv.pst"));

        try (Harvester harvester = Harvester.builder()
                .source(new SourceSettingsConfig("mail", "outlook", "Mail", null, null,
                        Map.of("mailbox", mailbox.toString())))
                .dataDirectory(data).build()) {
            HarvestSource source = harvester.sources().sources().stream()
                    .filter(candidate -> candidate.id().equals("mail")).findFirst().orElseThrow();
            // Outlook erzeugt keine Vorschauen von Anhängen (ADR 0026 Punkt 9).
            assertThat(source.capabilities()).contains(SourceCapability.PAGE_ATTACHMENTS)
                    .doesNotContain(SourceCapability.ATTACHMENT_PREVIEWS);

            Map<String, PageMetadata> pages = new HashMap<>();
            PageMetadataListing listing = harvester.sources().pageMetadata("mail", "mail", null, 100);
            for (PageMetadataListing.Entry entry : listing.entries()) {
                pages.put(entry.page().title(), entry.page());
            }
            PageAttachmentList first = harvester.sources().attachments("mail", pages.get("Bericht").pageId());
            PageAttachmentList second = harvester.sources().attachments("mail", pages.get("Notiz").pageId());
            assertThat(first.attachments().attachments()).hasSize(1);
            assertThat(second.attachments().attachments()).hasSize(1);
            assertThat(first.attachments().complete()).isTrue();
            PageAttachment pdf = first.attachments().attachments().get(0);
            PageAttachment text = second.attachments().attachments().get(0);

            // Zwei erste Anhänge, zwei Kennungen; die Kennung taugt als Dateiname.
            assertThat(pdf.attachmentId()).isNotEqualTo(text.attachmentId()).matches("[A-Za-z0-9_-]+");
            assertThat(pdf.resourceId()).isNotEqualTo(text.resourceId());
            assertThat(pdf.title()).isEqualTo("bericht.pdf");
            assertThat(pdf.mediaType()).isEqualTo("application/pdf");
            assertThat(pdf.fileSize()).isEqualTo(report.length);

            assertThat(sha256(source.attachmentFile(pdf, report.length).content())).isEqualTo(sha256(report));
            Sink sink = new Sink(Long.MAX_VALUE);
            source.streamAttachment(text, sink);
            assertThat(sha256(sink.bytes.toByteArray())).isEqualTo(sha256(note));
        }
    }

    private static byte[] random(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        // Kein zlib-Kopf am Anfang: java-libpst entpackt solche Werte.
        bytes[0] = 'x';
        return bytes;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** Ziel im Speicher für den Strom. */
    private static final class Sink extends AttachmentSink {
        private final long maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean exceeded;

        Sink(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            if (bytes.size() + (long) len > maxBytes) {
                exceeded = true;
                throw new java.io.IOException("zu groß");
            }
            bytes.write(b, off, len);
        }

        @Override
        public void restart() {
            bytes.reset();
        }

        @Override
        public long maxBytes() {
            return maxBytes;
        }

        @Override
        public boolean exceeded() {
            return exceeded;
        }
    }
}
