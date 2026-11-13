package com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeMetadata;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.PageView;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.SourceFile;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.StoredAttachmentList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Anhänge der Outlook-Quelle (ADR 0026 Punkt 9 im Space Harvester) über ein im Test erzeugtes Postfach: Liste,
 * Kennung je Postfachdatei, Nachricht und Anhangsnummer, Originalbytes als Strom, angehängte Elemente als Mail.
 */
public class OutlookKnowledgeSourceTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final byte[] big = new byte[300 * 1024 + 17];
    private final byte[] pdf = "%PDF-1.4 kleiner Bericht".getBytes(StandardCharsets.US_ASCII);
    private final byte[] note = "Notiz eins".getBytes(StandardCharsets.UTF_8);
    private final byte[] otherNote = "Notiz zwei".getBytes(StandardCharsets.UTF_8);
    private final byte[] inner = "Innerer Anhang".getBytes(StandardCharsets.UTF_8);
    private OutlookKnowledgeSource source;

    @Before
    public void setUp() throws Exception {
        new Random(7).nextBytes(big);
        PstTestMailbox mailbox = new PstTestMailbox();
        PstTestMailbox.Folder inbox = mailbox.folder("Posteingang");
        PstTestMailbox.Message report = inbox.message("Bericht", "Siehe Anhang")
                .sender("Erika Muster", "erika@example.org")
                .displayTo("Max Muster")
                .deliveredAt(Instant.parse("2026-11-12T10:00:00Z"));
        report.attachment(3, "bericht.pdf", "application/pdf", pdf);
        report.attachment(5, "notiz.txt", "", note);
        report.attachment(6, "notiz.txt", "", otherNote);
        PstTestMailbox.Message large = inbox.message("Groß", "Großer Anhang")
                .deliveredAt(Instant.parse("2026-11-12T11:00:00Z"));
        large.attachment("daten.bin", "application/octet-stream", big);
        PstTestMailbox.Message forwarded = inbox.folder("Projekt").message("Weiterleitung", "Siehe unten")
                .deliveredAt(Instant.parse("2026-11-12T12:00:00Z"));
        PstTestMailbox.Message embedded = forwarded.embeddedMessage("Ursprüngliche Nachricht", "Innerer Text");
        embedded.attachment("innen.txt", "text/plain", inner);
        inbox.message("Ohne Anhang", "Nur Text");
        PstTestMailbox.Message twice = inbox.message("Doppelt", "Zwei Anhänge mit derselben Nummer");
        twice.attachment(2, "a.txt", "text/plain", note);
        twice.attachment(2, "b.txt", "text/plain", otherNote);
        nest(inbox.message("Tief", "Neun Ebenen"), OutlookMessageWriter.MAX_DEPTH + 1);
        inbox.message("Beschädigt", "Kennzeichen ohne Tabelle").flaggedWithAttachmentsButNoTable();
        inbox.message("Beschädigt innen", "Weitergeleitet").embeddedMessage("Innen beschädigt", "Text")
                .flaggedWithAttachmentsButNoTable();
        nest(inbox.message("Tief genug", "Acht Ebenen"), OutlookMessageWriter.MAX_DEPTH);
        Path file = temp.getRoot().toPath().resolve("archiv.pst");
        mailbox.write(file);
        source = new OutlookKnowledgeSource(SourceAddress.of("outlook://test"), file);
    }

    @Test
    public void listsTheAttachmentsOfAMessageWithIdsFromFileMessageAndAttachmentNumber() throws Exception {
        KnowledgeResource report = message("Bericht");
        String localId = report.id().value().substring(report.id().value().lastIndexOf('/', report.id().value()
                .lastIndexOf('/') - 1) + 1);

        StoredAttachmentList list = source.storedAttachments(localId);

        assertEquals(3, list.attachments().size());
        assertTrue(list.complete());
        assertTrue(list.payloads().isEmpty());
        assertEquals(OutlookKnowledgeSource.ATTACHMENT_LIST_FORMAT, list.nativeFormat());
        String node = report.id().value().substring(report.id().value().lastIndexOf('/') + 1);
        KnowledgeResource pdfAttachment = list.attachments().get(0);
        assertEquals(report.id().value() + "/" + token("archiv.pst/" + node + "/3"), pdfAttachment.id().value());
        // Die Kennung taugt als Dateiname und URL-Teil.
        for (KnowledgeResource attachment : list.attachments()) {
            assertTrue(last(attachment.id()), last(attachment.id()).matches("[A-Za-z0-9_-]+"));
        }
        assertEquals("bericht.pdf", pdfAttachment.title());
        assertEquals("application/pdf", pdfAttachment.contentType());
        assertEquals(String.valueOf(pdf.length), pdfAttachment.metadata().asMap().get(KnowledgeMetadata.FILE_SIZE));
        assertEquals(report.id(), pdfAttachment.parentId().get());
        // Ohne MIME-Angabe in der Postfachdatei gilt die Dateiendung.
        assertEquals("text/plain", list.attachments().get(1).contentType());
        // Die ganze Ressourcen-ID taugt ebenso als lokale ID.
        assertEquals(ids(list.attachments()), ids(source.storedAttachments(report.id().value()).attachments()));
        assertEquals(ids(list.attachments()), ids(source.attachmentsOf(report.id())));
    }

    @Test
    public void twoAttachmentsWithTheSameNameAreTwoAttachments() throws Exception {
        List<KnowledgeResource> attachments = source.attachmentsOf(message("Bericht").id());

        assertEquals("notiz.txt", attachments.get(1).title());
        assertEquals("notiz.txt", attachments.get(2).title());
        assertNotEquals(attachments.get(1).id(), attachments.get(2).id());
        assertArrayEquals(note, source.loadAttachmentFile(attachments.get(1).id()).content());
        assertArrayEquals(otherNote, source.loadAttachmentFile(attachments.get(2).id()).content());
    }

    @Test
    public void theFirstAttachmentsOfTwoMessagesGetDifferentIds() throws Exception {
        KnowledgeResource first = source.attachmentsOf(message("Groß").id()).get(0);
        KnowledgeResource second = source.attachmentsOf(message("Weiterleitung").id()).get(0);

        assertEquals("0", first.metadata().asMap().get(OutlookKnowledgeSource.METADATA_ATTACHMENT_NUMBER));
        assertEquals("0", second.metadata().asMap().get(OutlookKnowledgeSource.METADATA_ATTACHMENT_NUMBER));
        assertNotEquals(last(first.id()), last(second.id()));
    }

    @Test
    public void aLargeAttachmentFlowsUnchangedWithTheSameHash() throws Exception {
        KnowledgeResource attachment = source.attachmentsOf(message("Groß").id()).get(0);
        assertTrue(source.isAttachment(attachment.id()));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        KnowledgeResource streamed = source.streamAttachmentFile(attachment.id(), sink);

        assertEquals(attachment.id(), streamed.id());
        assertArrayEquals(sha256(big), sha256(sink.toByteArray()));
        SourceFile loaded = source.loadAttachmentFile(attachment.id());
        assertArrayEquals(big, loaded.content());
        assertArrayEquals(big, source.loadAttachmentFile(attachment.id(), big.length).content());
        try {
            source.loadAttachmentFile(attachment.id(), big.length - 1);
            fail("Grenze nicht beachtet");
        } catch (ResponseTooLargeException expected) {
            assertEquals(big.length - 1, expected.maxBytes());
        }
    }

    @Test
    public void anAttachedOutlookItemComesAsMailWithItsOwnAttachments() throws Exception {
        KnowledgeResource attachment = source.attachmentsOf(message("Weiterleitung").id()).get(0);
        assertEquals(OutlookKnowledgeSource.EMBEDDED_MEDIA_TYPE, attachment.contentType());
        assertTrue(attachment.title().endsWith(".eml"));
        assertTrue(attachment.revision().version().startsWith(OutlookMessageWriter.VERSION + "-"));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        source.streamAttachmentFile(attachment.id(), sink);
        String mail = new String(sink.toByteArray(), StandardCharsets.US_ASCII);

        assertTrue(mail, mail.startsWith("MIME-Version: 1.0\r\n"));
        assertTrue(mail, mail.contains("Subject: =?UTF-8?B?"));
        assertTrue(mail, mail.contains("multipart/mixed"));
        assertTrue(mail, mail.contains("filename=\"innen.txt\""));
        assertTrue(mail, mail.contains(Base64.getEncoder().encodeToString(inner)));
        assertTrue(mail, mail.contains(Base64.getEncoder().encodeToString("Innerer Text"
                .getBytes(StandardCharsets.UTF_8))));
        // Gleiche Eingabe, gleiche Bytes: der Hash des Anhangs ist stabil.
        assertArrayEquals(sink.toByteArray(), source.loadAttachmentFile(attachment.id()).content());
    }

    @Test
    public void attachmentIdsAreRecognisedWithoutTouchingTheFile() throws Exception {
        KnowledgeResource report = message("Bericht");
        KnowledgeResource attachment = source.attachmentsOf(report.id()).get(0);
        String node = last(report.id());

        assertTrue(source.isAttachment(attachment.id()));
        assertFalse(source.isAttachment(report.id()));
        assertFalse(source.isAttachment(KnowledgeResourceId.of(report.id().value() + "/"
                + token("archiv.pst/" + node + "/03"))));
        assertFalse(source.isAttachment(KnowledgeResourceId.of(report.id().value() + "/"
                + token("andere.pst/" + node + "/3"))));
        assertFalse(source.isAttachment(KnowledgeResourceId.of(report.id().value() + "/archiv.pst:" + node + ":3")));
        assertFalse(source.isAttachment(KnowledgeResourceId.of("mail:/anderes/postfach.pst/archiv.pst/" + node
                + "/" + token("archiv.pst/" + node + "/3"))));
        try {
            source.load(attachment.id());
            fail("Anhänge liest loadAttachmentFile, nicht load");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.UNSUPPORTED, expected.kind());
        }
        try {
            source.loadAttachmentFile(KnowledgeResourceId.of(report.id().value() + "/"
                    + token("archiv.pst/" + node + "/99")));
            fail("unbekannte Anhangsnummer");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.NOT_FOUND, expected.kind());
        }
    }

    @Test
    public void theViewShowsTheMessageMaskedAndListsItsAttachments() throws Exception {
        KnowledgeResource report = message("Bericht");

        PageView view = source.pageView(report.id());

        assertTrue(view.html(), view.html().contains("<h1>Bericht</h1>"));
        assertTrue(view.html(), view.html().contains("Siehe Anhang"));
        assertTrue(view.images().isEmpty());
        assertEquals(3, view.attachments().size());
        assertEquals("bericht.pdf", view.attachments().get(0).fileName());
        assertTrue(view.attachments().get(0).previews().isEmpty());
        assertArrayEquals(pdf, source.loadAttachment(view.attachments().get(0)));
        try {
            source.loadViewImage("cid:bild");
            fail("die Ansicht hat keine Bilder");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.UNSUPPORTED, expected.kind());
        }
    }

    @Test
    public void aMessageWithoutAttachmentsHasAnEmptyCompleteList() throws Exception {
        StoredAttachmentList list = source.storedAttachments(message("Ohne Anhang").id().value());

        assertTrue(list.attachments().isEmpty());
        assertTrue(list.complete());
    }

    @Test
    public void anUnreadableAttachmentTableIsAnErrorNeverAnEmptyList() throws Exception {
        KnowledgeResource broken = message("Beschädigt");
        try {
            source.storedAttachments(broken.id().value());
            fail("unlesbare Anhangstabelle als leere Liste");
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, e.kind());
        }
        try {
            source.attachmentsOf(broken.id());
            fail("unlesbare Anhangstabelle als leere Liste");
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, e.kind());
        }
        // Die Ansicht bleibt lesbar, nur ohne Anhänge.
        assertTrue(source.pageView(broken.id()).attachments().isEmpty());

        KnowledgeResource item = source.attachmentsOf(message("Beschädigt innen").id()).get(0);
        try {
            source.streamAttachmentFile(item.id(), new ByteArrayOutputStream());
            fail("angehängtes Element ohne seine Dateien als vollständig geliefert");
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, e.kind());
        }
    }

    @Test
    public void aDuplicateAttachmentNumberKeepsBothEntriesWithACounterAndAWarning() throws Exception {
        KnowledgeResource message = message("Doppelt");
        String node = last(message.id());

        StoredAttachmentList list = source.storedAttachments(message.id().value());

        assertEquals(2, list.attachments().size());
        assertEquals(1, list.warnings().size());
        assertFalse(list.complete());
        assertEquals(token("archiv.pst/" + node + "/2"), last(list.attachments().get(0).id()));
        assertEquals(token("archiv.pst/" + node + "/2/1"), last(list.attachments().get(1).id()));
        assertArrayEquals(note, source.loadAttachmentFile(list.attachments().get(0).id()).content());
        assertArrayEquals(otherNote, source.loadAttachmentFile(list.attachments().get(1).id()).content());
        // Warnungen nennen keine Dateinamen.
        assertFalse(list.warnings().get(0), list.warnings().get(0).contains(".txt"));
    }

    @Test
    public void tooDeeplyNestedItemsAreListedButNeverShortened() throws Exception {
        KnowledgeResource deep = source.attachmentsOf(message("Tief").id()).get(0);
        KnowledgeResource fine = source.attachmentsOf(message("Tief genug").id()).get(0);

        assertEquals(OutlookKnowledgeSource.EMBEDDED_MEDIA_TYPE, deep.contentType());
        try {
            source.streamAttachmentFile(deep.id(), new ByteArrayOutputStream());
            fail("zu tief verschachtelt");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.UNSUPPORTED, expected.kind());
        }
        String mail = new String(source.loadAttachmentFile(fine.id()).content(), StandardCharsets.US_ASCII);
        assertTrue(mail, mail.contains("outlook-part-" + (OutlookMessageWriter.MAX_DEPTH - 2)));
        assertEquals(Base64.getEncoder().encodeToString("Ebene 8".getBytes(StandardCharsets.UTF_8)),
                lastBodyLine(mail));
    }

    @Test
    public void headerValuesNeverEndALine() {
        assertEquals("a b", OutlookMessageWriter.oneLine("a\r\nb"));
        assertEquals("=?UTF-8?B?" + Base64.getEncoder().encodeToString("Grüße".getBytes(StandardCharsets.UTF_8))
                + "?=", OutlookMessageWriter.encodedText("Grüße"));
        assertEquals("filename*=UTF-8''Gr%C3%BC%C3%9Fe.txt", OutlookMessageWriter.parameter("filename", "Grüße.txt"));
        assertEquals("filename=\"a b.txt\"", OutlookMessageWriter.parameter("filename", "a\nb.txt"));
        assertEquals("\"Erika Muster\" <erika@example.org>",
                OutlookMessageWriter.mailbox("Erika Muster", "erika@example.org"));
        assertEquals("\"Erika\"", OutlookMessageWriter.mailbox("Erika", "erika@example.org>\r\nBcc: x"));
        String longText = OutlookMessageWriter.encodedText(new String(new char[100]).replace('\0', 'ä'));
        for (String line : longText.split("\r\n")) {
            assertTrue(line, line.length() <= 76);
        }
    }

    private KnowledgeResource message(String subject) throws KnowledgeSourceException {
        for (KnowledgeResource resource : source.discover(SourceScope.builder().startPoints(".").maxDepth(15)
                .build())) {
            if (subject.equals(resource.title())) {
                return resource;
            }
        }
        throw new AssertionError("Nachricht fehlt: " + subject);
    }

    /** Hängt an {@code message} eine Kette aus {@code levels} ineinander angehängten Elementen. */
    private static void nest(PstTestMailbox.Message message, int levels) {
        PstTestMailbox.Message current = message;
        for (int level = 1; level <= levels; level++) {
            current = current.embeddedMessage("Ebene " + level, "Ebene " + level);
        }
    }

    /** Die letzte Base64-Zeile des Texts der innersten Mail. */
    private static String lastBodyLine(String mail) {
        String[] lines = mail.split("\r\n");
        String last = "";
        for (String line : lines) {
            if (line.matches("[A-Za-z0-9+/=]{4,}")) {
                last = line;
            }
        }
        return last;
    }

    private static String token(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> ids(List<KnowledgeResource> resources) {
        List<String> ids = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            ids.add(resource.id().value());
        }
        return ids;
    }

    private static String last(KnowledgeResourceId id) {
        return id.value().substring(id.value().lastIndexOf('/') + 1);
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }
}
