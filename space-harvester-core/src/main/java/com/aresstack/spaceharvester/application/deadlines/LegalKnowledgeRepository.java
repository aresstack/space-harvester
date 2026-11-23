package com.aresstack.spaceharvester.application.deadlines;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Rechtswissen als dünner Port (ADR 0035, Abschnitt 5). Der Standardadapter sucht über die vorhandene Suche in
 * Wissensquellen, die der Benutzer als Rechtsquellen markiert hat; Gültigkeit und Art stehen in deren Metadaten. Ein
 * leerer oder nicht eingerichteter Bestand ist ausdrücklich erkennbar: Er führt nie zu einer bestätigten
 * Standardfrist.
 */
public interface LegalKnowledgeRepository {

    /** Ob überhaupt Rechtsquellen da sind. */
    State state();

    /**
     * Blockiert. Treffer nach Relevanz; die Rechteprüfung macht der Adapter.
     *
     * @return leer, wenn nichts passt oder {@link #state()} nicht {@link State#READY} ist
     */
    List<LegalHit> search(LegalQuery query, int limit);

    /** Zustand des Bestands. */
    enum State {
        /** Keine Quelle als Rechtsquelle markiert. */
        NOT_CONFIGURED,
        /** Markiert, aber noch nichts gelesen. */
        EMPTY,
        READY
    }

    /** Art einer Rechtsquelle. */
    enum Kind {
        NORM,
        GUIDELINE,
        SERVICE_INSTRUCTION,
        DECREE,
        INDIVIDUAL_APPROVAL,
        UNKNOWN
    }

    /**
     * Eine Anfrage; leere Angaben schränken nicht ein.
     *
     * @param text         freier Suchtext
     * @param legalBasis   Rechtsvorschrift, z. B. {@code § 60 BHO}
     * @param topic        Thema, z. B. {@code Vorschuss}
     * @param accountType  Kontenart ({@code vorschuss}, {@code verwahrung})
     * @param bookingPoint Buchungsstelle
     * @param validOn      Stichtag der Gültigkeit oder {@code null}
     */
    record LegalQuery(String text, String legalBasis, String topic, String accountType, String bookingPoint,
                      LocalDate validOn) {

        public LegalQuery {
            text = text == null ? "" : text;
            legalBasis = legalBasis == null ? "" : legalBasis;
            topic = topic == null ? "" : topic;
            accountType = accountType == null ? "" : accountType;
            bookingPoint = bookingPoint == null ? "" : bookingPoint;
        }
    }

    /**
     * Ein Treffer.
     *
     * @param sourceId   Wissensquelle
     * @param documentId Dokument in der Quelle
     * @param version    Version des Dokuments
     * @param title      Titel
     * @param legalBasis Rechtsgrundlage, leer wenn unbekannt
     * @param location   Fundstelle, z. B. {@code Abs. 1 Satz 2}
     * @param excerpt    Textausschnitt, wörtlich
     * @param kind       Art
     * @param validFrom  Gültig ab oder {@code null} (unbekannt)
     * @param validUntil Gültig bis oder {@code null} (unbekannt oder unbefristet)
     * @param relevance  Relevanz der Suche, nur zum Ordnen
     * @param provenance nachvollziehbare Herkunft, z. B. Adresse und Abrufdatum
     */
    record LegalHit(String sourceId, String documentId, String version, String title, String legalBasis,
                    String location, String excerpt, Kind kind, LocalDate validFrom, LocalDate validUntil,
                    double relevance, String provenance) {

        public LegalHit {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(documentId, "documentId");
            version = version == null ? "" : version;
            title = title == null ? "" : title;
            legalBasis = legalBasis == null ? "" : legalBasis;
            location = location == null ? "" : location;
            excerpt = excerpt == null ? "" : excerpt;
            kind = kind == null ? Kind.UNKNOWN : kind;
            provenance = provenance == null ? "" : provenance;
        }
    }

    /** Kein Rechtswissen angeschlossen. */
    LegalKnowledgeRepository NONE = new LegalKnowledgeRepository() {
        @Override
        public State state() {
            return State.NOT_CONFIGURED;
        }

        @Override
        public List<LegalHit> search(LegalQuery query, int limit) {
            return List.of();
        }
    };
}
