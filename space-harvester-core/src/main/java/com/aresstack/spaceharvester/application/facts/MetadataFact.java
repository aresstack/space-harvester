package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ein strukturierter Fakt zu einer Seite (wie {@code MetadataFact} im Enterprise AI Client): Kennung, Seite, Typ,
 * optionale Rolle, JSON-Payload, die suchbaren {@link Argument}e, Herkunft und Fundstelle.
 *
 * @param id         stabile Kennung aus Seite, Prozessor und Position im Ergebnis; verrät die Seite nicht
 * @param page       die Seite
 * @param type       {@link FactTypes}, etwa {@code ACRONYM_DEFINITION}
 * @param role       {@code DEFINES} oder {@code MENTIONS} wie im Client, oder {@code null}
 * @param payloadJson Angaben als JSON-Objekt, Aufbau je Typ ({@link FactTypes})
 * @param arguments  suchbare Rollen mit normalisiertem Wert, nach Rolle und Ordnungszahl
 * @param provenance aus welchem Ergebnis welches Prozessors der Fakt stammt
 * @param anchor     Fundstelle im Text oder {@code null}
 * @param origin     automatisch abgeleitet oder von einer Person angelegt (#182)
 * @param review     Prüfstatus; automatische Fakten sind ungeprüft, solange niemand sie bestätigt oder verwirft
 */
public record MetadataFact(String id, PageKey page, String type, String role, String payloadJson,
                           List<Argument> arguments, Provenance provenance, Anchor anchor, FactOrigin origin,
                           FactReview review) {

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public MetadataFact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(page, "page");
        requireName("type", type);
        if (role != null) {
            requireName("role", role);
        }
        payloadJson = Objects.requireNonNull(payloadJson, "payloadJson").trim();
        if (!payloadJson.startsWith("{") || !payloadJson.endsWith("}")) {
            throw new IllegalArgumentException("Payload muss ein JSON-Objekt sein");
        }
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(provenance, "provenance");
        origin = origin == null ? FactOrigin.AUTOMATIC : origin;
        review = review == null ? FactReview.UNREVIEWED : review;
    }

    /** Ein automatischer, ungeprüfter Fakt. */
    public MetadataFact(String id, PageKey page, String type, String role, String payloadJson,
                        List<Argument> arguments, Provenance provenance, Anchor anchor) {
        this(id, page, type, role, payloadJson, arguments, provenance, anchor, FactOrigin.AUTOMATIC,
                FactReview.UNREVIEWED);
    }

    /** Derselbe Fakt mit anderem Prüfstatus. */
    public MetadataFact withReview(FactReview value) {
        return new MetadataFact(id, page, type, role, payloadJson, arguments, provenance, anchor, origin, value);
    }

    /** Derselbe Fakt unter anderer Kennung. */
    public MetadataFact withId(String value) {
        return new MetadataFact(value, page, type, role, payloadJson, arguments, provenance, anchor, origin, review);
    }

    /**
     * Ob beide dieselbe Aussage aus demselben Ergebnis sind: alles außer Prüfstatus und Rechenzeitpunkt gleich. Daran
     * erkennt die Prüfablage, ob ein geprüfter automatischer Fakt noch zum aktuellen Ergebnis gehört.
     */
    public boolean sameStatement(MetadataFact other) {
        return other != null && id.equals(other.id) && page.equals(other.page) && type.equals(other.type)
                && Objects.equals(role, other.role) && payloadJson.equals(other.payloadJson)
                && arguments.equals(other.arguments) && Objects.equals(anchor, other.anchor) && origin == other.origin
                && provenance.processorId().equals(other.provenance.processorId())
                && provenance.processorVersion().equals(other.provenance.processorVersion())
                && provenance.inputSha256().equals(other.provenance.inputSha256())
                && Objects.equals(provenance.revision(), other.provenance.revision());
    }

    /**
     * Ob der Fakt zur aktuellen Eingabe und Prozessorversion passt (wie {@link Enrichment#freshness}); ohne aktuelle
     * Angaben {@link Enrichment.Freshness#STALE}.
     */
    public Enrichment.Freshness freshness(String currentInputSha256, String currentVersion) {
        return provenance.inputSha256().equals(currentInputSha256)
                && provenance.processorVersion().equals(currentVersion)
                ? Enrichment.Freshness.CURRENT : Enrichment.Freshness.STALE;
    }

    /** Die Werte einer Rolle in Ordnungsfolge. */
    public List<String> values(String argumentRole) {
        return arguments.stream().filter(argument -> argument.role().equals(argumentRole))
                .map(Argument::value).toList();
    }

    static void requireName(String what, String value) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Ungültiger Name für " + what + ": erwartet [A-Z][A-Z0-9_]*");
        }
    }

    @Override
    public String toString() {
        // Ohne Seite und Werte: toString landet leicht im Log.
        return "MetadataFact[" + id + ", " + type + "]";
    }

    /**
     * Ein suchbares Argument (wie {@code FactArgument} im Client, ohne Verweis auf andere Fakten).
     *
     * @param role    Rolle, etwa {@code SHORT_FORM}
     * @param ordinal Ordnungszahl bei wiederholter Rolle, ab 0
     * @param value   normalisierter Suchwert; ein Suchbegriff, keine Identität über Seiten hinweg
     */
    public record Argument(String role, int ordinal, String value) {

        /** Längster Wert. */
        public static final int MAX_VALUE_LENGTH = 512;

        public Argument {
            requireName("argument role", role);
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal darf nicht negativ sein");
            }
            value = Objects.requireNonNull(value, "value").strip();
            if (value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("value braucht 1 bis " + MAX_VALUE_LENGTH + " Zeichen");
            }
        }
    }

    /**
     * Herkunft (wie {@code FactProvenance} im Client): bei automatischen Fakten das Ergebnis eines Prozessors, bei
     * manuellen {@value FactEdits#MANUAL_PROCESSOR} mit dem Eingabe-Hash, gegen den die Person den Fakt angelegt hat.
     *
     * @param inputSha256 Eingabe-Hash des Ergebnisses ({@link Enrichment#inputSha256()}), leer wenn unbekannt
     * @param revision    Revision der Payload oder {@code null}
     */
    public record Provenance(String processorId, String processorVersion, String inputSha256, String revision,
                             Instant computedAt) {

        public Provenance {
            Objects.requireNonNull(processorId, "processorId");
            Objects.requireNonNull(processorVersion, "processorVersion");
            Objects.requireNonNull(inputSha256, "inputSha256");
            Objects.requireNonNull(computedAt, "computedAt");
        }

        static Provenance of(Enrichment enrichment) {
            return new Provenance(enrichment.processorId(), enrichment.processorVersion(), enrichment.inputSha256(),
                    enrichment.revision(), enrichment.computedAt());
        }
    }

    /**
     * Fundstelle (wie {@code FactAnchor} im Client): Abschnitt und Zeichenbereich im Text der Eingabe; gilt nur, solange
     * der Fakt aktuell ist.
     *
     * @param section   Überschrift oder {@code null}
     * @param charStart erste Position oder {@code null}
     * @param charEnd   Position danach oder {@code null}
     */
    public record Anchor(String section, Integer charStart, Integer charEnd) {
    }
}
