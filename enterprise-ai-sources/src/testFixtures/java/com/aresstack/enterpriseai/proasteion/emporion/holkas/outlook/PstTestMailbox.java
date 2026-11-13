package com.aresstack.enterpriseai.proasteion.emporion.holkas.outlook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Erzeugt eine minimale Outlook-PST-Datei, die java-libpst 0.9.3 liest, damit Tests ohne binäres Beispielpostfach im
 * Repository auskommen. Nur JDK. Deterministisch: gleiche Aufrufe in gleicher Reihenfolge ergeben byte-gleiche
 * Dateien (keine Zufallswerte, keine Uhrzeit, NIDs in Aufbaureihenfolge, BIDs in Schreibreihenfolge).
 *
 * <pre>
 * PstTestMailbox mailbox = new PstTestMailbox();
 * PstTestMailbox.Message m = mailbox.folder("Posteingang").message("Betreff", "Text")
 *         .sender("Erika Muster", "erika@example.org").deliveredAt(Instant.parse("2026-11-12T10:00:00Z"));
 * m.attachment("bericht.pdf", "application/pdf", bytes);
 * m.embeddedMessage("Weitergeleitet", "Innerer Text").attachment("innen.txt", "text/plain", bytes);
 * mailbox.write(path);
 * </pre>
 *
 * <p>Layout nach MS-PST, beschränkt auf das, was java-libpst auswertet:
 * <ul>
 * <li>Unicode-Header (wVer 23, bCryptMethod 0); keine AMap/PMap/DList ({@code fAMapValid = 0}), CRCs und wSig 0.</li>
 * <li>Blöcke ab 0x4400 (64-Byte-ausgerichtet mit BLOCKTRAILER), höchstens 8176 Datenbytes; größere Werte als
 * XBLOCK über mehrere Blöcke. Erst ab 0x4400, weil {@code PSTNodeInputStream.seek} bei mehrblockigen Strömen auf
 * {@code Blockanfang - 8176} positioniert und vor Offset 8176 eine negative Dateiposition entstünde.</li>
 * <li>NBT und BBT als 512-Byte-Seiten, bei Bedarf mehrstufig (Blatt: 15 bzw. 20 Einträge, Zweig: 20).</li>
 * <li>NIDs: Nachrichtenspeicher 0x21, Name-to-ID-Map 0x61, Wurzelordner 0x122, IPM_SUBTREE 0x8022, weitere Ordner
 * ab 0x8042, Nachrichten ab 0x200004; je Ordner Hierarchietabelle (NID + 11) und Inhaltstabelle (NID + 12), die
 * Tabelle der FAI-Inhalte entfällt (wird nicht gelesen).</li>
 * <li>Eigenschaften (PC) und Tabellen (TC) liegen je in einem Heap-on-Node-Block. Werte über 3580 Bytes oder ohne
 * Platz im Heap wandern in einen Subknoten. Anhänge sind Subknoten der Nachricht (Anhangstabelle 0x671, je Anhang
 * ein PC), eingebettete Nachrichten Subknoten des Anhangs. Subknoten-NIDs sind dateiweit eindeutig, weil
 * java-libpst die Subknoten eines Anhangs und seiner eingebetteten Nachricht in einer Map zusammenführt.</li>
 * </ul>
 *
 * <p>Grenzen: höchstens etwa 470 Zeilen je Tabelle (Unterordner/Nachrichten je Ordner, Anhänge je Nachricht),
 * 339 Subknoten je Ebene und etwa 8 MB je Wert. java-libpst entpackt Werte, die mit 0x78 0x9C beginnen, als zlib;
 * Testdaten sollten daher nicht so beginnen.
 */
public final class PstTestMailbox {

    // NIDs (MS-PST 2.2.2.1 und 2.4.1); die unteren 5 Bit sind der NID-Typ.
    private static final int NID_MESSAGE_STORE = 0x21;
    private static final int NID_NAME_TO_ID_MAP = 0x61;
    private static final int NID_ROOT_FOLDER = 0x122;
    private static final int NID_IPM_SUBTREE = 0x8022;
    private static final int NID_ATTACHMENT_TABLE = 0x671;
    private static final int NID_TYPE_NORMAL_FOLDER = 0x02;
    private static final int NID_TYPE_NORMAL_MESSAGE = 0x04;
    private static final int NID_TYPE_ATTACHMENT = 0x05;
    private static final int NID_TYPE_LTP = 0x1F;
    private static final int HIERARCHY_TABLE = 11;
    private static final int CONTENTS_TABLE = 12;
    private static final int FIRST_FOLDER_INDEX = 0x402;
    private static final int FIRST_MESSAGE_INDEX = 0x10000;
    private static final int FIRST_SUBNODE_INDEX = 0x400;

