package com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook;

import com.pff.PSTAttachment;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import com.pff.PSTObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Liest das erzeugte Postfach ausschließlich über java-libpst zurück. */
public class PstTestMailboxTest {

    private static final Instant T1 = Instant.parse("2026-11-12T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-12T11:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-13T08:30:00Z");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** Über 300 KiB: XBLOCK über 38 Blöcke. */
    private final byte[] big = pattern("%PDF-1.4", 300 * 1024 + 123);
    /** Größer als ein Heap-Wert, kleiner als ein Block: eigener Datenblock ohne XBLOCK. */
    private final byte[] medium = pattern("MITTEL", 5000);
    private final byte[] note = bytes("Notiz eins");
    private final byte[] otherNote = bytes("Notiz zwei");
    private final byte[] inner = bytes("Innerer Anhang");
    /** UTF-16 über 8176 Bytes: Text als Subknoten mit XBLOCK. */
    private final String longBody = repeat("Langer Text mit Umlauten äöü. ", 400);

    private PstTestMailbox.Folder inbox;
    private PstTestMailbox.Folder project;
    private PstTestMailbox.Folder archiveYear;
    private PstTestMailbox.Message report;
    private PstTestMailbox.Message second;
    private PstTestMailbox.Message third;
    private PstTestMailbox.Message forwarded;
    private PstTestMailbox.Message old;
    private PSTFile pst;

    @Before
    public void writeAndOpen() throws Exception {
        Path file = temp.getRoot().toPath().resolve("postfach.pst");
        build().write(file);
        pst = new PSTFile(file.toFile());
    }

    @After
    public void close() throws IOException {
        if (pst != null) {
            pst.close();
        }
    }

    private PstTestMailbox build() {
        PstTestMailbox mailbox = new PstTestMailbox();
        inbox = mailbox.folder("Posteingang");
        report = inbox.message("Bericht", "Siehe Anhang")
                .sender("Erika Muster", "erika@example.org")
                .displayTo("Max Muster")
                .deliveredAt(T1);
        report.attachment("bericht.pdf", "application/pdf", big);
        report.attachment(7, "notiz.txt", "text/plain", note);
        report.attachment("notiz.txt", "text/plain", otherNote);
        report.attachment("leer.txt", "text/plain", new byte[0]);
        report.attachment("mittel.bin", "application/octet-stream", medium).shortFilename("MITTEL~1.BIN");
        second = inbox.message("Zweite", "Text zwei").deliveredAt(T2).modifiedAt(T3);
        third = inbox.message("Dritte", longBody).messageClass("IPM.Note").messageSize(4711);
        project = inbox.folder("Projekt");
        forwarded = project.message("Weiterleitung", "Siehe unten").deliveredAt(T2);
        PstTestMailbox.Message embedded = forwarded.embeddedMessage("Weitergeleitet", "Innerer Text")
                .sender("Max Muster", "max@example.org");
        embedded.attachment("innen.txt", "text/plain", inner);
        project.message("Status", "Alles gut");
        mailbox.folder("Gesendet").message("Antwort", "Danke");
        archiveYear = mailbox.folder("Archiv").folder("2025");
        old = archiveYear.message("Alt", "Archiviert");
        return mailbox;
    }

    @Test
    public void opensAsUnencryptedUnicodeFile() throws Exception {
        assertEquals(PSTFile.PST_TYPE_UNICODE, pst.getPSTFileType());
        assertEquals(PSTFile.ENCRYPTION_TYPE_NONE, pst.getEncryptionType());
        assertEquals("Testpostfach", pst.getMessageStore().getDisplayName());
    }

    @Test
    public void rebuildsTheFolderTreeUnderIpmSubtree() throws Exception {
        PSTFolder root = pst.getRootFolder();
        assertEquals(0x122, root.getDescriptorNodeId());
        assertEquals(1, root.getSubFolders().size());
        PSTFolder subtree = root.getSubFolders().get(0);
        assertEquals("IPM_SUBTREE", subtree.getDisplayName());
        assertEquals(Arrays.asList("Posteingang", "Gesendet", "Archiv"), names(subtree.getSubFolders()));

        PSTFolder inboxFolder = subtree.getSubFolders().get(0);
        assertEquals(inbox.nodeId(), inboxFolder.getDescriptorNodeId());
        assertEquals(3, inboxFolder.getContentCount());
        assertEquals(3, inboxFolder.getEmailCount());
        assertTrue(inboxFolder.hasSubfolders());
        assertEquals(Arrays.asList("Projekt"), names(inboxFolder.getSubFolders()));

        PSTFolder projectFolder = inboxFolder.getSubFolders().get(0);
        assertEquals(project.nodeId(), projectFolder.getDescriptorNodeId());
        assertEquals(2, projectFolder.getContentCount());
        assertFalse(projectFolder.hasSubfolders());
        assertEquals(0, projectFolder.getSubFolderCount());

        assertEquals(1, subtree.getSubFolders().get(1).getContentCount());
        PSTFolder archive = subtree.getSubFolders().get(2);
        assertEquals(0, archive.getContentCount());
        assertNull(archive.getNextChild());
        PSTFolder year = archive.getSubFolders().get(0);
        assertEquals("2025", year.getDisplayName());
        assertEquals(1, year.getContentCount());
        assertEquals("Alt", ((PSTMessage) year.getNextChild()).getSubject());
    }

