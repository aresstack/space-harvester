package com.aresstack.spaceharvester.testcorpus;

import com.aresstack.spaceharvester.source.DocumentFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die benannten Fälle des Testkorpus (ADR 0026, Abnahme 3 bis 7 und 11), gebaut aus {@link Mail}, {@link Archives},
 * {@link OutlookMessage}, {@link DamagedFiles} und {@link LargeFiles}. Jeder lesbare Teil trägt eine eigene Marke
 * ({@code MARKE-…}), an der ein Test erkennt, ob und wo sein Text ankommt. Die Fälle entsprechen den Testdateien der
 * Messungen unter {@code anhaenge-analyse} ({@code samples-mail}, {@code samples-id}), sind aber selbst erzeugt.
 */
public final class Corpus {

    public static final String TEXT = "text/plain";
    public static final String MAIL_BODY_MARK = "MARKE-MAILTEXT";

    private Corpus() {
    }

    /** Ein Anhang: Dateiname, Medientyp, Inhalt. */
    public record Attachment(String fileName, String mediaType, byte[] content) {
    }

    /**
     * Doppelte Namen (Abnahme 3): zwei {@code notiz.txt} mit verschiedenem Inhalt, ein Name mit Pfadzeichen
     * ({@code ordner/unter.txt}, dazu {@code ../flucht.txt}) und derselbe Inhalt unter zwei Namen
     * ({@code kopie-a.txt}, {@code kopie-b.txt}).
     */
    public static List<Attachment> duplicateNameAttachments() {
        return List.of(
                text("notiz.txt", "MARKE-NOTIZ-1 Wartungsfenster Dienstag."),
                text("notiz.txt", "MARKE-NOTIZ-2 Wartungsfenster Freitag."),
                text("ordner/unter.txt", "MARKE-PFAD Datei mit Schrägstrich im Namen."),
                text("../flucht.txt", "MARKE-FLUCHT Datei mit Elternverweis im Namen."),
                text("kopie-a.txt", "MARKE-KOPIE Gleicher Inhalt in zwei Dateien."),
                text("kopie-b.txt", "MARKE-KOPIE Gleicher Inhalt in zwei Dateien."));
    }

    /** Mail mit {@link #duplicateNameAttachments()}, auf Wunsch in vertauschter Reihenfolge (Abnahme 3). */
    public static Mail duplicateNames(boolean reversed) {
        List<Attachment> attachments = new ArrayList<>(duplicateNameAttachments());
        if (reversed) {
            Collections.reverse(attachments);
        }
        return withAttachments(Mail.of("Doppelte Namen", MAIL_BODY_MARK + " mit doppelten Anhangsnamen."),
                attachments);
    }