    private static final int PT_INT32 = 0x0003;
    private static final int PT_BOOLEAN = 0x000B;
    private static final int PT_OBJECT = 0x000D;
    private static final int PT_STRING = 0x001F;
    private static final int PT_TIME = 0x0040;
    private static final int PT_BINARY = 0x0102;

    private static final int MAX_BLOCK_DATA = 8176;
    private static final int MAX_HEAP_ALLOC = 3580;
    private static final int FIRST_BLOCK_OFFSET = 0x4400;
    private static final int PAGE_SIZE = 512;
    private static final long FILETIME_EPOCH_MILLIS = 11644473600000L;
    private static final Instant DEFAULT_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<Node> NO_NODES = Collections.emptyList();
    private static final Comparator<Node> BY_NID = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            return Integer.compare(a.nid, b.nid);
        }
    };

    private final Folder root;
    private final Folder ipmSubtree;
    private int nextFolderIndex = FIRST_FOLDER_INDEX;
    private int nextMessageIndex = FIRST_MESSAGE_INDEX;

    public PstTestMailbox() {
        root = new Folder(this, "", NID_ROOT_FOLDER, NID_ROOT_FOLDER);
        ipmSubtree = new Folder(this, "IPM_SUBTREE", NID_IPM_SUBTREE, NID_ROOT_FOLDER);
        root.folders.add(ipmSubtree);
    }

    /** Neuer Ordner direkt unter {@code IPM_SUBTREE}; jeder Aufruf legt einen eigenen Ordner an. */
    public Folder folder(String name) {
        return ipmSubtree.folder(name);
    }

    public void write(Path target) throws IOException {
        Files.write(target, toBytes());
    }

    /** Die Datei als Bytes, z. B. für {@code new PSTFile(byte[])}. */
    public byte[] toBytes() {
        return new Writer().build();
    }

    /** Ein Postfachordner; NID wird beim Anlegen vergeben. */
    public static final class Folder {
        private final PstTestMailbox mailbox;
        private final String name;
        private final int nid;
        private final int parentNid;
        private final List<Folder> folders = new ArrayList<Folder>();
        private final List<Message> messages = new ArrayList<Message>();

        private Folder(PstTestMailbox mailbox, String name, int nid, int parentNid) {
            this.mailbox = mailbox;
            this.name = name;
            this.nid = nid;
            this.parentNid = parentNid;
        }

        /** Neuer Unterordner; jeder Aufruf legt einen eigenen Ordner an. */
        public Folder folder(String name) {
            Folder child = new Folder(mailbox, Objects.requireNonNull(name, "name"),
                    (mailbox.nextFolderIndex++ << 5) | NID_TYPE_NORMAL_FOLDER, nid);
            folders.add(child);
            return child;
        }

        /** Neue Nachricht (Klasse {@code IPM.Note}) in diesem Ordner. */
        public Message message(String subject, String body) {
            Message message = new Message((mailbox.nextMessageIndex++ << 5) | NID_TYPE_NORMAL_MESSAGE, subject, body);
            messages.add(message);
            return message;
        }

        public String name() {
            return name;
        }

        /** NID, wie java-libpst sie über {@code getDescriptorNodeId()} meldet. */
        public long nodeId() {
            return nid;
        }
    }

    /** Eine Nachricht; als eingebettete Nachricht (Anhangsmethode 5) mit NID 0 wie bei java-libpst. */
    public static final class Message {
        private final int nid;
        private final String subject;
        private final String body;
        private String senderName;
        private String senderEmail;
        private String displayTo;
        private Instant deliveredAt = DEFAULT_TIME;
        private Instant modifiedAt;
        private String messageClass = "IPM.Note";
        private Integer messageSize;
        private final List<Attachment> attachments = new ArrayList<Attachment>();
        private boolean flaggedWithoutTable;

        private Message(int nid, String subject, String body) {
            this.nid = nid;
            this.subject = subject;
            this.body = body;
        }

        public Message sender(String name, String email) {
            this.senderName = name;
            this.senderEmail = email;
            return this;
        }

        public Message displayTo(String displayTo) {
            this.displayTo = displayTo;
            return this;
        }

        /** PidTagMessageDeliveryTime; Vorgabe 2026-01-01T00:00:00Z. */
        public Message deliveredAt(Instant time) {
            this.deliveredAt = Objects.requireNonNull(time, "time");
            return this;
        }

        /** PidTagLastModificationTime; Vorgabe ist die Zustellzeit. */
        public Message modifiedAt(Instant time) {
            this.modifiedAt = time;
            return this;
        }

        public Message messageClass(String messageClass) {
            this.messageClass = messageClass;
            return this;
        }

        /** PidTagMessageSize; Vorgabe: Bytes der Texte (UTF-16) plus Anhangsgrößen. */
        public Message messageSize(int bytes) {
            this.messageSize = bytes;
            return this;
        }

        /**
         * Kennzeichen „hat Anhänge“ (MSGFLAG_HASATTACH) ohne Anhangstabelle, wie bei einer beschädigten Nachricht:
         * java-libpst meldet dann null Anhänge.
         */
        public Message flaggedWithAttachmentsButNoTable() {
            this.flaggedWithoutTable = true;
            return this;
        }

        /** Anhang als Wert (Methode 1) mit der Listenposition als Anhangsnummer. */
        public Attachment attachment(String fileName, String mimeType, byte[] data) {
            return attachment(attachments.size(), fileName, mimeType, data);
        }

        /** Anhang als Wert (Methode 1) mit ausdrücklicher Anhangsnummer (PidTagAttachNumber). */
        public Attachment attachment(int attachNumber, String fileName, String mimeType, byte[] data) {
            Attachment attachment = new Attachment(attachNumber, fileName, mimeType,
                    Objects.requireNonNull(data, "data").clone(), null);
            attachments.add(attachment);
            return attachment;
        }

        /** Eingebettete Nachricht (Methode 5) mit der Listenposition als Anhangsnummer. */
        public Message embeddedMessage(String subject, String body) {
            return embeddedMessage(attachments.size(), subject, body);
        }

        public Message embeddedMessage(int attachNumber, String subject, String body) {
            Message inner = new Message(0, subject, body);
            attachments.add(new Attachment(attachNumber, null, null, null, inner));
            return inner;
        }

        /** NID, wie java-libpst sie über {@code getDescriptorNodeId()} meldet (0 bei eingebetteten Nachrichten). */
        public long nodeId() {
            return nid;
        }

        private int size() {
            if (messageSize != null) {
                return messageSize;
            }
            long size = utf16Length(subject) + utf16Length(body) + utf16Length(senderName) + utf16Length(senderEmail)
                    + utf16Length(displayTo);
            for (Attachment attachment : attachments) {
                size += attachment.size();
            }
            return (int) Math.min(Integer.MAX_VALUE, size);
        }
    }

    /** Ein Anhang als Wert; kurzer Dateiname ist standardmäßig gleich dem langen. */
    public static final class Attachment {
        private final int number;
        private final String longFilename;
        private String shortFilename;
        private final String mimeType;
        private final byte[] data;
        private final Message embedded;
        private boolean withoutDataField;
        private int method = 1;

        private Attachment(int number, String fileName, String mimeType, byte[] data, Message embedded) {
            this.number = number;
            this.longFilename = fileName;
            this.shortFilename = fileName;
            this.mimeType = mimeType;
            this.data = data;
            this.embedded = embedded;
        }

        /** PidTagAttachFilename (0x3704); {@code null} lässt die Eigenschaft weg. */
        public Attachment shortFilename(String name) {
            this.shortFilename = name;
            return this;
        }

        /**
         * Lässt das Datenfeld (PidTagAttachDataBinary, 0x3701) weg, wie bei einem beschädigten Anhang; java-libpst
         * liefert dann keinen Strom. Ein leerer Anhang hat dagegen ein leeres Datenfeld.
         */
        public Attachment withoutDataField() {
            this.withoutDataField = true;
            return this;
        }

        /** Anhangsart (PidTagAttachMethod, 0x3705) statt 1 (Wert), etwa 4 für einen Verweis oder 6 für OLE. */
        public Attachment attachMethod(int method) {
            this.method = method;
            return this;
        }

        public int attachNumber() {
            return number;
        }

        private int size() {
            return data != null ? data.length : embedded.size();
        }
    }

    // ── Schreiben ────────────────────────────────────────────────────────────────────────────────────────────

    /** Knoten im NBT (mit Elternordner) oder Subknoten (parentNid 0); children = Subknoten. */
    private static final class Node {
        final int nid;
        final byte[] data;
        final List<Node> children;
        final int parentNid;

        Node(int nid, byte[] data, List<Node> children, int parentNid) {
            this.nid = nid;
            this.data = data;
            this.children = children;
            this.parentNid = parentNid;
        }
    }

    private final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final List<byte[]> bbtEntries = new ArrayList<byte[]>();
        private long nextBlockIndex = 1;
        private long nextPageIndex = 1;
        private int nextSubnodeIndex = FIRST_SUBNODE_INDEX;

        byte[] build() {
            out.write(new byte[FIRST_BLOCK_OFFSET], 0, FIRST_BLOCK_OFFSET);
            List<Node> nodes = new ArrayList<Node>();
            nodes.add(pcNode(NID_MESSAGE_STORE, new Pc().string(0x3001, "Testpostfach"), 0));
            nodes.add(pcNode(NID_NAME_TO_ID_MAP, nameToIdMap(), 0));
            addFolder(nodes, root);
            Collections.sort(nodes, BY_NID);

            List<byte[]> nbtEntries = new ArrayList<byte[]>();
            for (Node node : nodes) {
                byte[] entry = new byte[32]; // NBTENTRY: nid, bidData, bidSub, nidParent
                le(entry, 0, node.nid, 8);
                le(entry, 8, writeData(node.data), 8);
                le(entry, 16, writeSubnodes(node.children), 8);
                le(entry, 24, node.parentNid, 4);
                nbtEntries.add(entry);
            }
            long[] nbtRoot = writeBTree(nbtEntries, 32, 0x81);
            long[] bbtRoot = writeBTree(bbtEntries, 24, 0x80);

            byte[] file = out.toByteArray();
            file[0] = '!';
            file[1] = 'B';
            file[2] = 'D';
            file[3] = 'N';
            file[8] = 'S'; // wMagicClient
            file[9] = 'M';
            le(file, 10, 23, 2); // wVer: Unicode
            le(file, 12, 19, 2); // wVerClient
            file[14] = 1; // bPlatformCreate
            file[15] = 1; // bPlatformAccess
            le(file, 32, nextPageIndex << 2, 8); // bidNextP
            le(file, 184, file.length, 8); // ROOT.ibFileEof
            le(file, 216, nbtRoot[1], 8); // ROOT.BREFNBT
            le(file, 224, nbtRoot[2], 8);
            le(file, 232, bbtRoot[1], 8); // ROOT.BREFBBT
            le(file, 240, bbtRoot[2], 8);
            file[512] = (byte) 0x80; // bSentinel; bCryptMethod (513) bleibt 0 = unverschlüsselt
            le(file, 516, nextBlockIndex << 2, 8); // bidNextB
            return file;
        }

        private void addFolder(List<Node> nodes, Folder folder) {
            nodes.add(pcNode(folder.nid, new Pc()
                    .string(0x3001, folder.name)
                    .int32(0x3602, folder.messages.size())
                    .int32(0x3603, 0)
                    .bool(0x360A, !folder.folders.isEmpty()), folder.parentNid));
            List<Integer> folderIds = new ArrayList<Integer>();
            for (Folder child : folder.folders) {
                folderIds.add(child.nid);
            }
            List<Integer> messageIds = new ArrayList<Integer>();
            for (Message message : folder.messages) {
                messageIds.add(message.nid);
            }
            nodes.add(new Node(folder.nid + HIERARCHY_TABLE, tableContext(folderIds), NO_NODES, 0));
            nodes.add(new Node(folder.nid + CONTENTS_TABLE, tableContext(messageIds), NO_NODES, 0));
            for (Message message : folder.messages) {
                List<Node> subnodes = new ArrayList<Node>();
                byte[] pc = message(message, subnodes);
                nodes.add(new Node(message.nid, pc, subnodes, folder.nid));
            }
            for (Folder child : folder.folders) {
                addFolder(nodes, child);
            }
        }

        /** PC der Nachricht; Anhangstabelle, Anhänge und große Werte landen in {@code subnodes}. */
        private byte[] message(Message message, List<Node> subnodes) {
            if (!message.attachments.isEmpty()) {
                List<Integer> rowIds = new ArrayList<Integer>();
                for (Attachment attachment : message.attachments) {
                    int nid = newSubnodeNid(NID_TYPE_ATTACHMENT);
                    List<Node> attachmentSubnodes = new ArrayList<Node>();
                    Pc pc = new Pc().int32(0x0E20, attachment.size()).int32(0x0E21, attachment.number);
                    if (attachment.embedded != null) {
                        int embeddedNid = newSubnodeNid(NID_TYPE_NORMAL_MESSAGE);
                        List<Node> embeddedSubnodes = new ArrayList<Node>();
                        byte[] embeddedPc = message(attachment.embedded, embeddedSubnodes);
                        attachmentSubnodes.add(new Node(embeddedNid, embeddedPc, embeddedSubnodes, 0));
                        pc.string(0x3001, attachment.embedded.subject)
                                .object(0x3701, embeddedNid, embeddedPc.length)
                                .int32(0x3705, 5);
                    } else {
                        pc.string(0x3001, attachment.longFilename);
                        if (!attachment.withoutDataField) {
                            pc.binary(0x3701, attachment.data);
                        }
                        pc.string(0x3704, attachment.shortFilename)
                                .int32(0x3705, attachment.method)
                                .string(0x3707, attachment.longFilename)
                                .string(0x370E, attachment.mimeType);
                    }
                    subnodes.add(new Node(nid, pc.build(this, attachmentSubnodes), attachmentSubnodes, 0));
                    rowIds.add(nid);
                }
                subnodes.add(new Node(NID_ATTACHMENT_TABLE, tableContext(rowIds), NO_NODES, 0));
            }
            boolean flagged = !message.attachments.isEmpty() || message.flaggedWithoutTable;
            int flags = 0x01 | (flagged ? 0x10 : 0); // MSGFLAG_READ, MSGFLAG_HASATTACH
            return new Pc()
                    .string(0x001A, message.messageClass)
                    .string(0x0037, message.subject)
                    .string(0x0C1A, message.senderName)
                    .string(0x0C1F, message.senderEmail)
                    .string(0x0E04, message.displayTo)
                    .time(0x0E06, message.deliveredAt)
                    .int32(0x0E07, flags)
                    .int32(0x0E08, message.size())
                    .string(0x1000, message.body)
                    .time(0x3008, message.modifiedAt != null ? message.modifiedAt : message.deliveredAt)
                    .build(this, subnodes);
        }

        /**
         * java-libpst liest beim Öffnen die Streams 0x0002 (GUIDs), 0x0003 (NAMEID-Sätze) und 0x0004 (Namen) und lehnt
         * leere ab. Der eine Satz (PidLidReminderDelta in der ersten GUID des Streams) wird wegen der Schleife
         * {@code x + 8 < length} ohnehin übersprungen; es gibt also keine benannten Eigenschaften.
         */
        private Pc nameToIdMap() {
            byte[] psetidCommon = {0x08, 0x20, 0x06, 0, 0, 0, 0, 0, (byte) 0xC0, 0, 0, 0, 0, 0, 0, 0x46};
            byte[] entry = new byte[8];
            le(entry, 0, 0x8501, 4);
            le(entry, 4, 3 << 1, 2); // wGuid = 3 (erste GUID im Stream), N = 0 (numerisch); wPropIdx = 0
            return new Pc().binary(0x0002, psetidCommon).binary(0x0003, entry).binary(0x0004, new byte[4]);
        }

        private Node pcNode(int nid, Pc pc, int parentNid) {
            List<Node> subnodes = new ArrayList<Node>();
            byte[] data = pc.build(this, subnodes);
            return new Node(nid, data, subnodes, parentNid);
        }

        int newSubnodeNid(int type) {
            return (nextSubnodeIndex++ << 5) | type;
        }

        /** BID: Index * 4, Bit 1 markiert interne Blöcke (XBLOCK, SLBLOCK); Bit 0 bleibt frei. */
        private long writeBlock(byte[] data, boolean internal) {
            long bid = (nextBlockIndex++ << 2) | (internal ? 2 : 0);
            long ib = out.size();
            byte[] block = new byte[align(data.length + 16, 64)];
            System.arraycopy(data, 0, block, 0, data.length);
            int trailer = block.length - 16; // BLOCKTRAILER: cb, wSig, dwCRC, bid
            le(block, trailer, data.length, 2);
            le(block, trailer + 8, bid, 8);
            out.write(block, 0, block.length);
            byte[] entry = new byte[24]; // BBTENTRY: BREF (bid, ib), cb, cRef
            le(entry, 0, bid, 8);
            le(entry, 8, ib, 8);
            le(entry, 16, data.length, 2);
            le(entry, 18, 2, 2);
            bbtEntries.add(entry);
            return bid;
        }

        /** Ein Datenblock, ab 8177 Bytes ein XBLOCK (nie mit nur einem Eintrag: seek scheitert daran). */
        private long writeData(byte[] data) {
            if (data.length <= MAX_BLOCK_DATA) {
                return writeBlock(data, false);
            }
            int count = (data.length + MAX_BLOCK_DATA - 1) / MAX_BLOCK_DATA;
            byte[] xblock = new byte[8 + 8 * count];
            if (xblock.length > MAX_BLOCK_DATA) {
                throw new IllegalArgumentException("Wert zu groß für ein XBLOCK: " + data.length + " Bytes");
            }
            xblock[0] = 0x01; // btype XBLOCK
            xblock[1] = 0x01; // cLevel 1
            le(xblock, 2, count, 2);
            le(xblock, 4, data.length, 4); // lcbTotal
            for (int i = 0; i < count; i++) {
                int from = i * MAX_BLOCK_DATA;
                byte[] chunk = Arrays.copyOfRange(data, from, Math.min(data.length, from + MAX_BLOCK_DATA));
                le(xblock, 8 + 8 * i, writeBlock(chunk, false), 8);
            }
            return writeBlock(xblock, true);
        }

        /** SLBLOCK (cLevel 0) mit SLENTRY je Subknoten; 0, wenn keine Subknoten. */
        private long writeSubnodes(List<Node> nodes) {
            if (nodes.isEmpty()) {
                return 0;
            }
            List<Node> sorted = new ArrayList<Node>(nodes);
            Collections.sort(sorted, BY_NID);
            byte[] slblock = new byte[8 + 24 * sorted.size()];
            if (slblock.length > MAX_BLOCK_DATA) {
                throw new IllegalStateException("Zu viele Subknoten: " + sorted.size());
            }
            slblock[0] = 0x02; // btype SLBLOCK, cLevel 0
            le(slblock, 2, sorted.size(), 2);
            for (int i = 0; i < sorted.size(); i++) {
                Node node = sorted.get(i);
                int offset = 8 + 24 * i; // SLENTRY: nid, bidData, bidSub
                le(slblock, offset, node.nid, 8);
                le(slblock, offset + 8, writeData(node.data), 8);
                le(slblock, offset + 16, writeSubnodes(node.children), 8);
            }
            return writeBlock(slblock, true);
        }

        /** B-Baum aus sortierten Blatteinträgen; liefert {erster Schlüssel, bid, ib} der Wurzelseite. */
        private long[] writeBTree(List<byte[]> entries, int entrySize, int ptype) {
            int perLeaf = 488 / entrySize;
            List<long[]> level = new ArrayList<long[]>();
            int i = 0;
            do {
                level.add(writePage(entries.subList(i, Math.min(entries.size(), i + perLeaf)), entrySize, perLeaf, 0,
                        ptype));
                i += perLeaf;
            } while (i < entries.size());
            for (int cLevel = 1; level.size() > 1; cLevel++) {
                List<byte[]> branch = new ArrayList<byte[]>();
                for (long[] page : level) {
                    byte[] entry = new byte[24]; // BTENTRY: btkey, BREF (bid, ib)
                    le(entry, 0, page[0], 8);
                    le(entry, 8, page[1], 8);
                    le(entry, 16, page[2], 8);
                    branch.add(entry);
                }
                List<long[]> next = new ArrayList<long[]>();
                for (int j = 0; j < branch.size(); j += 20) {
                    next.add(writePage(branch.subList(j, Math.min(branch.size(), j + 20)), 24, 20, cLevel, ptype));
                }
                level = next;
            }
            return level.get(0);
        }

        private long[] writePage(List<byte[]> entries, int entrySize, int maxEntries, int cLevel, int ptype) {
            byte[] page = new byte[PAGE_SIZE];
            for (int i = 0; i < entries.size(); i++) {
                System.arraycopy(entries.get(i), 0, page, i * entrySize, entrySize);
            }
            page[488] = (byte) entries.size();
            page[489] = (byte) maxEntries;
            page[490] = (byte) entrySize;
            page[491] = (byte) cLevel;
            page[496] = (byte) ptype; // PAGETRAILER: ptype, ptypeRepeat, wSig, dwCRC, bid
            page[497] = (byte) ptype;
            long bid = nextPageIndex++ << 2;
            le(page, 504, bid, 8);
            int padding = align(out.size(), PAGE_SIZE) - out.size();
            out.write(new byte[padding], 0, padding);
            long ib = out.size();
            out.write(page, 0, page.length);
            long firstKey = entries.isEmpty() ? 0 : readLe(entries.get(0), 0, 8);
            return new long[] {firstKey, bid, ib};
        }
    }

    /** Ein Eigenschaftswert: direkt (int32, boolean) oder als Bytes (Heap oder Subknoten). */
    private static final class Prop {
        final int type;
        final int inline;
        final byte[] bytes;

        Prop(int type, int inline, byte[] bytes) {
            this.type = type;
            this.inline = inline;
            this.bytes = bytes;
        }
    }

    /** Property Context (BTH mit 2-Byte-Schlüssel und 6-Byte-Wert im Heap-on-Node, bClientSig 0xBC). */
    private static final class Pc {
        private final TreeMap<Integer, Prop> props = new TreeMap<Integer, Prop>();

        Pc int32(int id, int value) {
            props.put(id, new Prop(PT_INT32, value, null));
            return this;
        }

        Pc bool(int id, boolean value) {
            props.put(id, new Prop(PT_BOOLEAN, value ? 1 : 0, null));
            return this;
        }

        /** PtypString (UTF-16LE ohne Terminator); {@code null} lässt die Eigenschaft weg. */
        Pc string(int id, String value) {
            if (value != null) {
                props.put(id, new Prop(PT_STRING, 0, value.getBytes(StandardCharsets.UTF_16LE)));
            }
            return this;
        }

        Pc binary(int id, byte[] value) {
            props.put(id, new Prop(PT_BINARY, 0, value));
            return this;
        }

        Pc time(int id, Instant time) {
            byte[] filetime = new byte[8];
            le(filetime, 0, (time.toEpochMilli() + FILETIME_EPOCH_MILLIS) * 10000L, 8);
            props.put(id, new Prop(PT_TIME, 0, filetime));
            return this;
        }

        /** PtypObject: Heapwert aus Subknoten-NID und Größe. */
        Pc object(int id, int nid, int size) {
            byte[] value = new byte[8];
            le(value, 0, nid, 4);
            le(value, 4, size, 4);
            props.put(id, new Prop(PT_OBJECT, 0, value));
            return this;
        }

        byte[] build(Writer writer, List<Node> subnodes) {
            int count = props.size();
            Heap heap = new Heap();
            int header = heap.add(new byte[8]); // hid 0x20 = hidUserRoot
            int records = count == 0 ? 0 : heap.add(new byte[8 * count]); // hid 0x40
            // Platz im Block: HNHDR, BTH-Kopf, Sätze, HNPAGEMAP (Kopf, drei Offsets) und ein Byte Ausrichtung.
            int free = MAX_BLOCK_DATA - 12 - 8 - 8 * count - 4 - 6 - 1;
            int[] refs = new int[count];
            // Erst die festen 8-Byte-Werte (Zeit, Objekt), die immer in den Heap gehören, dann die variablen.
            for (int pass = 0; pass < 2; pass++) {
                int i = 0;
                for (Prop prop : props.values()) {
                    boolean fixed = prop.type == PT_TIME || prop.type == PT_OBJECT;
                    if (prop.bytes == null) {
                        refs[i] = prop.inline;
                    } else if (fixed == (pass == 0)) {
                        if (prop.bytes.length == 0) {
                            refs[i] = 0; // leerer Wert: hnid 0
                        } else if (fixed || (prop.bytes.length <= MAX_HEAP_ALLOC && prop.bytes.length + 2 <= free)) {
                            refs[i] = heap.add(prop.bytes);
                            free -= prop.bytes.length + 2;
                        } else {
                            int nid = writer.newSubnodeNid(NID_TYPE_LTP);
                            subnodes.add(new Node(nid, prop.bytes, NO_NODES, 0));
                            refs[i] = nid;
                        }
                    }
                    i++;
                }
            }
            byte[] recordBytes = new byte[8 * count];
            int i = 0;
            for (Map.Entry<Integer, Prop> entry : props.entrySet()) {
                le(recordBytes, 8 * i, entry.getKey(), 2); // wPropId
                le(recordBytes, 8 * i + 2, entry.getValue().type, 2); // wPropType
                le(recordBytes, 8 * i + 4, refs[i], 4); // dwValueHnid
                i++;
            }
            heap.set(header, bthHeader(2, 6, records));
            if (count > 0) {
                heap.set(records, recordBytes);
            }
            return heap.toBytes(0xBC, header);
        }
    }

    /**
     * Table Context mit den Spalten PidTagLtpRowId und PidTagLtpRowVer (je 4 Byte). java-libpst erwartet den
     * BTH-Kopf des Zeilenindex bei hid 0x20, TCINFO liegt daher bei 0x40 (= hidUserRoot).
     */
    private static byte[] tableContext(List<Integer> rowIds) {
        int rowCount = rowIds.size();
        Heap heap = new Heap();
        int header = heap.add(new byte[8]);
        byte[] info = new byte[22 + 2 * 8];
        int infoHid = heap.add(info);
        byte[] rows = new byte[9 * rowCount];
        byte[] index = new byte[8 * rowCount];
        List<Integer> sorted = new ArrayList<Integer>(rowIds);
        Collections.sort(sorted);
        for (int i = 0; i < rowCount; i++) {
            le(rows, 9 * i, rowIds.get(i), 4); // RowId; RowVer (ib 4) bleibt 0
            rows[9 * i + 8] = (byte) 0xFF; // CEB: beide Zellen vorhanden (unabhängig von der Bitreihenfolge)
            le(index, 8 * i, sorted.get(i), 4); // TCROWID: dwRowID, dwRowIndex
            le(index, 8 * i + 4, rowIds.indexOf(sorted.get(i)), 4);
        }
        int rowsHid = rowCount == 0 ? 0 : heap.add(rows);
        int indexHid = rowCount == 0 ? 0 : heap.add(index);
        heap.set(header, bthHeader(4, 4, indexHid));
        info[0] = 0x7C; // bType
        info[1] = 2; // cCols
        le(info, 2, 8, 2); // TCI_4b
        le(info, 4, 8, 2); // TCI_2b
        le(info, 6, 8, 2); // TCI_1b
        le(info, 8, 9, 2); // TCI_bm (Zeilenlänge inkl. CEB)
        le(info, 10, header, 4); // hidRowIndex
        le(info, 14, rowsHid, 4); // hnidRows
        column(info, 22, 0x67F2, 0, 0);
        column(info, 30, 0x67F3, 4, 1);
        return heap.toBytes(0x7C, infoHid);
    }

    private static void column(byte[] info, int offset, int propId, int ibData, int iBit) {
        le(info, offset, PT_INT32, 2); // TCOLDESC: tag (Typ, ID), ibData, cbData, iBit
        le(info, offset + 2, propId, 2);
        le(info, offset + 4, ibData, 2);
        info[offset + 6] = 4;
        info[offset + 7] = (byte) iBit;
    }

    private static byte[] bthHeader(int keySize, int valueSize, int hidRoot) {
        byte[] header = {(byte) 0xB5, (byte) keySize, (byte) valueSize, 0, 0, 0, 0, 0};
        le(header, 4, hidRoot, 4);
        return header;
    }

    /** Heap-on-Node in einem Block: HNHDR, Allokationen, HNPAGEMAP; hid = Index (ab 1) &lt;&lt; 5. */
    private static final class Heap {
        private final List<byte[]> allocations = new ArrayList<byte[]>();

        int add(byte[] data) {
            allocations.add(data);
            return allocations.size() << 5;
        }

        void set(int hid, byte[] data) {
            allocations.set((hid >> 5) - 1, data);
        }

        byte[] toBytes(int clientSig, int hidUserRoot) {
            int end = 12;
            for (byte[] allocation : allocations) {
                end += allocation.length;
            }
            int pageMap = align(end, 2);
            byte[] heap = new byte[pageMap + 4 + 2 * (allocations.size() + 1)];
            if (heap.length > MAX_BLOCK_DATA) {
                throw new IllegalStateException("Heap passt nicht in einen Block (" + heap.length
                        + " Bytes); zu viele Tabellenzeilen?");
            }
            le(heap, 0, pageMap, 2); // ibHnpm
            heap[2] = (byte) 0xEC; // bSig
            heap[3] = (byte) clientSig;
            le(heap, 4, hidUserRoot, 4);
            le(heap, pageMap, allocations.size(), 2); // cAlloc; cFree 0
            int position = 12;
            le(heap, pageMap + 4, position, 2);
            for (int i = 0; i < allocations.size(); i++) {
                byte[] allocation = allocations.get(i);
                System.arraycopy(allocation, 0, heap, position, allocation.length);
                position += allocation.length;
                le(heap, pageMap + 6 + 2 * i, position, 2);
            }
            return heap;
        }
    }

    private static long utf16Length(String value) {
        return value == null ? 0 : 2L * value.length();
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private static void le(byte[] target, int offset, long value, int length) {
        for (int i = 0; i < length; i++) {
            target[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    private static long readLe(byte[] source, int offset, int length) {
        long value = 0;
        for (int i = length - 1; i >= 0; i--) {
            value = (value << 8) | (source[offset + i] & 0xFF);
        }
        return value;
    }
}
