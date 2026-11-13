package com.aresstack.spaceharvester.testcorpus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Eine kleine Outlook-Nachricht ({@code .msg}, [MS-OXMSG]) für den Testkorpus (ADR 0026, Abnahme 11: „Outlook-Mail in
 * Mail“), zur Laufzeit als OLE2-Behälter über POIFS gebaut statt als fremde Testdatei eingecheckt. Sie trägt Betreff,
 * Absender, Text und beliebig viele Dateianhänge ({@code __attach_version1.0_#n}, Anhangsmethode „by value“), genug für
 * Tikas {@code OutlookExtractor}. Eine Postfachdatei ({@code .pst}) lässt sich so nicht bauen; die braucht Paket OL2.
 */
public final class OutlookMessage {

    private static final int PT_LONG = 0x0003;
    private static final int PT_UNICODE = 0x001F;
    private static final int PT_BINARY = 0x0102;

    private final String subject;
    private final String body;
    private final List<String[]> names = new ArrayList<>();
    private final List<byte[]> contents = new ArrayList<>();

    private OutlookMessage(String subject, String body) {
        this.subject = subject;
        this.body = body;
    }

    public static OutlookMessage of(String subject, String body) {
        return new OutlookMessage(subject, body);
    }

    /** Hängt eine Datei an; {@code mediaType} geht als MIME-Tag in den Anhang. */
    public OutlookMessage attach(String fileName, String mediaType, byte[] content) {
        names.add(new String[] {fileName, mediaType});
        contents.add(content.clone());
        return this;
    }

    public byte[] bytes() {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DirectoryEntry root = fs.getRoot();
            string(root, 0x001A, "IPM.Note");
            string(root, 0x0037, subject);
            string(root, 0x0E1D, subject);
            string(root, 0x0C1A, "Absender Test");
            string(root, 0x0C1F, "absender@example.org");
            string(root, 0x0E04, "Empfaenger Test");
            string(root, 0x1000, body);
            root.createDocument("__properties_version1.0", new ByteArrayInputStream(messageProperties()));
            for (int i = 0; i < contents.size(); i++) {
                DirectoryEntry attachment = root.createDirectory(String.format("__attach_version1.0_#%08X", i));
                string(attachment, 0x3704, shortName(names.get(i)[0]));
                string(attachment, 0x3707, names.get(i)[0]);
                string(attachment, 0x370E, names.get(i)[1]);
                attachment.createDocument(chunk(0x3701, PT_BINARY), new ByteArrayInputStream(contents.get(i)));
                attachment.createDocument("__properties_version1.0",
                        new ByteArrayInputStream(attachmentProperties(i)));
            }
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Kopf der obersten Ebene (32 Bytes): Zähler für Empfänger und Anhänge, danach keine festen Eigenschaften. */
    private byte[] messageProperties() {
        ByteBuffer header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        header.putLong(0);
        header.putInt(0);
        header.putInt(contents.size());
        header.putInt(0);
        header.putInt(contents.size());
        header.putLong(0);
        return header.array();
    }

    /** Kopf eines Anhangs (8 Bytes) und die Eigenschaften Anhangsmethode (1 = by value) und Nummer. */
    private static byte[] attachmentProperties(int number) {
        ByteBuffer properties = ByteBuffer.allocate(8 + 2 * 16).order(ByteOrder.LITTLE_ENDIAN);
        properties.putLong(0);
        fixed(properties, 0x3705, 1);
        fixed(properties, 0x0E21, number);
        return properties.array();
    }

    private static void fixed(ByteBuffer properties, int id, int value) {
        properties.putInt(id << 16 | PT_LONG);
        properties.putInt(0x6);
        properties.putInt(value);
        properties.putInt(0);
    }

    private static void string(DirectoryEntry directory, int id, String value) throws IOException {
        directory.createDocument(chunk(id, PT_UNICODE),
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
    }

    private static String chunk(int id, int type) {
        return String.format("__substg1.0_%04X%04X", id, type);
    }

    private static String shortName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = (dot > 0 ? fileName.substring(0, dot) : fileName).replaceAll("[^A-Za-z0-9]", "");
        String extension = dot > 0 ? fileName.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "") : "";
        stem = stem.isEmpty() ? "ANHANG" : stem.substring(0, Math.min(8, stem.length()));
        return (stem + (extension.isEmpty() ? "" : "." + extension.substring(0, Math.min(3, extension.length()))))
                .toUpperCase(Locale.ROOT);
    }
}