    /** ZIP in Mail (Abnahme 11): {@code unterlagen.zip} mit {@code hinweis.txt} und {@code bericht.docx}. */
    public static Mail zipInMail() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("hinweis.txt", Archives.text("MARKE-ZIP-TXT Der Nachtlauf startet um 22:00 Uhr."));
        entries.put("bericht.docx", DocumentFixtures.docx("MARKE-ZIP-DOCX Bericht zum Nachtlauf"));
        byte[] zip = Archives.zip(entries);
        return Mail.of("Unterlagen zum Nachtlauf", MAIL_BODY_MARK + " Anbei die Unterlagen als ZIP.")
                .attach("unterlagen.zip", Archives.ZIP, zip);
    }

    /** Mail in Mail (Abnahme 11): eine weitergeleitete Mail ohne Dateinamen, darin RTF und Excel. */
    public static Mail mailInMail() {
        Mail inner = Mail.of("Protokoll der Lenkungsrunde", "MARKE-INNEN Protokoll und Kennzahlen im Anhang.")
                .attach("protokoll.rtf", DocumentFixtures.RTF, DocumentFixtures.rtf("MARKE-INNEN-RTF Protokoll"))
                .attach("kennzahlen.xlsx", DocumentFixtures.XLSX, DocumentFixtures.xlsx("MARKE-INNEN-XLSX 4711"));
        return Mail.of("WG: Protokoll der Lenkungsrunde", MAIL_BODY_MARK + " Zur Kenntnis, siehe weitergeleitete Mail.")
                .attach(inner);
    }

    /** Outlook-Nachricht mit Word- und Textanhang, als Datei an einer Mail (Abnahme 11). */
    public static OutlookMessage outlookMessage() {
        return OutlookMessage.of("Abstimmung Wartung", "MARKE-MSG-TEXT Bitte die Anlagen prüfen.")
                .attach("anlage.docx", DocumentFixtures.DOCX, DocumentFixtures.docx("MARKE-MSG-DOCX Anlage"))
                .attach("notiz.txt", TEXT, Archives.text("MARKE-MSG-TXT Notiz"));
    }

    /** Outlook-Mail in Mail (Abnahme 11): {@link #outlookMessage()} als {@code weiter.msg}. */
    public static Mail outlookMailInMail() {
        return Mail.of("WG: Abstimmung Wartung", MAIL_BODY_MARK + " Outlook-Mail im Anhang.")
                .attach("weiter.msg", Mail.OUTLOOK, outlookMessage().bytes());
    }

    /**
     * Defekte, leere und verschlüsselte Teile (Abnahme 7) neben einem gesunden ({@code liesmich.txt} mit
     * {@code MARKE-GESUND}): gekürztes Word, PDF ohne Sinn, leere Datei, verschlüsseltes PDF, verschlüsseltes Word und
     * ein ZIP mit einem verschlüsselten Eintrag.
     */
    public static List<Attachment> damagedAttachments() {
        byte[] docx = DocumentFixtures.docx("MARKE-DEFEKT-DOCX darf nicht erscheinen");
        return List.of(
                new Attachment("kaputt.docx", DocumentFixtures.DOCX, DamagedFiles.truncated(docx)),
                new Attachment("kaputt.pdf", DocumentFixtures.PDF, DamagedFiles.garbagePdf()),
                new Attachment("leer.pdf", DocumentFixtures.PDF, DamagedFiles.empty()),
                new Attachment("geheim.pdf", DocumentFixtures.PDF,
                        DamagedFiles.encryptedPdf(DocumentFixtures.pdf("MARKE-GEHEIM-PDF"))),
                new Attachment("geheim.docx", DocumentFixtures.DOCX,
                        DamagedFiles.encryptedOffice(DocumentFixtures.docx("MARKE-GEHEIM-DOCX"))),
                new Attachment("teils-geheim.zip", Archives.ZIP, Archives.partlyEncrypted()),
                text("liesmich.txt", "MARKE-GESUND Dieser Anhang ist in Ordnung."));
    }

    /** Mail mit {@link #damagedAttachments()}. */
    public static Mail damaged() {
        return withAttachments(Mail.of("Defekte Anhänge", MAIL_BODY_MARK + " Einige Anhänge sind beschädigt."),
                damagedAttachments());
    }

    /**
     * Lange Teile (Messung „gemeinsames Budget“): Mailtext und {@code parts} Textanhänge {@code lang-<n>.txt} zu je
     * rund {@code chars} Zeichen; jeder Teil endet mit {@code ENDE-MAIL} bzw. {@code ENDE-ANHANG-<n>}.
     */
    public static Mail longParts(int parts, int chars) {
        Mail mail = Mail.of("Lange Teile", filler("Langer Text der Mail. ", chars) + "\nENDE-MAIL");
        for (int i = 0; i < parts; i++) {
            mail.attach("lang-" + i + ".txt", TEXT,
                    (filler("Anhang " + i + " Zeile mit Inhalt. ", chars) + "\nENDE-ANHANG-" + i + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        }
        return mail;
    }

    /** Mail mit {@code count} kleinen Textanhängen {@code anhang-<n>.txt} (Grenze der Anzahl in einer Mail). */
    public static Mail manyAttachments(int count) {
        Mail mail = Mail.of("Viele Anhänge", MAIL_BODY_MARK + " mit vielen Anhängen.");
        for (int i = 0; i < count; i++) {
            mail.attach(String.format("anhang-%04d.txt", i), TEXT, Archives.text(Archives.fileMark(i)));
        }
        return mail;
    }

    /**
     * Mail in Mail in … ({@code depth} Mails ineinander, 1 = eine Mail ohne angehängte). Die Mail der Ebene {@code n}
     * trägt {@link Archives#levelMark} im Text (Grenze der Tiefe).
     */
    public static Mail nestedMails(int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("Tiefe muss positiv sein");
        }
        Mail mail = Mail.of("Ebene " + depth, Archives.levelMark(depth));
        for (int level = depth - 1; level >= 1; level--) {
            mail = Mail.of("Ebene " + level, Archives.levelMark(level)).attach(mail);
        }
        return mail;
    }

    /**
     * Stark gepacktes Archiv in einer Mail (Abnahme 5): {@code gepackt.zip} aus {@link Archives#packed(Path, long)} mit
     * {@code unpackedBytes}, danach {@code danach.txt} mit {@code MARKE-NACH-ARCHIV}. Die Messung nahm 150 MiB.
     */
    public static Path packedArchiveMail(Path target, long unpackedBytes) {
        try {
            Path zip = Files.createTempFile(target.toAbsolutePath().getParent(), "korpus-gepackt", ".zip");
            try {
                Archives.packed(zip, unpackedBytes);
                return Mail.of("Stark gepacktes Archiv", MAIL_BODY_MARK + " vor dem Archiv.")
                        .attach("gepackt.zip", Archives.ZIP, zip)
                        .attach("danach.txt", TEXT, Archives.text("MARKE-NACH-ARCHIV Kleiner Anhang."))
                        .write(target);
            } finally {
                Files.deleteIfExists(zip);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Mail mit einem großen Anhang aus einer Datei (Abnahme 5, mit {@link LargeFiles#pdfWithImages} und
     * {@link LargeFiles#PDF_OVER_300_MIB_PAGES}), danach {@code danach.txt} mit {@code MARKE-NACH-GROSS}. Der Anhang
     * wird beim Schreiben fortlaufend kodiert.
     */
    public static Path largeAttachmentMail(Path target, Path attachment, String fileName, String mediaType) {
        return Mail.of("Großer Anhang", MAIL_BODY_MARK + " Anbei die Unterlage.")
                .attach(fileName, mediaType, attachment)
                .attach("danach.txt", TEXT, Archives.text("MARKE-NACH-GROSS Kleiner Anhang."))
                .write(target);
    }

    private static Mail withAttachments(Mail mail, List<Attachment> attachments) {
        for (Attachment attachment : attachments) {
            mail.attach(attachment.fileName(), attachment.mediaType(), attachment.content());
        }
        return mail;
    }

    private static Attachment text(String fileName, String text) {
        return new Attachment(fileName, TEXT, Archives.text(text));
    }

    private static String filler(String phrase, int chars) {
        StringBuilder text = new StringBuilder(chars + phrase.length());
        while (text.length() < chars) {
            text.append(phrase);
        }
        return text.toString();
    }
}
