package com.aresstack.spaceharvester.application.extraction;

import java.util.Objects;

/**
 * Eine extrahierte Information. Mehrere Felder gleichen Schlüssels sind erlaubt und bleiben getrennt (etwa eine
 * beantragte und eine genehmigte Fristverlängerung oder zwei widersprüchliche Aussagen).
 *
 * @param key      stabiler fachlicher Schlüssel, etwa {@code bewirtschafternummer}
 * @param label    Feldname, wie er angezeigt wird; leer: der Schlüssel
 * @param value    normalisierter Wert; leer bei {@link FieldStatus#NOT_FOUND}
 * @param rawValue Wert in der Schreibweise des Dokuments; leer: wie {@code value}
 * @param status   Aussagequalität
 * @param origin   Herkunft des Werts
 * @param verified ob die Fundstelle gegen den Originaltext geprüft wurde; ungeprüfte Werte gelten als unbestätigt
 * @param source   Fundstelle, {@link SourceReference#NONE} ohne
 * @param note     kurze Erläuterung, etwa „beantragt“ oder „genehmigt“; leer ohne
 * @param group    verbindet Felder einer Aussage innerhalb eines Sachverhalts, etwa {@code frist-2} für Wortlaut,
 *                 Beginn, Dauer, Bezug und Bedingung derselben Fristaussage; leer ohne
 */
public record ExtractedField(String key, String label, String value, String rawValue, FieldStatus status,
                             ExtractionOrigin origin, boolean verified, SourceReference source, String note,
                             String group) {

    public ExtractedField {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(origin, "origin");
        label = label == null || label.isBlank() ? key : label;
        value = value == null ? "" : value;
        rawValue = rawValue == null || rawValue.isEmpty() ? value : rawValue;
        source = source == null ? SourceReference.NONE : source;
        note = note == null ? "" : note;
        group = group == null ? "" : group;
    }

    /** Kurzform: Anzeige gleich Schlüssel, Rohwert gleich Wert, ohne Fundstelle und Erläuterung. */
    public static ExtractedField of(String label, String value, FieldStatus status, ExtractionOrigin origin) {
        return new ExtractedField(label, label, value, value, status, origin, false, SourceReference.NONE, "", "");
    }

    /** Dasselbe Feld mit anderem Stand. */
    public ExtractedField withStatus(FieldStatus other) {
        return new ExtractedField(key, label, value, rawValue, other, origin, verified, source, note, group);
    }

    /** Dasselbe Feld mit Fundstelle. */
    public ExtractedField withSource(SourceReference other) {
        return new ExtractedField(key, label, value, rawValue, status, origin, verified, other, note, group);
    }

    /** Dasselbe Feld in einer Aussagegruppe. */
    public ExtractedField inGroup(String other) {
        return new ExtractedField(key, label, value, rawValue, status, origin, verified, source, note, other);
    }

    /** Dasselbe Feld mit Erläuterung. */
    public ExtractedField withNote(String other) {
        return new ExtractedField(key, label, value, rawValue, status, origin, verified, source, other, group);
    }
}
