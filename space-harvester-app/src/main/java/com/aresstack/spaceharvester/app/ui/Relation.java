package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.terms.TermRelation;
import java.util.Objects;

/**
 * Eine Beziehung zwischen zwei Begriffen, wie sie die Oberfläche zeigt (#120): Wortlaute, Art, Prüfstand und Herkunft
 * als Namen der Core-Aufzählungen ({@code RelationType}, {@code ReviewStatus}, {@code RelationOrigin}). Die App
 * leitet nichts ab; sie übersetzt nur in Worte.
 *
 * @param id       Kennung im Core, für Bestätigen, Ablehnen und Entfernen
 * @param revision Stand für das optimistische Sperren
 */
public record Relation(String id, String fromTerm, String type, String toTerm, String status, String origin,
                       long revision) {

    public Relation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fromTerm, "fromTerm");
        Objects.requireNonNull(toTerm, "toTerm");
    }

    /** Die Beziehung des Core in Worten der Oberfläche. */
    public static Relation of(TermRelation relation) {
        return new Relation(relation.id(), relation.fromTerm(), relation.type().name(), relation.toTerm(),
                relation.status().name(), relation.origin().name(), relation.revision());
    }

    /** „Abkürzung für“, „Synonym“, „Schreibvariante“, „Verwandt mit“; Unbekanntes bleibt, wie es kommt. */
    public static String typeText(String type) {
        return switch (String.valueOf(type)) {
            case "ABBREVIATION" -> "Abkürzung für";
            case "SYNONYM" -> "Synonym";
            case "VARIANT" -> "Schreibvariante";
            case "RELATED" -> "Verwandt mit";
            default -> String.valueOf(type);
        };
    }

    public static String statusText(String status) {
        return switch (String.valueOf(status)) {
            case "PROPOSED" -> "Vorgeschlagen";
            case "CONFIRMED" -> "Bestätigt";
            case "REJECTED" -> "Abgelehnt";
            default -> String.valueOf(status);
        };
    }

    public static String originText(String origin) {
        return switch (String.valueOf(origin)) {
            case "MANUAL" -> "Manuell";
            case "RULE" -> "Regel";
            case "AI" -> "KI";
            default -> String.valueOf(origin);
        };
    }

    /** „API · Abkürzung für · Application Programming Interface (Bestätigt, Regel)“ */
    public String line() {
        return fromTerm + " · " + typeText(type) + " · " + toTerm + " (" + statusText(status) + ", "
                + originText(origin) + ")";
    }
}
