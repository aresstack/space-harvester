package com.aresstack.spaceharvester.application.convert;

import java.util.Objects;

/**
 * Die Lese-Ansicht einer Seite im Datenbestand (#218): beim Herunterladen einmal von der Quelle abgerufen (bei
 * Confluence {@code body.export_view} und {@code body.view}, wie in der Lese-Ansicht #176) und neben der Payload
 * abgelegt, damit Converter ausgeführte Makros ({@code include}, {@code excerpt-include}, {@code children}) zeigen
 * können, ohne die Quelle zu fragen. Das Storage-Format bleibt die Grundlage für Struktur, Links und Bilder.
 *
 * @param version Version der Seite, zu der die Ansicht gehört (gleich der exportierten Payload)
 * @param status  ob die Ansicht vorliegt; nur bei {@link Status#AVAILABLE} sind {@code html} und {@code text} gefüllt
 * @param reason  warum keine Ansicht vorliegt, sonst {@code null}; nennt keine Seiten-IDs oder Titel
 * @param html    gerenderter HTML-Körper der Quelle mit ausgeführten Makros
 * @param baseUri Basis für relative Adressen im HTML
 * @param text    Lesetext für Leser und KI, aus der gerenderten Ansicht
 */
public record ExportReadingView(String pageId, String version, Status status, String reason, String html,
                                String baseUri, String text) {

    public static final String FORMAT = "space-harvester/export-reading-view/v1";

    public ExportReadingView {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(status, "status");
        html = html == null ? "" : html;
        baseUri = baseUri == null ? "" : baseUri;
        text = text == null ? "" : text;
        if (status != Status.AVAILABLE && (!html.isEmpty() || !text.isEmpty())) {
            throw new IllegalArgumentException("Ohne Ansicht keine Inhalte");
        }
    }

    public static ExportReadingView available(String pageId, String version, String html, String baseUri,
                                              String text) {
        return new ExportReadingView(pageId, version, Status.AVAILABLE, null, html, baseUri, text);
    }

    public static ExportReadingView without(String pageId, String version, Status status, String reason) {
        if (status == Status.AVAILABLE) {
            throw new IllegalArgumentException("Ohne Ansicht nicht verfügbar");
        }
        return new ExportReadingView(pageId, version, status, reason, "", "", "");
    }

    public boolean available() {
        return status == Status.AVAILABLE;
    }

    /** Stand der Ansicht; alles außer {@code AVAILABLE} gilt in Konvertierungen als fehlender eingebundener Inhalt. */
    public enum Status {
        /** Abgelegt. */
        AVAILABLE,
        /**
         * Die Seite bindet Fremdes ein, das der Besitzer des Exports nicht nachweislich lesen darf (keine Freigabe der
         * ganzen Quelle); ohne Inhalt, wie in der Lese-Ansicht.
         */
        RESTRICTED,
        /** Die Quelle hat keine Lese-Ansicht. */
        UNSUPPORTED,
        /** Quelle nicht erreichbar, die Seite hat sich seit dem Export geändert oder die Ansicht ist zu groß. */
        UNAVAILABLE
    }
}
