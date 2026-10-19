package com.aresstack.spaceharvester.application.facts;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Die Faktentypen, Rollen und Argumentrollen des Core. Namen wie im Enterprise AI Client, wo es dort dieselbe Sache
 * gibt ({@code ACRONYM_CANDIDATE}, {@code ACRONYM_DEFINITION} aus {@code AcronymFindings}, {@code MENTION} aus
 * {@code FactCatalogStatements}); {@code PROPERTY} ist eigen, weil der Client Eigenschaften nicht als eigene Fakten
 * führt.
 *
 * <table>
 *   <caption>Typen</caption>
 *   <tr><th>Typ</th><th>aus</th><th>Rolle</th><th>Argumente</th><th>Payload</th></tr>
 *   <tr><td>{@code ACRONYM_CANDIDATE}</td><td>{@code acronyms}</td><td>{@code MENTIONS}</td>
 *       <td>{@code SHORT_FORM}</td><td>{@code shortForm, count, expansion, section}</td></tr>
 *   <tr><td>{@code ACRONYM_DEFINITION}</td><td>{@code acronyms} mit Ausschreibung</td><td>{@code DEFINES}</td>
 *       <td>{@code SHORT_FORM, LONG_FORM}</td><td>{@code shortForm, longForm, section}</td></tr>
 *   <tr><td>{@code MENTION}</td><td>{@code entities}</td><td>{@code MENTIONS}</td><td>{@code TERM, KIND}</td>
 *       <td>{@code term, normalized, kind, count, section}</td></tr>
 *   <tr><td>{@code PROPERTY}</td><td>{@code properties}</td><td>—</td><td>{@code SUBJECT, VALUE, KIND, STATUS}</td>
 *       <td>{@code subject, property, normalized, kind, form, negated, section}</td></tr>
 * </table>
 *
 * <p>Argumentwerte sind {@link #normalize normalisiert}: Unicode NFKC, Kleinbuchstaben, Leerraum zusammengefasst.
 */
public final class FactTypes {

    public static final String ACRONYM_CANDIDATE = "ACRONYM_CANDIDATE";
    public static final String ACRONYM_DEFINITION = "ACRONYM_DEFINITION";
    public static final String MENTION = "MENTION";
    public static final String PROPERTY = "PROPERTY";

    /** Die Seite definiert, was der Fakt sagt (wie {@code FactRole.defines()} im Client). */
    public static final String DEFINES = "DEFINES";
    /** Die Seite erwähnt es nur. */
    public static final String MENTIONS = "MENTIONS";

    public static final String SHORT_FORM = "SHORT_FORM";
    public static final String LONG_FORM = "LONG_FORM";
    public static final String TERM = "TERM";
    public static final String KIND = "KIND";
    public static final String SUBJECT = "SUBJECT";
    public static final String VALUE = "VALUE";
    public static final String STATUS = "STATUS";

    /** Alle Typen. */
    public static final List<String> ALL = List.of(ACRONYM_CANDIDATE, ACRONYM_DEFINITION, MENTION, PROPERTY);

    private FactTypes() {
    }

    /** Suchwert eines Arguments. */
    public static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip()
                .replaceAll("\\s+", " ");
    }

    /** Beschreibung eines Typs, leer bei unbekanntem. */
    public static String describe(String type) {
        return switch (type) {
            case ACRONYM_CANDIDATE -> "Wie ein Akronym geschrieben; Bedeutung unbekannt, außer die Seite nennt eine "
                    + "Ausschreibung";
            case ACRONYM_DEFINITION -> "Ausschreibung eines Akronyms, wörtlich auf der Seite (Langform (KURZ)); gilt "
                    + "nur für diese Seite";
            case MENTION -> "Begriff (Kern einer Nominalgruppe) mit Häufigkeit";
            case PROPERTY -> "Eigenschaft eines Begriffs, wie der Satz sie nennt; gilt nur für ihren Satz";
            default -> "";
        };
    }
}