    @Test
    public void readsMessagesInBuildOrderWithTheirProperties() throws Exception {
        List<PSTMessage> messages = messages(folder("Posteingang"));
        assertEquals(3, messages.size());

        PSTMessage first = messages.get(0);
        assertEquals("Bericht", first.getSubject());
        assertEquals("Siehe Anhang", first.getBody());
        assertEquals("IPM.Note", first.getMessageClass());
        assertEquals("Erika Muster", first.getSenderName());
        assertEquals("erika@example.org", first.getSenderEmailAddress());
        assertEquals("Max Muster", first.getDisplayTo());
        assertEquals(T1.toEpochMilli(), first.getMessageDeliveryTime().getTime());
        assertEquals(T1.toEpochMilli(), first.getLastModificationTime().getTime());
        assertTrue(first.getMessageSize() > big.length);
        assertTrue(first.hasAttachments());

        PSTMessage secondMessage = messages.get(1);
        assertEquals("Zweite", secondMessage.getSubject());
        assertEquals(T2.toEpochMilli(), secondMessage.getMessageDeliveryTime().getTime());
        assertEquals(T3.toEpochMilli(), secondMessage.getLastModificationTime().getTime());
        assertEquals("", secondMessage.getSenderName());
        assertEquals(0, secondMessage.getNumberOfAttachments());
        assertFalse(secondMessage.hasAttachments());

        PSTMessage thirdMessage = messages.get(2);
        assertEquals(longBody, thirdMessage.getBody());
        assertEquals(4711, thirdMessage.getMessageSize());

        Set<Long> ids = new HashSet<Long>();
        for (PSTMessage message : messages) {
            ids.add(message.getDescriptorNodeId());
        }
        assertEquals(3, ids.size());
        assertEquals(report.nodeId(), first.getDescriptorNodeId());
        assertEquals(second.nodeId(), secondMessage.getDescriptorNodeId());
        assertEquals(third.nodeId(), thirdMessage.getDescriptorNodeId());
    }

    @Test
    public void returnsAttachmentsWithGivenNumbersNamesAndExactBytes() throws Exception {
        PSTMessage message = messages(folder("Posteingang")).get(0);
        assertEquals(5, message.getNumberOfAttachments());
        int[] numbers = {0, 7, 2, 3, 4};
        String[] longNames = {"bericht.pdf", "notiz.txt", "notiz.txt", "leer.txt", "mittel.bin"};
        String[] shortNames = {"bericht.pdf", "notiz.txt", "notiz.txt", "leer.txt", "MITTEL~1.BIN"};
        String[] mimeTypes = {"application/pdf", "text/plain", "text/plain", "text/plain",
                "application/octet-stream"};
        byte[][] contents = {big, note, otherNote, new byte[0], medium};
        for (int i = 0; i < 5; i++) {
            PSTAttachment attachment = message.getAttachment(i);
            assertEquals(numbers[i], attachment.getAttachNum());
            assertEquals(longNames[i], attachment.getLongFilename());
            assertEquals(shortNames[i], attachment.getFilename());
            assertEquals(mimeTypes[i], attachment.getMimeTag());
            assertEquals(PSTAttachment.ATTACHMENT_METHOD_BY_VALUE, attachment.getAttachMethod());
            assertEquals(contents[i].length, attachment.getFilesize());
            assertEquals(contents[i].length, attachment.getSize());
            assertArrayEquals("Anhang " + i, contents[i], readAll(attachment.getFileInputStream()));
        }
    }

