package com.aresstack.spaceharvester.parity;

import java.util.List;
import java.util.Set;

/**
 * Was ein Export an Struktur trägt, unabhängig vom Format (ADR 0012, docs/PARITY.md, „Vergleichstests“).
 *
 * <p>Verglichen wird dieses Modell, nicht die Bytes: Zwei Exporte desselben Datenbestands sind paritätisch, wenn
 * Kapitel, Verzeichnis, Bilder, Tabellen, Links und Text übereinstimmen, auch wenn Layout und Dateiaufbau abweichen.</p>
 *
 * @param format             {@code docx}, {@code pdf} oder {@code md}
 * @param headings           Überschriften in Dokumentreihenfolge; bei PDF die Lesezeichen
 * @param tableOfContents    sichtbares Inhaltsverzeichnis (DOCX: TOC-Feld; PDF: Verzeichnisseite mit Sprüngen;
 *                           Markdown: Liste von Ankerlinks am Anfang)
 * @param updateFieldsOnOpen DOCX: Word aktualisiert Felder beim Öffnen ({@code w:updateFields})
 * @param pageNumbers        Seitenzahlen im Dokument (DOCX: PAGE-Feld; PDF: Zahl auf den meisten Seiten)
 * @param outlineEntries     PDF-Lesezeichen insgesamt
 * @param images             eingebettete Bilder (DOCX {@code w:drawing}, PDF-Bild-XObjects, Markdown {@code data:} oder
 *                           lokale Datei)
 * @param imageNotes         sichtbare Hinweise auf nicht eingebettete Bilder ({@code [Bild: …]},
 *                           „Abbildung nicht verfügbar“, {@code attachment:}-Verweise)
 * @param tables             Tabellen; {@code null}, wenn das Format sie nicht erkennbar macht (PDF)
 * @param externalLinks      Ziele externer Links ({@code http}, {@code https}, {@code mailto})
 * @param internalLinks      Sprünge innerhalb des Dokuments
 * @param mermaidSources     Mermaid-Quelltexte im Dokument
 * @param text               Fließtext, Absätze durch Zeilenumbruch getrennt
 */
public record DocumentOutline(String format, List<Heading> headings, boolean tableOfContents,
                              boolean updateFieldsOnOpen, boolean pageNumbers, int outlineEntries, int images,
                              int imageNotes, List<Table> tables, Set<String> externalLinks, int internalLinks,
                              int mermaidSources, String text) {

    public DocumentOutline {
        headings = List.copyOf(headings);
        tables = tables == null ? null : List.copyOf(tables);
        externalLinks = Set.copyOf(externalLinks);
    }

    /** Eine Überschrift; Ebene 1 ist die oberste. Markdown-Fett-Zeilen jenseits von Ebene 6 zählen als Ebene 7. */
    public record Heading(int level, String text) {
    }

    /** Eine Tabelle mit Zeilen, größter Spaltenzahl und ob die erste Zeile als Kopfzeile ausgezeichnet ist. */
    public record Table(int rows, int columns, boolean headerRow) {
    }
}
