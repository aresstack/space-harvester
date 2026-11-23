package com.aresstack.spaceharvester.application.extraction;

import java.util.Objects;

/**
 * Eine extrahierte Information. Mehrere Felder gleichen Schlüssels sind erlaubt und bleiben getrennt (etwa eine
 * beantragte und eine genehmigte Fristverlängerung oder zwei widersprüchliche Aussagen).
 *
 * @param key       stabiler fachlicher Schlüssel, etwa {@code bewirtschafternummer}
 * @param label     Feldname, wie er angezeigt wird; leer: der Schlüssel
 * @param value     normalisierter Wert; leer bei {@link FieldStatus#NOT_FOUND}
 * @param rawValue  Wert in der Schreibweise des Dokuments; leer: wie {@code value}
 * @param status    Aussagequalität
 * @param origin    Herkunft des Werts
 * @param processor Stufe samt Version, die das Feld geliefert hat; {@link Processor#UNKNOWN} ohne
 * @param verified  ob die Fundstelle gegen den Originaltext geprüft wurde; ungeprüfte Werte gelten als unbestätigt
 * @param source    Fundstelle, {@link SourceReference#NONE} ohne
 * @param qualifier Art der Aussage (beantragt, genehmigt); trennt Felder gleichen Schlüssels
 * @param group     verbindet Felder einer Aussage innerhalb eines Sachverhalts, etwa {@code frist-2} für Wortlaut,
 *                  Beginn, Dauer, Bezug und Bedingung derselben Fristaussage; leer ohne
 * @param note      freie Erläuterung nur zur Anzeige, nie zum Zusammenführen; leer ohne
 */
public record ExtractedField(String key, String label, String value, String rawValue, FieldStatus status,
                             ExtractionOrigin origin, Processor processor, boolean verified, SourceReference source,
                             Qualifier qualifier, String group, String note) {

    public ExtractedField {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(origin, "origin");
        label = label == null || label.isBlank() ? key : label;
        value = value == null ? "" : value;
        rawValue = rawValue == null || rawValue.isEmpty() ? value : rawValue;
        processor = processor == null ? Processor.UNKNOWN : processor;
        source = source == null ? SourceReference.NONE : source;
        qualifier = qualifier == null ? Qualifier.NONE : qualifier;
        group = group == null ? "" : group;
        note = note == null ? "" : note;
    }

    /** Kurzform: Anzeige gleich Schlüssel, Rohwert gleich Wert, sonst ohne Angaben. */
    public static ExtractedField of(String label, String value, FieldStatus status, ExtractionOrigin origin) {
        return new ExtractedField(label, label, value, value, status, origin, Processor.UNKNOWN, false,
                SourceReference.NONE, Qualifier.NONE, "", "");
    }

    /** Dasselbe Feld mit anderem Stand. */
    public ExtractedField withStatus(FieldStatus other) {
        return new ExtractedField(key, label, value, rawValue, other, origin, processor, verified, source, qualifier,
                group, note);
    }

    /** Dasselbe Feld mit Fundstelle. */
    public ExtractedField withSource(SourceReference other) {
        return new ExtractedField(key, label, value, rawValue, status, origin, processor, verified, other, qualifier,
                group, note);
    }

    /** Dasselbe Feld von dieser Stufe. */
    public ExtractedField from(Processor other) {
        return new ExtractedField(key, label, value, rawValue, status, origin, other, verified, source, qualifier,
                group, note);
    }

    /** Dasselbe Feld mit Art der Aussage. */
    public ExtractedField qualified(Qualifier other) {
        return new ExtractedField(key, label, value, rawValue, status, origin, processor, verified, source, other,
                group, note);
    }

    /** Dasselbe Feld in einer Aussagegruppe. */
    public ExtractedField inGroup(String other) {
        return new ExtractedField(key, label, value, rawValue, status, origin, processor, verified, source, qualifier,
                other, note);
    }

    /** Dasselbe Feld mit Erläuterung zur Anzeige. */
    public ExtractedField withNote(String other) {
        return new ExtractedField(key, label, value, rawValue, status, origin, processor, verified, source, qualifier,
                group, other);
    }
}
