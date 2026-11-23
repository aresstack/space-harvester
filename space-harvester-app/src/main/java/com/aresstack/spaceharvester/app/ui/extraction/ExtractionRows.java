package com.aresstack.spaceharvester.app.ui.extraction;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.FieldStatus;
import com.aresstack.spaceharvester.application.extraction.SourceReference;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Wie ein Analyseergebnis in der Extraktionsansicht erscheint: Zeilen (Abschnittsüberschrift oder Feld), Wörter und
 * Farben je Stand. Ohne Swing-Zustand, damit es sich ohne Fenster prüfen lässt. Farben stehen immer mit ihrem Wort.
 */
final class ExtractionRows {

    static final String NOT_FOUND_VALUE = "— nicht gefunden —";

    private ExtractionRows() {
    }

    /** Eine Zeile der Tabelle: eine Überschrift ({@code field == null}) oder ein Feld. */
    record Row(String heading, ExtractedField field) {

        boolean isHeading() {
            return field == null;
        }
    }

    /** Je Abschnitt eine Überschrift, darunter seine Felder in Lieferreihenfolge; widersprüchliche bleiben getrennt. */
    static List<Row> rows(DocumentAnalysis analysis) {
        List<Row> rows = new ArrayList<>();
        if (analysis == null) {
            return rows;
        }
        for (AnalysisSection section : analysis.sections()) {
            rows.add(new Row(section.heading(), null));
            for (ExtractedField field : section.fields()) {
                rows.add(new Row(section.heading(), field));
            }
        }
        return rows;
    }

    /** Der Stand als Wort (UX: ausdrücklich, abgeleitet, nicht gefunden, Widerspruch). */
    static String word(FieldStatus status) {
        return switch (status) {
            case EXPLICIT -> "ausdrücklich";
            case DERIVED -> "abgeleitet";
            case NOT_FOUND -> "nicht gefunden";
            case CONFLICT -> "Widerspruch";
        };
    }

    /** Die Farbe zum Wort: grün, gelb, grau; Rot nur für Widerspruch. */
    static Color tone(FieldStatus status) {
        return switch (status) {
            case EXPLICIT -> Comic.STANDING_CURRENT;
            case DERIVED -> Comic.STANDING_UNCLEAR;
            case NOT_FOUND -> Comic.STANDING_OUTDATED;
            case CONFLICT -> Comic.RED;
        };
    }

    /** Feldname, mit Erläuterung in Klammern, etwa „Fristverlängerung (beantragt)“. */
    static String label(ExtractedField field) {
        return field.note().isEmpty() ? field.label() : field.label() + " (" + field.note() + ")";
    }

    /** Der Wert oder die ausdrückliche Leermeldung. */
    static String value(ExtractedField field) {
        return field.value().isEmpty() ? NOT_FOUND_VALUE : field.value();
    }

    /** Die Fundstelle kurz: Abschnitt, Seite, Absatz, sonst der Anfang des Auszugs; leer ohne. */
    static String source(SourceReference source) {
        List<String> parts = new ArrayList<>();
        if (!source.section().isEmpty()) {
            parts.add(source.section());
        }
        if (source.page() > 0) {
            parts.add("S. " + source.page());
        }
        if (source.paragraph() > 0) {
            parts.add("Abs. " + source.paragraph());
        }
        if (parts.isEmpty() && !source.excerpt().isEmpty()) {
            parts.add("„" + shorten(source.excerpt(), 60) + "“");
        }
        return String.join(" · ", parts);
    }

    /** Herkunft und Prüfung für den Tooltip des Stands. */
    static String origin(ExtractedField field) {
        String origin = switch (field.origin()) {
            case REGEX -> "Muster im Text";
            case AI -> "KI";
            case DERIVED -> "abgeleitet aus anderen Angaben";
        };
        return "Herkunft: " + origin + (field.verified() ? " · Fundstelle geprüft" : " · unbestätigt");
    }

    /** Der Stand des Dokuments für die Statuszeile, mit Zählern bei fertiger Analyse. */
    static String status(DocumentAnalysis analysis) {
        if (analysis == null) {
            return "Kein Dokument ausgewählt";
        }
        return switch (analysis.state()) {
            case NOT_ANALYZED -> "Noch nicht analysiert";
            case IN_PROGRESS -> "Analyse läuft …";
            case FAILED -> "Analyse fehlgeschlagen" + (analysis.failure().isEmpty() ? "" : ": " + analysis.failure());
            case COMPLETED -> analysis.fieldCount() == 0 ? "Analysiert: keine Informationen gefunden"
                    : "Analysiert: " + analysis.fieldCount() + " Informationen, davon "
                    + conflicts(analysis.count(FieldStatus.CONFLICT));
        };
    }

    private static String conflicts(long count) {
        return count + (count == 1 ? " Widerspruch" : " Widersprüche");
    }

    private static String shorten(String text, int max) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
