package com.aresstack.spaceharvester.deck.eakte.http;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Eine erfundene Outlook-Nachricht ({@code .msg}, OLE2 wie von Outlook) mit einem PDF als Anlage, wie eine in der
 * E-Akte archivierte E-Mail, in der der Erlass steckt. Gebaut mit dem POIFS von Apache POI aus den MAPI-Strömen,
 * die Outlook schreibt (Betreff, Absender, Empfänger, Text, Anlage mit Name, Medientyp und Inhalt). Alle Inhalte sind
 * erfunden.
 */
public final class FakeOutlookMessage {

    /** Betreff der Nachricht. */
    public static final String SUBJECT = "Erlass Frachtraum – Zuweisung";
    /** Absender (Anzeigename). */
    public static final String FROM = "Kapitänin Avenger";
    /** Empfänger (Anzeigename). */
    public static final String TO = "Ladungsoffizier";
    /** Text der Nachricht. */
    public static final String BODY = "Guten Tag,\r\n\r\nanbei der Erlass zur Zuweisung von Frachtraum 7.\r\n";
    /** Name der PDF-Anlage. */
    public static final String ATTACHMENT = "Erlass Frachtraum 7.pdf";
    /** Satz auf der einzigen Seite des PDF. */
    public static final String PDF_TEXT = "Erlass: Frachtraum 7 bis 31.12.2026 zuweisen";
    /** Das PDF der Anlage (eine Seite, gültige Querverweistabelle). */
    public static final byte[] PDF = pdf(PDF_TEXT);

    private FakeOutlookMessage() {
    }

    /**
     * Die Nachricht; {@code padding} Bytes erfundener Daten kommen als zweite Anlage „Messwerte.bin“ dazu (große
     * Dateien, die gestreamt werden müssen).
     */
    public static byte[] build(int padding) {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            string(fs.getRoot(), "0037", SUBJECT);
            string(fs.getRoot(), "0C1A", FROM);
            string(fs.getRoot(), "0E04", TO);
            string(fs.getRoot(), "1000", BODY);
            attachment(fs.getRoot(), 0, ATTACHMENT, "application/pdf", PDF);
            if (padding > 0) {
                byte[] noise = new byte[padding];
                long seed = 4711;
                for (int i = 0; i < noise.length; i++) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    noise[i] = (byte) (seed >>> 56);
                }
                attachment(fs.getRoot(), 1, "Messwerte.bin", "application/octet-stream", noise);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void attachment(DirectoryEntry root, int number, String name, String mediaType, byte[] data)
            throws IOException {
        DirectoryEntry attachment = root.createDirectory(String.format("__attach_version1.0_#%08X", number));
        string(attachment, "3707", name);
        string(attachment, "3704", name);
        string(attachment, "370E", mediaType);
        attachment.createDocument("__substg1.0_37010102", new ByteArrayInputStream(data));
    }

    /** Ein Unicode-Textstrom (Typ 001F) der Eigenschaft {@code id}. */
    private static void string(DirectoryEntry directory, String id, String value) throws IOException {
        directory.createDocument("__substg1.0_" + id + "001F",
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
    }

    /** Ein kleines, gültiges PDF mit einer Seite und {@code text} darauf. */
    static byte[] pdf(String text) {
        String stream = "BT /F1 18 Tf 72 720 Td (" + text.replace("\\", "\\\\").replace("(", "\\(")
                .replace(")", "\\)") + ") Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