    @Test
    public void returnsEmbeddedMessageWithItsOwnAttachment() throws Exception {
        PSTMessage outer = messages(folder("Posteingang", "Projekt")).get(0);
        assertEquals("Weiterleitung", outer.getSubject());
        assertEquals(1, outer.getNumberOfAttachments());
        PSTAttachment attachment = outer.getAttachment(0);
        assertEquals(PSTAttachment.ATTACHMENT_METHOD_EMBEDDED, attachment.getAttachMethod());
        assertEquals(0, attachment.getAttachNum());

        PSTMessage embedded = attachment.getEmbeddedPSTMessage();
        assertEquals("Weitergeleitet", embedded.getSubject());
        assertEquals("Innerer Text", embedded.getBody());
        assertEquals("Max Muster", embedded.getSenderName());
        assertEquals(1, embedded.getNumberOfAttachments());
        PSTAttachment innerAttachment = embedded.getAttachment(0);
        assertEquals("innen.txt", innerAttachment.getLongFilename());
        assertEquals("text/plain", innerAttachment.getMimeTag());
        assertArrayEquals(inner, readAll(innerAttachment.getFileInputStream()));
    }

    @Test
    public void loadsMessageByNodeIdWithItsFolderAsParent() throws Exception {
        PSTObject loaded = PSTObject.detectAndLoadPSTObject(pst, forwarded.nodeId());
        assertTrue(loaded instanceof PSTMessage);
        assertEquals("Weiterleitung", ((PSTMessage) loaded).getSubject());
        assertEquals(forwarded.nodeId(), loaded.getDescriptorNodeId());
        assertEquals(project.nodeId(), loaded.getDescriptorNode().parentDescriptorIndexIdentifier);
        assertEquals(Arrays.asList("IPM_SUBTREE", "Posteingang", "Projekt"), folderPath(loaded));

        PSTObject archived = PSTObject.detectAndLoadPSTObject(pst, old.nodeId());
        assertEquals("Alt", ((PSTMessage) archived).getSubject());
        assertEquals(archiveYear.nodeId(), archived.getDescriptorNode().parentDescriptorIndexIdentifier);
        assertEquals(Arrays.asList("IPM_SUBTREE", "Archiv", "2025"), folderPath(archived));
    }

    @Test
    public void writesByteIdenticalFilesForTheSameBuild() throws Exception {
        long firstReportId = report.nodeId();
        Path a = temp.getRoot().toPath().resolve("a.pst");
        Path b = temp.getRoot().toPath().resolve("b.pst");
        build().write(a);
        build().write(b);
        assertEquals(firstReportId, report.nodeId());
        byte[] first = Files.readAllBytes(a);
        assertArrayEquals(first, Files.readAllBytes(b));
        PstTestMailbox mailbox = build();
        assertArrayEquals(first, mailbox.toBytes());
        assertArrayEquals(first, mailbox.toBytes());

        report.attachment("neu.txt", "text/plain", note);
        assertFalse(Arrays.equals(first, mailbox.toBytes()));
    }

    // ── Hilfen ───────────────────────────────────────────────────────────────────────────────────────────────

    private PSTFolder folder(String... path) throws Exception {
        PSTFolder current = pst.getRootFolder().getSubFolders().get(0);
        for (String name : path) {
            PSTFolder next = null;
            for (PSTFolder sub : current.getSubFolders()) {
                if (name.equals(sub.getDisplayName())) {
                    next = sub;
                }
            }
            current = next;
        }
        return current;
    }

    private static List<PSTMessage> messages(PSTFolder folder) throws Exception {
        List<PSTMessage> result = new ArrayList<PSTMessage>();
        PSTObject child = folder.getNextChild();
        while (child != null) {
            result.add((PSTMessage) child);
            child = folder.getNextChild();
        }
        return result;
    }

    /** Ordnernamen über die Eltern-Descriptoren bis unter den Wurzelordner (der sein eigener Elternknoten ist). */
    private List<String> folderPath(PSTObject object) throws Exception {
        List<String> names = new ArrayList<String>();
        long parent = object.getDescriptorNode().parentDescriptorIndexIdentifier;
        while (parent != 0x122) {
            PSTFolder folder = (PSTFolder) PSTObject.detectAndLoadPSTObject(pst, parent);
            names.add(0, folder.getDisplayName());
            parent = folder.getDescriptorNode().parentDescriptorIndexIdentifier;
        }
        assertEquals(0x122, PSTObject.detectAndLoadPSTObject(pst, 0x122).getDescriptorNode()
                .parentDescriptorIndexIdentifier);
        return names;
    }

    private static List<String> names(List<PSTFolder> folders) {
        List<String> names = new ArrayList<String>();
        for (PSTFolder folder : folders) {
            names.add(folder.getDisplayName());
        }
        return names;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Deterministische Testdaten mit festem Anfang (kein zlib-Kopf 0x78 0x9C). */
    private static byte[] pattern(String prefix, int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 31 + i / 251);
        }
        byte[] head = bytes(prefix);
        System.arraycopy(head, 0, data, 0, head.length);
        return data;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(String text, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(text);
        }
        return sb.toString();
    }
}
